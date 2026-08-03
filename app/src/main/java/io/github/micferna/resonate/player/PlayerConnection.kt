package io.github.micferna.resonate.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/** Ce dont l'interface a besoin pour se dessiner, sans jamais toucher au lecteur. */
data class PlayerUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentTrackId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
)

/**
 * Passerelle entre l'interface et le service de lecture.
 *
 * L'UI ne détient jamais l'`ExoPlayer` : elle parle à un `MediaController`, exactement
 * comme le ferait l'écran de verrouillage ou une montre connectée. Le service reste
 * ainsi l'unique détenteur de l'état de lecture, et il n'y a pas deux vérités à
 * réconcilier quand l'app est fermée puis rouverte pendant qu'un morceau tourne.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null

    init {
        scope.launch { connect() }
    }

    private suspend fun connect() = withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val connected = MediaController.Builder(context, token).buildAsync().await()
        controller = connected
        connected.addListener(StateListener())
        publish()
        launch { trackPositionWhileObserved() }
    }

    /**
     * Rafraîchit la position, mais seulement quand quelqu'un regarde.
     *
     * Le lecteur ne signale pas l'écoulement du temps : la position est la seule
     * donnée à sonder, tout le reste arrive par événements. Or ce composant est un
     * singleton lié au processus, pas à un écran : une boucle inconditionnelle
     * continuerait de tourner musique en pause, application fermée, écran éteint —
     * à ne rien produire d'autre que des réveils du processeur.
     *
     * [MutableStateFlow.subscriptionCount] dit exactement quand une interface est
     * abonnée. Sans abonné, la boucle s'arrête complètement ; elle redémarre à la
     * réouverture de l'app. Pendant la lecture en arrière-plan, c'est la
     * notification média qui affiche la progression, alimentée par le service.
     */
    private suspend fun trackPositionWhileObserved() {
        _state.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .collectLatest { observed ->
                if (!observed) return@collectLatest
                while (true) {
                    val player = controller
                    if (player != null && player.isPlaying) {
                        publish()
                        delay(PLAYING_TICK_MS)
                    } else {
                        // En pause avec l'écran allumé : un battement par seconde
                        // suffit à réagir à une reprise déclenchée ailleurs.
                        delay(IDLE_TICK_MS)
                    }
                }
            }
    }

    // ------------------------------------------------------------------ commandes

    /** Remplace la file par [tracks] et démarre à [startIndex]. */
    fun play(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        withController { player ->
            player.setMediaItems(tracks.map(TrackEntity::toMediaItem), startIndex, 0L)
            player.prepare()
            player.play()
        }
    }

    /** Ajoute à la suite du morceau en cours. */
    fun playNext(tracks: List<TrackEntity>) = withController { player ->
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItems(insertAt, tracks.map(TrackEntity::toMediaItem))
        if (player.mediaItemCount == tracks.size) player.prepare()
    }

    /** Ajoute en fin de file. */
    fun enqueue(tracks: List<TrackEntity>) = withController { player ->
        val wasEmpty = player.mediaItemCount == 0
        player.addMediaItems(tracks.map(TrackEntity::toMediaItem))
        if (wasEmpty) {
            player.prepare()
            player.play()
        }
    }

    fun togglePlayPause() = withController { player ->
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    /**
     * Un premier appui revient au début du morceau ; c'est seulement au-delà de
     * quelques secondes écoulées, ou en début de piste, qu'on recule dans la file —
     * comportement attendu de tous les lecteurs.
     */
    fun previous() = withController { player ->
        if (player.currentPosition > RESTART_THRESHOLD_MS && player.isCurrentMediaItemSeekable) {
            player.seekTo(0)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun seekToQueueIndex(index: Int) = withController { it.seekToDefaultPosition(index) }

    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }

    fun moveInQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeatMode() = withController { player ->
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleLike() = sendCustom(PlaybackService.COMMAND_TOGGLE_LIKE)

    fun toggleDislike() = sendCustom(PlaybackService.COMMAND_TOGGLE_DISLIKE)

    /** File courante, pour l'écran « À suivre ». */
    fun queueSnapshot(): List<MediaItem> {
        val player = controller ?: return emptyList()
        return (0 until player.mediaItemCount).map(player::getMediaItemAt)
    }

    fun currentQueueIndex(): Int = controller?.currentMediaItemIndex ?: 0

    // ------------------------------------------------------------------ interne

    private fun sendCustom(action: String) = withController { player ->
        player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
    }

    /** Toute commande de lecture doit partir du thread principal. */
    private fun withController(block: (MediaController) -> Unit) {
        val player = controller ?: return
        scope.launch(Dispatchers.Main) { block(player) }
    }

    private fun publish() {
        val player = controller
        if (player == null) {
            _state.value = PlayerUiState()
            return
        }
        val metadata = player.mediaMetadata
        _state.value = PlayerUiState(
            isConnected = true,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            currentTrackId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            artworkUri = metadata.artworkUri?.toString(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queueSize = player.mediaItemCount,
        )
    }

    private inner class StateListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val PLAYING_TICK_MS = 250L
        const val IDLE_TICK_MS = 1_000L
    }
}
