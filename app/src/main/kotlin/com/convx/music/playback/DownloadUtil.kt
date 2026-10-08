/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.playback
import timber.log.Timber
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.music.innertube.YouTube
import com.convx.music.constants.AudioQuality
import com.convx.music.constants.AudioQualityKey
import com.convx.music.constants.DownloadFormatKey
import com.convx.music.constants.IpVersionKey
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import com.convx.music.db.MusicDatabase
import com.convx.music.db.entities.FormatEntity
import com.convx.music.db.entities.SongEntity
import com.convx.music.di.DownloadCache
import com.convx.music.ui.utils.resize
import com.convx.music.constants.AutoDownloadOnLikeKey
import com.convx.music.utils.YTPlayerUtils
import com.convx.music.utils.enumPreference
import com.convx.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.convx.music.applecanvas.AppleMusicCanvasProvider
import com.convx.music.canvas.AppleMusicArtistBackgroundProvider
import com.convx.music.constants.CanvasSource
import com.convx.music.constants.CanvasSourceKey
import com.convx.music.ui.player.normalizeCanvasArtistName
import com.convx.music.ui.player.normalizeCanvasSongTitle
import com.convx.music.utils.dataStore
import com.convx.music.vivimusiccanvas.EchoMusicCanvasProvider
import com.convx.music.vivimusiccanvas.ViviMusicCanvasProvider
import com.convx.music.canvas.TidalCanvasProvider
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import java.io.IOException
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val selectedDownloadFormat by enumPreference(context, DownloadFormatKey, DownloadFormat.STANDARD)
    private val ipVersion by enumPreference(context, IpVersionKey, IpVersion.AUTO)
    private data class ResolvedDownloadUrl(
        val url: String,
        val expiresAt: Long,
        val youtube: Boolean,
        val contentLength: Long?,
    ) {
        fun resolve(dataSpec: DataSpec): DataSpec {
            if (!youtube) return dataSpec.withUri(url.toUri())
            val uri = url.toUri().buildUpon()
                .appendQueryParameter("range", downloadByteRange(dataSpec.position, dataSpec.length, contentLength))
                .build()
            return dataSpec.withUri(uri)
        }
    }

    private val songUrlCache = ConcurrentHashMap<String, ResolvedDownloadUrl>()
    // Keep a reference to context so we can read DataStore prefs for JioSaavn support
    private val appContext: Context = context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    /** Store the choice in Media3's durable request, so retries never mix formats. */
    fun prepareDownloadRequest(request: DownloadRequest): DownloadRequest {
        val existing = downloadManager.downloadIndex.getDownload(request.id)?.request
        val format = downloadFormatForRequest(existing != null, existing?.mimeType, selectedDownloadFormat)
        if (existing != null) return existing
        return DownloadRequest.Builder(request.id, request.uri)
            .setMimeType(if (format == DownloadFormat.FLAC) "audio/flac" else request.mimeType)
            .setStreamKeys(request.streamKeys)
            .setKeySetId(request.keySetId)
            .setCustomCacheKey(request.customCacheKey)
            .setData(request.data)
            .build()
    }

    init {
        // Auto-download-on-like watches the `liked` column instead of hooking the
        // like action: the hook used to live in MusicService.toggleLike(), which
        // only the player screen's like button and the media-session action ever
        // reach. Liking from a song/queue/selection menu, a swipe, or the YouTube
        // sync writes the same column and never triggered a download.
        scope.launch {
            var known: Set<String>? = null
            database.likedSongIds().collect { ids ->
                val current = ids.toSet()
                val previous = known
                known = current
                // The first emission is the existing library, not a batch of new
                // likes — seeding it stops a fresh start from queueing everything.
                if (previous == null) return@collect
                if (!appContext.dataStore.get(AutoDownloadOnLikeKey, false)) return@collect

                for (songId in current - previous) {
                    if (downloads.value[songId] != null) continue
                    runCatching {
                        val title = database.songTitle(songId).orEmpty()
                        DownloadService.sendAddDownload(
                            appContext,
                            ExoDownloadService::class.java,
                            DownloadRequest.Builder(songId, songId.toUri())
                                .setCustomCacheKey(songId)
                                .setData(title.toByteArray())
                                .build(),
                            false,
                        )
                    }.onFailure {
                        // Backgrounded apps can be blocked from starting the download
                        // service; losing one auto-download must not kill the collector.
                        Timber.e(it, "Auto-download on like failed for $songId")
                    }
                }
            }
        }
    }

    private val downloadHttpClient =
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    return when (this@DownloadUtil.ipVersion) {
                        IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                        IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                        IpVersion.AUTO -> addresses
                    }
                }
            })
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            // Offline bytes never reuse fragments from the streaming cache.
            OkHttpDataSource.Factory(downloadHttpClient),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val requireFlac = downloadManager.downloadIndex.getDownload(mediaId)?.request?.mimeType == "audio/flac"
            songUrlCache[mediaId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return@Factory it.resolve(dataSpec)
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    // Pass context so the JioSaavn intercept fires when the toggle is ON
                    context = appContext,
                    allowLossless = requireFlac,
                    forceStandardAudio = !requireFlac,
                    requireFlac = requireFlac,
                )
            }.getOrThrow()
            val format = playbackData.format
            if (requireFlac) {
                // Providers can mislabel AAC as lossless. Verify the native FLAC
                // marker before any bytes or format metadata enter the cache.
                val probe = Request.Builder().url(playbackData.streamUrl)
                    .header("Range", "bytes=0-3")
                    .header("Accept-Encoding", "identity").build()
                downloadHttpClient.newCall(probe).execute().use { response ->
                    if (!response.isSuccessful || response.body == null) {
                        throw IOException(appContext.getString(com.convx.music.R.string.flac_download_unavailable))
                    }
                    val header = response.body!!.source().run {
                        if (request(4)) readByteArray(4) else byteArrayOf()
                    }
                    if (!isFlacHeader(header)) {
                        throw IOException(appContext.getString(com.convx.music.R.string.flac_download_invalid))
                    }
                }
            }

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "mp4a.40.2",
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength ?: 0L,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song

                val updatedSong = if (existing != null) {
                    existing.copy(
                        dateDownload = existing.dateDownload ?: now,
                        // Rows inserted before a full metadata fetch (search-result add, queue
                        // add, local-scan hybrid) can have a null thumbnailUrl; backfill it here
                        // or a downloaded song is left with no thumbnail to show or pre-cache.
                        thumbnailUrl = existing.thumbnailUrl
                            ?: playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url?.resize(1200, 1200),
                    )
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url?.resize(1200, 1200),
                        dateDownload = now,
                        isDownloaded = false
                    )
                }

                upsert(updatedSong)

                // Pre-cache the high-res thumbnail immediately when download starts.
                // Keyed on the raw (un-resized) URL, not the default (the actual
                // request data, which is this same string): every UI thumbnail
                // request instead resizes this URL to its own target decode size
                // first, so without a shared stable key this entry sits under a
                // URL nothing else ever asks for and is invisible offline despite
                // being cached. ItemThumbnail/LocalThumbnail below key their
                // requests the same way, off the same DB-stored thumbnailUrl.
                updatedSong.thumbnailUrl?.let { url ->
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .diskCacheKey(url)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    SingletonImageLoader.get(context).enqueue(request)
                }

                // --- CANVAS CACHING ---
                scope.launch {
                    val canvasSource = context.dataStore.data.map { it[CanvasSourceKey] ?: CanvasSource.AUTO.name }.first().let { name -> CanvasSource.entries.find { it.name == name } ?: CanvasSource.AUTO }

                    val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
                    val requestedTitle = playbackData.videoDetails?.title.orEmpty()
                    val requestedArtist = playbackData.videoDetails?.author.orEmpty()
                    
                    val s = normalizeCanvasSongTitle(requestedTitle)
                    val a = normalizeCanvasArtistName(requestedArtist)

                    val canvas = when (canvasSource) {
                        CanvasSource.AUTO -> {
                            EchoMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                                ?: AppleMusicCanvasProvider.getBySongArtist(s, a, "", storefront)?.preferredAnimationUrl
                                ?: ViviMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                                ?: TidalCanvasProvider.getBySongArtist(s, a, "")?.preferredAnimationUrl
                        }
                        CanvasSource.ECHO_MUSIC -> EchoMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                        CanvasSource.APPLE_MUSIC -> AppleMusicCanvasProvider.getBySongArtist(s, a, "", storefront)?.preferredAnimationUrl
                        CanvasSource.VIVIMUSIC -> ViviMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                        CanvasSource.TIDAL -> TidalCanvasProvider.getBySongArtist(s, a, "")?.preferredAnimationUrl
                    }

                    canvas?.let { url ->
                        val dataSpec = DataSpec.Builder()
                            .setUri(url.toUri())
                            .setKey("$mediaId#canvas")
                            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                            .build()
                            
                        val dataSource = CacheDataSource.Factory()
                            .setCache(downloadCache)
                            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
                            .setCacheWriteDataSinkFactory(null)
                            .createDataSource()
                        
                        kotlin.runCatching {
                            val writer = CacheWriter(
                                dataSource,
                                dataSpec,
                                null,
                                null
                            )
                            writer.cache()
                            Timber.tag("CanvasDownload").d("Successfully cached canvas for $mediaId")
                        }.onFailure { e ->
                            Timber.tag("CanvasDownload").e(e, "Failed to cache canvas for $mediaId")
                        }
                    }
                }
            }

            // Cache the base URL, not a range tied to the first request. A resumed
            // download must request its remaining bytes, without an arbitrary 10 MB cap.
            val resolved = ResolvedDownloadUrl(
                url = playbackData.streamUrl,
                expiresAt = System.currentTimeMillis() + playbackData.streamExpiresInSeconds * 1000L,
                youtube = !playbackData.isSaavnStream && !playbackData.isTidalStream && !playbackData.isSpineStream,
                contentLength = format.contentLength,
            )
            songUrlCache[mediaId] = resolved
            resolved.resolve(dataSpec)
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            // Built to post a notification on a failed download but never actually
            // registered anywhere — a failure produced no system notification, no
            // in-app error state (see the STATE_FAILED handling below/in the menus),
            // nothing. It just silently looked like the download had never happened.
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context,
                    downloadNotificationHelper,
                    ExoDownloadService.NOTIFICATION_ID + 1,
                )
            )
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                        downloads.update { it - download.request.id }
                        songUrlCache.remove(download.request.id)
                        scope.launch {
                            database.updateDownloadedInfo(download.request.id, false, null)
                        }
                    }

                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        // finalException was being dropped on the floor, so a download
                        // that failed left no trace of WHY anywhere — which is exactly
                        // the situation in the "only half my songs download, and the
                        // Hindi ones never do" reports: no way to tell a region block
                        // from a dead stream URL from a network drop. Logged through
                        // Timber so it lands in the in-app log viewer (Settings ->
                        // Content -> Logs) and can be read off a user's device.
                        if (download.state == Download.STATE_FAILED) {
                            // A server can reject a signed URL before its advertised expiry.
                            // Retrying must resolve a fresh URL instead of reusing that one.
                            songUrlCache.remove(download.request.id)
                            Timber.e(
                                finalException,
                                "Download failed: id=%s title=%s reason=%d",
                                download.request.id,
                                Util.fromUtf8Bytes(download.request.data),
                                download.failureReason,
                            )
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
        }
        downloads.value = result
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }
}
