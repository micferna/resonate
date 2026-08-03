package io.github.micferna.resonate.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.micferna.resonate.ui.TrackActionsViewModel
import io.github.micferna.resonate.ui.screens.playlists.AddToPlaylistDialog

/**
 * Branche les éléments transverses d'un écran de morceaux : la boîte de choix de
 * playlist et les messages de confirmation.
 *
 * Sans ce point de montage commun, chaque écran devrait recopier la même vingtaine
 * de lignes — et l'un d'eux finirait par oublier d'afficher les retours.
 */
@Composable
fun TrackActionsHost(
    viewModel: TrackActionsViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val target by viewModel.playlistTarget.collectAsStateWithLifecycle()
    val playlists by viewModel.availablePlaylists.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    if (target != null) {
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect = viewModel::confirmAddToPlaylist,
            onCreate = viewModel::createPlaylistWithSelection,
            onDismiss = viewModel::dismissPlaylistPicker,
        )
    }
}
