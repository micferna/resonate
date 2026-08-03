package io.github.micferna.resonate.ui.screens.playlists

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.micferna.resonate.data.db.dao.PlaylistSummary
import io.github.micferna.resonate.data.db.entity.PlaylistEntity
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.repo.LibraryRepository
import io.github.micferna.resonate.data.repo.PlaylistRepository
import io.github.micferna.resonate.player.PlayerConnection
import io.github.micferna.resonate.ui.TrackActionsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    playerConnection: PlayerConnection,
    playlistRepo: PlaylistRepository,
) : TrackActionsViewModel(libraryRepository, playerConnection, playlistRepo) {

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val openedId = MutableStateFlow<Long?>(null)

    val opened: StateFlow<PlaylistEntity?> = openedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else playlistRepository.observePlaylist(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val openedTracks: StateFlow<List<TrackEntity>> = openedId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else playlistRepository.observeTracks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun openPlaylist(id: Long) {
        openedId.value = id
    }

    fun closePlaylist() {
        openedId.value = null
    }

    fun create(name: String) {
        viewModelScope.launch { playlistRepository.create(name) }
    }

    fun renameOpened(name: String) {
        val playlist = opened.value ?: return
        viewModelScope.launch { playlistRepository.rename(playlist, name, playlist.description) }
    }

    fun deleteOpened() {
        val id = openedId.value ?: return
        openedId.value = null
        viewModelScope.launch { playlistRepository.delete(id) }
    }

    /** Réordonnancement par glisser-déposer depuis l'écran de détail. */
    fun move(from: Int, to: Int) {
        val id = openedId.value ?: return
        val current = openedTracks.value.map { it.id }.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        current.add(to, current.removeAt(from))
        viewModelScope.launch { playlistRepository.reorder(id, current) }
    }

    fun removeAt(index: Int) {
        val id = openedId.value ?: return
        viewModelScope.launch { playlistRepository.removeAt(id, index) }
    }
}
