package io.github.micferna.resonate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.micferna.resonate.data.db.dao.PlaylistSummary
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.repo.LibraryRepository
import io.github.micferna.resonate.data.repo.PlaylistRepository
import io.github.micferna.resonate.player.PlayerConnection
import io.github.micferna.resonate.ui.components.TrackActions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Comportements communs à tout écran affichant des morceaux.
 *
 * Bibliothèque, recherche, playlists et téléchargements proposent exactement le même
 * menu contextuel. Le regrouper ici évite d'en avoir quatre variantes qui divergeraient
 * à la première évolution.
 */
abstract class TrackActionsViewModel(
    protected val library: LibraryRepository,
    protected val player: PlayerConnection,
    protected val playlistRepository: PlaylistRepository,
) : ViewModel() {

    /** Morceau en attente d'être rangé dans une playlist, `null` si la boîte est fermée. */
    private val _playlistTarget = MutableStateFlow<List<TrackEntity>?>(null)
    val playlistTarget: StateFlow<List<TrackEntity>?> = _playlistTarget.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val availablePlaylists: StateFlow<List<PlaylistSummary>> =
        playlistRepository.observePlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun actionsFor(track: TrackEntity, queue: List<TrackEntity>, index: Int) = TrackActions(
        // Lancer un morceau depuis une liste met toute la liste en file : c'est ce
        // qu'attend l'utilisateur, plutôt que de se retrouver avec un seul titre.
        onPlay = { player.play(queue, index) },
        onPlayNext = { player.playNext(listOf(track)) },
        onEnqueue = { player.enqueue(listOf(track)) },
        onToggleLike = { cycleRating(track, Rating.LIKED) },
        onToggleDislike = { cycleRating(track, Rating.DISLIKED) },
        onToggleOffline = { library.toggleOffline(track) },
        onAddToPlaylist = { _playlistTarget.value = listOf(track) },
    )

    fun dismissPlaylistPicker() {
        _playlistTarget.value = null
    }

    fun confirmAddToPlaylist(playlistId: Long) {
        val tracks = _playlistTarget.value ?: return
        _playlistTarget.value = null
        viewModelScope.launch {
            val added = playlistRepository.addUnique(playlistId, tracks.map { it.id })
            _messages.tryEmit(
                when {
                    added == 0 -> "Déjà présent dans cette playlist."
                    added == tracks.size -> "${pluralize(added, "morceau", "morceaux")} ajouté(s)."
                    else -> "$added ajouté(s), ${tracks.size - added} déjà présent(s)."
                },
            )
        }
    }

    fun createPlaylistWithSelection(name: String) {
        val tracks = _playlistTarget.value ?: return
        _playlistTarget.value = null
        viewModelScope.launch {
            val id = playlistRepository.create(name)
            playlistRepository.add(id, tracks.map { it.id })
            _messages.tryEmit("Playlist « $name » créée.")
        }
    }

    fun playAll(tracks: List<TrackEntity>, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        player.play(if (shuffled) tracks.shuffled() else tracks, 0)
    }

    fun downloadAll(tracks: List<TrackEntity>) {
        library.downloadAll(tracks)
        _messages.tryEmit("${pluralize(tracks.size, "morceau", "morceaux")} en file de téléchargement.")
    }

    private fun cycleRating(track: TrackEntity, target: Rating) {
        viewModelScope.launch { library.cycleRating(track, target) }
    }

    protected companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
