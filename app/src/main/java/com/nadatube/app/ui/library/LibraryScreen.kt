package com.nadatube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadatube.app.AppContainer
import com.nadatube.app.data.db.DownloadStatus
import com.nadatube.app.download.StorageSummary
import com.nadatube.app.ui.components.Artwork
import com.nadatube.app.ui.components.TrackRow
import com.nadatube.app.ui.components.nadaCard
import com.nadatube.app.ui.components.rememberTrackStates
import com.nadatube.app.ui.theme.Nada
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TABS = listOf("Diunduh", "Playlist", "Favorit", "Riwayat")

@Composable
fun LibraryScreen(container: AppContainer, onOpenPlaylist: (Long, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val downloads by container.library.downloads.collectAsState(initial = emptyList())
    val playlists by container.library.playlists.collectAsState(initial = emptyList())
    val favorites by container.library.favorites.collectAsState(initial = emptyList())
    val history by container.library.history.collectAsState(initial = emptyList())
    val rowState = rememberTrackStates(container)
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<StorageSummary?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    LaunchedEffect(downloads) { storage = runCatching { container.storageSummary() }.getOrNull() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Text("Pustaka", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(14.dp))
                storage?.let { StoragePanel(it) { scope.launch { container.clearCache(); storage = container.storageSummary() } } }
                Spacer(Modifier.height(8.dp))
            }
            TabRow(
                selectedTabIndex = tab,
                containerColor = Nada.Canvas,
                contentColor = Nada.Indigo,
                indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Nada.Indigo) },
                divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(Nada.Border)) },
            ) {
                TABS.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, color = if (tab == i) Nada.Indigo else Nada.Muted) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when (tab) {
            0 -> {
                val playable = downloads.filter { it.isPlayable }.map { it.track }
                if (downloads.isEmpty()) item { Empty(Icons.Rounded.CloudDownload, "Belum ada unduhan", "Ketuk ikon unduh pada lagu untuk mendengarkan tanpa internet.") }
                itemsIndexed(downloads, key = { _, d -> "dl-${d.track.videoId}" }) { _, d ->
                    when (d.status) {
                        DownloadStatus.COMPLETED -> TrackRow(
                            d.track, container, state = rowState(d.track).copy(badge = d.formatLabel.ifEmpty { null }),
                            onClick = { container.player.play(playable, playable.indexOfFirst { it.videoId == d.track.videoId }.coerceAtLeast(0), withRadio = false) },
                            extraMenu = listOf("Hapus unduhan" to { scope.launch { container.library.deleteDownload(d.track.videoId) }; Unit }),
                        )
                        else -> PendingDownloadRow(d.track.title, d.track.artist, d.track.thumbnailUrl, d.status, d.progress) {
                            scope.launch { container.library.deleteDownload(d.track.videoId) }
                        }
                    }
                }
            }
            1 -> {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { newPlaylist = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Nada.IndigoSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, tint = Nada.Indigo)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text("Buat playlist baru", style = MaterialTheme.typography.titleSmall, color = Nada.Indigo)
                    }
                }
                items(playlists, key = { "pl-${it.playlist.id}" }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPlaylist(p.playlist.id, p.playlist.name) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.coverUrl, Modifier.size(56.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.playlist.name, style = MaterialTheme.typography.titleSmall)
                            Text("${p.trackCount} lagu", style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
                        }
                        IconButton(onClick = { scope.launch { container.library.deletePlaylist(p.playlist.id) } }) {
                            Icon(Icons.Rounded.Delete, "Hapus playlist", tint = Nada.Faint)
                        }
                    }
                }
                if (playlists.isEmpty()) item { Empty(Icons.AutoMirrored.Rounded.QueueMusic, "Belum ada playlist", "Kumpulkan lagu favoritmu dalam playlist lokal.") }
            }
            2 -> {
                if (favorites.isEmpty()) item { Empty(Icons.Rounded.Favorite, "Belum ada favorit", "Ketuk ikon hati di pemutar untuk menyimpan lagu.") }
                itemsIndexed(favorites, key = { _, t -> "fav-${t.videoId}" }) { i, t ->
                    TrackRow(t, container, state = rowState(t), onClick = { container.player.play(favorites, i, withRadio = false) })
                }
            }
            else -> {
                if (history.isEmpty()) item { Empty(Icons.Rounded.History, "Riwayat kosong", "Lagu yang kamu putar akan muncul di sini.") }
                else item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { scope.launch { container.library.clearHistory() } }) { Text("Hapus riwayat", color = Nada.Rose) }
                    }
                }
                val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("id"))
                items(history, key = { "h-${it.track.videoId}-${it.playedAt}" }) { h ->
                    val t = h.track.toTrack()
                    Column {
                        TrackRow(t, container, state = rowState(t), onClick = { container.player.play(t) })
                        Text(
                            fmt.format(Date(h.playedAt)), style = MaterialTheme.typography.labelSmall, color = Nada.Faint,
                            modifier = Modifier.padding(start = 86.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (newPlaylist) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPlaylist = false },
            containerColor = Nada.Card,
            title = { Text("Playlist baru") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nama playlist") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { container.library.createPlaylist(name) }; newPlaylist = false }) { Text("Buat") }
            },
            dismissButton = { TextButton(onClick = { newPlaylist = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun StoragePanel(s: StorageSummary, onClearCache: () -> Unit) {
    Column(Modifier.fillMaxWidth().nadaCard().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Nada.IndigoSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Storage, null, tint = Nada.Indigo, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Penyimpanan", style = MaterialTheme.typography.titleSmall)
                Text("${StorageSummary.formatBytes(s.freeBytes)} tersedia di perangkat", style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        StorageLine("Unduhan (${s.downloadCount} lagu)", StorageSummary.formatBytes(s.downloadsBytes), s.usedFraction, Nada.Indigo)
        Spacer(Modifier.height(10.dp))
        StorageLine(
            "Cache terenkripsi", "${StorageSummary.formatBytes(s.cacheBytes)} / ${StorageSummary.formatBytes(s.cacheLimitBytes)}",
            s.cacheFraction, Nada.IndigoLight,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClearCache) { Text("Bersihkan cache", color = Nada.Indigo) }
        }
    }
}

@Composable
private fun StorageLine(label: String, value: String, fraction: Float, color: Color) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Nada.InkSoft, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = Nada.Ink)
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { fraction.coerceAtLeast(0.01f) },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        color = color, trackColor = Nada.Border,
    )
}

@Composable
private fun PendingDownloadRow(title: String, artist: String, thumb: String?, status: DownloadStatus, progress: Float, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(thumb, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (status) {
                    DownloadStatus.QUEUED -> "Menunggu…"
                    DownloadStatus.DOWNLOADING -> "Mengunduh ${(progress * 100).toInt()}% • $artist"
                    DownloadStatus.FAILED -> "Gagal — akan dicoba lagi"
                    DownloadStatus.COMPLETED -> artist
                },
                style = MaterialTheme.typography.bodySmall, color = if (status == DownloadStatus.FAILED) Nada.Rose else Nada.Muted, maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Nada.Indigo, trackColor = Nada.Border)
        }
        IconButton(onClick = onCancel) {
            Icon(if (status == DownloadStatus.FAILED) Icons.Rounded.ErrorOutline else Icons.Rounded.Delete, "Batalkan", tint = Nada.Faint)
        }
    }
}

@Composable
private fun Empty(icon: ImageVector, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).border(1.dp, Nada.Border, RoundedCornerShape(18.dp))
            .background(Nada.Card, RoundedCornerShape(18.dp)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Nada.IndigoLight, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
    }
}
