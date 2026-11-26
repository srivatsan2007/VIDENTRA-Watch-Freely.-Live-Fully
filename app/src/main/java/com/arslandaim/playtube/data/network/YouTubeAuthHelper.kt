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
     * Generates cryptographic Authorization: SAPISIDHASH / SAPISID1PHASH / SAPISID3PHASH header.
     * Google's InnerTube API requires this header to sign authenticated YouTube requests.
     */
    fun generateSapisidHash(
        sapisid: String,
        origin: String = YOUTUBE_ORIGIN,
        scheme: String = "SAPISIDHASH"
    ): String {
        val currentTimeInSeconds = System.currentTimeMillis() / 1000
        val rawPayload = "$currentTimeInSeconds $sapisid $origin"

        val sha1Digest = MessageDigest.getInstance("SHA-1")
        val hashBytes = sha1Digest.digest(rawPayload.toByteArray(Charsets.UTF_8))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

        return "$scheme ${currentTimeInSeconds}_${hashHex}"
    }

    private fun buildAuthorizationHeader(cookieString: String, origin: String): String? {
        val cookieMap = cookieString.split(";")
            .mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

        val parts = mutableListOf<String>()
        val sapisid = cookieMap["SAPISID"] ?: cookieMap["__Secure-3PAPISID"]
        if (!sapisid.isNullOrBlank()) {
            parts.add(generateSapisidHash(sapisid, origin, "SAPISIDHASH"))
        }
        cookieMap["__Secure-1PAPISID"]?.takeIf { it.isNotBlank() }?.let {
            parts.add(generateSapisidHash(it, origin, "SAPISID1PHASH"))
        }
        cookieMap["__Secure-3PAPISID"]?.takeIf { it.isNotBlank() }?.let {
            parts.add(generateSapisidHash(it, origin, "SAPISID3PHASH"))
        }
        return if (parts.isNotEmpty()) parts.joinToString(" ") else null
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
        clientVersion: String = "2.20260101.00.00"
    ): Request {
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("X-Origin", origin)
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("X-YouTube-Client-Name", clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Cookie", cookieString)

        if (!visitorData.isNullOrEmpty()) {
            requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        buildAuthorizationHeader(cookieString, origin)?.let { authHeader ->
            requestBuilder.header("Authorization", authHeader)
        }

        return requestBuilder.build()
    }

    private fun normalizeAvatarUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val httpsUrl = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "https://")
            else -> trimmed
        }
        return if (httpsUrl.contains("default_user", ignoreCase = true)) null else httpsUrl
    }

    /**
     * Validates authentication and retrieves the user profile (Name, Email, Avatar)
     * from Google Services (ListAccounts) and YouTube InnerTube endpoints.
     */
    suspend fun fetchUserProfile(
        okHttpClient: OkHttpClient,
        cookieString: String,
        visitorData: String? = null
    ): YouTubeUserProfile? = withContext(Dispatchers.IO) {
        // Attempt 1: Google Services ListAccounts (authoritative Google display name, email & Google avatar)
        val googleProfile = tryFetchGoogleListAccounts(okHttpClient, cookieString)

        // Attempt 2: Standard YouTube Web account_menu (using JSON clientName = "WEB")
        val ytProfile = tryFetchMenu(
            okHttpClient = okHttpClient,
            apiUrl = "https://www.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false",
            cookieString = cookieString,
            visitorData = visitorData,
            origin = YOUTUBE_ORIGIN,
            headerClientName = "1",
            jsonClientName = "WEB",
            clientVersion = "2.20260101.00.00"
        )

        val mergedPrimary = mergeProfiles(googleProfile, ytProfile)
        if (mergedPrimary != null && !mergedPrimary.name.isNullOrBlank() && !mergedPrimary.avatarUrl.isNullOrBlank()) {
            return@withContext mergedPrimary
        }

        // Attempt 3: YouTube Web guide endpoint (contains guideAccountRenderer)
        val guideProfile = tryFetchGuide(
            okHttpClient = okHttpClient,
            cookieString = cookieString,
            visitorData = visitorData
        )
        val mergedWithGuide = mergeProfiles(mergedPrimary, guideProfile)
        if (mergedWithGuide != null && !mergedWithGuide.name.isNullOrBlank() && !mergedWithGuide.avatarUrl.isNullOrBlank()) {
            return@withContext mergedWithGuide
        }

        // Attempt 4: YouTube Music endpoint fallback
        val musicProfile = tryFetchMenu(
            okHttpClient = okHttpClient,
            apiUrl = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false",
            cookieString = cookieString,
            visitorData = visitorData,
            origin = YOUTUBE_MUSIC_ORIGIN,
            headerClientName = "67",
            jsonClientName = "WEB_REMIX",
            clientVersion = "1.20260101.00.00"
        )
        mergeProfiles(mergedWithGuide, musicProfile)
    }

    private fun mergeProfiles(primary: YouTubeUserProfile?, secondary: YouTubeUserProfile?): YouTubeUserProfile? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        return YouTubeUserProfile(
            name = primary.name?.takeIf { it.isNotBlank() } ?: secondary.name?.takeIf { it.isNotBlank() },
            email = primary.email?.takeIf { it.isNotBlank() } ?: secondary.email?.takeIf { it.isNotBlank() },
            avatarUrl = primary.avatarUrl?.takeIf { it.isNotBlank() } ?: secondary.avatarUrl?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Queries Google's official ListAccounts endpoint used across Google services
     * to retrieve the user's Google Account Name, Email, and Avatar URL.
     */
    private fun tryFetchGoogleListAccounts(
        okHttpClient: OkHttpClient,
        cookieString: String
    ): YouTubeUserProfile? {
        try {
            val request = Request.Builder()
                .url("https://accounts.google.com/ListAccounts?gpsia=1&source=ogb&mo=1&mn=1&hl=en")
                .post(" ".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Origin", "https://www.google.com")
                .header("Referer", "https://www.google.com/")
                .header("Cookie", cookieString)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    PTLog.w("YouTubeAuthHelper", "ListAccounts returned code ${response.code}")
                    return null
                }

                var body = response.body?.string()?.trim() ?: return null
                // Strip optional XSSI prefix )]}'
                if (body.startsWith(")]}'")) {
                    body = body.substringAfter('\n').trim()
                }
                if (!body.startsWith("[")) return null

                val root = JSONArray(body)
                val accounts = root.optJSONArray(1) ?: return null
                for (i in 0 until accounts.length()) {
                    val entry = accounts.optJSONArray(i) ?: continue
                    val name = entry.optString(2).takeIf { it.isNotBlank() && it != "null" }
                    val email = entry.optString(3).takeIf { it.isNotBlank() && it != "null" }
                    val avatar = normalizeAvatarUrl(entry.optString(4))
                    val isSignedIn = entry.optInt(5, 1) == 1
                    if (isSignedIn && (!name.isNullOrBlank() || !email.isNullOrBlank() || !avatar.isNullOrBlank())) {
                        return YouTubeUserProfile(name, email, avatar)
                    }
                }
            }
        } catch (e: Exception) {
            PTLog.e("YouTubeAuthHelper", "Failed fetching Google ListAccounts", e)
        }
        return null
    }

    private fun tryFetchMenu(
        okHttpClient: OkHttpClient,
        apiUrl: String,
        cookieString: String,
        visitorData: String?,
        origin: String,
        headerClientName: String,
        jsonClientName: String,
        clientVersion: String
    ): YouTubeUserProfile? {
        try {
            val jsonPayload = """
                {
                    "context": {
                        "client": {
                            "clientName": "$jsonClientName",
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
                clientName = headerClientName,
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
                    val avatarUrl = if (thumbnails != null && thumbnails.length() > 0) {
                        normalizeAvatarUrl(thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url"))
                    } else null

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
        val avatarUrl = if (thumbnails != null && thumbnails.length() > 0) {
            normalizeAvatarUrl(thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url"))
        } else null

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
