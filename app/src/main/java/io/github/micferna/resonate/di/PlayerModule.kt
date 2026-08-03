package io.github.micferna.resonate.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.micferna.resonate.player.DownloadCache
import io.github.micferna.resonate.player.MediaCaches
import io.github.micferna.resonate.player.StreamCache
import io.github.micferna.resonate.source.ResonateDataSourceFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

/** Chaîne de lecture complète : hors-ligne, puis cache, puis réseau. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackDataSource

/** Chaîne d'écriture des téléchargements : réseau vers cache hors-ligne. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadDataSource

/** Exécuteur dédié aux téléchargements hors-ligne. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadExecutor

@Module
@InstallIn(SingletonComponent::class)
@OptIn(UnstableApi::class)
object PlayerModule {

    @Provides
    @Singleton
    fun databaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        MediaCaches.databaseProvider(context)

    @Provides
    @Singleton
    @StreamCache
    fun streamCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache = MediaCaches.streamCache(context, databaseProvider)

    @Provides
    @Singleton
    @DownloadCache
    fun downloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache = MediaCaches.downloadCache(context, databaseProvider)

    /**
     * Ordre des maillons, du plus proche au plus lointain :
     *
     * 1. cache hors-ligne, en lecture seule — un morceau téléchargé se lit sans réseau
     *    et ne doit jamais être réécrit ni évincé ;
     * 2. cache de streaming, en lecture-écriture — remplit au fil de l'écoute ;
     * 3. le réseau, via l'aiguillage vers SFTP, SMB, WebDAV ou Subsonic.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` garantit qu'un cache corrompu dégrade la lecture en
     * simple streaming au lieu de la faire échouer.
     */
    @Provides
    @Singleton
    @PlaybackDataSource
    fun playbackDataSourceFactory(
        @DownloadCache downloadCache: Cache,
        @StreamCache streamCache: Cache,
        routing: ResonateDataSourceFactory,
    ): DataSource.Factory {
        val streamingLayer = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(routing)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streamingLayer)
            .setCacheWriteDataSinkFactory(null) // lecture seule
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Le téléchargeur écrit dans le cache hors-ligne, sans passer par celui de streaming. */
    @Provides
    @Singleton
    @DownloadDataSource
    fun downloadDataSourceFactory(
        @DownloadCache downloadCache: Cache,
        routing: ResonateDataSourceFactory,
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(routing)

    @Provides
    @Singleton
    @DownloadExecutor
    fun downloadExecutor(): Executor =
        // Trois téléchargements simultanés : de quoi saturer une connexion domestique
        // sans monopoliser les canaux SFTP/SMB dont la lecture a besoin en parallèle.
        Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "resonate-download").apply { isDaemon = true }
        }
}
