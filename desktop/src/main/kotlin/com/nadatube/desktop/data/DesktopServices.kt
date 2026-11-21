package com.nadatube.desktop.data

import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.Ipv4FirstDns
import com.nadatube.app.extractor.StreamRangeInterceptor
import com.nadatube.app.extractor.StreamResolver
import com.nadatube.app.lyrics.LyricsClient
import com.nadatube.app.sponsorblock.SponsorBlockClient
import com.nadatube.desktop.audio.DesktopPlayer
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Merakit semua layanan desktop: HTTP (dengan interceptor stream + DNS IPv4-dulu),
 * InnerTube, resolver stream, SponsorBlock, lirik, dan player — semuanya di atas
 * logika yang sama dengan aplikasi Android (:core).
 */
class DesktopServices(
    val store: DesktopStore = DesktopStore(DesktopStore.defaultConfigDir()),
    baseUrl: String = "https://www.youtube.com",
    musicBaseUrl: String = "https://music.youtube.com",
    sponsorBlockBaseUrl: String = "https://sponsor.ajay.app",
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(StreamRangeInterceptor())
        .dns(Ipv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val innerTube = InnerTubeClient(
        http = http,
        baseUrl = baseUrl,
        musicBaseUrl = musicBaseUrl,
        initialVisitorData = store.visitorData(),
        onVisitorData = { store.saveVisitorData(it) },
    )

    val resolver = StreamResolver(innerTube, qualityProvider = { store.quality() })
    val sponsorBlock = SponsorBlockClient(http, sponsorBlockBaseUrl)
    val lyrics = LyricsClient(http)

    val player = DesktopPlayer(
        resolver = resolver,
        http = http,
        sponsorBlock = sponsorBlock,
        store = store,
        cacheDir = store.cacheDir(),
    )

    suspend fun search(query: String, filter: com.nadatube.app.model.SearchFilter) =
        innerTube.search(query, filter)

    suspend fun radio(videoId: String) = innerTube.radio(videoId)
}
