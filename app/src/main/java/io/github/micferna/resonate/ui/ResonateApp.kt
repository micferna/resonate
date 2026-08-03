package io.github.micferna.resonate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.ui.components.MiniPlayer
import io.github.micferna.resonate.ui.components.PlaybackFailureBanner
import io.github.micferna.resonate.ui.screens.library.LibraryScreen
import io.github.micferna.resonate.ui.screens.player.NowPlayingSheet
import io.github.micferna.resonate.ui.screens.player.QueueSheet
import io.github.micferna.resonate.ui.screens.playlists.PlaylistsScreen
import io.github.micferna.resonate.ui.screens.search.SearchScreen
import io.github.micferna.resonate.ui.screens.settings.SettingsScreen
import io.github.micferna.resonate.ui.screens.sources.SourcesScreen

/** Destinations de la barre de navigation. */
private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    LIBRARY("library", "Bibliothèque", Icons.Filled.LibraryMusic),
    SEARCH("search", "Recherche", Icons.Filled.Search),
    PLAYLISTS("playlists", "Playlists", Icons.AutoMirrored.Filled.PlaylistPlay),
    SOURCES("sources", "Sources", Icons.Filled.Dns),
    SETTINGS("settings", "Réglages", Icons.Filled.Settings),
}

@Composable
fun ResonateApp(shellViewModel: AppShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val playerState by shellViewModel.playerState.collectAsStateWithLifecycle()
    val currentTrack by shellViewModel.currentTrack.collectAsStateWithLifecycle()
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    /**
     * Sélectionne un onglet.
     *
     * **Toute** navigation vers un onglet doit passer par ici, y compris depuis un
     * bouton à l'intérieur d'un écran. Un `navigate()` direct empilerait la
     * destination *au-dessus* de l'onglet courant ; l'appui suivant sur la barre du
     * bas sauvegarderait alors cette pile sous l'onglet de départ, puis la
     * restaurerait — l'onglet Bibliothèque rouvrait ainsi indéfiniment l'écran
     * Sources, sans plus aucun moyen de revenir à la musique.
     */
    fun selectTab(route: String) {
        navController.navigate(route) {
            // Empêche l'empilement de copies d'un même écran quand on fait des
            // allers-retours dans la barre du bas.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectTab(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Destination.LIBRARY.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Destination.LIBRARY.route) {
                    LibraryScreen(
                        onOpenSources = { selectTab(Destination.SOURCES.route) },
                        currentTrackId = playerState.currentTrackId,
                    )
                }
                composable(Destination.SEARCH.route) {
                    SearchScreen(currentTrackId = playerState.currentTrackId)
                }
                composable(Destination.PLAYLISTS.route) {
                    PlaylistsScreen(currentTrackId = playerState.currentTrackId)
                }
                composable(Destination.SOURCES.route) { SourcesScreen() }
                composable(Destination.SETTINGS.route) { SettingsScreen() }
            }

            // Bandeau d'erreur et mini-lecteur forment un bloc solidaire, collé au
            // bas de l'écran : le message concerne la lecture en cours, il doit
            // apparaître juste au-dessus d'elle.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                PlaybackFailureBanner(
                    message = playerState.failureMessage,
                    retrying = playerState.retrying,
                )
                MiniPlayer(
                    state = playerState,
                    onExpand = { nowPlayingOpen = true },
                    onTogglePlay = shellViewModel::togglePlayPause,
                    onNext = shellViewModel::next,
                )
            }
        }
    }

    if (nowPlayingOpen) {
        NowPlayingSheet(
            state = playerState,
            onDismiss = { nowPlayingOpen = false },
            onTogglePlay = shellViewModel::togglePlayPause,
            onNext = shellViewModel::next,
            onPrevious = shellViewModel::previous,
            onSeek = shellViewModel::seekTo,
            onToggleShuffle = shellViewModel::toggleShuffle,
            onCycleRepeat = shellViewModel::cycleRepeat,
            onToggleLike = shellViewModel::toggleLike,
            onToggleDislike = shellViewModel::toggleDislike,
            onOpenQueue = { queueOpen = true },
            isLiked = currentTrack?.rating == Rating.LIKED,
            isDisliked = currentTrack?.rating == Rating.DISLIKED,
        )
    }

    if (queueOpen) {
        // La file est relue à chaque changement de taille ou de morceau courant :
        // le lecteur est la source de vérité, l'UI n'en garde pas de copie.
        val queue = remember(playerState.queueSize, playerState.currentTrackId) {
            shellViewModel.queue()
        }
        QueueSheet(
            queue = queue,
            currentIndex = shellViewModel.currentQueueIndex(),
            onDismiss = { queueOpen = false },
            onPlayIndex = shellViewModel::playQueueIndex,
            onRemoveIndex = shellViewModel::removeQueueIndex,
            onMove = shellViewModel::moveQueueItem,
        )
    }
}
