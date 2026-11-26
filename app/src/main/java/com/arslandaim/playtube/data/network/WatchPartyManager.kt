/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.data.network

import com.arslandaim.playtube.data.local.PreferencesManager
import com.arslandaim.playtube.domain.model.*
import com.arslandaim.playtube.utils.PTLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchPartyManager @Inject constructor(
    private val client: OkHttpClient,
    private val preferencesManager: PreferencesManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null

    val senderId = UUID.randomUUID().toString()

    private val _currentRoomCode = MutableStateFlow<String?>(null)
    val currentRoomCode: StateFlow<String?> = _currentRoomCode.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _userName = MutableStateFlow("Guest")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _partySession = MutableStateFlow<WatchPartySession?>(null)
    val partySession: StateFlow<WatchPartySession?> = _partySession.asStateFlow()

    private val _floatingReactions = MutableSharedFlow<FloatingReaction>(extraBufferCapacity = 64)
    val floatingReactions: SharedFlow<FloatingReaction> = _floatingReactions.asSharedFlow()

    // Callbacks to communicate with playback
    var onRemotePlay: (() -> Unit)? = null
    var onRemotePause: (() -> Unit)? = null
    var onRemoteSeek: ((Long) -> Unit)? = null
    var onRemoteChangeVideo: ((String, String?, String?) -> Unit)? = null
    var onRemoteSyncState: ((String, String?, String?, Long, Boolean) -> Unit)? = null

    // Provider lambda for host's live position
    var localPositionProvider: (() -> Long)? = null
    var localIsPlayingProvider: (() -> Boolean)? = null
    var localCurrentVideoProvider: (() -> VideoItem?)? = null

    init {
        scope.launch {
            preferencesManager.youtubeUserName.collectLatest { name ->
                if (!name.isNullOrBlank()) {
                    _userName.value = name
                }
            }
        }
    }

    fun generateRoomCode(): String {
        val num = (1000..9999).random()
        return "VID-$num"
    }

    private fun getTopicForRoom(roomCode: String): String {
        val clean = roomCode.trim().uppercase().replace("[^A-Z0-9]".toRegex(), "")
        return "videntra_watchparty_$clean"
    }

    /**
     * Creates and hosts a new Watch Party room.
     */
    fun createRoom(
        roomCode: String = generateRoomCode(),
        customHostName: String? = null
    ): String {
        leaveRoom()

        val hostName = customHostName?.takeIf { it.isNotBlank() } ?: _userName.value
        _userName.value = hostName
        _currentRoomCode.value = roomCode
        _isHost.value = true

        val currentVideo = localCurrentVideoProvider?.invoke()
        val currentPos = localPositionProvider?.invoke() ?: 0L
        val isPlaying = localIsPlayingProvider?.invoke() ?: false

        val hostMember = WatchPartyMember(id = senderId, name = hostName, isHost = true)
        _partySession.value = WatchPartySession(
            roomId = roomCode,
            isHost = true,
            hostName = hostName,
            currentVideoId = currentVideo?.id,
            currentVideoTitle = currentVideo?.title,
            currentThumbnailUrl = currentVideo?.thumbnailUrl,
            positionMs = currentPos,
            isPlaying = isPlaying,
            members = listOf(hostMember)
        )

        connectWebSocket(roomCode)
        startHostHeartbeat()

        PTLog.i("WatchPartyManager", "Created Watch Party room $roomCode as host $hostName")
        return roomCode
    }

    /**
     * Joins an existing Watch Party room as a participant.
     */
    fun joinRoom(
        roomCode: String,
        customGuestName: String? = null
    ) {
        leaveRoom()

        val cleanCode = roomCode.trim().uppercase()
        val guestName = customGuestName?.takeIf { it.isNotBlank() } ?: _userName.value
        _userName.value = guestName
        _currentRoomCode.value = cleanCode
        _isHost.value = false

        val myMember = WatchPartyMember(id = senderId, name = guestName, isHost = false)
        _partySession.value = WatchPartySession(
            roomId = cleanCode,
            isHost = false,
            hostName = "Connecting...",
            currentVideoId = null,
            currentVideoTitle = null,
            currentThumbnailUrl = null,
            positionMs = 0L,
            isPlaying = false,
            members = listOf(myMember)
        )

        connectWebSocket(cleanCode)

        // Send JOIN message to request host sync
        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.JOIN,
                roomId = cleanCode,
                senderId = senderId,
                senderName = guestName,
                isHost = false
            )
        )

        PTLog.i("WatchPartyManager", "Joined Watch Party room $cleanCode as guest $guestName")
    }

    /**
     * Leaves the current room and cleans up resources.
     */
    fun leaveRoom() {
        val current = _currentRoomCode.value
        if (current != null) {
            sendMessage(
                WatchPartyMessage(
                    action = PartyActionType.LEAVE,
                    roomId = current,
                    senderId = senderId,
                    senderName = _userName.value,
                    isHost = _isHost.value
                )
            )
        }

        heartbeatJob?.cancel()
        heartbeatJob = null

        webSocket?.close(1000, "Leaving party")
        webSocket = null

        _currentRoomCode.value = null
        _isHost.value = false
        _partySession.value = null
    }

    private fun connectWebSocket(roomCode: String) {
        val topic = getTopicForRoom(roomCode)
        val wsUrl = "wss://ntfy.sh/$topic/ws"

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                PTLog.d("WatchPartyManager", "WebSocket connected to room $roomCode")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingRawMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                PTLog.w("WatchPartyManager", "WebSocket error: ${t.message}. Reconnecting in 3s...")
                scope.launch {
                    delay(3000L)
                    if (_currentRoomCode.value == roomCode) {
                        connectWebSocket(roomCode)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                PTLog.d("WatchPartyManager", "WebSocket closed: $reason")
            }
        })
    }

    private fun handleIncomingRawMessage(rawText: String) {
        try {
            val root = JSONObject(rawText)
            val eventType = root.optString("event")
            if (eventType != "message") return

            val payloadStr = root.optString("message")
            if (payloadStr.isBlank()) return

            val json = JSONObject(payloadStr)
            val actionName = json.optString("action")
            val action = try { PartyActionType.valueOf(actionName) } catch (e: Exception) { return }

            val fromSenderId = json.optString("senderId")
            if (fromSenderId == senderId) return // Ignore self-published messages

            val fromSenderName = json.optString("senderName", "Friend")
            val fromIsHost = json.optBoolean("isHost", false)
            val roomId = json.optString("roomId")
            val videoId = json.optString("videoId").takeIf { it.isNotBlank() }
            val videoTitle = json.optString("videoTitle").takeIf { it.isNotBlank() }
            val thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() }
            val positionMs = json.optLong("positionMs", 0L)
            val isPlaying = json.optBoolean("isPlaying", false)
            val emoji = json.optString("emoji").takeIf { it.isNotBlank() }
            val chatText = json.optString("chatText").takeIf { it.isNotBlank() }
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())

            val message = WatchPartyMessage(
                action = action,
                roomId = roomId,
                senderId = fromSenderId,
                senderName = fromSenderName,
                isHost = fromIsHost,
                videoId = videoId,
                videoTitle = videoTitle,
                thumbnailUrl = thumbnailUrl,
                positionMs = positionMs,
                isPlaying = isPlaying,
                emoji = emoji,
                chatText = chatText,
                timestamp = timestamp
            )

            scope.launch(Dispatchers.Main) {
                processPartyMessage(message)
            }
        } catch (e: Exception) {
            PTLog.e("WatchPartyManager", "Failed to parse incoming party message", e)
        }
    }

    private fun processPartyMessage(msg: WatchPartyMessage) {
        val currentSession = _partySession.value ?: return

        when (msg.action) {
            PartyActionType.JOIN -> {
                // Add new participant
                val exists = currentSession.members.any { it.id == msg.senderId }
                if (!exists) {
                    val updatedMembers = currentSession.members + WatchPartyMember(
                        id = msg.senderId,
                        name = msg.senderName,
                        isHost = msg.isHost
                    )
                    _partySession.value = currentSession.copy(members = updatedMembers)
                }

                // If we are host, broadcast our current state so the newly joined member syncs immediately
                if (_isHost.value) {
                    val currentVideo = localCurrentVideoProvider?.invoke()
                    val pos = localPositionProvider?.invoke() ?: 0L
                    val playing = localIsPlayingProvider?.invoke() ?: false

                    sendMessage(
                        WatchPartyMessage(
                            action = PartyActionType.SYNC_STATE,
                            roomId = currentSession.roomId,
                            senderId = senderId,
                            senderName = _userName.value,
                            isHost = true,
                            videoId = currentVideo?.id,
                            videoTitle = currentVideo?.title,
                            thumbnailUrl = currentVideo?.thumbnailUrl,
                            positionMs = pos,
                            isPlaying = playing
                        )
                    )
                }
            }

            PartyActionType.LEAVE -> {
                val updatedMembers = currentSession.members.filterNot { it.id == msg.senderId }
                _partySession.value = currentSession.copy(members = updatedMembers)
            }

            PartyActionType.SYNC_STATE -> {
                if (!_isHost.value) {
                    _partySession.value = currentSession.copy(
                        hostName = msg.senderName,
                        currentVideoId = msg.videoId,
                        currentVideoTitle = msg.videoTitle,
                        currentThumbnailUrl = msg.thumbnailUrl,
                        positionMs = msg.positionMs,
                        isPlaying = msg.isPlaying
                    )
                    if (msg.videoId != null) {
                        onRemoteSyncState?.invoke(msg.videoId, msg.videoTitle, msg.thumbnailUrl, msg.positionMs, msg.isPlaying)
                    }
                }
            }

            PartyActionType.PLAY -> {
                if (!_isHost.value) {
                    _partySession.value = currentSession.copy(isPlaying = true, positionMs = msg.positionMs)
                    onRemotePlay?.invoke()
                }
            }

            PartyActionType.PAUSE -> {
                if (!_isHost.value) {
                    _partySession.value = currentSession.copy(isPlaying = false, positionMs = msg.positionMs)
                    onRemotePause?.invoke()
                }
            }

            PartyActionType.SEEK -> {
                if (!_isHost.value) {
                    _partySession.value = currentSession.copy(positionMs = msg.positionMs)
                    onRemoteSeek?.invoke(msg.positionMs)
                }
            }

            PartyActionType.CHANGE_VIDEO -> {
                if (!_isHost.value && msg.videoId != null) {
                    _partySession.value = currentSession.copy(
                        currentVideoId = msg.videoId,
                        currentVideoTitle = msg.videoTitle,
                        currentThumbnailUrl = msg.thumbnailUrl,
                        positionMs = 0L,
                        isPlaying = true
                    )
                    onRemoteChangeVideo?.invoke(msg.videoId, msg.videoTitle, msg.thumbnailUrl)
                }
            }

            PartyActionType.HEARTBEAT -> {
                if (!_isHost.value) {
                    val localPos = localPositionProvider?.invoke() ?: 0L
                    val drift = Math.abs(localPos - msg.positionMs)
                    // If drift is greater than 1.8 seconds, align to host timestamp
                    if (drift > 1800L) {
                        PTLog.d("WatchPartyManager", "Drift of ${drift}ms detected. Aligning with host...")
                        onRemoteSeek?.invoke(msg.positionMs)
                    }
                }
            }

            PartyActionType.REACTION -> {
                if (msg.emoji != null) {
                    val reaction = FloatingReaction(
                        id = UUID.randomUUID().toString(),
                        emoji = msg.emoji,
                        senderName = msg.senderName
                    )
                    _floatingReactions.tryEmit(reaction)
                }
            }

            PartyActionType.CHAT -> {
                if (msg.chatText != null) {
                    val chatMsg = WatchPartyChatMessage(
                        id = UUID.randomUUID().toString(),
                        senderName = msg.senderName,
                        text = msg.chatText,
                        isHost = msg.isHost
                    )
                    val updatedChats = (currentSession.chatMessages + chatMsg).takeLast(50)
                    _partySession.value = currentSession.copy(chatMessages = updatedChats)
                }
            }
        }
    }

    private fun startHostHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isHost.value) {
                delay(5000L) // Checkpoint every 5 seconds
                val room = _currentRoomCode.value ?: break
                val pos = localPositionProvider?.invoke() ?: 0L
                val isPlaying = localIsPlayingProvider?.invoke() ?: false

                sendMessage(
                    WatchPartyMessage(
                        action = PartyActionType.HEARTBEAT,
                        roomId = room,
                        senderId = senderId,
                        senderName = _userName.value,
                        isHost = true,
                        positionMs = pos,
                        isPlaying = isPlaying
                    )
                )
            }
        }
    }

    /**
     * Publishes a message to the room topic via high-speed HTTP POST.
     */
    fun sendMessage(msg: WatchPartyMessage) {
        val topic = getTopicForRoom(msg.roomId)
        val postUrl = "https://ntfy.sh/$topic"

        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("action", msg.action.name)
                    put("roomId", msg.roomId)
                    put("senderId", msg.senderId)
                    put("senderName", msg.senderName)
                    put("isHost", msg.isHost)
                    put("videoId", msg.videoId ?: "")
                    put("videoTitle", msg.videoTitle ?: "")
                    put("thumbnailUrl", msg.thumbnailUrl ?: "")
                    put("positionMs", msg.positionMs)
                    put("isPlaying", msg.isPlaying)
                    put("emoji", msg.emoji ?: "")
                    put("chatText", msg.chatText ?: "")
                    put("timestamp", msg.timestamp)
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(postUrl)
                    .post(body)
                    .build()

                client.newCall(request).execute().close()
            } catch (e: Exception) {
                PTLog.w("WatchPartyManager", "Failed to send party message: ${e.message}")
            }
        }
    }

    /**
     * Broadcasts Play action when Host resumes video.
     */
    fun broadcastPlay(positionMs: Long) {
        val room = _currentRoomCode.value ?: return
        if (!_isHost.value) return

        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.PLAY,
                roomId = room,
                senderId = senderId,
                senderName = _userName.value,
                isHost = true,
                positionMs = positionMs,
                isPlaying = true
            )
        )
    }

    /**
     * Broadcasts Pause action when Host pauses video.
     */
    fun broadcastPause(positionMs: Long) {
        val room = _currentRoomCode.value ?: return
        if (!_isHost.value) return

        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.PAUSE,
                roomId = room,
                senderId = senderId,
                senderName = _userName.value,
                isHost = true,
                positionMs = positionMs,
                isPlaying = false
            )
        )
    }

    /**
     * Broadcasts Seek action when Host seeks / skips.
     */
    fun broadcastSeek(positionMs: Long) {
        val room = _currentRoomCode.value ?: return
        if (!_isHost.value) return

        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.SEEK,
                roomId = room,
                senderId = senderId,
                senderName = _userName.value,
                isHost = true,
                positionMs = positionMs,
                isPlaying = localIsPlayingProvider?.invoke() ?: true
            )
        )
    }

    /**
     * Broadcasts Video Change when Host switches to a new video.
     */
    fun broadcastChangeVideo(video: VideoItem) {
        val room = _currentRoomCode.value ?: return
        if (!_isHost.value) return

        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.CHANGE_VIDEO,
                roomId = room,
                senderId = senderId,
                senderName = _userName.value,
                isHost = true,
                videoId = video.id,
                videoTitle = video.title,
                thumbnailUrl = video.thumbnailUrl,
                positionMs = 0L,
                isPlaying = true
            )
        )
    }

    /**
     * Sends an emoji reaction (❤️, 🔥, 😂, 👏, 🎉, 🍿) visible to all members.
     */
    fun sendReaction(emoji: String) {
        val room = _currentRoomCode.value ?: return

        // Local emit immediately for responsiveness
        _floatingReactions.tryEmit(
            FloatingReaction(
                id = UUID.randomUUID().toString(),
                emoji = emoji,
                senderName = _userName.value
            )
        )

        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.REACTION,
                roomId = room,
                senderId = senderId,
                senderName = _userName.value,
                isHost = _isHost.value,
                emoji = emoji
            )
        )
    }

    /**
     * Sends a chat message in the party room.
     */
    fun sendChatMessage(text: String) {
        val room = _currentRoomCode.value ?: return
        if (text.isBlank()) return

        val myChat = WatchPartyChatMessage(
            id = UUID.randomUUID().toString(),
            senderName = _userName.value,
            text = text,
            isHost = _isHost.value
        )

        val current = _partySession.value
        if (current != null) {
            _partySession.value = current.copy(chatMessages = (current.chatMessages + myChat).takeLast(50))
        }

        sendMessage(
            WatchPartyMessage(
                action = PartyActionType.CHAT,
                roomId = room,
                senderId = senderId,
                senderName = _userName.value,
                isHost = _isHost.value,
                chatText = text
            )
        )
    }
}
