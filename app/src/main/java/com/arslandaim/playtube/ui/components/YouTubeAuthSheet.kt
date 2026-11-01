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
import org.json.JSONObject

/**
 * Sandboxed Google / YouTube Sign-In Dialog.
 * Directs user to official Google accounts sign-in, securely captures session cookies,
 * user profile information (name, email, avatar), and notifies Videntra when authentication is complete.
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
        cookieManager.flush()
        val urlsToProbe = listOfNotNull(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://accounts.google.com",
            "https://myaccount.google.com",
            "https://www.google.com",
            "https://google.com",
            "https://accounts.youtube.com",
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
                (cookies.contains("SAPISID") && (cookies.contains("SSID") || cookies.contains("SID") || cookies.contains("APISID"))) ||
                cookies.contains("__Secure-1PSID") ||
                cookies.contains("__Secure-3PSID") ||
                (cookies.contains("SID=") && cookies.contains("HSID="))
    }

    fun completeAuthIfPossible(cookies: String, fallbackToDismiss: Boolean = false) {
        if (isAuthHandled) return
        if (isSessionAuthenticated(cookies)) {
            isAuthHandled = true

            val jsExtractor = """
                (function() {
                  try {
                    var name = '';
                    var email = '';
                    var avatar = '';

                    var ytBtn = document.querySelector('#avatar-btn, ytm-profile-icon');
                    if (ytBtn) {
                      var label = ytBtn.getAttribute('aria-label') || '';
                      if (label) {
                        var match = label.match(/Google Account:\s*(.*?)(?:\s*\((.*?)\)|$)/i);
                        if (match) {
                          name = match[1] ? match[1].trim() : '';
                          if (match[2] && match[2].indexOf('@') !== -1) email = match[2].trim();
                        } else {
                          name = label.replace(/Google Account:/i, '').trim();
                        }
                      }
                      var img = ytBtn.querySelector('img');
                      if (img && img.src) avatar = img.src;
                    }

                    if (!name || !email) {
                      var gBtn = document.querySelector('a[aria-label*="Google Account"], button[aria-label*="Google Account"], a[aria-label*="@"], button[aria-label*="@"]');
                      if (gBtn) {
                        var label = gBtn.getAttribute('aria-label') || '';
                        var match = label.match(/(?:Google Account:\s*)?(.*?)(?:\s*[\n(]([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})[\n)]|$)/);
                        if (match) {
                          if (!name) name = match[1] ? match[1].trim() : '';
                          if (!email && match[2]) email = match[2].trim();
                        }
                        var img = gBtn.querySelector('img');
                        if (!avatar && img && img.src) avatar = img.src;
                      }
                    }

                    if (!email) {
                      var emailEl = document.querySelector('[data-email]');
                      if (emailEl) email = emailEl.getAttribute('data-email') || '';
                    }
                    if (!email) {
                      var idEl = document.querySelector('[data-identifier]');
                      var idVal = idEl ? idEl.getAttribute('data-identifier') : '';
                      if (idVal && idVal.indexOf('@') !== -1) email = idVal;
                    }

                    if (!avatar) {
                      var pImg = document.querySelector('img[src*="googleusercontent.com"], img[src*="yt3.ggpht.com"]');
                      if (pImg && pImg.src && pImg.src.indexOf('default_user') === -1) avatar = pImg.src;
                    }

                    if (name && name.indexOf('\n') !== -1) name = name.split('\n')[0].trim();
                    if (email && email.indexOf('\n') !== -1) email = email.split('\n')[0].trim();

                    return JSON.stringify({ name: name, email: email, avatar: avatar });
                  } catch(e) {
                    return JSON.stringify({ name: '', email: '', avatar: '' });
                  }
                })()
            """.trimIndent()

            val finishWithData = { extractedName: String?, extractedEmail: String?, extractedAvatar: String? ->
                val finalName = extractedName?.takeIf { it.isNotBlank() }
                    ?: extractedEmail?.substringBefore('@')?.replaceFirstChar { it.uppercase() }
                    ?: "Google User"
                val finalEmail = extractedEmail?.takeIf { it.isNotBlank() }
                val finalAvatar = extractedAvatar?.takeIf { it.isNotBlank() }
                Toast.makeText(context, "Welcome, $finalName!", Toast.LENGTH_SHORT).show()
                onAuthSuccess(cookies, finalName, finalEmail, finalAvatar)
            }

            val webView = webViewInstance
            if (webView != null) {
                webView.evaluateJavascript(jsExtractor) { rawResult ->
                    try {
                        var jsonStr = rawResult ?: ""
                        if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                            jsonStr = JSONObject(mapOf("wrapped" to jsonStr)).getString("wrapped")
                        }
                        val jsonObj = JSONObject(jsonStr)
                        val name = jsonObj.optString("name").ifBlank { null }
                        val email = jsonObj.optString("email").ifBlank { null }
                        val avatar = jsonObj.optString("avatar").ifBlank { null }
                        finishWithData(name, email, avatar)
                    } catch (e: Exception) {
                        finishWithData(null, null, null)
                    }
                }
            } else {
                finishWithData(null, null, null)
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
                                text = "Sign In with Google",
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

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

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
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    // Keep all Google and YouTube authentication links within the WebView
                                    if (reqUrl.contains("google.") || reqUrl.contains("youtube.") || reqUrl.contains("youtu.be")) {
                                        return false
                                    }
                                    return false
                                }

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
                                    // Check if cookies contain valid session identifiers
                                    if (isSessionAuthenticated(merged) && !isAuthHandled) {
                                        completeAuthIfPossible(merged)
                                    }
                                }
                            }

                            // Start at official Google Sign-In with YouTube service
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
