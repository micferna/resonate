package io.github.micferna.resonate.player

import io.github.micferna.resonate.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Ce que l'interface affiche de la minuterie. */
data class SleepTimerState(
    val remainingMs: Long = 0,
    /** Arrêt à la fin du morceau en cours, plutôt qu'à une heure fixe. */
    val untilEndOfTrack: Boolean = false,
) {
    val isRunning: Boolean get() = remainingMs > 0 || untilEndOfTrack
}

/**
 * Minuterie d'arrêt.
 *
 * Deux modes, parce qu'ils répondent à deux besoins différents : une durée fixe pour
 * s'endormir, et « à la fin du morceau » pour ne pas couper une chanson au milieu
 * quand on arrive à destination.
 *
 * La mise en pause est préférée à l'arrêt du service : l'utilisateur qui se réveille
 * retrouve sa file exactement où elle en était, et il lui suffit d'appuyer sur
 * Lecture. Arrêter le service ferait disparaître la notification et perdre le
 * contexte.
 */
@Singleton
class SleepTimer @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var countdown: Job? = null

    /** Action de mise en pause, fournie par le service qui détient le lecteur. */
    var onExpire: (() -> Unit)? = null

    fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0) return

        _state.value = SleepTimerState(remainingMs = durationMs)
        countdown = scope.launch(Dispatchers.Main) {
            var remaining = durationMs
            while (isActive && remaining > 0) {
                delay(TICK_MS)
                remaining -= TICK_MS
                _state.value = SleepTimerState(remainingMs = remaining.coerceAtLeast(0))
            }
            if (isActive) {
                onExpire?.invoke()
                _state.value = SleepTimerState()
            }
        }
    }

    /**
     * Arrêt à la fin du morceau en cours. La durée restante n'est pas décomptée :
     * c'est le service qui déclenche l'arrêt à la transition suivante.
     */
    fun startUntilEndOfTrack() {
        cancel()
        _state.value = SleepTimerState(untilEndOfTrack = true)
    }

    /** Appelé par le service lorsqu'un morceau se termine. */
    fun onTrackFinished() {
        if (!_state.value.untilEndOfTrack) return
        onExpire?.invoke()
        _state.value = SleepTimerState()
    }

    fun cancel() {
        countdown?.cancel()
        countdown = null
        _state.value = SleepTimerState()
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
