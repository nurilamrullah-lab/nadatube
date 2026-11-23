package com.nadatube.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadatube.app.AppContainer
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import com.nadatube.app.model.Track
import com.nadatube.app.ui.components.Artwork
import com.nadatube.app.ui.components.Pill
import com.nadatube.app.ui.components.SectionHeader
import com.nadatube.app.ui.components.TrackRow
import com.nadatube.app.ui.components.WavyBackground
import com.nadatube.app.ui.components.rememberTrackStates
import com.nadatube.app.ui.settings.ThemeSheet
import com.nadatube.app.ui.settings.ThemeToggleButton
import com.nadatube.app.ui.theme.Nada
import java.util.Calendar

enum class Mood(val label: String, val query: String?) {
    ALL("Semua", null),
    CHILL("Santai", "lagu santai akustik indonesia"),
    FOCUS("Fokus", "lofi focus study music"),
    WORKOUT("Latihan", "workout motivation songs"),
    SLEEP("Tidur", "calm sleep piano music"),
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 4..10 -> "Selamat pagi"
    in 11..14 -> "Selamat siang"
    in 15..17 -> "Selamat sore"
    else -> "Selamat malam"
}

@Composable
fun HomeScreen(container: AppContainer, onSearch: (String) -> Unit, onOpenPlayer: () -> Unit) {
    var mood by rememberSaveable { mutableStateOf(Mood.ALL) }
    var mix by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val recent by container.library.recentlyPlayed.collectAsState(initial = emptyList())
    val quality by container.settings.quality.collectAsState()
    val sponsorOn by container.settings.sponsorBlockEnabled.collectAsState()
    val rowState = rememberTrackStates(container)
    var showThemeSheet by remember { mutableStateOf(false) }
    if (showThemeSheet) ThemeSheet(container.settings) { showThemeSheet = false }
    val seed = recent.firstOrNull()?.videoId
    LaunchedEffect(mood, seed) {
        loading = true
        mix = runCatching {
            val q = mood.query
            when {
                q == null && seed != null -> container.innerTube.radio(seed).drop(1)
                else -> container.innerTube.search(q ?: "lagu populer indonesia terbaru", SearchFilter.SONGS)
                    .items.filterIsInstance<SearchItem.Song>().map { it.track }
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = Nada.Muted)
                        Text("NadaTube", style = MaterialTheme.typography.headlineSmall, color = Nada.Ink)
                    }
                    val themeMode by container.settings.themeMode.collectAsState()
                    ThemeToggleButton(onClick = { showThemeSheet = true }, mode = themeMode)
                }
                Spacer(Modifier.height(14.dp))
                AudioStatusHeader(quality, sponsorOn)
                Spacer(Modifier.height(16.dp))
                QuickSearchBar(onSearch)
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Mood.entries) { m ->
                    FilterChip(
                        selected = m == mood,
                        onClick = { mood = m },
                        label = { Text(m.label) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Nada.Card,
                            labelColor = Nada.InkSoft,
                            selectedContainerColor = Nada.Indigo,
                            selectedLabelColor = Color.White,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = m == mood,
                            borderColor = Nada.Border, selectedBorderColor = Nada.Indigo,
                        ),
                    )
                }
            }
        }
        item {
            DailyMixCard(
                mood = mood,
                tracks = mix,
                loading = loading,
                onPlay = { container.player.play(mix, 0, withRadio = false); onOpenPlayer() },
                onShuffle = { container.player.play(mix.shuffled(), 0, withRadio = false); onOpenPlayer() },
            )
        }
        if (recent.isNotEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                SectionHeader("Baru diputar")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(recent, key = { it.videoId }) { t ->
                        Column(Modifier.width(128.dp).clickable { container.player.play(t) }) {
                            Artwork(t.thumbnailUrl, Modifier.size(128.dp).shadow(6.dp, RoundedCornerShape(16.dp)), corner = 16.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (mix.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader(if (mood == Mood.ALL) "Rekomendasi untukmu" else "Pilihan ${mood.label.lowercase()}")
                Spacer(Modifier.height(4.dp))
            }
            itemsIndexed(mix.take(12), key = { _, t -> "mix-${t.videoId}" }) { i, t ->
                TrackRow(t, container, state = rowState(t), onClick = { container.player.play(mix, i, withRadio = false) })
            }
        }
    }
}

@Composable
private fun AudioStatusHeader(quality: StreamQuality, sponsorOn: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Nada.Card, RoundedCornerShape(16.dp)).border(1.dp, Nada.Border, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill("Bebas iklan", color = Nada.Green, background = Nada.GreenSoft, dot = true)
        Pill(if (quality == StreamQuality.BEST) "Opus 160 kbps" else "Hemat data")
        if (sponsorOn) Pill("SponsorBlock", color = Nada.AmberInk, background = Nada.AmberSoft)
    }
}

@Composable
private fun QuickSearchBar(onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().border(1.dp, Nada.Border, RoundedCornerShape(16.dp)),
        placeholder = { Text("Cari lagu atau tempel link YouTube", color = Nada.Faint) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Nada.Muted) },
        trailingIcon = {
            IconButton(onClick = {
                clipboard.getText()?.text?.let { text = it; onSearch(it) }
            }) { Icon(Icons.Rounded.ContentPaste, "Tempel link", tint = Nada.Indigo) }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) onSearch(text) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Nada.Card, unfocusedContainerColor = Nada.Card,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun DailyMixCard(mood: Mood, tracks: List<Track>, loading: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(196.dp)
            .shadow(18.dp, RoundedCornerShape(26.dp), ambientColor = Nada.Indigo, spotColor = Nada.Indigo)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Nada.IndigoLight, Nada.Indigo, Color(0xFF3730A3)))),
    ) {
        WavyBackground(Modifier.fillMaxSize(), phase)
        Column(Modifier.padding(20.dp).fillMaxSize()) {
            Text("MIX HARIAN", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(4.dp))
            Text(
                if (mood == Mood.ALL) "Mix Harianmu" else "Mix ${mood.label}",
                style = MaterialTheme.typography.headlineSmall, color = Color.White,
            )
            Text(
                when {
                    loading -> "Menyusun lagu untukmu…"
                    tracks.isEmpty() -> "Tidak dapat memuat mix. Periksa koneksi."
                    else -> tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ") + " dan lainnya"
                },
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(Color.White).clickable(enabled = tracks.isNotEmpty(), onClick = onPlay)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Nada.Indigo, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null, tint = Nada.Indigo)
                    Spacer(Modifier.width(6.dp))
                    Text("Putar", color = Nada.Indigo, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(enabled = tracks.isNotEmpty(), onClick = onShuffle),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Shuffle, "Acak", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(98.dp).height(56.dp)) {
                    tracks.take(3).forEachIndexed { i, tr ->
                        Artwork(
                            tr.thumbnailUrl,
                            Modifier.offset(x = (i * 22).dp).size(56.dp).border(2.dp, Color.White, RoundedCornerShape(14.dp)),
                            corner = 14.dp,
                        )
                    }
                }
            }
        }
    }
}
