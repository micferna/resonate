package io.github.micferna.resonate.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ce que l'interface doit dire à l'utilisateur quand la lecture échoue. */
data class PlaybackFailure(
    val message: String,
    /** `true` tant qu'une reprise automatique est en cours. */
    val retrying: Boolean,
)

/**
 * Rattrape les erreurs de lecture et retente, plutôt que de laisser le silence.
 *
 * Toute la bibliothèque de cette app vit au bout d'un lien réseau : SSH qui tombe,
 * NAS qui se met en veille, Wi-Fi perdu au coin de la rue. Ces coupures sont la
 * normalité, pas l'exception — un lecteur qui s'arrête définitivement à la première
 * d'entre elles serait inutilisable en mobilité.
 *
 * La stratégie distingue deux familles d'erreurs :
 *
 * - **passagères** (réseau coupé, délai dépassé, lecture interrompue) : on retente
 *   au même endroit, avec des délais croissants. Le morceau reprend là où il s'était
 *   arrêté, sans perdre la file ;
 * - **définitives** (fichier absent, format illisible, accès refusé) : réessayer
 *   n'a aucun sens. On passe au morceau suivant, en le signalant.
 *
 * Le compteur se remet à zéro dès qu'une lecture repart, pour qu'une longue session
 * ponctuée de micro-coupures n'épuise pas le quota de reprises.
 */
@OptIn(UnstableApi::class)
class PlaybackRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
    private val onFailure: (PlaybackFailure?) -> Unit,
) : Player.Listener {

    private var consecutiveRetries = 0
    private var retryJob: Job? = null

    override fun onPlayerError(error: PlaybackException) {
        val transient = error.errorCode in TRANSIENT_ERRORS

        if (!transient) {
            Log.w(TAG, "Erreur définitive : ${PlaybackException.getErrorCodeName(error.errorCode)}", error)
            onFailure(PlaybackFailure(error.readableMessage(), retrying = false))
            skipToNextIfPossible()
            return
        }

        if (consecutiveRetries >= MAX_RETRIES) {
            Log.w(TAG, "Abandon après $consecutiveRetries reprises", error)
            onFailure(
                PlaybackFailure(
                    "Lecture interrompue : ${error.readableMessage()} " +
                        "La source est peut-être hors ligne.",
                    retrying = false,
                ),
            )
            consecutiveRetries = 0
            return
        }

        val attempt = ++consecutiveRetries
        val waitMs = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        onFailure(
            PlaybackFailure(
                "Connexion perdue. Nouvelle tentative $attempt/$MAX_RETRIES…",
                retrying = true,
            ),
        )

        // La position est relevée maintenant : après `prepare()`, le lecteur est
        // revenu au début du morceau.
        val resumeAt = player.currentPosition
        retryJob?.cancel()
        retryJob = scope.launch(Dispatchers.Main) {
            delay(waitMs)
            player.prepare()
            if (resumeAt > 0 && player.isCurrentMediaItemSeekable) player.seekTo(resumeAt)
            player.play()
        }
    }

    /** Une lecture qui repart efface l'ardoise. */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && consecutiveRetries > 0) {
            consecutiveRetries = 0
            onFailure(null)
        }
    }

    fun cancel() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun skipToNextIfPossible() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    private companion object {
        const val TAG = "PlaybackRecovery"
        const val MAX_RETRIES = 4

        /** 1 s, 3 s, 8 s, 20 s : de quoi traverser un tunnel ou un NAS qui se réveille. */
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 8_000, 20_000)

        /**
         * Erreurs qui justifient une nouvelle tentative. Un fichier absent ou un
         * format non pris en charge ne se répareront pas tout seuls.
         */
        val TRANSIENT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}

/** Message technique traduit en quelque chose d'actionnable. */
@OptIn(UnstableApi::class)
private fun PlaybackException.readableMessage(): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "le serveur ne répond pas."

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "le fichier n'existe plus sur la source. Relancez une analyse."

    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
        "accès refusé par le serveur. Vérifiez les identifiants de la source."

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "le serveur a refusé la requête."

    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> "ce format audio n'est pas pris en charge par l'appareil."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "le fichier semble corrompu."

    else -> "erreur de lecture (${PlaybackException.getErrorCodeName(errorCode)})."
}
