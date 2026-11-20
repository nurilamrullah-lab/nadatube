package com.nadatube.app

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.nadatube.app.data.LibraryRepository
import com.nadatube.app.data.SettingsStore
import com.nadatube.app.data.db.NadaDatabase
import com.nadatube.app.download.DownloadWorker
import com.nadatube.app.download.StorageSummary
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.StreamResolver
import com.nadatube.app.lyrics.LyricsClient
import com.nadatube.app.playback.NowPlayingState
import com.nadatube.app.playback.PlayerConnection
import com.nadatube.app.playback.SleepTimer
import com.nadatube.app.playback.VisualizerSink
import com.nadatube.app.sponsorblock.SponsorBlockClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class NadaTubeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency graph; everything here is a process-wide singleton. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val settings = SettingsStore(app)
    val innerTube = InnerTubeClient(httpClient)
    val streamResolver = StreamResolver(innerTube, { settings.quality.value })
    val sponsorBlock = SponsorBlockClient(httpClient)
    val lyrics = LyricsClient(httpClient)
    val database = NadaDatabase.create(app)
    val library = LibraryRepository(app, database)
    val sleepTimer = SleepTimer(appScope)
    val visualizer = VisualizerSink()
    val nowPlaying = NowPlayingState()
    val player = PlayerConnection(app, appScope, library, innerTube)

    /** Chunked, AES-encrypted LRU cache of streamed audio (SimpleCache must be a singleton per dir). */
    val audioCache: SimpleCache by lazy {
        SimpleCache(File(app.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES), StandaloneDatabaseProvider(app))
    }

    suspend fun storageSummary(): StorageSummary = withContext(Dispatchers.IO) {
        val (downloadBytes, count) = library.downloadedBytes()
        val dir = DownloadWorker.downloadDir(app)
        StorageSummary(
            downloadsBytes = downloadBytes,
            downloadCount = count,
            cacheBytes = audioCache.cacheSpace,
            cacheLimitBytes = CACHE_LIMIT_BYTES,
            freeBytes = dir.usableSpace,
        )
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        audioCache.keys.toList().forEach { audioCache.removeResource(it) }
    }

    companion object {
        const val CACHE_LIMIT_BYTES = 512L * 1024 * 1024
    }
}
