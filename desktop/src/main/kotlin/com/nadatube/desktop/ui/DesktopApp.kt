package com.nadatube.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class Screen { HOME, SEARCH, LIBRARY, SETTINGS, NOW_PLAYING }

/** Tab panel samping kanan (menggantikan dialog modal Lirik/Antrean v1.2.0). */
enum class SidePanelTab { LYRICS, QUEUE }

enum class LibraryTab { DOWNLOADS, LIKED, HISTORY }

fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
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
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()
    private val _likesVersion = MutableStateFlow(0)
    val likesVersion: StateFlow<Int> = _likesVersion.asStateFlow()
    private val _themeMode = MutableStateFlow(store.themeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    private val _quality = MutableStateFlow(store.quality())
    val quality: StateFlow<com.nadatube.app.extractor.StreamQuality> = _quality.asStateFlow()
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    // --- Redesign Fluent: sidebar lipat, panel samping, tab pustaka, fokus cari ---
    private val _sidebarCollapsed = MutableStateFlow(false)
    val sidebarCollapsed: StateFlow<Boolean> = _sidebarCollapsed.asStateFlow()
    private val _sidePanel = MutableStateFlow<SidePanelTab?>(null)
    val sidePanel: StateFlow<SidePanelTab?> = _sidePanel.asStateFlow()
    private val _libraryTab = MutableStateFlow(LibraryTab.DOWNLOADS)
    val libraryTab: StateFlow<LibraryTab> = _libraryTab.asStateFlow()
    private val _searchFocusTick = MutableStateFlow(0)
    val searchFocusTick: StateFlow<Int> = _searchFocusTick.asStateFlow()
    private val _pasteLinkOpen = MutableStateFlow(false)
    val pasteLinkOpen: StateFlow<Boolean> = _pasteLinkOpen.asStateFlow()

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

    /** Tempel tautan YouTube/YouTube Music: putar lewat radio dari videoId-nya. */
    fun playFromLink(raw: String) {
        val id = parseVideoId(raw.trim())
        if (id == null) {
            _status.value = "Tautan tidak dikenali — tempel tautan YouTube."
            return
        }
        scope.launch {
            _status.value = "Memuat…"
            val tracks = runCatching { services.radio(id) }.getOrNull().orEmpty()
            if (tracks.isEmpty()) {
                _status.value = "Tidak bisa memutar tautan ini"
            } else {
                _status.value = null
                play(tracks.first(), tracks)
            }
        }
    }

    private fun parseVideoId(raw: String): String? {
        if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) return raw
        return Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})")
            .find(raw)?.groupValues?.getOrNull(1)
    }

    fun toggleLike(videoId: String) {
        store.toggleLike(videoId)
        _likesVersion.value += 1
    }

    fun download(track: Track) {
        scope.launch {
            _downloading.value = _downloading.value + track.videoId
            _status.value = "Mengunduh ${track.title}…"
            runCatching { player.downloadTrack(track) }
                .onSuccess { _status.value = "Tersimpan: ${it.name}" }
                .onFailure { _status.value = "Gagal mengunduh: ${it.message}" }
            _downloading.value = _downloading.value - track.videoId
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        store.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setQuality(quality: com.nadatube.app.extractor.StreamQuality) {
        store.setQuality(quality)
        _quality.value = quality
    }

    fun setVolume(v: Float) {
        player.setVolume(v)
        store.setVolume(v)
    }

    fun toggleSidebar() {
        _sidebarCollapsed.value = !_sidebarCollapsed.value
    }

    fun openSidePanel(tab: SidePanelTab) {
        if (tab == SidePanelTab.LYRICS) openLyrics() else _sidePanel.value = SidePanelTab.QUEUE
    }

    fun closeSidePanel() {
        _sidePanel.value = null
    }

    fun setLibraryTab(tab: LibraryTab) {
        _libraryTab.value = tab
    }

    fun requestSearchFocus() {
        _searchFocusTick.value += 1
    }

    fun setPasteLinkOpen(open: Boolean) {
        _pasteLinkOpen.value = open
    }

    fun openLyrics(track: Track? = null) {
        val t = track ?: player.state.value.track ?: return
        _sidePanel.value = SidePanelTab.LYRICS
        scope.launch {
            _lyrics.value = runCatching { services.lyrics.lyrics(t) }.getOrNull()
        }
    }

    fun closeLyrics() {
        if (_sidePanel.value == SidePanelTab.LYRICS) _sidePanel.value = null
    }

    fun setQueueOpen(open: Boolean) {
        _sidePanel.value = if (open) SidePanelTab.QUEUE else null
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
    val sidePanel by vm.sidePanel.collectAsState()
    val pasteLinkOpen by vm.pasteLinkOpen.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        Modifier.fillMaxSize().background(Nada.Mica)
            .focusRequester(focusRequester).focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyUp) return@onKeyEvent false
                when {
                    (ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.K -> {
                        vm.setScreen(Screen.SEARCH)
                        vm.requestSearchFocus()
                        true
                    }
                    ev.key == Key.Escape && sidePanel != null -> {
                        vm.closeSidePanel()
                        true
                    }
                    ev.key == Key.Spacebar -> {
                        vm.player.togglePlayPause()
                        true
                    }
                    else -> false
                }
            },
    ) {
        TitleBar(vm)
        HorizontalDivider(color = Nada.Border)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SidebarNav(vm)
            // Lapisan konten utama (Fluent layer, sudut kiri-atas 10dp)
            Box(Modifier.weight(1f).fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize()
                        .background(Nada.Layer, RoundedCornerShape(topStart = 10.dp))
                        .clip(RoundedCornerShape(topStart = 10.dp)),
                ) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(vm)
                        Screen.SEARCH -> SearchScreen(vm)
                        Screen.LIBRARY -> LibraryScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                        Screen.NOW_PLAYING -> NowPlayingScreen(vm)
                    }
                }
                // InfoBar status mengambang di pojok kanan atas
                if (status != null) {
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(16.dp).widthIn(max = 420.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Nada.Flyout)
                            .border(1.dp, Nada.Border, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(status ?: "", Modifier.weight(1f), color = Nada.Ink)
                        TextButton(onClick = { vm.dismissStatus() }) { Text("✕", color = Nada.Muted) }
                    }
                }
            }
            if (sidePanel != null) SidePanel(vm, sidePanel!!)
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
    if (pasteLinkOpen) PasteLinkDialog(vm)
}

@Composable
private fun TitleBar(vm: DesktopViewModel) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(Nada.Mica).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { vm.toggleSidebar() },
            modifier = Modifier.size(32.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("☰", color = Nada.Ink) }
        Image(
            painterResource("icon-512.png"),
            contentDescription = "NadaTube",
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text("NadaTube", fontWeight = FontWeight.Bold, color = Nada.Ink)
        Text(" Desktop", color = Nada.Muted)
        Spacer(Modifier.width(16.dp))
        // Kotak cari di title bar — klik pindah ke layar Cari + fokus
        Box(
            Modifier.width(320.dp).height(32.dp).clip(RoundedCornerShape(6.dp)).background(Nada.Card)
                .clickable { vm.setScreen(Screen.SEARCH); vm.requestSearchFocus() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔍 ", color = Nada.Faint)
                Text(
                    "Cari lagu, artis, album…",
                    color = Nada.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Ctrl K",
                    color = Nada.Faint,
                    fontSize = 11.sp,
                    modifier = Modifier.background(Nada.IndigoSoft, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.setPasteLinkOpen(true) }) { Text("Tempel Link", color = Nada.Indigo) }
        Text(
            "Tanpa Iklan · AAC",
            color = Nada.Green,
            fontSize = 12.sp,
            modifier = Modifier.background(Nada.GreenSoft, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("v1.2.0 desktop", color = Nada.Faint, fontSize = 12.sp)
    }
}

@Composable
private fun SidebarNav(vm: DesktopViewModel) {
    val screen by vm.screen.collectAsState()
    val collapsed by vm.sidebarCollapsed.collectAsState()
    val libraryTab by vm.libraryTab.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val likesVersion by vm.likesVersion.collectAsState()
    val history = remember(vm.player.state.collectAsState().value) { vm.store.history() }
    val likedCount = history.count { vm.store.isLiked(it.videoId) }
    val downloadCount = vm.store.downloads().size
    val width by animateDpAsState(
        targetValue = if (collapsed) 64.dp else 244.dp,
        label = "sidebarWidth",
    )
    Column(
        Modifier.width(width).fillMaxHeight().background(Nada.Mica)
            .padding(vertical = 8.dp, horizontal = if (collapsed) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NavItem("⌂", "Beranda", screen == Screen.HOME, collapsed) { vm.setScreen(Screen.HOME) }
        NavItem("🔍", "Cari", screen == Screen.SEARCH, collapsed) { vm.setScreen(Screen.SEARCH) }
        NavItem("▶", "Sedang Diputar", screen == Screen.NOW_PLAYING, collapsed) { vm.setScreen(Screen.NOW_PLAYING) }
        NavItem("▤", "Pustaka", screen == Screen.LIBRARY, collapsed) { vm.setScreen(Screen.LIBRARY) }
        if (!collapsed) {
            Spacer(Modifier.height(6.dp))
            Text(
                "KOLEKSI CEPAT",
                color = Nada.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            HorizontalDivider(color = Nada.Border)
        }
        NavItem("♥", "Disukai", screen == Screen.LIBRARY && libraryTab == LibraryTab.LIKED, collapsed, likedCount) {
            vm.setScreen(Screen.LIBRARY); vm.setLibraryTab(LibraryTab.LIKED)
        }
        NavItem("↓", "Unduhan", screen == Screen.LIBRARY && libraryTab == LibraryTab.DOWNLOADS, collapsed, downloadCount) {
            vm.setScreen(Screen.LIBRARY); vm.setLibraryTab(LibraryTab.DOWNLOADS)
        }
        NavItem("◷", "Riwayat", screen == Screen.LIBRARY && libraryTab == LibraryTab.HISTORY, collapsed) {
            vm.setScreen(Screen.LIBRARY); vm.setLibraryTab(LibraryTab.HISTORY)
        }
        Spacer(Modifier.weight(1f))
        NavItem("⚙", "Pengaturan", screen == Screen.SETTINGS, collapsed) { vm.setScreen(Screen.SETTINGS) }
        if (!collapsed) {
            Text("NadaTube v1.2.0", color = Nada.Faint, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun NavItem(
    icon: String,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) Nada.IndigoSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(18.dp)
                .background(if (selected) Nada.Indigo else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(icon, color = if (selected) Nada.Indigo else Nada.Muted, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
        if (!collapsed) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = if (selected) Nada.Ink else Nada.InkSoft,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null && badge > 0) {
                Text(
                    badge.toString(),
                    color = Nada.IndigoInk,
                    fontSize = 11.sp,
                    modifier = Modifier.background(Nada.IndigoSoft, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: DesktopViewModel) {
    val history = remember(vm.player.state.collectAsState().value) { vm.store.history() }
    @Suppress("UNUSED_VARIABLE")
    val likesVersion by vm.likesVersion.collectAsState()
    val liked = history.filter { vm.store.isLiked(it.videoId) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Selamat datang", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Nada.Ink)
        }
        if (history.isNotEmpty()) {
            val first = history.first()
            item {
                // Hero Mix — kartu Campuran Radio Otomatis
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Nada.IndigoSoft)
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(first.thumbnailUrl, vm.services.http, 112.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "CAMPURAN RADIO OTOMATIS",
                                color = Nada.Indigo,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Campuran dari \"${first.title}\"",
                                color = Nada.Ink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("${first.artist} · dibuat dari lagu terakhir diputar", color = Nada.Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.play(first.toTrack()) }) { Text("▶ Putar Campuran") }
                                OutlinedButton(onClick = {
                                    vm.play(first.toTrack())
                                    vm.openLyrics(first.toTrack())
                                }) { Text("Buka Lirik Tersinkron") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Terakhir diputar", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Nada.Ink)
        }
        if (history.isEmpty()) {
            item { Text("Belum ada riwayat — cari lagu di tab Cari.", color = Nada.Muted) }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    history.take(8).forEach { entry ->
                        Column(Modifier.width(160.dp).clickable { vm.play(entry.toTrack()) }) {
                            Thumb(entry.thumbnailUrl, vm.services.http, 160.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(entry.title, color = Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.artist, color = Nada.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Text("Disukai", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Nada.Ink)
        }
        if (liked.isEmpty()) {
            item { Text("Belum ada lagu yang disukai.", color = Nada.Muted) }
        } else {
            items(liked, key = { it.videoId }) { entry -> TrackRow(entry.toTrack(), vm, showLike = true) }
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
    val focusTick by vm.searchFocusTick.collectAsState()
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) searchFocusRequester.requestFocus()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Cari", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Nada.Ink)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                placeholder = { Text("Cari lagu, artis, album…") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotBlank()) {
                        TextButton(onClick = { vm.setQuery("") }) { Text("✕", color = Nada.Muted) }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !searching) {
                Text("Cari")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Ctrl K",
                color = Nada.Faint,
                fontSize = 11.sp,
                modifier = Modifier.background(Nada.IndigoSoft, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        // Filter pills
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in SearchFilter.entries) {
                val selected = filter == f
                Button(
                    onClick = { vm.setFilter(f) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Nada.Indigo else Nada.Card,
                        contentColor = if (selected) Color.White else Nada.Ink,
                    ),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(f.label) }
            }
        }
        // Chip pencarian cepat
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (chip in listOf("Tulus", "Hindia", "Nadin Amizah", "Sheila on 7")) {
                OutlinedButton(onClick = { vm.setQuery(chip); vm.search(chip) }) { Text(chip) }
            }
        }
        Spacer(Modifier.height(12.dp))
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
    @Suppress("UNUSED_VARIABLE")
    val likesVersion by vm.likesVersion.collectAsState()
    val downloads = vm.store.downloads()
    val tab by vm.libraryTab.collectAsState()
    val liked = history.filter { vm.store.isLiked(it.videoId) }
    val totalBytes = downloads.sumOf { it.filePath?.let { p -> File(p).length() } ?: 0L }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Pustaka", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Nada.Ink)
        Spacer(Modifier.height(12.dp))
        // Kartu ringkasan penyimpanan offline
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Nada.Card)
                .border(1.dp, Nada.Border, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("↓", color = Nada.Indigo)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Unduhan Offline", color = Nada.Ink, fontWeight = FontWeight.SemiBold)
                Text(
                    "${downloads.size} lagu · ${formatBytes(totalBytes)} · ${vm.store.downloadDir().absolutePath}",
                    color = Nada.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Pivot tabs
        Row {
            for (t in LibraryTab.entries) {
                val label = when (t) {
                    LibraryTab.DOWNLOADS -> "Unduhan Offline"
                    LibraryTab.LIKED -> "Disukai"
                    LibraryTab.HISTORY -> "Riwayat"
                }
                val selected = tab == t
                Column(
                    Modifier.clickable { vm.setLibraryTab(t) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        color = if (selected) Nada.Indigo else Nada.Muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.width(48.dp).height(2.dp)
                            .background(if (selected) Nada.Indigo else Color.Transparent, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
        HorizontalDivider(color = Nada.Border)
        Spacer(Modifier.height(8.dp))
        when (tab) {
            LibraryTab.DOWNLOADS -> {
                if (downloads.isEmpty()) {
                    Text("Belum ada unduhan.", color = Nada.Muted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(downloads, key = { it.videoId }) { entry ->
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
                }
            }
            LibraryTab.LIKED -> {
                if (liked.isEmpty()) {
                    Text("Belum ada lagu yang disukai.", color = Nada.Muted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(liked, key = { it.videoId }) { entry -> TrackRow(entry.toTrack(), vm, showLike = true) }
                    }
                }
            }
            LibraryTab.HISTORY -> {
                if (history.isEmpty()) {
                    Text("Belum ada riwayat.", color = Nada.Muted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(history, key = { it.videoId }) { entry -> TrackRow(entry.toTrack(), vm, showLike = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: DesktopViewModel) {
    val themeMode by vm.themeMode.collectAsState()
    val quality by vm.quality.collectAsState()
    val playerState by vm.player.state.collectAsState()
    var downloadDir by remember { mutableStateOf(vm.store.downloadDir().absolutePath) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Pengaturan", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Nada.Ink) }
        item {
            SettingsCard("Tampilan") {
                Text("Tema", color = Nada.Ink, fontWeight = FontWeight.SemiBold)
                SegmentedControl(
                    options = listOf("Gelap", "Terang", "Sistem"),
                    selectedIndex = when (themeMode) {
                        ThemeMode.DARK -> 0
                        ThemeMode.LIGHT -> 1
                        ThemeMode.SYSTEM -> 2
                    },
                    onSelect = { i ->
                        vm.setThemeMode(
                            when (i) {
                                0 -> ThemeMode.DARK
                                1 -> ThemeMode.LIGHT
                                else -> ThemeMode.SYSTEM
                            },
                        )
                    },
                )
                Text(themeMode.description, color = Nada.Muted, fontSize = 12.sp)
            }
        }
        item {
            SettingsCard("Audio & Pemutaran") {
                Text("Kualitas stream", color = Nada.Ink, fontWeight = FontWeight.SemiBold)
                SegmentedControl(
                    options = listOf("Terbaik", "Hemat data"),
                    selectedIndex = if (quality == com.nadatube.app.extractor.StreamQuality.BEST) 0 else 1,
                    onSelect = { i ->
                        vm.setQuality(
                            if (i == 0) com.nadatube.app.extractor.StreamQuality.BEST
                            else com.nadatube.app.extractor.StreamQuality.DATA_SAVER,
                        )
                    },
                )
                Text("Volume: ${(playerState.volume * 100).toInt()}%", color = Nada.Ink, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = playerState.volume,
                    onValueChange = { vm.setVolume(it) },
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        item {
            SettingsCard("Unduhan & Penyimpanan") {
                Text("Folder unduhan", color = Nada.Ink, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = downloadDir, onValueChange = { downloadDir = it }, modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.store.setDownloadDir(downloadDir.ifBlank { null }) }) { Text("Simpan") }
                }
            }
        }
        item {
            SettingsCard("Tentang") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource("icon-512.png"),
                        contentDescription = "NadaTube",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("NadaTube desktop v1.2.0", color = Nada.Ink, fontWeight = FontWeight.SemiBold)
                        Text("Pemutar audio YouTube tanpa iklan.", color = Nada.Muted, fontSize = 12.sp)
                    }
                }
                Text("Desktop memutar AAC (itag 140). Opus belum didukung di JVM tanpa library native.", color = Nada.Muted)
                Text("Cache & pengaturan: ${vm.store.dir.absolutePath}", color = Nada.Faint)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Nada.Ink)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Nada.Card)
            .border(1.dp, Nada.Border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Button(
                onClick = { onSelect(i) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Nada.Indigo else Nada.Card,
                    contentColor = if (selected) Color.White else Nada.Ink,
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(label) }
        }
    }
}

@Composable
private fun NowPlayingScreen(vm: DesktopViewModel) {
    val state by vm.player.state.collectAsState()
    val track = state.track
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Belum ada lagu yang diputar — cari lagu di tab Cari.", color = Nada.Muted)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(track.thumbnailUrl, vm.services.http, 200.dp)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        color = Nada.Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(track.artist, color = Nada.Muted, fontSize = 16.sp)
                    val album = track.album
                    if (album != null) Text(album, color = Nada.Faint, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.player.togglePlayPause() }) {
                            Text(if (state.status == PlaybackStatus.PLAYING) "❚❚ Jeda" else "▶ Putar")
                        }
                        OutlinedButton(onClick = { vm.openLyrics() }) { Text("Lirik Tersinkron") }
                        OutlinedButton(onClick = { vm.openSidePanel(SidePanelTab.QUEUE) }) { Text("Antrean") }
                        OutlinedButton(onClick = { vm.toggleLike(track.videoId) }) {
                            Text(if (vm.store.isLiked(track.videoId)) "♥ Disukai" else "♡ Suka")
                        }
                        OutlinedButton(onClick = { vm.download(track) }) { Text("↓ Unduh") }
                    }
                }
            }
        }
        item { Text("Berikutnya", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Nada.Ink) }
        val next = if (state.queueIndex >= 0) state.queue.drop(state.queueIndex + 1) else state.queue
        if (next.isEmpty()) {
            item { Text("Tidak ada lagu berikutnya.", color = Nada.Muted) }
        } else {
            items(next, key = { it.videoId }) { t -> TrackRow(t, vm, showLike = true, showDownload = true) }
        }
    }
}

@Composable
private fun TrackRow(track: Track, vm: DesktopViewModel, showLike: Boolean = false, showDownload: Boolean = false) {
    val liked = vm.store.isLiked(track.videoId)
    val playerState by vm.player.state.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val playing = playerState.track?.videoId == track.videoId
    val downloaded = remember(playerState, liked) { vm.store.downloads().any { it.videoId == track.videoId } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (playing) Nada.IndigoSoft else Color.Transparent)
            .clickable { vm.play(track) }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(track.thumbnailUrl, vm.services.http, 48.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (playing) Nada.IndigoInk else Nada.Ink,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.durationMs > 0) Text(formatMs(track.durationMs), color = Nada.Muted)
        if (downloading.contains(track.videoId)) {
            CircularProgressIndicator(Modifier.size(20.dp))
        } else if (showDownload) {
            TextButton(onClick = { vm.download(track) }) {
                Text(if (downloaded) "✓" else "↓", color = if (downloaded) Nada.Green else Nada.Indigo)
            }
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
    var sleepRemaining by remember { mutableStateOf(0L) }
    // Hitung mundur timer tidur live (sleepRemainingMs() bukan state, jadi di-tick tiap detik)
    LaunchedEffect(Unit) {
        while (true) {
            sleepRemaining = vm.player.sleepRemainingMs()
            delay(1000)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(84.dp).background(Nada.Acrylic)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Zona kiri: info lagu
        Row(Modifier.width(280.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(track?.thumbnailUrl, vm.services.http, 52.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: "Tidak ada lagu", color = Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track?.artist ?: "NadaTube", color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (track != null) {
                TextButton(onClick = { vm.toggleLike(track.videoId) }) {
                    Text(if (vm.store.isLiked(track.videoId)) "♥" else "♡", color = Nada.Rose)
                }
            }
        }
        // Zona tengah: transport + scrubber
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.player.previous() }, enabled = track != null) { Text("◀◀", color = Nada.Indigo) }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Nada.Indigo)
                        .clickable(enabled = track != null) { vm.player.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (state.status == PlaybackStatus.PLAYING) "❚❚" else "▶", color = Color.White)
                }
                TextButton(onClick = { vm.player.next() }, enabled = track != null) { Text("▶▶", color = Nada.Indigo) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            }
        }
        // Zona kanan: utilitas
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                TextButton(onClick = { vm.download(track) }) { Text("↓", color = Nada.Indigo) }
                TextButton(onClick = { vm.openLyrics() }) { Text("Lirik", color = Nada.Indigo) }
                TextButton(onClick = { vm.setQueueOpen(true) }) { Text("Antrean", color = Nada.Indigo) }
                Box {
                    TextButton(onClick = { sleepExpanded = true }) {
                        Text(if (sleepRemaining > 0) "Tidur ${formatMs(sleepRemaining)}" else "Tidur", color = Nada.Indigo)
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
            Slider(
                value = state.volume,
                onValueChange = { vm.setVolume(it) },
                modifier = Modifier.width(110.dp),
            )
        }
    }
}

@Composable
private fun SidePanel(vm: DesktopViewModel, tab: SidePanelTab) {
    Column(
        Modifier.width(350.dp).fillMaxHeight().background(Nada.Mica).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.openSidePanel(SidePanelTab.LYRICS) }) {
                Text("Lirik", color = if (tab == SidePanelTab.LYRICS) Nada.Indigo else Nada.Muted)
            }
            TextButton(onClick = { vm.openSidePanel(SidePanelTab.QUEUE) }) {
                Text("Antrean", color = if (tab == SidePanelTab.QUEUE) Nada.Indigo else Nada.Muted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.closeSidePanel() }) { Text("✕", color = Nada.Muted) }
        }
        HorizontalDivider(color = Nada.Border)
        if (tab == SidePanelTab.LYRICS) LyricsPanel(vm) else QueuePanel(vm)
    }
}

@Composable
private fun LyricsPanel(vm: DesktopViewModel) {
    val lyrics by vm.lyrics.collectAsState()
    val state by vm.player.state.collectAsState()
    val track = state.track
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Belum ada lagu yang diputar.", color = Nada.Muted)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(track.title, color = Nada.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, color = Nada.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        when {
            lyrics == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Memuat lirik…", color = Nada.Muted)
                }
            }
            lyrics?.isSynced == true -> {
                val lines = lyrics?.lines.orEmpty()
                val currentIdx = lines.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                val listState = rememberLazyListState()
                LaunchedEffect(currentIdx) {
                    if (lines.isNotEmpty()) listState.scrollToItem(currentIdx.coerceIn(0, lines.size - 1))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(lines.size) { i ->
                        val active = i == currentIdx
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (active) Nada.IndigoSoft else Color.Transparent)
                                .clickable { vm.player.seekTo(lines[i].timeMs) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.width(3.dp).height(18.dp)
                                    .background(if (active) Nada.Indigo else Color.Transparent, RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                lines[i].text,
                                color = if (active) Nada.IndigoInk else Nada.Ink,
                                fontSize = if (active) 16.sp else 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
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

@Composable
private fun QueuePanel(vm: DesktopViewModel) {
    val state by vm.player.state.collectAsState()
    if (state.queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Antrean kosong.", color = Nada.Muted)
        }
        return
    }
    val current = state.queue.getOrNull(state.queueIndex)
    val next = if (state.queueIndex >= 0) state.queue.drop(state.queueIndex + 1) else state.queue
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (current != null) {
            item {
                Text("Sedang diputar", fontWeight = FontWeight.SemiBold, color = Nada.Ink)
                Spacer(Modifier.height(4.dp))
                QueueRow(current, isCurrent = true, vm)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        item { Text("Berikutnya (${next.size})", fontWeight = FontWeight.SemiBold, color = Nada.Ink) }
        items(next, key = { it.videoId }) { t -> QueueRow(t, isCurrent = false, vm) }
    }
}

@Composable
private fun QueueRow(track: Track, isCurrent: Boolean, vm: DesktopViewModel) {
    val state by vm.player.state.collectAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (isCurrent) Nada.IndigoSoft else Color.Transparent)
            .clickable { vm.player.play(track, state.queue) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) Nada.IndigoInk else Nada.Ink,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, color = Nada.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatMs(track.durationMs), color = Nada.Muted)
    }
}

@Composable
private fun PasteLinkDialog(vm: DesktopViewModel) {
    var url by remember { mutableStateOf("") }
    DialogWindow(onCloseRequest = { vm.setPasteLinkOpen(false) }, title = "Tempel Link") {
        Column(
            Modifier.background(Nada.Card, RoundedCornerShape(12.dp)).padding(16.dp).width(480.dp),
        ) {
            Text("Tempel Link", fontWeight = FontWeight.Bold, color = Nada.Ink)
            Spacer(Modifier.height(4.dp))
            Text("Tempel tautan YouTube / YouTube Music, lalu putar langsung.", color = Nada.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://www.youtube.com/watch?v=…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.setPasteLinkOpen(false) }) { Text("Batal", color = Nada.Muted) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.playFromLink(url); vm.setPasteLinkOpen(false) },
                    enabled = url.isNotBlank(),
                ) { Text("Putar") }
            }
        }
    }
}
