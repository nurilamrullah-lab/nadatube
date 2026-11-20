package com.nadatube.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadatube.app.AppContainer
import com.nadatube.app.model.SponsorSegment
import com.nadatube.app.model.Track
import com.nadatube.app.ui.theme.Nada
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun rememberTrackStates(container: AppContainer): (Track) -> TrackRowState {
    val favorites by container.library.favoriteIds.collectAsState(initial = emptySet())
    val downloads by container.library.downloads.collectAsState(initial = emptyList())
    val player by container.player.state.collectAsState()
    val done = remember(downloads) { downloads.filter { it.isPlayable }.map { it.track.videoId }.toSet() }
    return { t ->
        TrackRowState(
            isCurrent = player.track?.videoId == t.videoId,
            isPlaying = player.isPlaying,
            isFavorite = t.videoId in favorites,
            isDownloaded = t.videoId in done,
        )
    }
}

/** Floating mini-player card that sits above the bottom navigation. */
@Composable
fun MiniPlayer(container: AppContainer, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by container.player.state.collectAsState()
    val track = state.track ?: return
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    Box(
        modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = Nada.Indigo.copy(alpha = 0.25f), spotColor = Nada.Indigo.copy(alpha = 0.25f))
            .nadaCard(20.dp)
            .clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.thumbnailUrl, Modifier.size(44.dp), corner = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Nada.Indigo).clickable { container.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Putar/jeda", tint = Color.White)
                    }
                }
                IconButton(onClick = { container.player.next() }) { Icon(Icons.Rounded.SkipNext, "Berikutnya", tint = Nada.InkSoft) }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Nada.IndigoSoft)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Nada.IndigoLight))
            }
        }
    }
}

/** Seek bar with SponsorBlock segments drawn as amber blocks on the track. */
@Composable
fun SegmentSeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    segments: List<SponsorSegment>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thumb by animateFloatAsState(if (dragFraction != null) 9f else 6f, label = "thumb")
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            widthPx = size.width
            val cy = size.height / 2
            val h = 5.dp.toPx()
            val r = CornerRadius(h / 2)
            drawRoundRect(Nada.Border, Offset(0f, cy - h / 2), Size(size.width, h), r)
            if (durationMs > 0) {
                val buf = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                drawRoundRect(Nada.IndigoSoft.copy(alpha = 1f), Offset(0f, cy - h / 2), Size(size.width * buf, h), r)
                drawRoundRect(Nada.Indigo, Offset(0f, cy - h / 2), Size(size.width * fraction, h), r)
                segments.forEach { s ->
                    val x0 = size.width * (s.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val x1 = size.width * (s.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    drawRoundRect(Nada.Amber, Offset(x0, cy - h / 2 - 2.dp.toPx()), Size((x1 - x0).coerceAtLeast(3.dp.toPx()), h + 4.dp.toPx()), CornerRadius(3.dp.toPx()))
                }
            }
            drawCircle(Color.White, thumb.dp.toPx() + 2.dp.toPx(), Offset(size.width * fraction, cy))
            drawCircle(Nada.Indigo, thumb.dp.toPx(), Offset(size.width * fraction, cy))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(dragFraction?.let { (it * durationMs).toLong() } ?: positionMs), style = MaterialTheme.typography.labelMedium, color = Nada.Muted)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium, color = Nada.Muted)
        }
    }
}

/** Mirrored bar waveform driven by real PCM levels from the player. */
@Composable
fun WaveVisualizer(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier, color: Color = Nada.Indigo) {
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val cy = size.height / 2
        levels.forEachIndexed { i, raw ->
            val level = if (playing) raw else 0.04f
            val h = (size.height * (0.06f + 0.94f * level)).coerceAtMost(size.height)
            val alpha = 0.35f + 0.65f * (i.toFloat() / n)
            drawRoundRect(
                color.copy(alpha = alpha),
                Offset(i * (barW + gap), cy - h / 2), Size(barW, h), CornerRadius(barW / 2),
            )
        }
    }
}

/** Decorative layered sine waves for the "Mix Harian" card. */
@Composable
fun WavyBackground(modifier: Modifier = Modifier, phase: Float = 0f) {
    Canvas(modifier) {
        val layers = listOf(0.18f to 0.55f, 0.12f to 0.68f, 0.08f to 0.8f)
        layers.forEachIndexed { idx, (alpha, yFrac) ->
            val path = Path()
            val amp = size.height * (0.08f + idx * 0.03f)
            val baseY = size.height * yFrac
            path.moveTo(0f, baseY)
            var x = 0f
            while (x <= size.width) {
                val y = baseY + amp * sin((x / size.width * 2 * PI * (1.2 + idx * 0.4)) + phase * 2 * PI + idx).toFloat()
                path.lineTo(x, y)
                x += 6f
            }
            path.lineTo(size.width, size.height)
            path.lineTo(0f, size.height)
            path.close()
            drawPath(path, Color.White.copy(alpha = alpha))
        }
        drawCircle(Color.White.copy(alpha = 0.10f), size.height * 0.55f, Offset(size.width * 0.92f, size.height * 0.05f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Color.White.copy(alpha = 0.07f), size.height * 0.75f, Offset(size.width * 0.92f, size.height * 0.05f), style = Stroke(2.dp.toPx()))
    }
}
