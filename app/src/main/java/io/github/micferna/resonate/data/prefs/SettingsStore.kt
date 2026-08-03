package io.github.micferna.resonate.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resonate-settings")

/** Politique de consommation réseau appliquée au streaming et aux téléchargements. */
enum class NetworkPolicy {
    /** Tout réseau, y compris les données mobiles. */
    ANY,

    /** Wi-Fi ou Ethernet uniquement. */
    UNMETERED_ONLY,
}

/** Thème de l'interface. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val streamingPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val downloadPolicy: NetworkPolicy = NetworkPolicy.UNMETERED_ONLY,
    /** Taille maximale du cache de streaming, en octets. */
    val cacheSizeBytes: Long = DEFAULT_CACHE_BYTES,
    val autoScanEnabled: Boolean = true,
    val autoScanIntervalHours: Long = 12,
    val autoUpdateCheckEnabled: Boolean = true,
    val includePrereleases: Boolean = false,
    /** Télécharge automatiquement hors-ligne tout morceau aimé. */
    val autoDownloadLiked: Boolean = false,
    val skipDislikedTracks: Boolean = true,
    val lastUpdateCheckAt: Long = 0,
    /** Version dont l'utilisateur a explicitement refusé la mise à jour. */
    val dismissedUpdateTag: String = "",
) {
    companion object {
        const val DEFAULT_CACHE_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_CACHE_BYTES = 256L * 1024 * 1024
    }
}

@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            themeMode = prefs[Keys.THEME]?.toEnum(ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            streamingPolicy = prefs[Keys.STREAMING_POLICY]?.toEnum(NetworkPolicy.ANY) ?: NetworkPolicy.ANY,
            downloadPolicy = prefs[Keys.DOWNLOAD_POLICY]?.toEnum(NetworkPolicy.UNMETERED_ONLY)
                ?: NetworkPolicy.UNMETERED_ONLY,
            cacheSizeBytes = prefs[Keys.CACHE_BYTES] ?: Settings.DEFAULT_CACHE_BYTES,
            autoScanEnabled = prefs[Keys.AUTO_SCAN] ?: true,
            autoScanIntervalHours = prefs[Keys.AUTO_SCAN_HOURS] ?: 12,
            autoUpdateCheckEnabled = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
            includePrereleases = prefs[Keys.INCLUDE_PRERELEASES] ?: false,
            autoDownloadLiked = prefs[Keys.AUTO_DOWNLOAD_LIKED] ?: false,
            skipDislikedTracks = prefs[Keys.SKIP_DISLIKED] ?: true,
            lastUpdateCheckAt = prefs[Keys.LAST_UPDATE_CHECK] ?: 0,
            dismissedUpdateTag = prefs[Keys.DISMISSED_UPDATE_TAG].orEmpty(),
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = put(Keys.THEME, value.name)

    suspend fun setDynamicColor(value: Boolean) = put(Keys.DYNAMIC_COLOR, value)

    suspend fun setStreamingPolicy(value: NetworkPolicy) = put(Keys.STREAMING_POLICY, value.name)

    suspend fun setDownloadPolicy(value: NetworkPolicy) = put(Keys.DOWNLOAD_POLICY, value.name)

    suspend fun setCacheSizeBytes(value: Long) =
        put(Keys.CACHE_BYTES, value.coerceAtLeast(Settings.MIN_CACHE_BYTES))

    suspend fun setAutoScanEnabled(value: Boolean) = put(Keys.AUTO_SCAN, value)

    suspend fun setAutoScanIntervalHours(value: Long) = put(Keys.AUTO_SCAN_HOURS, value.coerceIn(1, 168))

    suspend fun setAutoUpdateCheckEnabled(value: Boolean) = put(Keys.AUTO_UPDATE_CHECK, value)

    suspend fun setIncludePrereleases(value: Boolean) = put(Keys.INCLUDE_PRERELEASES, value)

    suspend fun setAutoDownloadLiked(value: Boolean) = put(Keys.AUTO_DOWNLOAD_LIKED, value)

    suspend fun setSkipDislikedTracks(value: Boolean) = put(Keys.SKIP_DISLIKED, value)

    suspend fun setLastUpdateCheckAt(value: Long) = put(Keys.LAST_UPDATE_CHECK, value)

    suspend fun dismissUpdate(tag: String) = put(Keys.DISMISSED_UPDATE_TAG, tag)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    /** Une valeur inconnue (rétrogradage, préférence corrompue) retombe sur le défaut. */
    private inline fun <reified E : Enum<E>> String.toEnum(fallback: E): E =
        runCatching { enumValueOf<E>(this) }.getOrDefault(fallback)

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val STREAMING_POLICY = stringPreferencesKey("streaming_policy")
        val DOWNLOAD_POLICY = stringPreferencesKey("download_policy")
        val CACHE_BYTES = longPreferencesKey("cache_bytes")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val AUTO_SCAN_HOURS = longPreferencesKey("auto_scan_hours")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val INCLUDE_PRERELEASES = booleanPreferencesKey("include_prereleases")
        val AUTO_DOWNLOAD_LIKED = booleanPreferencesKey("auto_download_liked")
        val SKIP_DISLIKED = booleanPreferencesKey("skip_disliked")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val DISMISSED_UPDATE_TAG = stringPreferencesKey("dismissed_update_tag")
    }
}
