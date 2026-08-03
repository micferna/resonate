package io.github.micferna.resonate.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.github.micferna.resonate.data.prefs.Settings
import java.io.File
import javax.inject.Qualifier

/** Cache opportuniste : ce qui a été écouté récemment, évincé quand la place manque. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamCache

/** Cache épinglé : les morceaux explicitement rendus disponibles hors-ligne. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadCache

/**
 * Deux caches, et non un seul, parce qu'ils obéissent à des règles opposées.
 *
 * Le cache de streaming vit dans `cacheDir` : Android peut le vider quand le stockage
 * sature, ce qui est exactement le comportement voulu pour de l'écoute opportuniste.
 * Il est borné et évince les entrées les plus anciennes.
 *
 * Le cache hors-ligne vit dans `filesDir` et n'évince jamais rien. Un morceau que
 * l'utilisateur a demandé à emporter doit être là dans le métro, y compris trois
 * semaines plus tard après un pic d'occupation du stockage. Les mélanger reviendrait à
 * laisser l'éviction LRU effacer silencieusement de la musique téléchargée exprès.
 */
@OptIn(UnstableApi::class)
object MediaCaches {

    fun databaseProvider(context: Context): DatabaseProvider = StandaloneDatabaseProvider(context)

    fun streamCache(
        context: Context,
        databaseProvider: DatabaseProvider,
        maxBytes: Long = Settings.DEFAULT_CACHE_BYTES,
    ): Cache = SimpleCache(
        File(context.cacheDir, STREAM_DIR),
        LeastRecentlyUsedCacheEvictor(maxBytes),
        databaseProvider,
    )

    fun downloadCache(context: Context, databaseProvider: DatabaseProvider): Cache = SimpleCache(
        File(context.filesDir, DOWNLOAD_DIR),
        NoOpCacheEvictor(),
        databaseProvider,
    )

    private const val STREAM_DIR = "media-stream-cache"
    private const val DOWNLOAD_DIR = "media-offline"
}
