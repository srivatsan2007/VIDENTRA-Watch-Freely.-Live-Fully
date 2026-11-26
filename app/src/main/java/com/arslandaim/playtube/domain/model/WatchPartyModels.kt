/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.domain.model

enum class PartyActionType {
    SYNC_STATE,
    PLAY,
    PAUSE,
    SEEK,
    CHANGE_VIDEO,
    HEARTBEAT,
    REACTION,
    JOIN,
    LEAVE,
    CHAT
}

data class WatchPartyMessage(
    val action: PartyActionType,
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val isHost: Boolean,
    val videoId: String? = null,
    val videoTitle: String? = null,
    val thumbnailUrl: String? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val emoji: String? = null,
    val chatText: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class WatchPartyMember(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val lastSeen: Long = System.currentTimeMillis()
)

data class WatchPartyChatMessage(
    val id: String,
    val senderName: String,
    val text: String,
    val isHost: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class FloatingReaction(
    val id: String,
    val emoji: String,
    val senderName: String
)

data class WatchPartySession(
    val roomId: String,
    val isHost: Boolean,
    val hostName: String,
    val currentVideoId: String?,
    val currentVideoTitle: String?,
    val currentThumbnailUrl: String?,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val members: List<WatchPartyMember> = emptyList(),
    val chatMessages: List<WatchPartyChatMessage> = emptyList()
)
