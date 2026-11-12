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
import java.security.MessageDigest

data class YouTubeUserProfile(
    val name: String?,
    val email: String?,
    val avatarUrl: String?
)

object YouTubeAuthHelper {

    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
    private const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * Extracts SAPISID (or Secure-3PAPISID / Secure-1PAPISID) from raw Cookie header string.
     */
    fun extractSapisid(cookieString: String): String? {
        val cookieMap = cookieString.split(";")
            .mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
        return cookieMap["SAPISID"]
            ?: cookieMap["__Secure-3PAPISID"]
            ?: cookieMap["__Secure-1PAPISID"]
    }

    /**
     * Generates cryptographic Authorization: SAPISIDHASH ... header.
     * Google's InnerTube API requires this header to sign every authenticated YouTube / YouTube Music request.
     *
     * sapisidHash = SHA1(timestamp + " " + SAPISID + " " + ORIGIN)
     */
    fun generateSapisidHash(
        sapisid: String,
        origin: String = YOUTUBE_ORIGIN
    ): String {
        val currentTimeInSeconds = System.currentTimeMillis() / 1000
        val rawPayload = "$currentTimeInSeconds $sapisid $origin"

        val sha1Digest = MessageDigest.getInstance("SHA-1")
        val hashBytes = sha1Digest.digest(rawPayload.toByteArray(Charsets.UTF_8))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

        return "SAPISIDHASH ${currentTimeInSeconds}_${hashHex}"
    }

    /**
     * Constructs an authenticated OkHttp Request for YouTube InnerTube endpoints.
     */
    fun makeAuthenticatedRequest(
        apiUrl: String,
        jsonBody: String,
        cookieString: String,
        visitorData: String? = null,
        origin: String = YOUTUBE_ORIGIN,
        clientName: String = "1", // 1 = WEB (YouTube), 67 = WEB_REMIX (YouTube Music)
        clientVersion: String = "2.20240101.01.00"
    ): Request {
        val sapisid = extractSapisid(cookieString)
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("X-Origin", origin)
            .header("Referer", "$origin/")
            .header("X-YouTube-Client-Name", clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Cookie", cookieString)

        if (!visitorData.isNullOrEmpty()) {
            requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        if (sapisid != null) {
            val authHeader = generateSapisidHash(sapisid, origin)
            requestBuilder.header("Authorization", authHeader)
        }

        return requestBuilder.build()
    }

    /**
     * Validates authentication and retrieves the user profile (Name, Email, Channel Avatar)
     * using the official YouTube / YouTube Music account_menu and guide InnerTube endpoints.
     */
    suspend fun fetchUserProfile(
        okHttpClient: OkHttpClient,
        cookieString: String,
        visitorData: String? = null
    ): YouTubeUserProfile? = withContext(Dispatchers.IO) {
        // Attempt 1: Standard YouTube Web account_menu
        val ytProfile = tryFetchMenu(
            okHttpClient = okHttpClient,
            apiUrl = "https://www.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false",
            cookieString = cookieString,
            visitorData = visitorData,
            origin = YOUTUBE_ORIGIN,
            clientName = "1",
            clientVersion = "2.20240101.01.00"
        )
        if (ytProfile != null && (!ytProfile.name.isNullOrBlank() || !ytProfile.avatarUrl.isNullOrBlank())) {
            return@withContext ytProfile
        }

        // Attempt 2: YouTube Web guide endpoint (contains guideAccountRenderer)
        val guideProfile = tryFetchGuide(
            okHttpClient = okHttpClient,
            cookieString = cookieString,
            visitorData = visitorData
        )
        if (guideProfile != null && (!guideProfile.name.isNullOrBlank() || !guideProfile.avatarUrl.isNullOrBlank())) {
            return@withContext guideProfile
        }

        // Attempt 3: YouTube Music endpoint fallback
        tryFetchMenu(
            okHttpClient = okHttpClient,
            apiUrl = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false",
            cookieString = cookieString,
            visitorData = visitorData,
            origin = YOUTUBE_MUSIC_ORIGIN,
            clientName = "67",
            clientVersion = "1.20240101.01.00"
        )
    }

    private fun tryFetchMenu(
        okHttpClient: OkHttpClient,
        apiUrl: String,
        cookieString: String,
        visitorData: String?,
        origin: String,
        clientName: String,
        clientVersion: String
    ): YouTubeUserProfile? {
        try {
            val jsonPayload = """
                {
                    "context": {
                        "client": {
                            "clientName": "$clientName",
                            "clientVersion": "$clientVersion",
                            "hl": "en",
                            "gl": "US"
                        }
                    }
                }
            """.trimIndent()

            val request = makeAuthenticatedRequest(
                apiUrl = apiUrl,
                jsonBody = jsonPayload,
                cookieString = cookieString,
                visitorData = visitorData,
                origin = origin,
                clientName = clientName,
                clientVersion = clientVersion
            )

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    PTLog.w("YouTubeAuthHelper", "account_menu request to $apiUrl returned code ${response.code}")
                    return null
                }

                val body = response.body?.string() ?: return null
                val root = JSONObject(body)

                val header = findObjectWithKey(root, "activeAccountHeaderRenderer")
                if (header != null) {
                    return parseActiveAccountHeader(header)
                }
            }
        } catch (e: Exception) {
            PTLog.e("YouTubeAuthHelper", "Failed fetching account menu from $apiUrl", e)
        }
        return null
    }

    private fun tryFetchGuide(
        okHttpClient: OkHttpClient,
        cookieString: String,
        visitorData: String?
    ): YouTubeUserProfile? {
        try {
            val jsonPayload = """
                {
                    "context": {
                        "client": {
                            "clientName": "WEB",
                            "clientVersion": "2.20240101.01.00",
                            "hl": "en",
                            "gl": "US"
                        }
                    }
                }
            """.trimIndent()

            val request = makeAuthenticatedRequest(
                apiUrl = "https://www.youtube.com/youtubei/v1/guide?prettyPrint=false",
                jsonBody = jsonPayload,
                cookieString = cookieString,
                visitorData = visitorData,
                origin = YOUTUBE_ORIGIN,
                clientName = "1",
                clientVersion = "2.20240101.01.00"
            )

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body?.string() ?: return null
                val root = JSONObject(body)

                val guideAccount = findObjectWithKey(root, "guideAccountRenderer")
                if (guideAccount != null) {
                    val nameObj = guideAccount.optJSONObject("channelTitle")
                    var name = nameObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        ?: nameObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

                    val handleObj = guideAccount.optJSONObject("channelHandle")
                    val handle = handleObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        ?: handleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

                    if (name.isNullOrBlank() && !handle.isNullOrBlank()) {
                        name = handle
                    }

                    val photoObj = guideAccount.optJSONObject("thumbnail")
                    val thumbnails = photoObj?.optJSONArray("thumbnails")
                    var avatarUrl = if (thumbnails != null && thumbnails.length() > 0) {
                        thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")
                    } else null

                    if (avatarUrl != null && avatarUrl.startsWith("//")) {
                        avatarUrl = "https:$avatarUrl"
                    }

                    return YouTubeUserProfile(name, null, avatarUrl)
                }
            }
        } catch (e: Exception) {
            PTLog.e("YouTubeAuthHelper", "Failed fetching guide for profile", e)
        }
        return null
    }

    private fun parseActiveAccountHeader(header: JSONObject): YouTubeUserProfile {
        val nameObj = header.optJSONObject("accountName")
        var name = nameObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: nameObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

        val handleObj = header.optJSONObject("channelHandle")
        val handle = handleObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: handleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

        val emailObj = header.optJSONObject("email")
        val email = emailObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: emailObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

        if (name.isNullOrBlank() && !handle.isNullOrBlank()) {
            name = handle
        }

        val photoObj = header.optJSONObject("accountPhoto")
        val thumbnails = photoObj?.optJSONArray("thumbnails")
        var avatarUrl = if (thumbnails != null && thumbnails.length() > 0) {
            thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")
        } else null

        if (avatarUrl != null && avatarUrl.startsWith("//")) {
            avatarUrl = "https:$avatarUrl"
        }

        return YouTubeUserProfile(name, email, avatarUrl)
    }

    private fun findObjectWithKey(json: Any?, keyName: String): JSONObject? {
        if (json is JSONObject) {
            if (json.has(keyName)) {
                return json.optJSONObject(keyName)
            }
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val found = findObjectWithKey(json.opt(key), keyName)
                if (found != null) return found
            }
        } else if (json is JSONArray) {
            for (i in 0 until json.length()) {
                val found = findObjectWithKey(json.opt(i), keyName)
                if (found != null) return found
            }
        }
        return null
    }
}
