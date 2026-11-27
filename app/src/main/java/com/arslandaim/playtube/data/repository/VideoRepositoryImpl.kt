/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.data.repository

import android.util.LruCache
import com.arslandaim.playtube.data.local.PreferencesManager
import com.arslandaim.playtube.data.network.InnerTubeClient
import com.arslandaim.playtube.data.network.NewPipeInitializer
import com.arslandaim.playtube.domain.model.*
import com.arslandaim.playtube.domain.repository.VideoRepository
import com.arslandaim.playtube.utils.Constants
import com.arslandaim.playtube.utils.PTLog
import com.arslandaim.playtube.utils.VideoUtils
import com.arslandaim.playtube.utils.isShortVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepositoryImpl @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val initializer: NewPipeInitializer,
    private val innerTubeClient: InnerTubeClient
) : VideoRepository {
    private val streamCache = LruCache<String, StreamBundle>(Constants.STREAM_CACHE_SIZE)
    private val commentsCache = LruCache<String, CommentsInfoItem>(100)

    private suspend fun ensureInit() {
        initializer.ensureInitialized()
    }

    override suspend fun getStreamBundle(videoId: String, forceRefresh: Boolean): StreamBundle {
        if (videoId.isBlank()) throw IllegalArgumentException("Video ID cannot be blank")
        
        ensureInit()
        val isIncognito = preferencesManager.isIncognitoMode.first()
        
        if (!forceRefresh && !isIncognito) {
            streamCache.get(videoId)?.let { 
                if (!it.isExpired()) return it 
            }
        }
        
        // If forceRefresh is requested while a fresh bundle is already cached, playback likely failed (e.g. 403).
        // Skip NewPipe and go directly to InnerTube Android/iOS client for fresh bypass streams.
        val skipNewPipe = forceRefresh && streamCache.get(videoId) != null

        return withContext(Dispatchers.IO) {
            // ---- Attempt 1: NewPipe extractor (fastest, most metadata) ----
            if (!skipNewPipe) try {
                val service = ServiceList.YouTube
                val videoUrl = Constants.YouTube.VIDEO_URL_PREFIX + videoId
                val streamInfo = StreamInfo.getInfo(service, videoUrl)

                val isLive = streamInfo.streamType == StreamType.LIVE_STREAM || 
                             streamInfo.streamType == StreamType.AUDIO_LIVE_STREAM ||
                             streamInfo.streamType.name == "LIVE"

                val videoStreamsDeferred = async { extractVideoStreams(streamInfo, isLive) }
                val audioStreamsDeferred = async { extractAudioStreams(streamInfo) }
                val subtitlesDeferred = async { extractSubtitles(streamInfo) }

                val bestAudioStream = selectBestAudioStream(streamInfo)
                val videoStreams = videoStreamsDeferred.await()
                val audioStreams = audioStreamsDeferred.await()

                // If we got valid streams from NewPipe, use them
                if (videoStreams.isNotEmpty() || audioStreams.isNotEmpty() || isLive) {
                    val isUpcoming = (isLive && videoStreams.isEmpty()) || streamInfo.viewCount == -1L
                    val scheduledStartTime = if (isUpcoming) {
                        streamInfo.uploadDate?.offsetDateTime()?.toString() ?: streamInfo.textualUploadDate
                    } else null

                    val bundle = StreamBundle(
                        videoStreams = videoStreams,
                        audioStreams = audioStreams,
                        title = streamInfo.name ?: "Unknown",
                        uploaderName = streamInfo.uploaderName ?: "Unknown",
                        uploaderUrl = streamInfo.uploaderUrl,
                        uploaderThumbnailUrl = streamInfo.uploaderAvatars.maxByOrNull { it.width }?.url ?: streamInfo.uploaderAvatars.firstOrNull()?.url,
                        uploaderSubscriberCount = streamInfo.uploaderSubscriberCount,
                        description = VideoUtils.sanitizeDescription(streamInfo.description?.content),
                        viewCount = streamInfo.viewCount,
                        uploadDate = streamInfo.textualUploadDate ?: streamInfo.uploadDate?.offsetDateTime()?.toLocalDate()?.toString(),
                        thumbnailUrl = streamInfo.thumbnails.maxByOrNull { it.width }?.url ?: VideoUtils.getBestThumbnailUrl(videoId),
                        isLive = isLive,
                        isUpcoming = isUpcoming,
                        scheduledStartTime = scheduledStartTime,
                        relatedVideos = streamInfo.relatedItems
                            ?.filterIsInstance<StreamInfoItem>()
                            ?.filterNot { it.isShortVideo() }
                            ?.map { mapToVideoItem(it) } ?: emptyList(),
                        nextRelatedVideosPage = null,
                        bestAudioStreamUrl = bestAudioStream?.url,
                        progressiveFallbackUrl = streamInfo.videoStreams
                            ?.filter { !it.url.isNullOrBlank() }
                            ?.maxByOrNull { it.getResolution()?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                            ?.url,
                        subtitles = subtitlesDeferred.await()
                    )
                    if (!isIncognito) streamCache.put(videoId, bundle)
                    return@withContext bundle
                }

                PTLog.w("VideoRepository", "NewPipe returned empty streams for $videoId — falling back to InnerTube")
            } catch (e: Exception) {
                PTLog.w("VideoRepository", "NewPipe extraction failed for $videoId (${e.message}) — falling back to InnerTube")
            }

            // ---- Attempt 2: InnerTube Android/iOS client (bypasses PoToken requirement) ----
            try {
                val cookieString = if (preferencesManager.isYouTubeLoggedIn.first()) {
                    preferencesManager.youtubeAuthCookies.first()
                } else null

                val innerTubeStreams = innerTubeClient.fetchStreams(videoId, cookieString)
                    ?: throw Exception("InnerTube returned no streams for $videoId")

                val videoStreams = mutableListOf<StreamItem>()
                val audioStreams = mutableListOf<StreamItem>()

                // Map InnerTube video streams → StreamItem
                val qualitySeen = mutableSetOf<String>()
                for (s in innerTubeStreams.videoStreams) {
                    val quality = s.qualityLabel.ifBlank { s.quality }
                    if (quality.isBlank() || !qualitySeen.add(quality)) continue
                    val format = when {
                        s.mimeType.contains("webm") -> "webm"
                        s.mimeType.contains("mp4") -> "mp4"
                        else -> "mp4"
                    }
                    // isAdaptive = true only for video-only streams (no embedded audio)
                    val isAdaptive = s.isVideo && !s.isAudio
                    videoStreams.add(StreamItem(url = s.url, quality = quality, format = format, isAdaptive = isAdaptive))
                }
                videoStreams.sortByDescending { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

                // Map InnerTube audio streams → StreamItem
                for (s in innerTubeStreams.audioStreams) {
                    val format = when {
                        s.mimeType.contains("webm") -> "webm"
                        s.mimeType.contains("mp4") -> "m4a"
                        else -> "m4a"
                    }
                    audioStreams.add(StreamItem(url = s.url, quality = s.quality, format = format))
                }

                // For live streams, prefer HLS manifest
                val finalVideoStreams: List<StreamItem> = if (innerTubeStreams.isLive && innerTubeStreams.hlsManifestUrl != null) {
                    listOf(StreamItem(url = innerTubeStreams.hlsManifestUrl, quality = "Auto (Live)", format = "m3u8", isAdaptive = false))
                } else {
                    videoStreams
                }

                val bestAudioUrl = audioStreams.firstOrNull()?.url

                val bundle = StreamBundle(
                    videoStreams = finalVideoStreams,
                    audioStreams = audioStreams,
                    title = innerTubeStreams.title ?: "Video",
                    uploaderName = innerTubeStreams.author ?: "YouTube",
                    uploaderUrl = innerTubeStreams.channelId?.let { Constants.YouTube.CHANNEL_URL_PREFIX + it },
                    uploaderThumbnailUrl = null,
                    uploaderSubscriberCount = -1,
                    description = null,
                    viewCount = innerTubeStreams.viewCount,
                    uploadDate = null,
                    thumbnailUrl = VideoUtils.getBestThumbnailUrl(videoId),
                    isLive = innerTubeStreams.isLive,
                    isUpcoming = false,
                    scheduledStartTime = null,
                    relatedVideos = emptyList(),
                    nextRelatedVideosPage = null,
                    bestAudioStreamUrl = bestAudioUrl,
                    progressiveFallbackUrl = finalVideoStreams.firstOrNull { !it.isAdaptive }?.url,
                    subtitles = emptyList()
                )
                if (!isIncognito) streamCache.put(videoId, bundle)
                PTLog.d("VideoRepository", "InnerTube fallback succeeded for $videoId: ${finalVideoStreams.size} video, ${audioStreams.size} audio streams")
                bundle
            } catch (e: Exception) {
                PTLog.e("VideoRepository", "Both NewPipe and InnerTube failed for $videoId", e)
                throw e
            }
        }
    }

    private fun extractVideoStreams(streamInfo: StreamInfo, isLive: Boolean): List<StreamItem> {
        val streamsMap = mutableMapOf<String, StreamItem>()
        if (isLive) {
            val liveUrl = streamInfo.hlsUrl?.takeIf { it.isNotBlank() }
                ?: streamInfo.dashMpdUrl?.takeIf { it.isNotBlank() }
            liveUrl?.let { url ->
                val format = if (url == streamInfo.dashMpdUrl) "mpd" else "m3u8"
                val item = StreamItem(url = url, quality = "Auto (Live)", format = format, isAdaptive = false)
                streamsMap[item.quality] = item
            }
        } else {
            // 1. Progressive (muxed) streams as initial fallback
            streamInfo.videoStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.forEach {
                    val res = it.getResolution() ?: "Unknown"
                    streamsMap[res] = StreamItem(
                        url = it.url ?: "",
                        quality = res,
                        format = it.format?.suffix ?: "mp4",
                        isAdaptive = false
                    )
                }

            // 2. Adaptive video-only streams override progressive streams across all resolutions.
            // Prefer WebM (VP9) first because MatroskaExtractor starts immediately on chunked SABR byte-range streams
            // with C.LENGTH_UNSET without requiring end-of-file moov atom seeks, then MP4 (AVC1), and avoid yt_otf streams.
            streamInfo.videoOnlyStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.groupBy { it.getResolution() ?: "Unknown" }
                ?.forEach { (res, group) ->
                    val nonOtfGroup = group.filter { it.url?.contains("source=yt_otf") != true }
                    val candidates = nonOtfGroup.ifEmpty { group }
                    val bestAdaptive = candidates.maxWithOrNull(
                        compareBy<VideoStream> { stream ->
                            val codec = stream.codec?.lowercase() ?: ""
                            val suffix = stream.format?.suffix?.lowercase() ?: ""
                            when {
                                codec.startsWith("vp9") || codec.startsWith("vp09") || suffix == "webm" -> 3
                                codec.startsWith("avc1") || (suffix == "mp4" && !codec.startsWith("av01")) -> 2
                                else -> 1
                            }
                        }.thenBy { it.bitrate }
                    )
                    if (bestAdaptive != null) {
                        streamsMap[res] = StreamItem(
                            url = bestAdaptive.url ?: "",
                            quality = res,
                            format = bestAdaptive.format?.suffix ?: "webm",
                            isAdaptive = true
                        )
                    }
                }

            // 3. Fallback to HLS/DASH manifest if no direct streams were extracted
            if (streamsMap.isEmpty()) {
                val fallbackUrl = streamInfo.hlsUrl?.takeIf { it.isNotBlank() }
                    ?: streamInfo.dashMpdUrl?.takeIf { it.isNotBlank() }
                fallbackUrl?.let { url ->
                    val format = if (url == streamInfo.dashMpdUrl) "mpd" else "m3u8"
                    streamsMap["Auto"] = StreamItem(url = url, quality = "Auto", format = format, isAdaptive = false)
                }
            }
        }
        val streams = streamsMap.values.toMutableList()
        if (!isLive) streams.sortByDescending { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        return streams
    }

    private fun extractAudioStreams(streamInfo: StreamInfo): List<StreamItem> {
        return streamInfo.audioStreams?.filter { !it.url.isNullOrBlank() }?.map {
            StreamItem(url = it.url!!, quality = "${it.averageBitrate}kbps", format = it.format?.suffix ?: "m4a", languageTag = it.audioLocale?.language, trackType = it.audioTrackType?.name)
        } ?: emptyList()
    }

    private fun extractSubtitles(streamInfo: StreamInfo): List<SubtitleItem> {
        return streamInfo.subtitles?.map {
            SubtitleItem(url = it.url ?: "", languageTag = it.languageTag ?: "und", format = it.format?.suffix ?: "vtt", isAutoGenerated = it.isAutoGenerated)
        }?.sortedWith(compareBy({ it.isAutoGenerated }, { it.languageTag })) ?: emptyList()
    }

    private fun selectBestAudioStream(streamInfo: StreamInfo): AudioStream? {
        val streams = streamInfo.audioStreams?.filter { !it.url.isNullOrBlank() } ?: return null
        val originals = streams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
        return if (originals.isNotEmpty()) originals.maxByOrNull { it.averageBitrate }
        else streams.filter { it.audioLocale?.language == "en" }.maxByOrNull { it.averageBitrate } ?: streams.maxByOrNull { it.averageBitrate }
    }

    override suspend fun getCachedStreamBundle(videoId: String): StreamBundle? {
        val cached = streamCache.get(videoId)
        return if (cached != null && !cached.isExpired()) cached else null
    }

    override suspend fun preloadStreamBundle(videoId: String) {
        if (videoId.isBlank()) return
        val cached = streamCache.get(videoId)
        if (cached != null && !cached.isExpired()) return
        try { getStreamBundle(videoId, forceRefresh = true) } catch (e: Exception) { PTLog.w("VideoRepository", "Preload failed for $videoId: ${e.message}") }
    }

    override suspend fun fetchNextRelatedPage(videoId: String, page: Page): PaginatedList<VideoItem> {
        return PaginatedList(emptyList(), null)
    }

    override suspend fun getChannelInfo(channelUrl: String): ChannelInfoBasic {
        ensureInit()
        return withContext(Dispatchers.IO) {
            try {
                val finalUrl = if (channelUrl.startsWith("UC") && !channelUrl.contains("/")) Constants.YouTube.CHANNEL_URL_PREFIX + channelUrl else channelUrl
                val info = ChannelInfo.getInfo(ServiceList.YouTube, finalUrl)
                ChannelInfoBasic(id = info.id ?: "", name = info.name ?: "Unknown", avatarUrl = info.avatars?.find { it.width in 150..300 }?.url ?: info.avatars?.firstOrNull()?.url, subscriberCount = info.subscriberCount)
            } catch (e: Exception) {
                PTLog.e("VideoRepository", "Error fetching channel info for $channelUrl", e)
                throw e
            }
        }
    }

    override suspend fun getChannelDetails(channelUrl: String): ChannelDetails {
        ensureInit()
        return withContext(Dispatchers.IO) {
            try {
                val finalUrl = if (channelUrl.startsWith("UC") && !channelUrl.contains("/")) Constants.YouTube.CHANNEL_URL_PREFIX + channelUrl else channelUrl
                val info = ChannelInfo.getInfo(ServiceList.YouTube, finalUrl)
                val avatar = info.avatars?.find { it.width in 150..300 }?.url ?: info.avatars?.firstOrNull()?.url
                
                val videosTask = async {
                    try {
                        val handler = info.tabs.find { it.url.endsWith("/videos") || it.url.contains("flow=grid") } ?: ServiceList.YouTube.channelTabLHFactory.fromUrl(info.url + "/videos")
                        val extractor = ServiceList.YouTube.getChannelTabExtractor(handler)
                        extractor.fetchPage()
                        val page = extractor.initialPage
                        Pair(page.items.filterIsInstance<StreamInfoItem>().filterNot { it.isShortVideo() }.map { mapToVideoItem(it, avatar) }, if (page.hasNextPage()) page.nextPage else null)
                    } catch (e: Exception) { PTLog.w("VideoRepository", "Failed to fetch channel videos", e); Pair(emptyList<VideoItem>(), null) }
                }

                val playlistsTask = async {
                    try {
                        val handler = info.tabs.find { it.url.endsWith("/playlists") } ?: ServiceList.YouTube.channelTabLHFactory.fromUrl(info.url + "/playlists")
                        val extractor = ServiceList.YouTube.getChannelTabExtractor(handler)
                        extractor.fetchPage()
                        extractor.initialPage.items.filterIsInstance<PlaylistInfoItem>().map { 
                            PlaylistItem(id = VideoUtils.extractPlaylistId(it.url), title = it.name ?: "Unknown", thumbnailUrl = it.thumbnails?.find { t -> t.width in 400..800 }?.url ?: it.thumbnails?.firstOrNull()?.url ?: "", uploaderName = it.uploaderName ?: "Unknown", uploaderUrl = it.uploaderUrl ?: "", streamCount = it.streamCount)
                        }
                    } catch (e: Exception) { PTLog.w("VideoRepository", "Failed to fetch channel playlists", e); emptyList() }
                }

                val (videos, nextPage) = videosTask.await()
                ChannelDetails(id = info.id ?: "", name = info.name ?: "Unknown", description = info.description, bannerUrl = info.banners?.find { it.width in 800..1500 }?.url ?: info.banners?.firstOrNull()?.url, avatarUrl = avatar, subscriberCount = info.subscriberCount, videos = videos, nextVideosPage = nextPage, playlists = playlistsTask.await())
            } catch (e: Exception) {
                PTLog.e("VideoRepository", "Error fetching channel details for $channelUrl", e)
                throw e
            }
        }
    }

    override suspend fun fetchNextChannelVideosPage(channelUrl: String, page: Page): PaginatedList<VideoItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            try {
                val handler = ServiceList.YouTube.channelTabLHFactory.fromUrl(channelUrl + "/videos")
                val nextPage = ServiceList.YouTube.getChannelTabExtractor(handler).getPage(page)
                PaginatedList(nextPage.items.filterIsInstance<StreamInfoItem>().filterNot { it.isShortVideo() }.map { mapToVideoItem(it) }, if (nextPage.hasNextPage()) nextPage.nextPage else null)
            } catch (e: Exception) { PTLog.e("VideoRepository", "Error fetching next channel page", e); PaginatedList(emptyList(), null) }
        }
    }

    override suspend fun getTrendingVideos(): PaginatedList<VideoItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            try {
                // Use the Trending kiosk specifically for the YouTube service
                val kiosk = KioskInfo.getInfo(ServiceList.YouTube, Constants.YouTube.TRENDING_URL)
                PaginatedList(
                    items = kiosk.relatedItems.filterIsInstance<StreamInfoItem>().filterNot { it.isShortVideo() }.map { mapToVideoItem(it) },
                    nextPage = kiosk.nextPage
                )
            } catch (e: Exception) { 
                PTLog.e("VideoRepository", "Error fetching trending videos", e)
                PaginatedList(emptyList(), null) 
            }
        }
    }

    override suspend fun fetchNextTrendingPage(page: Page): PaginatedList<VideoItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            try {
                val nextKiosk = KioskInfo.getMoreItems(ServiceList.YouTube, Constants.YouTube.TRENDING_URL, page)
                PaginatedList(
                    items = nextKiosk.items.filterIsInstance<StreamInfoItem>().filterNot { it.isShortVideo() }.map { mapToVideoItem(it) },
                    nextPage = if (nextKiosk.hasNextPage()) nextKiosk.nextPage else null
                )
            } catch (e: Exception) {
                PTLog.e("VideoRepository", "Error fetching next trending page", e)
                PaginatedList(emptyList(), null)
            }
        }
    }

    private fun mapToVideoItem(item: StreamInfoItem, uploaderThumbnailUrl: String? = null): VideoItem {
        val vId = VideoUtils.extractVideoId(item.url)
        return VideoItem(id = vId, title = item.name ?: "Unknown", thumbnailUrl = VideoUtils.getThumbnailForList(vId), uploaderName = item.uploaderName ?: "Unknown", uploaderUrl = item.uploaderUrl ?: "", uploaderThumbnailUrl = uploaderThumbnailUrl ?: item.uploaderAvatars?.firstOrNull()?.url, viewCount = item.viewCount, subscriberCount = null, duration = item.duration, uploadDate = item.textualUploadDate ?: item.uploadDate?.offsetDateTime()?.toLocalDate()?.toString() ?: "", rawUploadDate = item.uploadDate?.instant?.toEpochMilli(), watchProgress = null)
    }

    override suspend fun getPlaylistDetails(playlistUrl: String): PlaylistDetails {
        ensureInit()
        return withContext(Dispatchers.IO) {
            try {
                val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
                PlaylistDetails(id = VideoUtils.extractPlaylistId(info.url), title = info.name ?: "Unknown", uploaderName = info.uploaderName ?: "Unknown", uploaderUrl = info.uploaderUrl, thumbnailUrl = info.thumbnails?.find { it.width in 400..800 }?.url ?: info.thumbnails?.firstOrNull()?.url ?: "", videos = info.relatedItems.filterIsInstance<StreamInfoItem>().map { mapToVideoItem(it) })
            } catch (e: Exception) {
                PTLog.e("VideoRepository", "Error fetching playlist details for $playlistUrl", e)
                throw e
            }
        }
    }

    override suspend fun getComments(videoId: String): PaginatedList<CommentItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            val url = Constants.YouTube.VIDEO_URL_PREFIX + videoId
            val commentsInfo = CommentsInfo.getInfo(ServiceList.YouTube, url)
            val items = commentsInfo.relatedItems.filterIsInstance<CommentsInfoItem>().map { mapToCommentItem(it) }
            PaginatedList(items, if (commentsInfo.hasNextPage()) commentsInfo.nextPage else null)
        }
    }

    override suspend fun fetchNextCommentsPage(videoId: String, page: Page): PaginatedList<CommentItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            val url = Constants.YouTube.VIDEO_URL_PREFIX + videoId
            val nextPage = CommentsInfo.getMoreItems(ServiceList.YouTube, url, page)
            val items = nextPage.items.filterIsInstance<CommentsInfoItem>().map { mapToCommentItem(it) }
            PaginatedList(items, if (nextPage.hasNextPage()) nextPage.nextPage else null)
        }
    }

    override suspend fun getCommentReplies(videoId: String, comment: CommentItem): PaginatedList<CommentItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            val infoItem = commentsCache.get(comment.commentId) 
                ?: throw IllegalStateException("Comment info not found in cache")
            
            val repliesPage = infoItem.getReplies()
            if (repliesPage == null) {
                return@withContext PaginatedList(emptyList(), null)
            }
            
            val videoUrl = Constants.YouTube.VIDEO_URL_PREFIX + videoId
            val result = CommentsInfo.getMoreItems(ServiceList.YouTube, videoUrl, repliesPage)
            val items = result.items.filterIsInstance<CommentsInfoItem>().map { mapToCommentItem(it) }
            PaginatedList(items, if (result.hasNextPage()) result.nextPage else null)
        }
    }

    override suspend fun fetchNextCommentRepliesPage(videoId: String, commentId: String, page: Page): PaginatedList<CommentItem> {
        ensureInit()
        return withContext(Dispatchers.IO) {
            val videoUrl = Constants.YouTube.VIDEO_URL_PREFIX + videoId
            val result = CommentsInfo.getMoreItems(ServiceList.YouTube, videoUrl, page)
            val items = result.items.filterIsInstance<CommentsInfoItem>().map { mapToCommentItem(it) }
            PaginatedList(items, if (result.hasNextPage()) result.nextPage else null)
        }
    }

    private fun mapToCommentItem(item: CommentsInfoItem): CommentItem {
        item.commentId?.let { commentsCache.put(it, item) }
        return CommentItem(
            authorName = item.uploaderName ?: "Unknown",
            authorThumbnailUrl = item.uploaderAvatars?.firstOrNull()?.url,
            authorUrl = item.uploaderUrl,
            commentText = VideoUtils.sanitizeDescription(item.commentText.content),
            publishedTime = item.textualUploadDate,
            likeCount = item.likeCount,
            isHeartedByUploader = item.isHeartedByUploader,
            replyCount = item.replyCount,
            commentId = item.commentId ?: ""
        )
    }
}
