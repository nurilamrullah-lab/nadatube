package com.nadatube.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nadatube.app.model.Lyrics
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import com.nadatube.app.model.Track
import com.nadatube.app.ui.theme.ThemeMode
import com.nadatube.desktop.audio.PlaybackStatus
import com.nadatube.desktop.data.DesktopServices
import com.nadatube.desktop.data.HistoryEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class Screen { HOME, SEARCH, LIBRARY, SETTINGS }

fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Thumbnail YouTube via OkHttp + ImageIO; placeholder nada bila gagal/tanpa URL. */
@Composable
fun rememberThumbnail(url: String?, http: OkHttpClient): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bmp = null
        if (url == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = http.newCall(Request.Builder().url(url).build()).execute()
                    .use { it.body?.bytes() } ?: return@runCatching null
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

class DesktopViewModel(val services: DesktopServices) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player get() = services.player
    val store get() = services.store

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _filter = MutableStateFlow(SearchFilter.SONGS)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()
    private val _results = MutableStateFlow<List<SearchItem>>(emptyList())
    val results: StateFlow<List<SearchItem>> = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _lyricsOpen = MutableStateFlow(false)
    val lyricsOpen: StateFlow<Boolean> = _lyricsOpen.asStateFlow()
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()
    private val _queueOpen = MutableStateFlow(false)
    val queueOpen: StateFlow<Boolean> = _queueOpen.asStateFlow()
    private val _likesVersion = MutableStateFlow(0)
    val likesVersion: StateFlow<Int> = _likesVersion.asStateFlow()
    private val _themeMode = MutableStateFlow(store.themeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setScreen(s: Screen) {
        _screen.value = s
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setFilter(f: SearchFilter) {
        _filter.value = f
        if (_query.value.isNotBlank()) search(_query.value)
    }

    fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        scope.launch {
            _searching.value = true
            _searchError.value = null
            runCatching { services.search(query, _filter.value) }
                .onSuccess { _results.value = it.items }
                .onFailure { _searchError.value = it.message }
            _searching.value = false
        }
    }

    fun play(track: Track, queue: List<Track> = listOf(track)) {
        player.play(track, queue)
        scope.launch {
            val radio = runCatching { services.radio(track.videoId) }.getOrNull() ?: return@launch
            player.appendToQueue(radio)
        }
    }

    fun playItem(item: SearchItem) {
        when (item) {
            is SearchItem.Song -> {
                val songs = _results.value.filterIsInstance<SearchItem.Song>().map { it.track }
                play(item.track, songs.ifEmpty { listOf(item.track) })
            }
            is SearchItem.Artist, is SearchItem.Album, is SearchItem.Playlist -> {
                val id = item.id
                scope.launch {
                    _status.value = "Memuat…"
                    val tracks = runCatching {
                        if (item is SearchItem.Playlist) services.innerTube.playlist(id)
                        else services.innerTube.browse(id)
                    }.getOrNull().orEmpty()
                    if (tracks.isEmpty()) {
                        _status.value = "Tidak ada lagu yang bisa diputar"
                    } else {
                        _status.value = null
                        play(tracks.first(), tracks)
                    }
                }
            }
        }
    }

    fun playDownloaded(entry: HistoryEntry) {
        val path = entry.filePath ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = java.io.File(path).readBytes()
                player.playFromBytes(entry.toTrack(), bytes)
            }.onFailure { _status.value = "Gagal memutar file: ${it.message}" }
        }
    }

    fun toggleLike(videoId: String) {
        store.toggleLike(videoId)
        _likesVersion.value += 1
    }

    fun download(track: Track) {
        scope.launch {
            _status.value = "Mengunduh ${track.title}…"
            runCatching { player.downloadTrack(track) }
                .onSuccess { _status.value = "Tersimpan: ${it.name}" }
                .onFailure { _status.value = "Gagal mengunduh: ${it.message}" }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        store.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setQuality(quality: com.nadatube.app.extractor.StreamQuality) {
        store.setQuality(quality)
    }

    fun setVolume(v: Float) {
        player.setVolume(v)
        store.setVolume(v)
    }

    fun openLyrics() {
        val track = player.state.value.track ?: return
        _lyricsOpen.value = true
        scope.launch {
            _lyrics.value = runCatching { services.lyrics.lyrics(track) }.getOrNull()
        }
    }

    fun closeLyrics() {
        _lyricsOpen.value = false
    }

    fun setQueueOpen(open: Boolean) {
        _queueOpen.value = open
    }

    fun dismissStatus() {
        _status.value = null
    }

    fun close() = scope.cancel()
}

@Composable
fun DesktopApp(services: DesktopServices) {
    val vm = remember { DesktopViewModel(services) }
    val screen by vm.screen.collectAsState()
    val playerState by vm.player.state.collectAsState()
    val status by vm.status.collectAsState()
    val lyricsOpen by vm.lyricsOpen.collectAsState()
    val queueOpen by vm.queueOpen.collectAsState()

    Column(Modifier.fillMaxSize().background(Nada.Canvas)) {
        // Navigasi atas
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("♪ NadaTube", fontWeight = FontWeight.Bold, color = Nada.Indigo)
            Spacer(Modifier.width(24.dp))
            for (s in Screen.entries) {
                val label = when (s) {
                    Screen.HOME -> "Beranda"
                    Screen.SEARCH -> "Cari"
                    Screen.LIBRARY -> "Pustaka"
                    Screen.SETTINGS -> "Pengaturan"
                }
                TextButton(onClick = { vm.setScreen(s) }) {
                    Text(label, color = if (screen == s) Nada.Indigo else Nada.Muted)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("v1.2.0 desktop", color = Nada.Faint)
        }
        HorizontalDivider(color = Nada.Border)
        if (status != null) {
            Row(
                Modifier.fillMaxWidth().background(Nada.IndigoSoft).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(status ?: "", Modifier.weight(1f), color = Nada.IndigoInk)
                TextButton(onClick = { vm.dismissStatus() }) { Text("✕", color = Nada.IndigoInk) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.SEARCH -> SearchScreen(vm)
                Screen.LIBRARY -> LibraryScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
        HorizontalDivider(color = Nada.Border)
        PlayerBar(vm)
        if (playerState.status == PlaybackStatus.ERROR) {
            Text(
                "Error: ${playerState.error}",
                Modifier.fillMaxWidth().background(Nada.RoseSoft).padding(8.dp),
                color = Nada.Rose,
            )
        }
    }
    if (lyricsOpen) LyricsDialog(vm)
    if (queueOpen) QueueDialog(vm)
}

@Composable
private fun HomeScreen(vm: DesktopViewModel) {
    val history = remember(vm.player.state.collectAsState().value) { vm.store.history() }
    @Suppress("UNUSED_VARIABLE")
    val likesVersion by vm.likesVersion.collectAsState()
    val liked = history.filter { vm.store.isLiked(it.videoId) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Terakhir diputar", fontWeight = FontWeight.Bold, color = Nada.Ink)
            Spacer(Modifier.height(4.dp))
        }
        if (history.isEmpty()) {
            item { Text("Belum ada riwayat — cari lagu di tab Cari.", color = Nada.Muted) }
        } else {
            items(history.take(10)) { entry -> TrackRow(entry.toTrack(), vm, showLike = true) }
            item {
                val first = history.first()
                OutlinedButton(onClick = { vm.play(first.toTrack()) }) {
                    Text("Putar campuran dari \"${first.title}\"")
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Disukai", fontWeight = FontWeight.Bold, color = Nada.Ink)
            Spacer(Modifier.height(4.dp))
        }
        if (liked.isEmpty()) {
            item { Text("Belum ada lagu yang disukai.", color = Nada.Muted) }
        } else {
            items(liked) { entry -> TrackRow(entry.toTrack(), vm, showLike = true) }
        }
    }
}

@Composable
private fun SearchScreen(vm: DesktopViewModel) {
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val searchError by vm.searchError.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Cari lagu, artis, album…") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !searching) {
                Text("Cari")
            }
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            for (f in SearchFilter.entries) {
                TextButton(onClick = { vm.setFilter(f) }) {
                    Text(f.label, color = if (filter == f) Nada.Indigo else Nada.Muted)
                }
            }
        }
        if (searching) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        if (searchError != null) {
            Text("Gagal mencari: $searchError", color = Nada.Rose)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results, key = { it.id }) { item ->
                when (item) {
                    is SearchItem.Song -> TrackRow(item.track, vm, showLike = true, showDownload = true)
                    is SearchItem.Artist -> SimpleRow(item.name, item.subtitle, item.thumbnailUrl, vm.services.http) {
                        vm.playItem(item)
                    }
                    is SearchItem.Album -> SimpleRow(item.title, item.artist, item.thumbnailUrl, vm.services.http) {
                        vm.playItem(item)
                    }
                    is SearchItem.Playlist -> SimpleRow(item.title, item.author, item.thumbnailUrl, vm.services.http) {
                        vm.playItem(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(vm: DesktopViewModel) {
    val history = remember(vm.player.state.collectAsState().value) { vm.store.history() }
    val downloads = vm.store.downloads()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Unduhan", fontWeight = FontWeight.Bold, color = Nada.Ink) }
        if (downloads.isEmpty()) {
            item { Text("Belum ada unduhan.", color = Nada.Muted) }
        } else {
            items(downloads) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.playDownloaded(entry) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("♪", color = Nada.Indigo)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, color = Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.artist, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatMs(entry.durationMs), color = Nada.Muted)
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Riwayat", fontWeight = FontWeight.Bold, color = Nada.Ink)
        }
        items(history) { entry -> TrackRow(entry.toTrack(), vm, showLike = true) }
    }
}

@Composable
private fun SettingsScreen(vm: DesktopViewModel) {
    val themeMode by vm.themeMode.collectAsState()
    val playerState by vm.player.state.collectAsState()
    var downloadDir by remember { mutableStateOf(vm.store.downloadDir().absolutePath) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tema", fontWeight = FontWeight.Bold, color = Nada.Ink)
        for (mode in ThemeMode.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setThemeMode(mode) }) {
                RadioButton(selected = themeMode == mode, onClick = { vm.setThemeMode(mode) })
                Column {
                    Text(mode.label, color = Nada.Ink)
                    Text(mode.description, color = Nada.Muted)
                }
            }
        }
        HorizontalDivider(color = Nada.Border)
        Text("Kualitas stream", fontWeight = FontWeight.Bold, color = Nada.Ink)
        for (q in com.nadatube.app.extractor.StreamQuality.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setQuality(q) }) {
                RadioButton(selected = vm.store.quality() == q, onClick = { vm.setQuality(q) })
                Text(if (q == com.nadatube.app.extractor.StreamQuality.BEST) "Terbaik" else "Hemat data", color = Nada.Ink)
            }
        }
        HorizontalDivider(color = Nada.Border)
        Text("Volume: ${(playerState.volume * 100).toInt()}%", fontWeight = FontWeight.Bold, color = Nada.Ink)
        Slider(
            value = playerState.volume,
            onValueChange = { vm.setVolume(it) },
            modifier = Modifier.width(300.dp),
        )
        HorizontalDivider(color = Nada.Border)
        Text("Folder unduhan", fontWeight = FontWeight.Bold, color = Nada.Ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = downloadDir, onValueChange = { downloadDir = it }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.store.setDownloadDir(downloadDir.ifBlank { null }) }) { Text("Simpan") }
        }
        HorizontalDivider(color = Nada.Border)
        Text("Tentang", fontWeight = FontWeight.Bold, color = Nada.Ink)
        Text("NadaTube desktop v1.2.0 — pemutar audio YouTube tanpa iklan.", color = Nada.Muted)
        Text("Desktop memutar AAC (itag 140). Opus belum didukung di JVM tanpa library native.", color = Nada.Muted)
        Text("Cache & pengaturan: ${vm.store.dir.absolutePath}", color = Nada.Faint)
    }
}

@Composable
private fun TrackRow(track: Track, vm: DesktopViewModel, showLike: Boolean = false, showDownload: Boolean = false) {
    val liked = vm.store.isLiked(track.videoId)
    Row(
        Modifier.fillMaxWidth().clickable { vm.play(track) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(track.thumbnailUrl, vm.services.http, 48.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.durationMs > 0) Text(formatMs(track.durationMs), color = Nada.Muted)
        if (showDownload) {
            TextButton(onClick = { vm.download(track) }) { Text("↓", color = Nada.Indigo) }
        }
        if (showLike) {
            TextButton(onClick = { vm.toggleLike(track.videoId) }) {
                Text(if (liked) "♥" else "♡", color = if (liked) Nada.Rose else Nada.Muted)
            }
        }
    }
}

@Composable
private fun SimpleRow(title: String, subtitle: String, thumbnailUrl: String?, http: OkHttpClient, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(thumbnailUrl, http, 48.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Thumb(url: String?, http: OkHttpClient, size: androidx.compose.ui.unit.Dp) {
    val bmp = rememberThumbnail(url, http)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Nada.IndigoSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Text("♪", color = Nada.Indigo)
    }
}

@Composable
private fun PlayerBar(vm: DesktopViewModel) {
    val state by vm.player.state.collectAsState()
    val track = state.track
    var sliderPos by remember { mutableStateOf<Float?>(null) }
    var sleepExpanded by remember { mutableStateOf(false) }
    val sleepMs = vm.player.sleepRemainingMs()
    Column(Modifier.fillMaxWidth().background(Nada.Card).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(track?.thumbnailUrl, vm.services.http, 48.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(220.dp)) {
                Text(track?.title ?: "Tidak ada lagu", color = Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track?.artist ?: "NadaTube", color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { vm.player.previous() }, enabled = track != null) { Text("◀◀", color = Nada.Indigo) }
            TextButton(
                onClick = { vm.player.togglePlayPause() },
                enabled = track != null,
            ) {
                Text(if (state.status == PlaybackStatus.PLAYING) "❚❚" else "▶", color = Nada.Indigo)
            }
            TextButton(onClick = { vm.player.next() }, enabled = track != null) { Text("▶▶", color = Nada.Indigo) }
            Text(formatMs(state.positionMs), color = Nada.Muted)
            Slider(
                value = sliderPos ?: state.positionMs.toFloat(),
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    sliderPos?.let { vm.player.seekTo(it.toLong()) }
                    sliderPos = null
                },
                valueRange = 0f..maxOf(1f, state.durationMs.toFloat()),
                modifier = Modifier.weight(1f),
            )
            Text(formatMs(state.durationMs), color = Nada.Muted)
            Slider(
                value = state.volume,
                onValueChange = { vm.setVolume(it) },
                modifier = Modifier.width(110.dp),
            )
            if (track != null) {
                TextButton(onClick = { vm.toggleLike(track.videoId) }) {
                    Text(if (vm.store.isLiked(track.videoId)) "♥" else "♡", color = Nada.Rose)
                }
                TextButton(onClick = { vm.download(track) }) { Text("↓", color = Nada.Indigo) }
                TextButton(onClick = { vm.openLyrics() }) { Text("Lirik", color = Nada.Indigo) }
                TextButton(onClick = { vm.setQueueOpen(true) }) { Text("Antrean", color = Nada.Indigo) }
                Box {
                    TextButton(onClick = { sleepExpanded = true }) {
                        Text(if (sleepMs > 0) "Tidur ${formatMs(sleepMs)}" else "Tidur", color = Nada.Indigo)
                    }
                    DropdownMenu(expanded = sleepExpanded, onDismissRequest = { sleepExpanded = false }) {
                        DropdownMenuItem(text = { Text("15 menit") }, onClick = { vm.player.setSleepMinutes(15); sleepExpanded = false })
                        DropdownMenuItem(text = { Text("30 menit") }, onClick = { vm.player.setSleepMinutes(30); sleepExpanded = false })
                        DropdownMenuItem(text = { Text("60 menit") }, onClick = { vm.player.setSleepMinutes(60); sleepExpanded = false })
                        DropdownMenuItem(text = { Text("Akhir lagu") }, onClick = { vm.player.sleepUntilEndOfTrack(); sleepExpanded = false })
                        DropdownMenuItem(text = { Text("Matikan") }, onClick = { vm.player.cancelSleep(); sleepExpanded = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsDialog(vm: DesktopViewModel) {
    val lyrics by vm.lyrics.collectAsState()
    val position by vm.player.state.collectAsState()
    Dialog(onCloseRequest = { vm.closeLyrics() }) {
        Column(
            Modifier.background(Nada.Card, RoundedCornerShape(12.dp)).padding(16.dp).width(480.dp).height(480.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lirik", fontWeight = FontWeight.Bold, color = Nada.Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.closeLyrics() }) { Text("✕", color = Nada.Muted) }
            }
            HorizontalDivider(color = Nada.Border)
            when {
                lyrics == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Lirik tidak ditemukan untuk lagu ini.", color = Nada.Muted)
                    }
                }
                lyrics?.isSynced == true -> {
                    val lines = lyrics?.lines.orEmpty()
                    val currentIdx = lines.indexOfLast { it.timeMs <= position.positionMs }.coerceAtLeast(0)
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(lines.size) { i ->
                            Text(
                                lines[i].text,
                                color = if (i == currentIdx) Nada.Indigo else Nada.Ink,
                                fontWeight = if (i == currentIdx) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { Text(lyrics?.plain.orEmpty(), color = Nada.Ink) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueDialog(vm: DesktopViewModel) {
    val state by vm.player.state.collectAsState()
    Dialog(onCloseRequest = { vm.setQueueOpen(false) }) {
        Column(
            Modifier.background(Nada.Card, RoundedCornerShape(12.dp)).padding(16.dp).width(480.dp).height(480.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Antrean", fontWeight = FontWeight.Bold, color = Nada.Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setQueueOpen(false) }) { Text("✕", color = Nada.Muted) }
            }
            HorizontalDivider(color = Nada.Border)
            if (state.queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Antrean kosong.", color = Nada.Muted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.queue.size) { i ->
                        val t = state.queue[i]
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.player.play(t, state.queue)
                                vm.setQueueOpen(false)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t.title,
                                color = if (i == state.queueIndex) Nada.Indigo else Nada.Ink,
                                fontWeight = if (i == state.queueIndex) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatMs(t.durationMs), color = Nada.Muted)
                        }
                    }
                }
            }
        }
    }
}
