/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.components.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

enum class CelebrationEvent(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val startColor: Color,
    val endColor: Color
) {
    DEVELOPER_BIRTHDAY(
        title = "Happy Birthday Developer! 🎂",
        subtitle = "Celebrating the creator of Videntra on December 10th!",
        icon = Icons.Default.Cake,
        startColor = Color(0xFFFFB300),
        endColor = Color(0xFFE91E63)
    ),
    NEW_YEAR(
        title = "Happy New Year! 🎉",
        subtitle = "Wishing you an amazing year ahead with Videntra!",
        icon = Icons.Default.Celebration,
        startColor = Color(0xFF5C6BC0),
        endColor = Color(0xFFFFB300)
    ),
    PONGAL(
        title = "Happy Pongal! 🌾☀️",
        subtitle = "Wishing you joy, prosperity, and warmth this Pongal!",
        icon = Icons.Default.WbSunny,
        startColor = Color(0xFFF57C00),
        endColor = Color(0xFF2E7D32)
    ),
    INDEPENDENCE_DAY(
        title = "Happy Independence Day! 🇮🇳",
        subtitle = "Celebrating freedom, pride, and unity on August 15th!",
        icon = Icons.Default.Flag,
        startColor = Color(0xFFFF9933),
        endColor = Color(0xFF138808)
    );

    companion object {
        fun getTodayCelebration(calendar: Calendar = Calendar.getInstance()): CelebrationEvent? {
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            return when {
                month == Calendar.DECEMBER && day == 10 -> DEVELOPER_BIRTHDAY
                month == Calendar.JANUARY && day == 1 -> NEW_YEAR
                month == Calendar.JANUARY && (day == 14 || day == 15) -> PONGAL
                month == Calendar.AUGUST && day == 15 -> INDEPENDENCE_DAY
                else -> null
            }
        }
    }
}

@Composable
fun CelebrationBanner(
    modifier: Modifier = Modifier
) {
    val event = remember { CelebrationEvent.getTodayCelebration() }
    var dismissed by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = event != null && !dismissed,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        if (event != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(event.startColor, event.endColor)
                        )
                    )
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.22f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = event.icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = event.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }

                    IconButton(
                        onClick = { dismissed = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
