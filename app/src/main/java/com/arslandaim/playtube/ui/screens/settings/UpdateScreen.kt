/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arslandaim.playtube.BuildConfig
import com.arslandaim.playtube.R
import com.arslandaim.playtube.ui.components.GlassSurface

private const val VIDENTRA_DOWNLOAD_PAGE_URL = "https://videntralandingpage.vercel.app/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    updateViewModel: UpdateViewModel,
    onBack: () -> Unit
) {
    val updateInfo by updateViewModel.updateInfo.collectAsStateWithLifecycle()
    val isChecking by updateViewModel.isChecking.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        updateViewModel.checkForUpdates()
    }

    Scaffold(
        topBar = {
            GlassSurface(tonalElevation = 0.dp) {
                TopAppBar(
                    title = { Text("App Updates", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Videntra Logo
            Image(
                painter = painterResource(id = R.drawable.videntra_logo),
                contentDescription = "Videntra",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
            )

            Text(
                text = "Videntra",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "Installed Version: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Status & Latest Release Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (updateInfo.hasUpdate) Icons.Default.NewReleases else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (updateInfo.hasUpdate) MaterialTheme.colorScheme.primary else Color(0xFF2E7D32),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isChecking -> "Checking GitHub Releases..."
                                    updateInfo.hasUpdate -> "Latest Version Available!"
                                    updateInfo.latestVersion.isNotBlank() -> "You're on the Latest Version"
                                    else -> "Check for Latest Release"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (updateInfo.latestVersion.isNotBlank()) {
                                Text(
                                    text = "Latest GitHub Release: v${updateInfo.latestVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (updateInfo.releaseNotes.isNotBlank()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = "Release Notes",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = updateInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (updateInfo.updateUrl.isNotBlank()) {
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, updateInfo.updateUrl.toUri()))
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("View on GitHub Releases")
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Download Button -> redirects to https://videntralandingpage.vercel.app/
            Button(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, VIDENTRA_DOWNLOAD_PAGE_URL.toUri()))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (updateInfo.hasUpdate) "Download Latest Update" else "Download from Videntra Website",
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = { updateViewModel.checkForUpdates() },
                enabled = !isChecking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check for Updates", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
