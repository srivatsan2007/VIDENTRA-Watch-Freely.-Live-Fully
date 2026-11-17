/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arslandaim.playtube.data.network.WatchPartyManager
import com.arslandaim.playtube.domain.model.*
import com.arslandaim.playtube.ui.theme.VidentraGreen
import com.arslandaim.playtube.ui.theme.VidentraMint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchPartySheet(
    watchPartyManager: WatchPartyManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentRoomCode by watchPartyManager.currentRoomCode.collectAsStateWithLifecycle()
    val isHost by watchPartyManager.isHost.collectAsStateWithLifecycle()
    val defaultUserName by watchPartyManager.userName.collectAsStateWithLifecycle()
    val partySession by watchPartyManager.partySession.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Create, 1 = Join
    var generatedCode by remember { mutableStateOf(watchPartyManager.generateRoomCode()) }
    var inputCode by remember { mutableStateOf("") }
    var inputName by remember { mutableStateOf(defaultUserName) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            if (currentRoomCode == null) {
                // Not in party: Show Create or Join
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Create Party", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Join Party", fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedTab == 0) {
                    // Create Room Tab
                    CreatePartyContent(
                        roomCode = generatedCode,
                        hostName = inputName,
                        onNameChange = { inputName = it },
                        onRegenerateCode = { generatedCode = watchPartyManager.generateRoomCode() },
                        onStartParty = {
                            watchPartyManager.createRoom(
                                roomCode = generatedCode,
                                customHostName = inputName
                            )
                            Toast.makeText(context, "Watch Party started! Share code $generatedCode", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    // Join Room Tab
                    JoinPartyContent(
                        roomCode = inputCode,
                        guestName = inputName,
                        onCodeChange = { inputCode = it.uppercase() },
                        onNameChange = { inputName = it },
                        onJoinParty = {
                            if (inputCode.isNotBlank()) {
                                watchPartyManager.joinRoom(
                                    roomCode = inputCode,
                                    customGuestName = inputName
                                )
                                Toast.makeText(context, "Joining room $inputCode...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            } else {
                // In party: Show Active Session Dashboard
                ActivePartyContent(
                    roomCode = currentRoomCode!!,
                    isHost = isHost,
                    session = partySession,
                    onSendReaction = { watchPartyManager.sendReaction(it) },
                    onSendMessage = { watchPartyManager.sendChatMessage(it) },
                    onLeave = {
                        watchPartyManager.leaveRoom()
                        Toast.makeText(context, "Left Watch Party", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun CreatePartyContent(
    roomCode: String,
    hostName: String,
    onNameChange: (String) -> Unit,
    onRegenerateCode: () -> Unit,
    onStartParty: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Host a Watch Party 🍿",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Watch synchronized YouTube videos with your friends in real-time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Big Room Code Badge
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "ROOM CODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = roomCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 2.sp
                    )
                }

                Row {
                    IconButton(onClick = onRegenerateCode) {
                        Icon(Icons.Default.Refresh, contentDescription = "New Code")
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Videntra Room Code", roomCode))
                        Toast.makeText(context, "Room Code copied!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = hostName,
            onValueChange = onNameChange,
            label = { Text("Your Display Name") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onStartParty,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Groups, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Watch Party 🚀", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun JoinPartyContent(
    roomCode: String,
    guestName: String,
    onCodeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onJoinParty: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Join a Watch Party 🎉",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Enter the 6-character room code shared by your friend.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = roomCode,
            onValueChange = onCodeChange,
            label = { Text("Room Code (e.g. VID-1234)") },
            leadingIcon = { Icon(Icons.Default.MeetingRoom, contentDescription = null) },
            placeholder = { Text("VID-XXXX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = guestName,
            onValueChange = onNameChange,
            label = { Text("Your Display Name") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onJoinParty,
            enabled = roomCode.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.PlayCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Join Room & Sync 🍿", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ActivePartyContent(
    roomCode: String,
    isHost: Boolean,
    session: WatchPartySession?,
    onSendReaction: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    var chatInput by remember { mutableStateOf("") }
    val chatListState = rememberLazyListState()

    // Auto-scroll chat to latest
    LaunchedEffect(session?.chatMessages?.size) {
        val count = session?.chatMessages?.size ?: 0
        if (count > 0) {
            chatListState.animateScrollToItem(count - 1)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Room Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "ROOM: $roomCode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isHost) "👑 You are the Host" else "Host: ${session?.hostName ?: "Friend"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row {
                IconButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join my Videntra Watch Party! Room Code: $roomCode 🍿")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Room Code"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share Code")
                }
                IconButton(onClick = onLeave) {
                    Icon(Icons.Default.ExitToApp, contentDescription = "Leave", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Members Chips Bar
        val members = session?.members ?: emptyList()
        Text(
            text = "Connected Friends (${members.size})",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            members.forEach { member ->
                Surface(
                    shape = CircleShape,
                    color = if (member.isHost) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (member.isHost) MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (member.isHost) "👑 ${member.name}" else "👤 ${member.name}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (member.isHost) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Floating Emoji Reaction Bar
        Text(
            text = "Send Instant Reaction",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val emojis = listOf("❤️", "🔥", "😂", "👏", "🎉", "🍿", "🤯")
            emojis.forEach { emoji ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(42.dp)
                        .clickable { onSendReaction(emoji) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live Chat Messages Box
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        ) {
            val chats = session?.chatMessages ?: emptyList()
            if (chats.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "No messages yet. Say hi to your friends!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(chats, key = { it.id }) { msg ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${msg.senderName}: ",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (msg.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Chat Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text("Chat with friends...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (chatInput.isNotBlank()) {
                        onSendMessage(chatInput)
                        chatInput = ""
                    }
                },
                enabled = chatInput.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (chatInput.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * Animated floating emoji overlay rendering reactions over the video player screen.
 */
@Composable
fun FloatingReactionOverlay(
    floatingReactions: SharedFlow<FloatingReaction>,
    modifier: Modifier = Modifier
) {
    var activeReactions by remember { mutableStateOf<List<FloatingReaction>>(emptyList()) }

    LaunchedEffect(Unit) {
        floatingReactions.collect { reaction ->
            activeReactions = (activeReactions + reaction).takeLast(10)
        }
    }

    Box(modifier = modifier) {
        activeReactions.forEach { reaction ->
            SingleFloatingEmoji(
                reaction = reaction,
                onFinished = {
                    activeReactions = activeReactions.filterNot { it.id == reaction.id }
                }
            )
        }
    }
}

@Composable
private fun SingleFloatingEmoji(
    reaction: FloatingReaction,
    onFinished: () -> Unit
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var alpha by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableFloatStateOf(0.7f) }

    val randomXOffset = remember { (-40..40).random() }

    LaunchedEffect(reaction.id) {
        // Animate floating up and fading
        val duration = 2200
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < duration) {
            val progress = (System.currentTimeMillis() - startTime).toFloat() / duration
            offsetY = -progress * 260f
            alpha = (1f - progress).coerceIn(0f, 1f)
            scale = 0.7f + progress * 0.5f
            delay(16L)
        }
        onFinished()
    }

    Box(
        modifier = Modifier
            .offset(x = randomXOffset.dp, y = offsetY.dp)
            .padding(16.dp)
    ) {
        Text(
            text = reaction.emoji,
            fontSize = (28 * scale).sp,
            modifier = Modifier.alpha(alpha)
        )
    }
}
