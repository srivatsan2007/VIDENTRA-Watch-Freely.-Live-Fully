/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Sandboxed Google / YouTube Sign-In Dialog.
 * Directs user to official Google accounts sign-in, securely captures session cookies,
 * and notifies Videntra when authentication is complete.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeAuthSheet(
    onDismiss: () -> Unit,
    onAuthSuccess: (cookies: String, userName: String?, email: String?, avatarUrl: String?) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var webProgress by remember { mutableFloatStateOf(0f) }
    var currentWebUrl by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isAuthHandled by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sign in to YouTube / Google",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Loading Bar
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { webProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Sandboxed Web Browser
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    webProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    currentWebUrl = url ?: ""
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    currentWebUrl = url ?: ""

                                    val cookieManager = CookieManager.getInstance()
                                    val cookies = cookieManager.getCookie(url) ?: ""
                                    val youtubeCookies = cookieManager.getCookie("https://www.youtube.com") ?: ""

                                    val allCookies = if (cookies.contains("LOGIN_INFO") || cookies.contains("SAPISID")) {
                                        cookies
                                    } else {
                                        youtubeCookies
                                    }

                                    // Detect YouTube session completion
                                    val isLoginSuccessful = (allCookies.contains("LOGIN_INFO") || 
                                            (allCookies.contains("SAPISID") && allCookies.contains("SSID"))) &&
                                            (url?.contains("youtube.com") == true || url?.contains("myaccount.google.com") == true)

                                    if (isLoginSuccessful && !isAuthHandled) {
                                        isAuthHandled = true
                                        // Attempt to read channel name or Google profile name
                                        view?.evaluateJavascript(
                                            "(function() { " +
                                            "  try { " +
                                            "    var name = document.querySelector('#avatar-btn')?.getAttribute('aria-label') || ''; " +
                                            "    return name; " +
                                            "  } catch(e) { return ''; } " +
                                            "})()"
                                        ) { rawName ->
                                            val parsedName = rawName?.trim('"', ' ')?.ifBlank { "YouTube User" } ?: "YouTube User"
                                            onAuthSuccess(allCookies, parsedName, null, null)
                                        }
                                    }
                                }
                            }

                            // Start at official Google Sign-In with YouTube redirect
                            val googleSignInUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
                            loadUrl(googleSignInUrl)
                        }
                    },
                    update = {
                        webViewInstance = it
                    }
                )

                // Bottom security disclaimer
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🔒 Your credentials are sent securely and directly to Google. Videntra never reads or stores your password.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
