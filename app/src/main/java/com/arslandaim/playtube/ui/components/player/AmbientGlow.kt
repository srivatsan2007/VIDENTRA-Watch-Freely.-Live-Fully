/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Ambient color palette extracted from the playing video / thumbnail.
 */
@Immutable
data class AmbientPalette(
    val primaryColor: Color,
    val secondaryColor: Color
) {
    companion object {
        val Default = AmbientPalette(
            primaryColor = Color(0xFF233B34),
            secondaryColor = Color(0xFF1A2A26)
        )
    }
}

/**
 * High-performance, memory-efficient color extractor that finds the dominant
 * and harmonious vibrant colors from a video thumbnail.
 */
object AmbientColorExtractor {
    private val paletteCache = LruCache<String, AmbientPalette>(35)

    private val fallbackHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun extractPalette(context: Context, thumbnailUrl: String?): AmbientPalette {
        if (thumbnailUrl.isNullOrBlank()) return AmbientPalette.Default

        paletteCache.get(thumbnailUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                var bitmap: Bitmap? = null

                // 1. Try loading via Coil singleton (leveraging in-memory & disk cache)
                try {
                    val request = ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .size(48, 48)
                        .allowHardware(false)
                        .build()
                    val result = SingletonImageLoader.get(context).execute(request)
                    if (result is coil3.request.SuccessResult) {
                        bitmap = when (val img = result.image) {
                            is coil3.BitmapImage -> img.bitmap
                            else -> null
                        }
                    }
                } catch (_: Exception) { }

                // 2. Fallback to direct HTTP thumbnail fetch if Coil didn't return a direct software bitmap
                if (bitmap == null) {
                    try {
                        val netRequest = Request.Builder().url(thumbnailUrl).build()
                        fallbackHttpClient.newCall(netRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                response.body?.bytes()?.let { bytes ->
                                    val options = BitmapFactory.Options().apply {
                                        inSampleSize = 8
                                        inPreferredConfig = Bitmap.Config.RGB_565
                                    }
                                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }

                if (bitmap == null) return@withContext AmbientPalette.Default

                // 3. Downscale to a compact 20x20 sample grid for near-instant analysis
                val sampleWidth = 20
                val sampleHeight = 20
                val scaled = if (bitmap!!.width == sampleWidth && bitmap!!.height == sampleHeight) {
                    bitmap!!
                } else {
                    Bitmap.createScaledBitmap(bitmap!!, sampleWidth, sampleHeight, true)
                }

                val hsv = FloatArray(3)
                val bucketScores = FloatArray(12)
                val bucketPixels = Array(12) { mutableListOf<Int>() }

                for (y in 0 until scaled.height) {
                    for (x in 0 until scaled.width) {
                        val pixel = scaled.getPixel(x, y)
                        val alpha = (pixel ushr 24) and 0xff
                        if (alpha < 128) continue

                        AndroidColor.colorToHSV(pixel, hsv)
                        val hue = hsv[0]
                        val saturation = hsv[1]
                        val value = hsv[2]

                        // Discard extreme near-blacks, pure whites, and muddy washed-out grays
                        if (value < 0.14f || (saturation < 0.12f && value > 0.88f) || saturation < 0.10f) {
                            continue
                        }

                        // Categorize into 12 distinct 30-degree hue buckets
                        val bucket = ((hue / 30f).toInt()).coerceIn(0, 11)
                        // Score by high saturation and pleasant mid-luminance
                        val score = (saturation * 2.8f) + (1f - abs(value - 0.60f))
                        bucketScores[bucket] += score
                        bucketPixels[bucket].add(pixel)
                    }
                }

                val sortedBuckets = bucketScores.indices.sortedByDescending { bucketScores[it] }
                val topBucket = sortedBuckets.firstOrNull { bucketScores[it] > 0f }

                if (topBucket == null) {
                    return@withContext AmbientPalette.Default
                }

                // Extract primary vibrant color
                val primaryPixel = selectBestVibrantPixel(bucketPixels[topBucket])
                AndroidColor.colorToHSV(primaryPixel, hsv)
                hsv[1] = hsv[1].coerceIn(0.55f, 0.88f) // rich saturation
                hsv[2] = hsv[2].coerceIn(0.42f, 0.76f) // radiant luminous value
                val primaryComposeColor = Color(AndroidColor.HSVToColor(hsv))

                // Extract secondary harmonious color (second top bucket, or complement hue)
                val secondaryBucket = sortedBuckets.getOrNull(1)?.takeIf { bucketScores[it] > 0f }
                val secondaryComposeColor = if (secondaryBucket != null) {
                    val secPixel = selectBestVibrantPixel(bucketPixels[secondaryBucket])
                    AndroidColor.colorToHSV(secPixel, hsv)
                    hsv[1] = hsv[1].coerceIn(0.48f, 0.82f)
                    hsv[2] = hsv[2].coerceIn(0.36f, 0.70f)
                    Color(AndroidColor.HSVToColor(hsv))
                } else {
                    // Slight shift (+30°) for subtle dual-tone atmospheric depth
                    hsv[0] = (hsv[0] + 30f) % 360f
                    hsv[1] = (hsv[1] * 0.85f).coerceIn(0.45f, 0.80f)
                    hsv[2] = (hsv[2] * 0.90f).coerceIn(0.32f, 0.65f)
                    Color(AndroidColor.HSVToColor(hsv))
                }

                val resultPalette = AmbientPalette(
                    primaryColor = primaryComposeColor,
                    secondaryColor = secondaryComposeColor
                )

                paletteCache.put(thumbnailUrl, resultPalette)
                resultPalette
            } catch (e: Exception) {
                AmbientPalette.Default
            }
        }
    }

    private fun selectBestVibrantPixel(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return AndroidColor.DKGRAY
        val hsv = FloatArray(3)
        var bestPixel = pixels[0]
        var maxScore = -1f

        for (p in pixels) {
            AndroidColor.colorToHSV(p, hsv)
            val score = (hsv[1] * 2f) + (1f - abs(hsv[2] - 0.58f))
            if (score > maxScore) {
                maxScore = score
                bestPixel = p
            }
        }
        return bestPixel
    }
}

/**
 * Remembers the extracted ambient palette for the given thumbnail,
 * fetching asynchronously without blocking UI composition.
 */
@Composable
fun rememberAmbientPalette(thumbnailUrl: String?): AmbientPalette {
    val context = LocalContext.current
    var palette by remember(thumbnailUrl) {
        mutableStateOf(AmbientColorExtractor.extractPaletteImmediate(thumbnailUrl) ?: AmbientPalette.Default)
    }

    LaunchedEffect(thumbnailUrl) {
        if (!thumbnailUrl.isNullOrBlank()) {
            val extracted = AmbientColorExtractor.extractPalette(context, thumbnailUrl)
            palette = extracted
        } else {
            palette = AmbientPalette.Default
        }
    }

    return palette
}

// Quick in-memory cache check helper
fun AmbientColorExtractor.extractPaletteImmediate(thumbnailUrl: String?): AmbientPalette? {
    if (thumbnailUrl.isNullOrBlank()) return AmbientPalette.Default
    return paletteCache.get(thumbnailUrl)
}

/**
 * YouTube-style Ambient Glow Background.
 * Renders a soft, diffused, multi-layered ambient light mesh behind the video player
 * and cascading down behind the video details and comments.
 * Cross-fades smoothly between color palettes as the video changes.
 */
@Composable
fun AmbientGlowBackground(
    thumbnailUrl: String?,
    isAmbientModeEnabled: Boolean,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = isSystemInDarkTheme()
) {
    val targetPalette = rememberAmbientPalette(thumbnailUrl)

    // Smooth 850ms cross-fade between extracted color palettes
    val animatedPrimary by animateColorAsState(
        targetValue = targetPalette.primaryColor,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "AmbientPrimaryColor"
    )

    val animatedSecondary by animateColorAsState(
        targetValue = targetPalette.secondaryColor,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "AmbientSecondaryColor"
    )

    // Smooth toggle fade in / fade out
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isAmbientModeEnabled) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "AmbientAlpha"
    )

    if (animatedAlpha <= 0.01f) return

    val baseAlpha = if (isDarkTheme) 0.42f else 0.18f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = animatedAlpha }
    ) {
        val width = size.width
        val height = size.height

        // Approximate 16:9 player area height + status bar offset
        val playerHeight = (width * 9f / 16f) + 48.dp.toPx()

        // 1. Top Halo: Soft ambient light shining behind and above the player into status bar
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = baseAlpha * 0.70f),
                    animatedPrimary.copy(alpha = baseAlpha * 0.30f),
                    Color.Transparent
                ),
                center = Offset(width / 2f, playerHeight * 0.30f),
                radius = width * 0.90f
            )
        )

        // 2. Center/Bottom Radiant Core: Deep glowing halo behind the player
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = baseAlpha * 0.95f),
                    animatedSecondary.copy(alpha = baseAlpha * 0.50f),
                    Color.Transparent
                ),
                center = Offset(width / 2f, playerHeight * 0.88f),
                radius = width * 1.05f
            )
        )

        // 3. Atmospheric Downward Spill: Soft vertical gradient cascading behind video title & comments
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to animatedPrimary.copy(alpha = baseAlpha * 0.75f),
                0.28f to animatedSecondary.copy(alpha = baseAlpha * 0.45f),
                0.62f to animatedPrimary.copy(alpha = baseAlpha * 0.15f),
                1.0f to Color.Transparent,
                startY = playerHeight * 0.55f,
                endY = playerHeight + 720.dp.toPx()
            )
        )
    }
}
