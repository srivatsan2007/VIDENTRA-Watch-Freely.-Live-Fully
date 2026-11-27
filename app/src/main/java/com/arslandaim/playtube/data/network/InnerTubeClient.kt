/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
 */
package com.arslandaim.playtube.data.network

import com.arslandaim.playtube.utils.PTLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct InnerTube API client for fetching video streams using the ANDROID client context.
 * The Android client does NOT require PoToken and returns valid stream URLs.
 * Used as a permanent fallback when WebView-based NewPipe extraction fails with 403.
 */
@Singleton
class InnerTubeClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "InnerTubeClient"
        private const val INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        // Android client: no PoToken required, returns direct MP4 stream URLs
        private const val ANDROID_CLIENT_NAME = "ANDROID"
        private const val ANDROID_CLIENT_VERSION = "19.44.38"
        private const val ANDROID_CLIENT_OS = "Android"
        private const val ANDROID_CLIENT_OS_VER = "14"

        // iOS client: also bypass bot check, returns HLS streams
        private const val IOS_CLIENT_NAME = "IOS"
        private const val IOS_CLIENT_VERSION = "19.45.4"

        // User agents
        private const val ANDROID_UA = "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"
        private const val IOS_UA = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X)"
    }

    data class StreamUrl(
        val url: String,
        val quality: String,
        val qualityLabel: String,
        val mimeType: String,
        val isAudio: Boolean,
        val isVideo: Boolean,
        val bitrate: Int,
        val width: Int = 0,
        val height: Int = 0,
        val audioSampleRate: Int = 0,
        val audioChannels: Int = 0
    )

    data class InnerTubeStreams(
        val videoStreams: List<StreamUrl>,
        val audioStreams: List<StreamUrl>,
        val hlsManifestUrl: String?,
        val dashManifestUrl: String?,
        val isLive: Boolean,
        val title: String? = null,
        val author: String? = null,
        val channelId: String? = null,
        val viewCount: Long = 0L
    )

    /**
     * Fetch stream URLs for a video using the Android InnerTube client.
     * Returns null if the request fails.
     */
    suspend fun fetchStreams(videoId: String, cookieString: String? = null): InnerTubeStreams? =
        withContext(Dispatchers.IO) {
            try {
                // Try Android client first (best for direct MP4)
                val androidResult = fetchWithClient(
                    videoId = videoId,
                    clientName = ANDROID_CLIENT_NAME,
                    clientVersion = ANDROID_CLIENT_VERSION,
                    userAgent = ANDROID_UA,
                    cookieString = cookieString,
                    extraClientFields = """
                        "androidSdkVersion": 34,
                        "osName": "$ANDROID_CLIENT_OS",
                        "osVersion": "$ANDROID_CLIENT_OS_VER",
                    """
                )
                if (androidResult != null &&
                    (androidResult.videoStreams.isNotEmpty() || androidResult.audioStreams.isNotEmpty() || androidResult.hlsManifestUrl != null)) {
                    PTLog.d(TAG, "Android client fetch succeeded for $videoId")
                    return@withContext androidResult
                }

                // Fallback to iOS client (returns HLS)
                PTLog.w(TAG, "Android client returned empty streams for $videoId, trying iOS client")
                val iosResult = fetchWithClient(
                    videoId = videoId,
                    clientName = IOS_CLIENT_NAME,
                    clientVersion = IOS_CLIENT_VERSION,
                    userAgent = IOS_UA,
                    cookieString = cookieString,
                    extraClientFields = """
                        "deviceMake": "Apple",
                        "deviceModel": "iPhone16,2",
                        "osName": "iPhone",
                        "osVersion": "18.1.0.22B83",
                        "clientFormFactor": "SMALL_FORM_FACTOR",
                    """
                )
                if (iosResult != null) {
                    PTLog.d(TAG, "iOS client fetch succeeded for $videoId")
                }
                iosResult
            } catch (e: Exception) {
                PTLog.e(TAG, "InnerTube fetchStreams failed for $videoId", e)
                null
            }
        }

    private fun fetchWithClient(
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        cookieString: String?,
        extraClientFields: String = ""
    ): InnerTubeStreams? {
        val body = """
            {
                "videoId": "$videoId",
                "context": {
                    "client": {
                        "clientName": "$clientName",
                        "clientVersion": "$clientVersion",
                        "hl": "en",
                        "gl": "US",
                        $extraClientFields
                        "utcOffsetMinutes": 0
                    }
                },
                "params": "CgIQBg=="
            }
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url(INNERTUBE_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", getClientId(clientName).toString())
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")

        // Do not send WEB cookies to ANDROID/IOS player clients as they reject web auth cookies.
        if (!cookieString.isNullOrBlank() && clientName == "WEB") {
            requestBuilder.header("Cookie", cookieString)
        }

        return try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    PTLog.w(TAG, "$clientName client returned HTTP ${response.code} for $videoId")
                    return null
                }
                val responseBody = response.body?.string() ?: return null
                parseStreamsFromResponse(responseBody)
            }
        } catch (e: Exception) {
            PTLog.e(TAG, "$clientName client request failed for $videoId", e)
            null
        }
    }

    private fun getClientId(clientName: String): Int = when (clientName) {
        "WEB" -> 1
        "ANDROID" -> 3
        "IOS" -> 5
        "TVHTML5" -> 7
        "WEB_REMIX" -> 67
        else -> 1
    }

    private fun parseStreamsFromResponse(json: String): InnerTubeStreams? {
        return try {
            val root = JSONObject(json)

            // Check for playability error
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status")
            if (status == "ERROR" || status == "UNPLAYABLE" || status == "LOGIN_REQUIRED") {
                PTLog.w(TAG, "Video not playable: $status - ${playabilityStatus?.optString("reason")}")
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: return null
            val videoDetails = root.optJSONObject("videoDetails")
            val isLive = videoDetails?.optBoolean("isLive", false) ?: false
            val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() }
            val author = videoDetails?.optString("author")?.takeIf { it.isNotBlank() }
            val channelId = videoDetails?.optString("channelId")?.takeIf { it.isNotBlank() }
            val viewCount = videoDetails?.optString("viewCount")?.toLongOrNull() ?: 0L

            val hlsManifestUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
            val dashManifestUrl = streamingData.optString("dashManifestUrl").takeIf { it.isNotBlank() }

            val videoStreams = mutableListOf<StreamUrl>()
            val audioStreams = mutableListOf<StreamUrl>()

            // Parse adaptive formats (video-only + audio-only)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                    val url = fmt.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val mimeType = fmt.optString("mimeType")
                    val bitrate = fmt.optInt("bitrate", 0)
                    val qualityLabel = fmt.optString("qualityLabel", "")
                    val quality = fmt.optString("quality", "")

                    if (mimeType.startsWith("video/")) {
                        val width = fmt.optInt("width", 0)
                        val height = fmt.optInt("height", 0)
                        videoStreams.add(StreamUrl(
                            url = url,
                            quality = qualityLabel.ifBlank { quality },
                            qualityLabel = qualityLabel,
                            mimeType = mimeType,
                            isAudio = false,
                            isVideo = true,
                            bitrate = bitrate,
                            width = width,
                            height = height
                        ))
                    } else if (mimeType.startsWith("audio/")) {
                        val sampleRate = fmt.optString("audioSampleRate", "0").toIntOrNull() ?: 0
                        val channels = fmt.optInt("audioChannels", 2)
                        audioStreams.add(StreamUrl(
                            url = url,
                            quality = "${bitrate / 1000}kbps",
                            qualityLabel = "",
                            mimeType = mimeType,
                            isAudio = true,
                            isVideo = false,
                            bitrate = bitrate,
                            audioSampleRate = sampleRate,
                            audioChannels = channels
                        ))
                    }
                }
            }

            // Parse progressive formats (video + audio combined muxed)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val fmt = formats.optJSONObject(i) ?: continue
                    val url = fmt.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val mimeType = fmt.optString("mimeType")
                    val bitrate = fmt.optInt("bitrate", 0)
                    val qualityLabel = fmt.optString("qualityLabel", "")
                    val quality = fmt.optString("quality", "")
                    val width = fmt.optInt("width", 0)
                    val height = fmt.optInt("height", 0)

                    // Progressive/muxed: add as video stream with combined audio
                    videoStreams.add(StreamUrl(
                        url = url,
                        quality = qualityLabel.ifBlank { quality },
                        qualityLabel = qualityLabel,
                        mimeType = mimeType,
                        isAudio = true, // has audio too
                        isVideo = true,
                        bitrate = bitrate,
                        width = width,
                        height = height
                    ))
                }
            }

            // Sort streams by quality (height, descending)
            videoStreams.sortByDescending { it.height.takeIf { h -> h > 0 } ?: it.qualityLabel.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            audioStreams.sortByDescending { it.bitrate }

            InnerTubeStreams(
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                hlsManifestUrl = hlsManifestUrl,
                dashManifestUrl = dashManifestUrl,
                isLive = isLive,
                title = title,
                author = author,
                channelId = channelId,
                viewCount = viewCount
            )
        } catch (e: Exception) {
            PTLog.e(TAG, "Failed to parse InnerTube streams response", e)
            null
        }
    }

    /**
     * Fetch authenticated YouTube account playlists via InnerTube API.
     */
    suspend fun fetchAccountPlaylists(cookieString: String): List<AccountPlaylist> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<AccountPlaylist>()
            try {
                val body = buildInnerTubeBody("library")
                val request = YouTubeAuthHelper.makeAuthenticatedRequest(
                    apiUrl = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    jsonBody = body,
                    cookieString = cookieString
                )
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext results
                    val json = JSONObject(response.body?.string() ?: return@withContext results)
                    // Parse playlist items from library browsing
                    findPlaylistItems(json, results)
                }
            } catch (e: Exception) {
                PTLog.e(TAG, "Failed to fetch account playlists", e)
            }
            results
        }

    /**
     * Fetch authenticated YouTube watch history via InnerTube API.
     */
    suspend fun fetchWatchHistory(cookieString: String): List<AccountVideo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<AccountVideo>()
            try {
                val body = """
                    {
                        "browseId": "FEhistory",
                        "context": {
                            "client": {
                                "clientName": "WEB",
                                "clientVersion": "2.20260101.00.00",
                                "hl": "en",
                                "gl": "US"
                            }
                        }
                    }
                """.trimIndent()
                val request = YouTubeAuthHelper.makeAuthenticatedRequest(
                    apiUrl = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    jsonBody = body,
                    cookieString = cookieString
                )
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext results
                    val json = JSONObject(response.body?.string() ?: return@withContext results)
                    findVideoItems(json, results)
                }
            } catch (e: Exception) {
                PTLog.e(TAG, "Failed to fetch watch history", e)
            }
            results
        }

    /**
     * Fetch authenticated YouTube Watch Later playlist via InnerTube API.
     */
    suspend fun fetchWatchLater(cookieString: String): List<AccountVideo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<AccountVideo>()
            try {
                val body = """
                    {
                        "browseId": "VLWL",
                        "context": {
                            "client": {
                                "clientName": "WEB",
                                "clientVersion": "2.20260101.00.00",
                                "hl": "en",
                                "gl": "US"
                            }
                        }
                    }
                """.trimIndent()
                val request = YouTubeAuthHelper.makeAuthenticatedRequest(
                    apiUrl = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    jsonBody = body,
                    cookieString = cookieString
                )
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext results
                    val json = JSONObject(response.body?.string() ?: return@withContext results)
                    findVideoItems(json, results)
                }
            } catch (e: Exception) {
                PTLog.e(TAG, "Failed to fetch Watch Later", e)
            }
            results
        }

    private fun buildInnerTubeBody(browseId: String): String = """
        {
            "browseId": "FE$browseId",
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": "2.20260101.00.00",
                    "hl": "en",
                    "gl": "US"
                }
            }
        }
    """.trimIndent()

    private fun findVideoItems(json: Any?, results: MutableList<AccountVideo>, depth: Int = 0) {
        if (depth > 15 || json == null) return
        when (json) {
            is JSONObject -> {
                // videoRenderer or playlistVideoRenderer
                if (json.has("videoId")) {
                    val videoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: return
                    val titleObj = json.optJSONObject("title")
                    val title = titleObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
                        ?: "Unknown"
                    val thumbnail = json.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?.let { arr ->
                            (arr.length() - 1 downTo 0).firstNotNullOfOrNull { i ->
                                arr.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }
                            }
                        } ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                    val shortBylineObj = json.optJSONObject("shortBylineText") ?: json.optJSONObject("longBylineText")
                    val channelName = shortBylineObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                    val durationObj = json.optJSONObject("lengthText")
                    val duration = durationObj?.optString("simpleText") ?: ""
                    if (results.none { it.videoId == videoId }) {
                        results.add(AccountVideo(videoId, title, thumbnail, channelName, duration))
                    }
                    return
                }
                json.keys().forEach { key -> findVideoItems(json.opt(key), results, depth + 1) }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) findVideoItems(json.opt(i), results, depth + 1)
            }
        }
    }

    private fun findPlaylistItems(json: Any?, results: MutableList<AccountPlaylist>, depth: Int = 0) {
        if (depth > 15 || json == null) return
        when (json) {
            is JSONObject -> {
                if (json.has("playlistId") && json.has("title")) {
                    val playlistId = json.optString("playlistId").takeIf { it.isNotBlank() } ?: return
                    if (playlistId == "WL" || playlistId == "LL") return // skip built-ins here
                    val titleObj = json.optJSONObject("title")
                    val title = titleObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
                        ?: "Playlist"
                    val thumbnail = json.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?.let { arr -> arr.optJSONObject(arr.length() - 1)?.optString("url") }
                    val count = json.optJSONObject("videoCountText")?.optString("simpleText")?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                    if (results.none { it.playlistId == playlistId }) {
                        results.add(AccountPlaylist(playlistId, title, thumbnail, count))
                    }
                    return
                }
                json.keys().forEach { key -> findPlaylistItems(json.opt(key), results, depth + 1) }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) findPlaylistItems(json.opt(i), results, depth + 1)
            }
        }
    }
}

data class AccountVideo(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val channelName: String,
    val duration: String
)

data class AccountPlaylist(
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String?,
    val videoCount: Int
)
