package com.nadatube.app.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nadatube.app.AppContainer
import com.nadatube.app.extractor.AudioStreamSelector
import com.nadatube.app.lyrics.LrcParser
import com.nadatube.app.model.Lyrics
import com.nadatube.app.playback.EqualizerPresets
import com.nadatube.app.playback.SleepTimerState
import com.nadatube.app.sponsorblock.SponsorSkipLogic
import com.nadatube.app.ui.components.Artwork
import com.nadatube.app.ui.components.Pill
import com.nadatube.app.ui.components.SegmentSeekBar
import com.nadatube.app.ui.components.WaveVisualizer
import com.nadatube.app.ui.components.formatDuration
import com.nadatube.app.ui.theme.ColorMath
import com.nadatube.app.ui.theme.Nada
import kotlinx.coroutines.launch

private enum class Sheet { NONE, EQ, SLEEP, SPEED, QUEUE }

@Composable
fun PlayerScreen(container: AppContainer, onClose: () -> Unit) {
    val state by container.player.state.collectAsState()
    val segments by container.nowPlaying.segments.collectAsState()
    val selected by container.streamResolver.selected.collectAsState()
    val levels by container.visualizer.levels.collectAsState()
    val favorites by container.library.favoriteIds.collectAsState(initial = emptySet())
    val downloads by container.library.downloads.collectAsState(initial = emptyList())
    val sleep by container.sleepTimer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var showLyrics by remember { mutableStateOf(false) }
    val track = state.track

    val context = LocalContext.current
    // Cover colour as sampled; tuned per theme below so the glow reads on white and on near-black.
    val coverColor by produceState(Nada.IndigoLight, track?.thumbnailUrl) {
        val url = track?.thumbnailUrl ?: return@produceState
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build())
        val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@produceState
        val p = Palette.from(bmp).generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
        swatch?.let { value = Color(it.rgb) }
    }
    val dark = Nada.IsDark
    val ambient = Color(ColorMath.tuneGlow(coverColor.toArgb(), dark) or (0xFF shl 24))
    val glow by animateColorAsState(ambient, tween(800), label = "glow")

    val lyrics by produceState<Lyrics?>(null, track?.videoId) {
        value = null
        val t = track ?: return@produceState
        value = runCatching { container.lyrics.lyrics(t) }.getOrNull()
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(glow.copy(alpha = if (dark) 0.42f else 0.28f), Nada.Canvas, Nada.Canvas)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Tutup", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SEDANG DIPUTAR", style = MaterialTheme.typography.labelSmall, color = Nada.Muted)
                    Text(track?.album ?: "Audio saja · tanpa iklan", style = MaterialTheme.typography.labelMedium, color = Nada.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { sheet = Sheet.QUEUE }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Antrean") }
            }

            if (track == null) {
                Spacer(Modifier.weight(1f))
                Text("Belum ada lagu diputar", color = Nada.Muted)
                Spacer(Modifier.weight(1f))
                return@Column
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(showLyrics, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "art") { lyricsMode ->
                    if (lyricsMode) LyricsView(lyrics, state.positionMs, onSeek = container.player::seekTo)
                    else Disc(track.thumbnailUrl, state.isPlaying, glow)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fav = track.videoId in favorites
                IconButton(onClick = { scope.launch { container.library.setFavorite(track, !fav) } }) {
                    Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorit", tint = if (fav) Nada.Rose else Nada.Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            val offline = downloads.firstOrNull { it.track.videoId == track.videoId && it.isPlayable }
            val stream = selected[track.videoId]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    offline != null -> Pill("Offline · ${offline.formatLabel}", color = Nada.Green, background = Nada.GreenSoft)
                    stream != null -> Pill("${stream.nominalKbps} kbps ${stream.codec.label} · ${stream.sampleRateLabel}")
                    else -> Pill("Menyiapkan audio…")
                }
                if (stream != null && offline == null) {
                    Pill("Hemat ${(AudioStreamSelector.dataSavingRatio(stream) * 100).toInt()}% data", color = Nada.Green, background = Nada.GreenSoft)
                }
                if (segments.isNotEmpty()) {
                    Pill("SB ${formatDuration(SponsorSkipLogic.totalSkippableMs(segments))}", color = Nada.AmberInk, background = Nada.AmberSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
            WaveVisualizer(levels, state.isPlaying, Modifier.fillMaxWidth().height(36.dp), color = Nada.IndigoLight)
            Spacer(Modifier.height(6.dp))
            SegmentSeekBar(state.positionMs, state.bufferedMs, state.durationMs, segments, container.player::seekTo, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = container.player::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, "Acak", tint = if (state.shuffle) Nada.Indigo else Nada.Muted)
                }
                IconButton(onClick = container.player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Sebelumnya", tint = Nada.Ink, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(76.dp).shadow(14.dp, CircleShape, ambientColor = Nada.Indigo, spotColor = Nada.Indigo)
                        .clip(CircleShape).background(Brush.linearGradient(listOf(Nada.IndigoLight, Nada.Indigo)))
                        .clickable { container.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                    else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Putar/jeda", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = container.player::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Berikutnya", tint = Nada.Ink, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = container.player::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Ulang",
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) Nada.Indigo else Nada.Muted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Nada.Card).border(1.dp, Nada.Border, RoundedCornerShape(18.dp)).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ActionButton(Icons.Rounded.Speed, "${"%.2f".format(state.speed).trimEnd('0').trimEnd('.')}x", state.speed != 1f) { sheet = Sheet.SPEED }
                ActionButton(Icons.Rounded.Equalizer, "EQ", container.settings.equalizer.collectAsState().value.enabled) { sheet = Sheet.EQ }
                ActionButton(
                    Icons.Rounded.Bedtime,
                    when (val s = sleep) {
                        is SleepTimerState.Counting -> "${((s.endsAtMs - System.currentTimeMillis()) / 60000 + 1)} mnt"
                        SleepTimerState.EndOfTrack -> "Akhir lagu"
                        SleepTimerState.Off -> "Tidur"
                    },
                    sleep != SleepTimerState.Off,
                ) { sheet = Sheet.SLEEP }
                ActionButton(Icons.Rounded.Lyrics, "Lirik", showLyrics) { showLyrics = !showLyrics }
                ActionButton(Icons.Rounded.DownloadForOffline, if (offline != null) "Tersimpan" else "Unduh", offline != null) {
                    scope.launch { container.library.download(track) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    when (sheet) {
        Sheet.EQ -> EqualizerSheet(container) { sheet = Sheet.NONE }
        Sheet.SLEEP -> SleepTimerSheet(container) { sheet = Sheet.NONE }
        Sheet.SPEED -> SpeedSheet(state.speed, container.player::setSpeed) { sheet = Sheet.NONE }
        Sheet.QUEUE -> QueueSheet(container) { sheet = Sheet.NONE }
        Sheet.NONE -> Unit
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (active) Nada.Indigo else Nada.InkSoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) Nada.Indigo else Nada.Muted, maxLines = 1)
    }
}

/** Cover art as a spinning disc with vinyl grooves, sitting in an ambient glow of the cover's colour. */
@Composable
private fun Disc(url: String?, playing: Boolean, glow: Color) {
    val t = rememberInfiniteTransition(label = "disc")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    var frozen by remember { mutableFloatStateOf(0f) }
    if (playing) frozen = angle
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight, 320.dp)
        Canvas(Modifier.size(size * 1.25f)) {
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0.18f), Color.Transparent)), radius = this.size.minDimension / 2)
        }
        Box(
            Modifier.size(size).shadow(24.dp, CircleShape, ambientColor = glow, spotColor = glow)
                .clip(CircleShape).background(Color(0xFF111827)).rotate(frozen),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2
                var rr = r * 0.96f
                while (rr > r * 0.62f) {
                    drawCircle(Color.White.copy(alpha = 0.05f), rr, style = Stroke(1f))
                    rr -= 5f
                }
            }
            Artwork(url, Modifier.size(size * 0.62f).clip(CircleShape), corner = size)
            Box(Modifier.size(size * 0.09f).clip(CircleShape).background(Nada.Canvas).border(2.dp, Color(0xFF111827).copy(alpha = 0.4f), CircleShape))
        }
    }
}

@Composable
private fun LyricsView(lyrics: Lyrics?, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val lines = lyrics?.lines.orEmpty()
    val active = LrcParser.activeIndex(lines, positionMs + 300)
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
    Box(Modifier.fillMaxSize().padding(vertical = 12.dp).clip(RoundedCornerShape(24.dp)).background(Nada.Card.copy(alpha = 0.7f))) {
        when {
            lyrics == null -> Text("Mencari lirik…", color = Nada.Muted, modifier = Modifier.align(Alignment.Center))
            lines.isEmpty() && lyrics.plain.isNullOrBlank() -> Text("Lirik tidak tersedia", color = Nada.Muted, modifier = Modifier.align(Alignment.Center))
            lines.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
                item { Text(lyrics.plain.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Nada.InkSoft) }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState) {
                item { Spacer(Modifier.height(24.dp)) }
                itemsIndexed(lines) { i, line ->
                    val isActive = i == active
                    Text(
                        line.text.ifBlank { "♪" },
                        style = if (isActive) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isActive -> Nada.Indigo
                            i < active -> Nada.Faint
                            else -> Nada.InkSoft
                        },
                        modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EqualizerSheet(container: AppContainer, onDismiss: () -> Unit) {
    val eq by container.settings.equalizer.collectAsState()
    val sbEnabled by container.settings.sponsorBlockEnabled.collectAsState()
    val categories by container.settings.sponsorCategories.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nada.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer 10-band", style = MaterialTheme.typography.titleLarge)
                    Text("DynamicsProcessing dengan limiter anti-clipping", style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
                }
                Switch(eq.enabled, { v -> container.settings.updateEqualizer { it.copy(enabled = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = Nada.Indigo))
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EqualizerPresets.ALL.size) { i ->
                    val p = EqualizerPresets.ALL[i]
                    FilterChip(
                        selected = eq.presetName == p.name,
                        onClick = { container.settings.updateEqualizer { it.copy(enabled = true, presetName = p.name, gainsDb = p.gainsDb) } },
                        label = { Text(p.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Nada.IndigoSoft, selectedLabelColor = Nada.Indigo),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                EqualizerPresets.FREQUENCIES.forEachIndexed { i, f ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%+.0f".format(eq.gainsDb[i]), style = MaterialTheme.typography.labelSmall, color = Nada.Muted)
                        VerticalSlider(
                            value = eq.gainsDb[i], enabled = eq.enabled,
                            onChange = { v -> container.settings.updateEqualizer { s -> s.copy(presetName = "CUSTOM", gainsDb = s.gainsDb.toMutableList().also { it[i] = v }) } },
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (f >= 1000) "${f / 1000}k" else "$f", style = MaterialTheme.typography.labelSmall, color = Nada.InkSoft)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Bass boost ${"%.0f".format(eq.bassBoostDb)} dB", style = MaterialTheme.typography.titleSmall)
            Slider(eq.bassBoostDb, { v -> container.settings.updateEqualizer { it.copy(bassBoostDb = v) } }, valueRange = 0f..8f, enabled = eq.enabled,
                colors = SliderDefaults.colors(thumbColor = Nada.Indigo, activeTrackColor = Nada.Indigo))
            SettingRow("Normalisasi volume", "Samakan kenyaringan antar lagu (data loudness YouTube)", eq.normalize) { v ->
                container.settings.updateEqualizer { it.copy(normalize = v) }
            }
            SettingRow("SponsorBlock", "Lewati otomatis bagian non-musik", sbEnabled, container.settings::setSponsorBlockEnabled)
            if (sbEnabled) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SponsorSkipLogic.CATEGORY_LABELS.forEach { (key, label) ->
                        FilterChip(
                            selected = key in categories, onClick = { container.settings.toggleSponsorCategory(key) }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Nada.AmberSoft, selectedLabelColor = Nada.AmberInk),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nada.Muted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Nada.Indigo))
    }
}

/** Compact vertical slider for EQ bands (−12..+12 dB). */
@Composable
private fun VerticalSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val range = EqualizerPresets.MAX_GAIN_DB
    val gain = { y: Float, h: Float -> kotlin.math.round(((1f - y / h) * 2f - 1f) * range).coerceIn(-range, range) }
    Canvas(
        modifier.width(28.dp).padding(vertical = 6.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, _ -> onChange(gain(change.position.y, size.height.toFloat())) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onChange(gain(o.y, size.height.toFloat())) }
            },
    ) {
        val cx = size.width / 2
        val color = if (enabled) Nada.Indigo else Nada.Faint
        drawLine(Nada.Border, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val mid = size.height / 2
        val y = mid - (value / range) * mid
        drawLine(color, Offset(cx, mid), Offset(cx, y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, 9.dp.toPx(), Offset(cx, y))
        drawCircle(color, 7.dp.toPx(), Offset(cx, y))
    }
}

@Composable
private fun SpeedSheetContent(speed: Float, onSpeed: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Kecepatan putar", style = MaterialTheme.typography.titleLarge)
        Text("${"%.2f".format(speed)}x", style = MaterialTheme.typography.displaySmall, color = Nada.Indigo)
        Slider(speed, { onSpeed((kotlin.math.round(it * 20) / 20f)) }, valueRange = 0.5f..2f, steps = 29,
            colors = SliderDefaults.colors(thumbColor = Nada.Indigo, activeTrackColor = Nada.Indigo))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                FilterChip(selected = speed == s, onClick = { onSpeed(s) }, label = { Text("${s}x") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Nada.IndigoSoft, selectedLabelColor = Nada.Indigo))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nada.Card) { SpeedSheetContent(speed, onSpeed) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(container: AppContainer, onDismiss: () -> Unit) {
    val state by container.sleepTimer.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nada.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Timer tidur", style = MaterialTheme.typography.titleLarge)
            Text(
                when (val s = state) {
                    is SleepTimerState.Counting -> "Berhenti dalam ${formatDuration(s.endsAtMs - System.currentTimeMillis())} (memudar perlahan)"
                    SleepTimerState.EndOfTrack -> "Berhenti di akhir lagu ini"
                    SleepTimerState.Off -> "Musik akan memudar lalu berhenti"
                },
                style = MaterialTheme.typography.bodySmall, color = Nada.Muted,
            )
            Spacer(Modifier.height(12.dp))
            listOf(5, 15, 30, 45, 60, 90).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        TimerChoice("$m menit", Modifier.weight(1f)) { container.sleepTimer.start(m); onDismiss() }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerChoice("Akhir lagu", Modifier.weight(1f)) { container.sleepTimer.stopAtEndOfTrack(); onDismiss() }
                TimerChoice("Matikan", Modifier.weight(1f), danger = true) { container.sleepTimer.cancel(); onDismiss() }
            }
        }
    }
}

@Composable
private fun TimerChoice(label: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Nada.Border, RoundedCornerShape(14.dp))
            .background(if (danger) Nada.RoseSoft else Nada.Canvas).clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (danger) Nada.Rose else Nada.Ink) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(container: AppContainer, onDismiss: () -> Unit) {
    val state by container.player.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nada.Card) {
        Text("Antrean (${state.queue.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.fillMaxWidth().wrapContentHeight().padding(bottom = 24.dp)) {
            itemsIndexed(state.queue) { i, t ->
                Row(
                    Modifier.fillMaxWidth().clickable { container.player.skipToQueueItem(i) }.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.thumbnailUrl, Modifier.size(44.dp).aspectRatio(1f), corner = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, color = if (i == state.queueIndex) Nada.Indigo else Nada.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Nada.Muted, maxLines = 1)
                    }
                    Text(formatDuration(t.durationMs), style = MaterialTheme.typography.labelMedium, color = Nada.Faint, textAlign = TextAlign.End)
                }
            }
        }
    }
}
