package io.github.micferna.resonate.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore(name = "resonate-playback")

/** File de lecture telle qu'elle était au dernier arrêt. */
data class SavedQueue(
    val trackIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
) {
    val isEmpty: Boolean get() = trackIds.isEmpty()
}

/**
 * Retient où en était la lecture.
 *
 * Android tue les processus en arrière-plan sans prévenir, et l'utilisateur balaie
 * l'app hors des récentes sans y penser. Sans cette sauvegarde, chaque retour repart
 * d'une file vide : la sélection patiemment constituée, l'album en cours d'écoute et
 * la position dans le morceau disparaissent ensemble.
 *
 * Seuls des identifiants sont conservés, pas les morceaux eux-mêmes. Un titre
 * supprimé de la source entre-temps est simplement ignoré à la restauration, ce qui
 * évite de reconstituer une file qui échouerait au premier morceau.
 */
@Singleton
class PlaybackStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    suspend fun save(queue: SavedQueue) {
        context.playbackStore.edit { prefs ->
            // Les identifiants sont hexadécimaux : un séparateur simple suffit, et
            // évite d'embarquer un format sérialisé pour une liste de chaînes.
            prefs[Keys.TRACK_IDS] = queue.trackIds.joinToString(SEPARATOR)
            prefs[Keys.INDEX] = queue.index
            prefs[Keys.POSITION] = queue.positionMs
            prefs[Keys.SHUFFLE] = queue.shuffleEnabled
            prefs[Keys.REPEAT] = queue.repeatMode
        }
    }

    suspend fun load(): SavedQueue {
        val prefs = context.playbackStore.data.first()
        val ids = prefs[Keys.TRACK_IDS]
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return SavedQueue(
            trackIds = ids,
            index = prefs[Keys.INDEX]?.coerceIn(0, (ids.size - 1).coerceAtLeast(0)) ?: 0,
            positionMs = prefs[Keys.POSITION] ?: 0,
            shuffleEnabled = prefs[Keys.SHUFFLE] ?: false,
            repeatMode = prefs[Keys.REPEAT] ?: 0,
        )
    }

    suspend fun clear() {
        context.playbackStore.edit { it.clear() }
    }

    private object Keys {
        val TRACK_IDS = stringPreferencesKey("queue_track_ids")
        val INDEX = intPreferencesKey("queue_index")
        val POSITION = longPreferencesKey("queue_position_ms")
        val SHUFFLE = booleanPreferencesKey("shuffle_enabled")
        val REPEAT = intPreferencesKey("repeat_mode")
    }

    private companion object {
        const val SEPARATOR = ","
    }
}
