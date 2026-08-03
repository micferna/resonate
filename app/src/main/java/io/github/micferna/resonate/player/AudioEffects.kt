package io.github.micferna.resonate.player

import android.media.audiofx.Equalizer
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/** Un préréglage d'égaliseur tel que le nomme le système. */
data class EqualizerPreset(val index: Short, val name: String)

/**
 * Égaliseur et normalisation du volume.
 *
 * L'égaliseur passe par celui d'Android plutôt que par un traitement maison : il est
 * accéléré par le matériel sur la plupart des appareils, ses préréglages portent des
 * noms que l'utilisateur reconnaît, et il ne coûte rien en batterie tant qu'il est
 * désactivé.
 *
 * L'effet est rattaché à la session audio du lecteur, et doit donc être recréé à
 * chaque fois que celle-ci change. Un effet laissé attaché à une session morte est
 * une fuite de ressource système, pas seulement de mémoire : le nombre d'effets
 * simultanés est limité à l'échelle de l'appareil.
 */
@Singleton
class AudioEffects @Inject constructor() {

    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0

    /** Préréglages proposés par l'appareil ; vide s'il n'a pas d'égaliseur. */
    var presets: List<EqualizerPreset> = emptyList()
        private set

    /**
     * Rattache l'égaliseur à une nouvelle session audio.
     *
     * Certains appareils refusent d'instancier l'effet — matériel absent, session
     * déjà occupée. Ce n'est pas une raison d'empêcher la lecture : on abandonne
     * silencieusement l'égaliseur et la musique continue.
     */
    fun bind(audioSessionId: Int, enabled: Boolean, presetIndex: Short) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId

        equalizer = runCatching {
            Equalizer(EFFECT_PRIORITY, audioSessionId).apply {
                presets = (0 until numberOfPresets).map { index ->
                    EqualizerPreset(index.toShort(), getPresetName(index.toShort()))
                }
                this@AudioEffects.presets = presets
                applyPreset(this, enabled, presetIndex)
            }
        }.onFailure {
            Log.i(TAG, "Égaliseur indisponible sur cet appareil", it)
        }.getOrNull()
    }

    fun update(enabled: Boolean, presetIndex: Short) {
        equalizer?.let { applyPreset(it, enabled, presetIndex) }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        sessionId = 0
    }

    private fun applyPreset(equalizer: Equalizer, enabled: Boolean, presetIndex: Short) {
        runCatching {
            equalizer.enabled = enabled
            if (enabled && presetIndex in 0 until equalizer.numberOfPresets) {
                equalizer.usePreset(presetIndex)
            }
        }.onFailure { Log.i(TAG, "Préréglage refusé", it) }
    }

    private companion object {
        const val TAG = "AudioEffects"

        /**
         * Priorité de l'effet. Zéro est la valeur neutre recommandée pour une
         * application ordinaire : elle laisse le système arbitrer si un autre
         * processus réclame le même effet.
         */
        const val EFFECT_PRIORITY = 0
    }
}

/**
 * Convertit un gain ReplayGain en facteur de volume pour le lecteur.
 *
 * La formule est celle du décibel : un gain de -6 dB correspond à la moitié de
 * l'amplitude. Le résultat est plafonné à 1 — le lecteur n'amplifie pas au-delà de
 * son volume nominal, sous peine d'écrêtage sur les morceaux déjà forts. Un morceau
 * sans tag garde un facteur de 1, donc son volume d'origine.
 */
fun replayGainToVolume(gainDb: Float): Float {
    if (gainDb == 0f) return 1f
    return 10f.pow(gainDb / 20f).coerceIn(MIN_VOLUME, 1f)
}

/** En dessous, le morceau deviendrait inaudible : le tag est plus probablement faux. */
private const val MIN_VOLUME = 0.1f
