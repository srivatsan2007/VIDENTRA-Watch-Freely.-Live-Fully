/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components

import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.arslandaim.playtube.ui.theme.VidentraGreen
import com.arslandaim.playtube.ui.theme.VidentraMint

private data class CelebrationVisuals(
    val icon: ImageVector,
    val gradientColors: List<Color>,
    val badgeBg: Color,
    val badgeBorder: Color,
    val badgeTextColor: Color
)

@Composable
fun CelebrationDialog(
    celebration: CelebrationType,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Festive pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "celebrationPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "celebrationScale"
    )

    val visuals = when (celebration) {
        CelebrationType.NEW_YEAR -> CelebrationVisuals(
            icon = Icons.Default.Celebration,
            gradientColors = listOf(Color(0xFF6200EA), Color(0xFFFFD54F), Color(0xFF00E5FF)),
            badgeBg = Color(0xFFEDE7F6),
            badgeBorder = Color(0xFFB39DDB),
            badgeTextColor = Color(0xFF4A148C)
        )
        CelebrationType.PONGAL -> CelebrationVisuals(
            icon = Icons.Default.WbSunny,
            gradientColors = listOf(Color(0xFFFFB300), Color(0xFFE65100), Color(0xFFFF7043)),
            badgeBg = Color(0xFFFFF3E0),
            badgeBorder = Color(0xFFFFB74D),
            badgeTextColor = Color(0xFFE65100)
        )
        CelebrationType.REPUBLIC_DAY -> CelebrationVisuals(
            icon = Icons.Default.Flag,
            gradientColors = listOf(Color(0xFFFF9933), Color(0xFF000080), Color(0xFF138808)),
            badgeBg = Color(0xFFFFF8E1),
            badgeBorder = Color(0xFFFFB74D),
            badgeTextColor = Color(0xFFBF360C)
        )
        CelebrationType.INDEPENDENCE_DAY -> CelebrationVisuals(
            icon = Icons.Default.Stars,
            gradientColors = listOf(Color(0xFFFF9933), Color(0xFF000080), Color(0xFF138808)),
            badgeBg = Color(0xFFE8F5E9),
            badgeBorder = Color(0xFF81C784),
            badgeTextColor = Color(0xFF1B5E20)
        )
        CelebrationType.DEVELOPER_BIRTHDAY -> CelebrationVisuals(
            icon = Icons.Default.Cake,
            gradientColors = listOf(Color(0xFFFFD54F), Color(0xFFFF7043), VidentraMint),
            badgeBg = Color(0xFFFFECC8),
            badgeBorder = Color(0xFFFFB74D),
            badgeTextColor = Color(0xFFE65100)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Festive Header Icon with Gradient & Pulse
                Box(
                    modifier = Modifier
                        .scale(scale)
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(colors = visuals.gradientColors)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = celebration.title,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Date Pill Badge
                Surface(
                    shape = CircleShape,
                    color = visuals.badgeBg,
                    border = BorderStroke(1.dp, visuals.badgeBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = celebration.dateBadge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = visuals.badgeTextColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Title
                Text(
                    text = celebration.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Subtitle
                Text(
                    text = celebration.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = VidentraGreen,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tribute Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = celebration.tributeMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = celebration.celebrationNote,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Optional Action Button (e.g. Portfolio / Greetings)
                if (celebration.actionUrl != null && celebration.actionButtonText != null) {
                    Button(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                celebration.actionUrl.toUri()
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = celebration.actionButtonText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Primary Dismiss / Celebrate Button
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = celebration.buttonText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
