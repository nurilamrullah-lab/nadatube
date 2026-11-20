package com.nadatube.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nadatube.app.AppContainer
import com.nadatube.app.model.Track
import com.nadatube.app.ui.theme.Nada
import kotlinx.coroutines.launch

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Nada.IndigoSoft, Color(0xFFE0E7FF)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Nada.IndigoLight.copy(alpha = 0.5f))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Nada.Indigo, background: Color = Nada.IndigoSoft, dot: Boolean = false) {
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** "HQ 160k Opus" style badge. */
@Composable
fun QualityBadge(label: String, hq: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (hq) Nada.IndigoLight.copy(alpha = 0.35f) else Nada.Border, RoundedCornerShape(6.dp))
            .background(if (hq) Nada.IndigoSoft else Nada.Canvas)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hq) {
            Text("HQ", style = MaterialTheme.typography.labelSmall, color = Nada.Indigo, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (hq) Nada.IndigoInk else Nada.Muted)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Nada.Ink, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = Nada.Indigo, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

/** Card surface used across screens: white, 1dp hairline border, soft radius. */
fun Modifier.nadaCard(corner: Dp = 18.dp): Modifier =
    this.clip(RoundedCornerShape(corner)).background(Nada.Card).border(1.dp, Nada.Border, RoundedCornerShape(corner))

/** Little animated equalizer shown on the currently playing row. */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, playing: Boolean = true, color: Color = Nada.Indigo) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier.size(16.dp)) {
        val w = size.width / 5
        for (i in 0 until 3) {
            val p = if (playing) (kotlin.math.sin((phase + i * 0.33f) * 2 * Math.PI).toFloat() + 1f) / 2f else 0.3f
            val h = size.height * (0.3f + 0.7f * p)
            drawRoundRect(color, Offset(i * 2 * w, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

data class TrackRowState(
    val isCurrent: Boolean = false,
    val isPlaying: Boolean = false,
    val isFavorite: Boolean = false,
    val isDownloaded: Boolean = false,
    val badge: String? = null,
    val badgeHq: Boolean = true,
)

@Composable
fun TrackRow(
    track: Track,
    container: AppContainer,
    modifier: Modifier = Modifier,
    state: TrackRowState = TrackRowState(),
    index: Int? = null,
    onClick: () -> Unit,
    showQuickActions: Boolean = false,
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var playlistDialog by remember { mutableStateOf(false) }
    Row(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Nada.Faint, modifier = Modifier.width(26.dp))
        }
        Box {
            Artwork(track.thumbnailUrl, Modifier.size(52.dp))
            if (state.isCurrent) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    PlayingBars(playing = state.isPlaying, color = Color.White)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, style = MaterialTheme.typography.titleSmall,
                color = if (state.isCurrent) Nada.Indigo else Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.isDownloaded && !showQuickActions) Icon(Icons.Rounded.CheckCircle, "Tersedia offline", tint = Nada.Green, modifier = Modifier.size(14.dp))
                if (state.badge != null) QualityBadge(state.badge, hq = state.badgeHq)
                Text(
                    listOfNotNull(track.artist.ifEmpty { null }, formatDuration(track.durationMs).takeIf { track.durationMs > 0 }).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showQuickActions) {
            IconButton(onClick = { container.player.addToQueue(track) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Tambah ke antrean", tint = Nada.Muted)
            }
            IconButton(onClick = { scope.launch { container.library.download(track) } }, modifier = Modifier.size(38.dp)) {
                Icon(
                    if (state.isDownloaded) Icons.Rounded.CheckCircle else Icons.Rounded.DownloadForOffline,
                    "Unduh offline", tint = if (state.isDownloaded) Nada.Green else Nada.Muted,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(if (showQuickActions) 34.dp else 48.dp)) { Icon(Icons.Rounded.MoreVert, "Opsi", tint = Nada.Faint) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Putar") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = { menu = false; onClick() })
                DropdownMenuItem(text = { Text("Putar berikutnya") }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) }, onClick = { menu = false; container.player.playNext(track) })
                DropdownMenuItem(text = { Text("Tambah ke antrean") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }, onClick = { menu = false; container.player.addToQueue(track) })
                DropdownMenuItem(
                    text = { Text(if (state.isFavorite) "Hapus dari favorit" else "Tambah ke favorit") },
                    leadingIcon = { Icon(if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
                    onClick = { menu = false; scope.launch { container.library.setFavorite(track, !state.isFavorite) } },
                )
                DropdownMenuItem(text = { Text("Tambah ke playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { menu = false; playlistDialog = true })
                if (!state.isDownloaded) {
                    DropdownMenuItem(text = { Text("Unduh offline") }, leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, null) }, onClick = { menu = false; scope.launch { container.library.download(track) } })
                }
                extraMenu.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                }
            }
        }
    }
    if (playlistDialog) AddToPlaylistDialog(track, container) { playlistDialog = false }
}

@Composable
fun AddToPlaylistDialog(track: Track, container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val playlists by container.library.playlists.collectAsState(initial = emptyList())
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nada.Card,
        title = { Text("Tambah ke playlist") },
        text = {
            Column {
                playlists.forEach { p ->
                    Row(
                        Modifier.clickable { scope.launch { container.library.addToPlaylist(p.playlist.id, track); onDismiss() } }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.coverUrl, Modifier.size(40.dp), corner = 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.playlist.name, style = MaterialTheme.typography.titleSmall)
                            Text("${p.trackCount} lagu", style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(newName, { newName = it }, label = { Text("Playlist baru") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                scope.launch {
                    val id = container.library.createPlaylist(newName)
                    container.library.addToPlaylist(id, track)
                    onDismiss()
                }
            }) {
                Icon(Icons.Rounded.Add, null)
                Text("Buat & tambah")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
