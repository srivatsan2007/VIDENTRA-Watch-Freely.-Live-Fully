/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.components.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.arslandaim.playtube.MainViewModel
import com.arslandaim.playtube.R
import com.arslandaim.playtube.ui.navigation.Destination
import com.arslandaim.playtube.ui.screens.settings.UpdateViewModel
import com.arslandaim.playtube.ui.theme.IncognitoPurple
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VidentraTopAppBar(
    isIncognitoMode: Boolean,
    currentRoute: String?,
    navController: NavHostController,
    mainViewModel: MainViewModel,
    updateViewModel: UpdateViewModel,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.videntra_icon),
                    contentDescription = "Videntra Logo",
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "VIDENTRA",
                        style = MaterialTheme.typography.titleMedium.copy(
                            letterSpacing = 0.5.sp
                        ),
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.5.sp,
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isIncognitoMode) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = IncognitoPurple.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.incognito_label),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        },
        actions = {
            if (currentRoute?.contains("Search") == false) {
                IconButton(onClick = { navController.navigate(Destination.Search()) }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            val updateInfo by updateViewModel.updateInfo.collectAsStateWithLifecycle()
            val isAutoUpdateEnabled by updateViewModel.isAutoUpdateEnabled.collectAsStateWithLifecycle()

            IconButton(onClick = { mainViewModel.toggleIncognitoMode() }) {
                Icon(
                    imageVector = if (isIncognitoMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.incognito_label),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            val isYouTubeLoggedIn by mainViewModel.isYouTubeLoggedIn.collectAsStateWithLifecycle()
            val youtubeUserName by mainViewModel.youtubeUserName.collectAsStateWithLifecycle()
            val youtubeUserAvatar by mainViewModel.youtubeUserAvatar.collectAsStateWithLifecycle()

            IconButton(onClick = {
                if (isYouTubeLoggedIn) {
                    navController.navigate(Destination.Settings)
                } else {
                    mainViewModel.onOpenAuthSheet()
                }
            }) {
                Box(contentAlignment = Alignment.TopEnd) {
                    if (isYouTubeLoggedIn && !youtubeUserAvatar.isNullOrBlank()) {
                        AsyncImage(
                            model = youtubeUserAvatar,
                            contentDescription = youtubeUserName ?: "Account",
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = if (isYouTubeLoggedIn) youtubeUserName ?: "Account" else "Sign in",
                            tint = if (isYouTubeLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    if (isYouTubeLoggedIn) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }
            }

            IconButton(onClick = { navController.navigate(Destination.Settings) }) {
                BadgedBox(
                    badge = {
                        if (isAutoUpdateEnabled && updateInfo.hasUpdate) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Text("!")
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun PlayTubeTopAppBar(
    isIncognitoMode: Boolean,
    currentRoute: String?,
    navController: NavHostController,
    mainViewModel: MainViewModel,
    updateViewModel: UpdateViewModel,
    modifier: Modifier = Modifier
) = VidentraTopAppBar(
    isIncognitoMode = isIncognitoMode,
    currentRoute = currentRoute,
    navController = navController,
    mainViewModel = mainViewModel,
    updateViewModel = updateViewModel,
    modifier = modifier
)
