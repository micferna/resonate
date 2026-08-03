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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import androidx.media3.session.LibraryResult
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.guava.future
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
class PlaybackService : MediaLibraryService() {

    @Inject @PlaybackDataSource lateinit var dataSourceFactory: DataSource.Factory

    @Inject lateinit var trackDao: TrackDao

    @Inject lateinit var settingsStore: SettingsStore

    @Inject lateinit var libraryTree: MediaLibraryTree

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var mediaSession: MediaLibrarySession? = null
    private var settingsJob: Job? = null
    private var skipDisliked: Boolean = true

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(batteryFriendlyLoadControl())
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

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openAppIntent())
            .setMediaButtonPreferences(customLayout())
            .build()

        // Lié au cycle de vie du service, et non à la portée applicative : sans cela,
        // la collecte survivrait à la destruction du service et s'accumulerait à
        // chaque redémarrage de la lecture.
        settingsJob = scope.launch {
            settingsStore.settings.collect { skipDisliked = it.skipDislikedTracks }
        }
    }

    /**
     * Mémoire tampon large, volontairement.
     *
     * Chaque réveil de la radio cellulaire ou Wi-Fi coûte bien plus que les octets
     * transférés : la puce reste en éveil plusieurs secondes après l'échange. Un
     * lecteur qui redemande 15 secondes d'audio toutes les 15 secondes tient donc la
     * radio allumée en permanence. En remplissant jusqu'à deux minutes d'avance, on
     * regroupe les transferts en rafales espacées, entre lesquelles la radio
     * redescend en veille — sur du streaming distant, c'est le réglage qui pèse le
     * plus lourd sur l'autonomie.
     *
     * Le plafond en octets empêche un fichier sans perte de faire gonfler la mémoire :
     * c'est la première des deux limites atteinte qui arrête le remplissage.
     */
    private fun batteryFriendlyLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ MIN_BUFFER_MS,
            /* maxBufferMs = */ MAX_BUFFER_MS,
            // Démarrage de lecture : rester réactif à l'appui sur Lecture.
            /* bufferForPlaybackMs = */ 2_000,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .setTargetBufferBytes(MAX_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        // Aucun tampon arrière : revenir en arrière dans un morceau est rare, et le
        // cache disque sert déjà les octets déjà lus sans repasser par le réseau.
        .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

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
        settingsJob?.cancel()
        settingsJob = null
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

    private inner class LibraryCallback : MediaLibrarySession.Callback {

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

        // ------------------------------------------------------ navigation Auto

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(
                libraryTree.root(),
                MediaLibraryService.LibraryParams.Builder()
                    .setExtras(libraryTree.rootExtras())
                    .build(),
            ),
        )

        /**
         * Les listes se construisent depuis la base locale, jamais depuis le réseau :
         * en voiture, une requête distante qui traîne se traduit par un écran vide au
         * moment précis où le conducteur ne peut pas insister.
         */
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = libraryTree.children(parentId)
                ?: return@future LibraryResult.ofError<ImmutableList<MediaItem>>(
                    SessionError.ERROR_BAD_VALUE,
                )
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            libraryTree.item(mediaId)
                ?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /**
         * Reconstitue les éléments à partir de leur seul identifiant.
         *
         * C'est le point de passage obligé de toute lecture déclenchée hors de l'app :
         * un appui dans Android Auto, une commande vocale à l'Assistant. Le client ne
         * renvoie que des identifiants — sans cette résolution, le lecteur recevrait
         * des éléments sans URI et ne jouerait rien.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            libraryTree.resolveForPlayback(mediaItems).toMutableList()
        }

        /**
         * Appui sur Lecture alors qu'aucune file n'existe — au démarrage du véhicule,
         * typiquement. Plutôt que de ne rien faire, on repart de ce qui a été écouté
         * en dernier.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val tracks = libraryTree.resumptionQueue()
            MediaSession.MediaItemsWithStartPosition(
                tracks.map(io.github.micferna.resonate.data.db.entity.TrackEntity::toMediaItem),
                /* startIndex = */ 0,
                /* startPositionMs = */ 0,
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = libraryTree.search(query)
            // Le client est prévenu du nombre de résultats, puis vient les chercher.
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = libraryTree.search(query)
            val from = (page * pageSize).coerceAtMost(results.size)
            val to = (from + pageSize).coerceAtMost(results.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(results.subList(from, to)), params)
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

        /** Remplissage visé : deux minutes d'avance, plafonnées à 32 Mo. */
        private const val MIN_BUFFER_MS = 60_000
        private const val MAX_BUFFER_MS = 120_000
        private const val MAX_BUFFER_BYTES = 32 * 1024 * 1024
    }
}
