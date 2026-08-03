package io.github.micferna.resonate.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.micferna.resonate.MainActivity
import io.github.micferna.resonate.R
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.prefs.SettingsStore
import io.github.micferna.resonate.di.ApplicationScope
import io.github.micferna.resonate.di.PlaybackDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service de lecture : il détient le lecteur et la session média.
 *
 * Tout passe par lui, y compris l'interface. Un `ExoPlayer` créé dans une activité
 * mourrait avec elle ; ici la musique survit à la fermeture de l'app, aux rotations et
 * au verrouillage, et les commandes de l'écran de verrouillage, du casque Bluetooth ou
 * de la montre arrivent au même endroit que celles des boutons de l'app.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    @Inject @PlaybackDataSource lateinit var dataSourceFactory: DataSource.Factory

    @Inject lateinit var trackDao: TrackDao

    @Inject lateinit var settingsStore: SettingsStore

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var mediaSession: MediaSession? = null
    private var skipDisliked: Boolean = true

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Coupe le son quand le casque est débranché, plutôt que de basculer
            // brutalement sur le haut-parleur.
            .setHandleAudioBecomingNoisy(true)
            // Maintient le Wi-Fi et le processeur éveillés le temps de remplir la
            // mémoire tampon : sans cela, la lecture depuis un serveur distant se
            // coupe dès que l'écran s'éteint.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(PlaybackStatsRecorder(player))

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openAppIntent())
            .setCustomLayout(customLayout())
            .build()

        scope.launch {
            settingsStore.settings.collect { skipDisliked = it.skipDislikedTracks }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Balayer l'app hors des applications récentes ne doit pas couper la musique en
     * cours ; en revanche, un lecteur en pause n'a aucune raison de garder un service
     * vivant et une notification affichée.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Les icônes sont désignées par leur rôle plutôt que par un dessin : les surfaces
     * système (notification, Android Auto, Wear) rendent ainsi le cœur et le pouce
     * avec leur propre habillage, cohérent avec le reste de l'appareil.
     */
    private fun customLayout(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(R.string.action_like))
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_LIKE, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setDisplayName(getString(R.string.action_dislike))
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_DISLIKE, Bundle.EMPTY))
            .build(),
    )

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_TOGGLE_DISLIKE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val mediaId = session.player.currentMediaItem?.mediaId
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))

            when (customCommand.customAction) {
                COMMAND_TOGGLE_LIKE -> toggleRating(mediaId, Rating.LIKED)
                COMMAND_TOGGLE_DISLIKE -> toggleRating(mediaId, Rating.DISLIKED)
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Appuyer deux fois sur « j'aime » retire le like plutôt que de le réappliquer. */
    private fun toggleRating(mediaId: String, target: Rating) {
        scope.launch {
            val current = trackDao.byId(mediaId)?.rating ?: Rating.NEUTRAL
            trackDao.setRating(mediaId, if (current == target) Rating.NEUTRAL else target)
        }
    }

    /**
     * Alimente les compteurs de lecture et de saut.
     *
     * Une écoute n'est comptée qu'au-delà d'un seuil, sans quoi parcourir sa
     * bibliothèque en enchaînant les morceaux gonflerait artificiellement les
     * statistiques et fausserait le classement « les plus écoutés ».
     */
    private inner class PlaybackStatsRecorder(private val player: Player) : Player.Listener {

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                reason != Player.DISCONTINUITY_REASON_SEEK
            ) {
                return
            }
            val mediaId = oldPosition.mediaItem?.mediaId ?: return
            val listened = oldPosition.positionMs
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val threshold = minOf(PLAY_THRESHOLD_MS, if (duration > 0) duration / 2 else PLAY_THRESHOLD_MS)

            scope.launch {
                if (listened >= threshold) {
                    trackDao.recordPlay(mediaId, System.currentTimeMillis())
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    trackDao.recordSkip(mediaId)
                }
            }
        }

        /** Saute automatiquement ce que l'utilisateur a explicitement rejeté. */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!skipDisliked || mediaItem == null) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            if (mediaItem.ratingOrNeutral() != Rating.DISLIKED) return
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
    }

    companion object {
        const val COMMAND_TOGGLE_LIKE = "io.github.micferna.resonate.TOGGLE_LIKE"
        const val COMMAND_TOGGLE_DISLIKE = "io.github.micferna.resonate.TOGGLE_DISLIKE"
        private const val PLAY_THRESHOLD_MS = 30_000L
    }
}
