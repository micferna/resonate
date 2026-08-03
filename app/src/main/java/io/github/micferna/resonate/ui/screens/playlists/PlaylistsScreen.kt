package io.github.micferna.resonate.ui.screens.playlists

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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.micferna.resonate.ui.components.Artwork
import io.github.micferna.resonate.ui.components.EmptyState
import io.github.micferna.resonate.ui.components.MiniPlayerSpacing
import io.github.micferna.resonate.ui.components.TrackActionsHost
import io.github.micferna.resonate.ui.components.TrackRow
import io.github.micferna.resonate.ui.formatTotalDuration
import io.github.micferna.resonate.ui.pluralize

@Composable
fun PlaylistsScreen(
    currentTrackId: String?,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val openedTracks by viewModel.openedTracks.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    TrackActionsHost(viewModel, snackbarHost)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = opened?.name ?: "Playlists",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (opened != null) {
                        IconButton(onClick = viewModel::closePlaylist) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    if (opened != null) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(
                                Icons.Filled.DriveFileRenameOutline,
                                contentDescription = "Renommer la playlist",
                            )
                        }
                        IconButton(onClick = { viewModel.playAll(openedTracks) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Tout lire")
                        }
                        IconButton(onClick = { viewModel.playAll(openedTracks, shuffled = true) }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Lecture aléatoire")
                        }
                        IconButton(onClick = { viewModel.downloadAll(openedTracks) }) {
                            Icon(Icons.Filled.Download, contentDescription = "Tout télécharger")
                        }
                        IconButton(onClick = viewModel::deleteOpened) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer la playlist")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (opened == null) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Nouvelle playlist")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when {
                opened != null -> PlaylistDetail(
                    tracks = openedTracks,
                    currentTrackId = currentTrackId,
                    viewModel = viewModel,
                )

                playlists.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = "Aucune playlist",
                    description = "Créez une playlist, puis ajoutez-y des morceaux depuis " +
                        "leur menu contextuel dans la bibliothèque.",
                )

                else -> LazyColumn(contentPadding = MiniPlayerSpacing) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openPlaylist(playlist.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(url = null, size = 48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${pluralize(playlist.trackCount, "titre")} · " +
                                        formatTotalDuration(playlist.totalDurationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NamePlaylistDialog(
            onConfirm = { name -> viewModel.create(name); creating = false },
            onDismiss = { creating = false },
        )
    }

    if (renaming) {
        NamePlaylistDialog(
            title = "Renommer la playlist",
            initial = opened?.name.orEmpty(),
            confirmLabel = "Renommer",
            onConfirm = { name -> viewModel.renameOpened(name); renaming = false },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun PlaylistDetail(
    tracks: List<io.github.micferna.resonate.data.db.entity.TrackEntity>,
    currentTrackId: String?,
    viewModel: PlaylistsViewModel,
) {
    if (tracks.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = "Playlist vide",
            description = "Ajoutez des morceaux depuis la bibliothèque : appui long, " +
                "puis « Ajouter à une playlist ».",
        )
        return
    }
    LazyColumn(contentPadding = MiniPlayerSpacing) {
        itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
            Column {
                TrackRow(
                    track = track,
                    actions = viewModel.actionsFor(track, tracks, index),
                    isCurrent = track.id == currentTrackId,
                )
                // Contrôles propres à la playlist : ils agissent sur la position dans
                // la liste, pas sur le morceau lui-même — d'où leur séparation du
                // menu contextuel générique.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = { viewModel.move(index, index - 1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter")
                    }
                    IconButton(
                        onClick = { viewModel.move(index, index + 1) },
                        enabled = index < tracks.lastIndex,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre")
                    }
                    IconButton(onClick = { viewModel.removeAt(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Retirer de la playlist")
                    }
                }
            }
        }
    }
}

@Composable
private fun NamePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Nouvelle playlist",
    initial: String = "",
    confirmLabel: String = "Créer",
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Boîte de sélection partagée par tous les écrans proposant « Ajouter à une playlist ». */
@Composable
fun AddToPlaylistDialog(
    playlists: List<io.github.micferna.resonate.data.db.dao.PlaylistSummary>,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creatingName by remember { mutableStateOf<String?>(null) }

    if (creatingName != null) {
        NamePlaylistDialog(
            onConfirm = { name -> onCreate(name); creatingName = null },
            onDismiss = { creatingName = null; onDismiss() },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à une playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (playlists.isEmpty()) {
                    Text("Aucune playlist pour l'instant.")
                }
                playlists.forEach { playlist ->
                    Text(
                        text = "${playlist.name}  ·  ${pluralize(playlist.trackCount, "titre")}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(playlist.id) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creatingName = "" }) { Text("Nouvelle playlist") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
