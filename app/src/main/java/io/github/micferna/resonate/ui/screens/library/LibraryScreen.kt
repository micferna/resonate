package io.github.micferna.resonate.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.micferna.resonate.data.db.dao.AlbumSummary
import io.github.micferna.resonate.data.db.dao.ArtistSummary
import io.github.micferna.resonate.ui.components.Artwork
import io.github.micferna.resonate.ui.components.EmptyState
import io.github.micferna.resonate.ui.components.MiniPlayerSpacing
import io.github.micferna.resonate.ui.components.TrackActionsHost
import io.github.micferna.resonate.ui.components.TrackRow
import io.github.micferna.resonate.ui.pluralize

@Composable
fun LibraryScreen(
    onOpenSources: () -> Unit,
    currentTrackId: String?,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    TrackActionsHost(viewModel, snackbarHost)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        LibraryTopBar(state, viewModel)

        if (state.focus == LibraryFocus.None) {
            PrimaryScrollableTabRow(
                selectedTabIndex = state.tab.ordinal,
                edgePadding = 8.dp,
            ) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }
        }

        when {
            !state.hasAnySource -> EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = "Aucune source configurée",
                description = "Ajoutez un serveur SFTP, un partage réseau, un Nextcloud " +
                    "ou un serveur Subsonic pour commencer à écouter votre musique.",
                action = {
                    ExtendedFloatingActionButton(
                        onClick = onOpenSources,
                        icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                        text = { Text("Ajouter une source") },
                    )
                },
            )

            state.focus == LibraryFocus.None && state.tab == LibraryTab.ARTISTS ->
                ArtistList(state.artists, viewModel::openArtist)

            state.focus == LibraryFocus.None && state.tab == LibraryTab.ALBUMS ->
                AlbumList(state.albums, viewModel::openAlbum)

            else -> TrackList(state, viewModel, currentTrackId)
        }
    }
    }
}

@Composable
private fun LibraryTopBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    val focus = state.focus
    TopAppBar(
        title = {
            Text(
                text = when (focus) {
                    is LibraryFocus.Artist -> focus.name
                    is LibraryFocus.Album -> focus.name
                    LibraryFocus.None -> "Bibliothèque"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (focus != LibraryFocus.None) {
                IconButton(onClick = viewModel::closeFocus) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            }
        },
        actions = {
            IconButton(onClick = viewModel::shuffleEverything) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire")
            }
            IconButton(onClick = viewModel::rescanAll) {
                Icon(Icons.Filled.Refresh, contentDescription = "Réanalyser les sources")
            }
        },
    )
}

@Composable
private fun TrackList(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    currentTrackId: String?,
) {
    if (state.tracks.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.LibraryMusic,
            title = when (state.tab) {
                LibraryTab.LIKED -> "Aucun morceau aimé"
                LibraryTab.OFFLINE -> "Rien en hors-ligne"
                LibraryTab.MOST_PLAYED -> "Aucune écoute enregistrée"
                else -> "Bibliothèque vide"
            },
            description = when (state.tab) {
                LibraryTab.LIKED -> "Appuyez longuement sur un morceau pour l'ajouter à vos favoris."
                LibraryTab.OFFLINE ->
                    "Rendez des morceaux disponibles hors-ligne pour les écouter sans réseau."
                LibraryTab.MOST_PLAYED ->
                    "Les morceaux apparaissent ici après avoir été écoutés au moins trente secondes."
                else -> "L'analyse est peut-être encore en cours. Relancez une analyse des sources."
            },
        )
        return
    }

    LazyColumn(contentPadding = MiniPlayerSpacing) {
        itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                actions = viewModel.actionsFor(track, state.tracks, index),
                isCurrent = track.id == currentTrackId,
            )
        }
    }
}

@Composable
private fun ArtistList(artists: List<ArtistSummary>, onOpen: (String) -> Unit) {
    LazyColumn(contentPadding = MiniPlayerSpacing) {
        items(artists, key = { it.artist }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(artist.artist) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(url = null, size = 44.dp, cornerRadius = 22.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(artist.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        text = "${pluralize(artist.trackCount, "titre")} · " +
                            pluralize(artist.albumCount, "album", "albums"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumList(albums: List<AlbumSummary>, onOpen: (AlbumSummary) -> Unit) {
    LazyColumn(contentPadding = MiniPlayerSpacing) {
        items(albums, key = { "${it.albumArtist}/${it.album}" }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(album) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Artwork(url = album.artworkUrl, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        album.album,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            album.albumArtist.takeIf { it.isNotBlank() },
                            album.year.takeIf { it > 0 }?.toString(),
                            pluralize(album.trackCount, "titre"),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
