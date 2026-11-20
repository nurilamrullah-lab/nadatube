package com.nadatube.app.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.nadatube.app.AppContainer
import com.nadatube.app.model.Track
import com.nadatube.app.ui.components.Artwork
import com.nadatube.app.ui.components.TrackRow
import com.nadatube.app.ui.components.formatDuration
import com.nadatube.app.ui.components.rememberTrackStates
import com.nadatube.app.ui.theme.Nada
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Album / public playlist (kind = album|playlist) or a local playlist (kind = local). */
@Composable
fun CollectionScreen(container: AppContainer, kind: String, id: String, title: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rowState = rememberTrackStates(container)
    var remote by remember { mutableStateOf<List<Track>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val localFlow = remember(kind, id) {
        if (kind == "local") container.library.playlistTracks(id.toLong()) else flowOf(emptyList())
    }
    val local by localFlow.collectAsState(initial = emptyList())

    LaunchedEffect(kind, id) {
        if (kind != "local") {
            runCatching { container.innerTube.browse(id) }
                .onSuccess { remote = it }
                .onFailure { error = it.message }
        }
    }
    val tracks = if (kind == "local") local else remote.orEmpty()
    val loading = kind != "local" && remote == null && error == null

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
            }
            Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(tracks.firstOrNull()?.thumbnailUrl, Modifier.size(180.dp).shadow(16.dp, RoundedCornerShape(24.dp)), corner = 24.dp)
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                val total = tracks.sumOf { it.durationMs }
                Text(
                    "${when (kind) { "album" -> "Album"; "local" -> "Playlist lokal"; else -> "Playlist" }} • ${tracks.size} lagu" +
                        if (total > 0) " • ${formatDuration(total)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = Nada.Muted,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { tracks.forEach { container.library.download(it) } } }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.DownloadForOffline, "Unduh semua", tint = Nada.Muted)
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier.size(60.dp).clip(CircleShape).background(Nada.Indigo)
                            .clickable(enabled = tracks.isNotEmpty()) { container.player.play(tracks, 0, withRadio = false) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.PlayArrow, "Putar semua", tint = Color.White, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { container.player.play(tracks.shuffled(), 0, withRadio = false) }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.Shuffle, "Acak", tint = Nada.Muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (loading) CircularProgressIndicator(color = Nada.Indigo)
                error?.let { Text("Gagal memuat: $it", color = Nada.Muted) }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.videoId }) { i, t ->
            TrackRow(
                t, container, state = rowState(t), index = i,
                onClick = { container.player.play(tracks, i, withRadio = false) },
                extraMenu = if (kind == "local") listOf("Hapus dari playlist" to { scope.launch { container.library.removeFromPlaylist(id.toLong(), t.videoId) }; Unit }) else emptyList(),
            )
        }
    }
}
