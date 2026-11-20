package com.nadatube.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.unit.dp
import com.nadatube.app.AppContainer
import com.nadatube.app.extractor.YouTubeLink
import com.nadatube.app.extractor.YouTubeUrlParser
import com.nadatube.app.sponsorblock.SponsorSkipLogic
import com.nadatube.app.ui.collection.CollectionScreen
import com.nadatube.app.ui.components.MiniPlayer
import com.nadatube.app.ui.home.HomeScreen
import com.nadatube.app.ui.library.LibraryScreen
import com.nadatube.app.ui.player.PlayerScreen
import com.nadatube.app.ui.search.SearchScreen
import com.nadatube.app.ui.theme.Nada
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab("home", "Beranda", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab("search", "Cari", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab("library", "Pustaka", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

object Routes {
    fun search(query: String = "") = "search?q=${Uri.encode(query)}"
    fun collection(kind: String, id: String, title: String) = "collection/$kind/${Uri.encode(id)}?title=${Uri.encode(title)}"
}

/** Plays a pasted/shared YouTube link. Returns false if the text is not a YouTube link. */
fun openYouTubeLink(container: AppContainer, text: String, onError: (String) -> Unit): Boolean {
    val link = YouTubeUrlParser.parse(text) ?: return false
    container.appScope.launch {
        runCatching {
            when (link) {
                is YouTubeLink.Video -> container.player.play(container.streamResolver.resolve(link.videoId).track)
                is YouTubeLink.Playlist -> {
                    val tracks = container.innerTube.playlist(link.playlistId)
                    val start = tracks.indexOfFirst { it.videoId == link.videoId }.coerceAtLeast(0)
                    container.player.play(tracks, start, withRadio = false)
                }
            }
        }.onFailure { onError(it.message ?: "Gagal membuka tautan") }
    }
    return true
}

@Composable
fun NadaApp(container: AppContainer, incomingLink: MutableStateFlow<String?>, openPlayer: MutableStateFlow<Boolean>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val onPlayer = route == "player"

    val link by incomingLink.collectAsState()
    LaunchedEffect(link) {
        link?.let {
            if (!openYouTubeLink(container, it) { msg -> scope.launch { snackbar.showSnackbar(msg) } }) {
                nav.navigate(Routes.search(it))
            }
            incomingLink.value = null
        }
    }
    val wantPlayer by openPlayer.collectAsState()
    LaunchedEffect(wantPlayer) {
        if (wantPlayer) {
            nav.navigate("player") { launchSingleTop = true }
            openPlayer.value = false
        }
    }
    LaunchedEffect(Unit) {
        container.nowPlaying.skipped.collect { s ->
            snackbar.showSnackbar("Dilewati: ${SponsorSkipLogic.CATEGORY_LABELS[s.category] ?: s.category} (${s.lengthMs / 1000} dtk)")
        }
    }
    val playerError = container.player.state.collectAsState().value.error
    LaunchedEffect(playerError) { playerError?.let { snackbar.showSnackbar("Gagal memutar: $it") } }

    Scaffold(
        containerColor = Nada.Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(!onPlayer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Column {
                    MiniPlayer(container, onOpen = { nav.navigate("player") { launchSingleTop = true } })
                    HorizontalDivider(color = Nada.Border)
                    NavigationBar(containerColor = Nada.Card, tonalElevation = 0.dp) {
                        TABS.forEach { tab ->
                            val selected = route.substringBefore('?') == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(if (tab.route == "search") Routes.search() else tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Nada.Indigo,
                                    selectedTextColor = Nada.Indigo,
                                    indicatorColor = Nada.IndigoSoft,
                                    unselectedIconColor = Nada.Muted,
                                    unselectedTextColor = Nada.Muted,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(if (onPlayer) PaddingValues() else padding)) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        container,
                        onSearch = { q ->
                            if (!openYouTubeLink(container, q) { msg -> scope.launch { snackbar.showSnackbar(msg) } }) nav.navigate(Routes.search(q))
                        },
                        onOpenPlayer = { nav.navigate("player") },
                    )
                }
                composable("search?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                    SearchScreen(
                        container,
                        initialQuery = it.arguments?.getString("q").orEmpty(),
                        onOpenCollection = { kind, id, title -> nav.navigate(Routes.collection(kind, id, title)) },
                        onLink = { text -> openYouTubeLink(container, text) { msg -> scope.launch { snackbar.showSnackbar(msg) } } },
                    )
                }
                composable("library") {
                    LibraryScreen(container, onOpenPlaylist = { id, name -> nav.navigate(Routes.collection("local", id.toString(), name)) })
                }
                composable(
                    "collection/{kind}/{id}?title={title}",
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType },
                        navArgument("id") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    CollectionScreen(
                        container,
                        kind = it.arguments?.getString("kind").orEmpty(),
                        id = it.arguments?.getString("id").orEmpty(),
                        title = it.arguments?.getString("title").orEmpty(),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("player") { PlayerScreen(container, onClose = { nav.popBackStack() }) }
            }
        }
    }
}

