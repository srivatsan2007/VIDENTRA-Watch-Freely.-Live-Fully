/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.screens.player

import com.arslandaim.playtube.domain.model.VideoItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueManager @Inject constructor() {

    // --- Skip events (hardware/button skip) ---
    private val _skipToNextEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val skipToNextEvent = _skipToNextEvent.asSharedFlow()

    private val _skipToPreviousEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val skipToPreviousEvent = _skipToPreviousEvent.asSharedFlow()

    // --- User-managed video queue ---
    private val _queue = LinkedList<VideoItem>()

    private val _queueSize = MutableStateFlow(0)
    val queueSize = _queueSize.asStateFlow()

    val hasQueuedVideo: Boolean get() = _queue.isNotEmpty()

    fun addToQueue(video: VideoItem) {
        _queue.add(video)
        _queueSize.value = _queue.size
    }

    fun dequeueNext(): VideoItem? {
        val next = if (_queue.isNotEmpty()) _queue.poll() else null
        _queueSize.value = _queue.size
        return next
    }

    fun clearQueue() {
        _queue.clear()
        _queueSize.value = 0
    }

    fun peekQueue(): List<VideoItem> = _queue.toList()

    fun skipToNext() {
        _skipToNextEvent.tryEmit(Unit)
    }

    fun skipToPrevious() {
        _skipToPreviousEvent.tryEmit(Unit)
    }
}
