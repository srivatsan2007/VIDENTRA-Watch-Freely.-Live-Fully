/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.data.network

import com.arslandaim.playtube.data.local.PreferencesManager
import com.arslandaim.playtube.utils.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

class YouTubeDownloader(
    private val client: OkHttpClient,
    private val preferencesManager: PreferencesManager? = null
) : Downloader() {

    @Volatile
    private var cachedIsLoggedIn: Boolean = false
    @Volatile
    private var cachedAuthCookies: String? = null
    @Volatile
    private var cachedVisitorData: String? = null
    @Volatile
    private var lastAuthCheckTime: Long = 0L

    private fun refreshAuthCache() {
        val now = System.currentTimeMillis()
        if (now - lastAuthCheckTime < 10000L && lastAuthCheckTime > 0) return

        preferencesManager?.let { prefs ->
            try {
                runBlocking {
                    cachedIsLoggedIn = prefs.isYouTubeLoggedIn.first()
                    if (cachedIsLoggedIn) {
                        cachedAuthCookies = prefs.youtubeAuthCookies.first()
                        cachedVisitorData = prefs.youtubeVisitorData.first()
                    } else {
                        cachedAuthCookies = null
                        cachedVisitorData = null
                    }
                }
                lastAuthCheckTime = now
            } catch (e: Exception) {
                // Keep existing cache on transient error
            }
        }
    }

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val headers = request.headers()
        val data = request.dataToSend()

        val okHttpRequestBuilder = OkHttpRequest.Builder()
            .url(url)
            .method(method, data?.toRequestBody())

        val mergedHeaders = mutableMapOf<String, MutableList<String>>()
        headers.forEach { (k, v) ->
            mergedHeaders[k] = v.toMutableList()
        }

        // Bypass YouTube Consent/GDPR redirection in Europe & attach authenticated session
        if (url.contains("youtube.com")) {
            val existingCookies = (mergedHeaders["Cookie"] ?: mutableListOf()).flatMap { it.split(";") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            if (existingCookies.none { it.startsWith("CONSENT=") }) {
                existingCookies.add(Constants.YouTube.CONSENT_COOKIE)
            }

            refreshAuthCache()
            if (cachedIsLoggedIn && !cachedAuthCookies.isNullOrBlank()) {
                val cookies = cachedAuthCookies!!
                existingCookies.addAll(cookies.split(";").map { it.trim() }.filter { it.isNotEmpty() })

                // Only attach InnerTube API headers to youtubei/v1 endpoints
                if (url.contains("youtube.com/youtubei/v1/")) {
                    val sapisid = YouTubeAuthHelper.extractSapisid(cookies)
                    if (sapisid != null && !mergedHeaders.containsKey("Authorization")) {
                        val authHeader = YouTubeAuthHelper.generateSapisidHash(sapisid, "https://www.youtube.com")
                        mergedHeaders["Authorization"] = mutableListOf(authHeader)
                        mergedHeaders["X-Origin"] = mutableListOf("https://www.youtube.com")
                        mergedHeaders["Referer"] = mutableListOf("https://www.youtube.com/")
                    }
                    if (!cachedVisitorData.isNullOrBlank() && !mergedHeaders.containsKey("X-Goog-Visitor-Id")) {
                        mergedHeaders["X-Goog-Visitor-Id"] = mutableListOf(cachedVisitorData!!)
                    }
                }
            }

            val combinedCookieString = existingCookies.distinct().joinToString("; ")
            mergedHeaders["Cookie"] = mutableListOf(combinedCookieString)
        }

        // Apply headers without duplicating User-Agent or Cookie
        mergedHeaders.forEach { (key, values) ->
            if (key.equals("Cookie", ignoreCase = true)) {
                okHttpRequestBuilder.header("Cookie", values.firstOrNull() ?: "")
            } else if (key.equals("User-Agent", ignoreCase = true)) {
                okHttpRequestBuilder.header("User-Agent", values.firstOrNull() ?: Constants.DEFAULT_USER_AGENT)
            } else {
                values.forEach { value ->
                    okHttpRequestBuilder.addHeader(key, value)
                }
            }
        }

        // Defaults if omitted by NewPipeExtractor
        if (!mergedHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            okHttpRequestBuilder.header("User-Agent", Constants.DEFAULT_USER_AGENT)
        }
        if (!mergedHeaders.keys.any { it.equals("Accept-Language", ignoreCase = true) }) {
            okHttpRequestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        }

        val maxRetries = 2
        var attempt = 0
        var lastResponse: okhttp3.Response? = null
        var lastException: IOException? = null

        while (attempt <= maxRetries) {
            try {
                val okHttpResponse = client.newCall(okHttpRequestBuilder.build()).execute()
                
                // If it's a 403 or 429, it might be an IP block or temporary throttle.
                if (okHttpResponse.code == 403 || okHttpResponse.code == 429) {
                    okHttpResponse.close()
                    attempt++
                    if (attempt <= maxRetries) {
                        Thread.sleep(1000L * attempt) // Linear backoff
                        continue
                    }
                }
                
                lastResponse = okHttpResponse
                break
            } catch (e: IOException) {
                lastException = e
                attempt++
                if (attempt <= maxRetries) {
                    Thread.sleep(500L * attempt)
                }
            }
        }

        val response = lastResponse ?: throw lastException ?: IOException("Request failed after $maxRetries retries")

        val responseBody = response.body?.string()
        val responseCode = response.code
        val responseMessage = response.message
        val responseHeaders = response.headers.toMultimap()

        return Response(responseCode, responseMessage, responseHeaders, responseBody, url)
    }
}
