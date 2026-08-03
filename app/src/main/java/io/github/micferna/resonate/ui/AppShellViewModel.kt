package io.github.micferna.resonate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.media3.common.MediaItem
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.prefs.Settings
import io.github.micferna.resonate.data.prefs.SettingsStore
import io.github.micferna.resonate.data.repo.LibraryRepository
import io.github.micferna.resonate.player.PlayerConnection
import io.github.micferna.resonate.player.SleepTimer
import io.github.micferna.resonate.player.SleepTimerState
import io.github.micferna.resonate.player.PlayerUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * État partagé par l'ossature de l'app : lecteur, thème, morceau courant.
 *
 * Ce modèle vit à la racine de la navigation et survit donc aux changements d'écran :
 * le mini-lecteur reste cohérent quand on passe de la bibliothèque aux réglages.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val library: LibraryRepository,
    private val sleepTimer: SleepTimer,
    settingsStore: SettingsStore,
) : ViewModel() {

    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    fun startSleepTimerAtEndOfTrack() = sleepTimer.startUntilEndOfTrack()

    fun cancelSleepTimer() = sleepTimer.cancel()

    val playerState: StateFlow<PlayerUiState> = player.state

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /**
     * Morceau en cours, relu depuis la base plutôt que depuis le lecteur : c'est ce qui
     * permet au cœur du lecteur plein écran de refléter immédiatement un like appliqué
     * depuis la notification ou une montre.
     */
    val currentTrack: StateFlow<TrackEntity?> = player.state
        .map { it.currentTrackId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else library.observeTrack(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Lance une lecture à partir d'une requête dictée.
     *
     * Une requête vide — « mets de la musique » sans plus de précision — donne une
     * sélection aléatoire de la bibliothèque plutôt qu'un silence : au volant,
     * répondre quelque chose vaut mieux que ne rien faire.
     */
    fun playFromSearch(query: String) {
        viewModelScope.launch {
            val results = if (query.isBlank()) {
                library.shuffleSeed()
            } else {
                library.search(query).first()
            }
            if (results.isNotEmpty()) player.play(results, 0)
        }
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun next() = player.next()

    fun previous() = player.previous()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun toggleShuffle() = player.toggleShuffle()

    fun cycleRepeat() = player.cycleRepeatMode()

    fun toggleLike() = player.toggleLike()

    fun toggleDislike() = player.toggleDislike()

    // --- file de lecture ---

    fun queue(): List<MediaItem> = player.queueSnapshot()

    fun currentQueueIndex(): Int = player.currentQueueIndex()

    fun playQueueIndex(index: Int) = player.seekToQueueIndex(index)

    fun removeQueueIndex(index: Int) = player.removeFromQueue(index)

    fun moveQueueItem(from: Int, to: Int) = player.moveInQueue(from, to)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
