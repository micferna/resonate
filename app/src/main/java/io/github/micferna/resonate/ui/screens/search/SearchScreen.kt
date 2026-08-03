package io.github.micferna.resonate.ui.screens.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.repo.LibraryRepository
import io.github.micferna.resonate.data.repo.PlaylistRepository
import io.github.micferna.resonate.player.PlayerConnection
import io.github.micferna.resonate.ui.TrackActionsViewModel
import io.github.micferna.resonate.ui.components.EmptyState
import io.github.micferna.resonate.ui.components.MiniPlayerSpacing
import io.github.micferna.resonate.ui.components.TrackActionsHost
import io.github.micferna.resonate.ui.components.TrackRow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    playerConnection: PlayerConnection,
    playlistRepo: PlaylistRepository,
) : TrackActionsViewModel(libraryRepository, playerConnection, playlistRepo) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Une pause avant d'interroger la base : sans elle, taper « Beethoven » lancerait
     * neuf requêtes `LIKE` successives sur toute la bibliothèque.
     */
    @OptIn(FlowPreview::class)
    val results: StateFlow<List<TrackEntity>> = _query
        .debounce(DEBOUNCE_MS)
        .flatMapLatest { library.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun clear() {
        _query.value = ""
    }

    private companion object {
        const val DEBOUNCE_MS = 220L
    }
}

@Composable
fun SearchScreen(
    currentTrackId: String?,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    TrackActionsHost(viewModel, snackbarHost)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            singleLine = true,
            label = { Text("Rechercher un titre, un artiste, un album") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Filled.Close, contentDescription = "Effacer")
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
        )

        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "Rechercher",
                description = "La recherche porte sur le titre, l'artiste et l'album de tous " +
                    "les morceaux indexés, y compris ceux qui ne sont pas téléchargés.",
            )

            results.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "Aucun résultat",
                description = "Rien ne correspond à « $query » dans la bibliothèque indexée.",
            )

            else -> LazyColumn(contentPadding = MiniPlayerSpacing) {
                itemsIndexed(results, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track = track,
                        actions = viewModel.actionsFor(track, results, index),
                        isCurrent = track.id == currentTrackId,
                    )
                }
            }
        }
    }
    }
}
