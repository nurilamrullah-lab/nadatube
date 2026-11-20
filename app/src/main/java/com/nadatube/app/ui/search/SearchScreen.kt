package com.nadatube.app.ui.search

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadatube.app.AppContainer
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.extractor.YouTubeUrlParser
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import com.nadatube.app.ui.components.Artwork
import com.nadatube.app.ui.components.TrackRow
import com.nadatube.app.ui.components.rememberTrackStates
import com.nadatube.app.ui.theme.Nada
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    container: AppContainer,
    initialQuery: String,
    onOpenCollection: (kind: String, id: String, title: String) -> Unit,
    onLink: (String) -> Boolean,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.SONGS) }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val quality by container.settings.quality.collectAsState()
    val rowState = rememberTrackStates(container)
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (initialQuery.isEmpty()) runCatching { focus.requestFocus() } }

    // Live search with debounce; pasted links play immediately.
    LaunchedEffect(query, filter) {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); error = null; return@LaunchedEffect }
        if (YouTubeUrlParser.parse(q) != null && q.contains("/")) {
            onLink(q)
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        runCatching { container.innerTube.search(q, filter).items }
            .onSuccess { results = it }
            .onFailure { error = "Pencarian gagal: ${it.message}" }
        loading = false
    }

    val badge = if (quality == StreamQuality.BEST) "160k Opus" else "70k Opus"

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Text("Cari", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, Nada.Border, RoundedCornerShape(16.dp)),
                placeholder = { Text("Lagu, artis, album, atau link YouTube", color = Nada.Faint) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Nada.Muted) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Hapus", tint = Nada.Muted) }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (!onLink(query)) Unit }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Nada.Card, unfocusedContainerColor = Nada.Card,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchFilter.entries) { f ->
                FilterChip(
                    selected = f == filter, onClick = { filter = f }, label = { Text(f.label) }, shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Nada.Card, labelColor = Nada.InkSoft,
                        selectedContainerColor = Nada.IndigoSoft, selectedLabelColor = Nada.Indigo,
                    ),
                    border = FilterChipDefaults.filterChipBorder(true, f == filter, borderColor = Nada.Border, selectedBorderColor = Nada.IndigoLight),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                loading && results.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Nada.Indigo)
                error != null -> Text(error!!, color = Nada.Muted, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                query.isBlank() -> EmptyHint(Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(results, key = { "${filter.name}-${it.id}" }) { item ->
                        when (item) {
                            is SearchItem.Song -> TrackRow(
                                item.track, container,
                                state = rowState(item.track).copy(badge = badge),
                                onClick = { container.player.play(item.track) },
                                showQuickActions = true,
                            )
                            is SearchItem.Album -> CollectionRow(item.thumbnailUrl, item.title, listOfNotNull("Album", item.artist, item.year).joinToString(" • "), false) {
                                onOpenCollection("album", item.id, item.title)
                            }
                            is SearchItem.Playlist -> CollectionRow(item.thumbnailUrl, item.title, "Playlist • ${item.author}", false) {
                                onOpenCollection("playlist", item.id, item.title)
                            }
                            is SearchItem.Artist -> CollectionRow(item.thumbnailUrl, item.name, item.subtitle.ifBlank { "Artis" }, true) {
                                query = item.name
                                filter = SearchFilter.SONGS
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(thumb: String?, title: String, subtitle: String, round: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(thumb, Modifier.size(56.dp), corner = if (round) 28.dp else 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nada.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Nada.Faint)
    }
}

@Composable
private fun EmptyHint(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Search, null, tint = Nada.IndigoLight, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Temukan musik tanpa iklan", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Hasil diputar sebagai audio saja — hemat kuota, kualitas tinggi.",
            style = MaterialTheme.typography.bodyMedium, color = Nada.Muted,
        )
    }
}
