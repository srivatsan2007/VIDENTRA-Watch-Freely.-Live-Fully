/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
 */
package com.arslandaim.playtube.data.network

import com.arslandaim.playtube.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoTokenProvider @Inject constructor(
    private val preferencesManager: PreferencesManager
) {
    private val tokenCache = mutableMapOf<String, String>()

    /**
     * Returns the genuine YouTube VisitorData token if available.
     * Never returns fake dummy strings (e.g. "visitor_data_...") which trigger Google 403 Forbidden.
     */
    fun getVisitorData(): String? {
        return try {
            val stored = runBlocking { preferencesManager.youtubeVisitorData.first() }
            stored?.takeIf { it.isNotBlank() && !it.startsWith("visitor_data_") }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns a genuine Proof of Origin (PoToken) if available.
     * Never returns synthetic dummy tokens like "po_token_..." which cause googlevideo.com to reject requests with 403.
     */
    fun generatePoToken(sessionId: String? = null): String? {
        if (sessionId != null) {
            val cached = tokenCache[sessionId]
            if (cached != null && !cached.startsWith("po_token_")) {
                return cached
            }
        }
        return null
    }

    fun setGenuinePoToken(sessionId: String, token: String) {
        if (token.isNotBlank() && !token.startsWith("po_token_")) {
            tokenCache[sessionId] = token
        }
    }
}
