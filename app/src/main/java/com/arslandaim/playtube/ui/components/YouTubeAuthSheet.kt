/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var webProgress by remember { mutableFloatStateOf(0f) }
    var currentWebUrl by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isAuthHandled by remember { mutableStateOf(false) }

    fun getMergedCookies(url: String?): String {
        val cookieManager = CookieManager.getInstance()
        val urlsToProbe = listOfNotNull(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://accounts.google.com",
            "https://google.com",
            url?.takeIf { it.isNotBlank() }
        )
        val cookieMap = mutableMapOf<String, String>()
        urlsToProbe.forEach { probeUrl ->
            val raw = cookieManager.getCookie(probeUrl) ?: ""
            raw.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isNotEmpty()) {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2) {
                        cookieMap[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun isSessionAuthenticated(cookies: String): Boolean {
        return cookies.contains("LOGIN_INFO") ||
                (cookies.contains("SAPISID") && (cookies.contains("SSID") || cookies.contains("SID"))) ||
                cookies.contains("__Secure-1PSID") ||
                (cookies.contains("SID=") && cookies.contains("HSID="))
    }

    fun completeAuthIfPossible(cookies: String, fallbackToDismiss: Boolean = false) {
        if (isAuthHandled) return
        if (isSessionAuthenticated(cookies)) {
            isAuthHandled = true
            webViewInstance?.evaluateJavascript(
                "(function() { " +
                "  try { " +
                "    var ytBtn = document.querySelector('#avatar-btn')?.getAttribute('aria-label') || ''; " +
                "    if (ytBtn) return ytBtn; " +
                "    var gBtn = document.querySelector('a[aria-label*=\"Google Account\"], button[aria-label*=\"Google Account\"]')?.getAttribute('aria-label') || ''; " +
                "    if (gBtn) return gBtn; " +
                "    var emailEl = document.querySelector('[data-email]')?.getAttribute('data-email') || ''; " +
                "    if (emailEl) return emailEl; " +
                "    return ''; " +
                "  } catch(e) { return ''; } " +
                "})()"
            ) { rawName ->
                var cleanName = rawName?.trim('"', ' ', '\n', '\r')?.ifBlank { null }
                if (cleanName?.startsWith("Google Account:") == true) {
                    cleanName = cleanName.substringAfter("Google Account:").trim()
                }
                val finalName = cleanName?.takeIf { it.isNotBlank() } ?: "Google User"
                onAuthSuccess(cookies, finalName, null, null)
            }
        } else if (fallbackToDismiss) {
            onDismiss()
        }
    }

    val safeDismiss = {
        val merged = getMergedCookies(currentWebUrl)
        if (isSessionAuthenticated(merged) && !isAuthHandled) {
            completeAuthIfPossible(merged, fallbackToDismiss = true)
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = safeDismiss,
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
                                text = "Sign In",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = safeDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        TextButton(
                            onClick = {
                                val merged = getMergedCookies(currentWebUrl)
                                if (isSessionAuthenticated(merged)) {
                                    completeAuthIfPossible(merged)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Please complete sign-in on Google first.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Done", fontWeight = FontWeight.Bold)
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
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                val defaultUa = userAgentString ?: ""
                                val cleanUa = defaultUa.replace("; wv", "")
                                userAgentString = if (cleanUa.contains("Chrome/")) {
                                    cleanUa
                                } else {
                                    "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                }
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

                                    val merged = getMergedCookies(url)
                                    val isPastLogin = (url == null || (!url.contains("ServiceLogin") && !url.contains("signin/v2")))
                                    if (isSessionAuthenticated(merged) && isPastLogin && !isAuthHandled) {
                                        completeAuthIfPossible(merged)
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
