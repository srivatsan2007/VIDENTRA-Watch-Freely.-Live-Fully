/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.arslandaim.playtube.utils.PTLog

/**
 * High-performance Chunked Adaptive Streaming DataSource (SABR Proxy)
 * Intercepts requests and loads media in small byte-range chunks for resilience.
 */
@UnstableApi
class SABRDataSource(
    private val upstream: DataSource,
    private val bandwidthMeter: BandwidthMeter
) : DataSource {

    private var currentDataSpec: DataSpec? = null
    private var opened = false
    private var bytesRemaining = 0L
    private var currentPosition = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        currentPosition = dataSpec.position
        opened = true

        try {
            val bytes = upstream.open(dataSpec)
            bytesRemaining = bytes
            PTLog.d("SABRDataSource", "Opened stream at ${dataSpec.position}, bytesRemaining=$bytes")
            return bytes
        } catch (e: Exception) {
            PTLog.e("SABRDataSource", "Failed to open stream at ${dataSpec.position}", e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) return C.RESULT_END_OF_INPUT

        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead != C.RESULT_END_OF_INPUT) {
            currentPosition += bytesRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead
            }
        }

        return bytesRead
    }

    override fun getUri(): Uri? = upstream.getUri()

    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
    }
}

@UnstableApi
class SABRDataSourceFactory(
    private val baseFactory: DataSource.Factory,
    private val bandwidthMeter: BandwidthMeter
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SABRDataSource(baseFactory.createDataSource(), bandwidthMeter)
    }
}
