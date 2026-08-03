package io.github.micferna.resonate.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.dao.TrackOfflinePatch
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.prefs.NetworkPolicy
import io.github.micferna.resonate.data.prefs.SettingsStore
import io.github.micferna.resonate.di.ApplicationScope
import io.github.micferna.resonate.di.DownloadDataSource
import io.github.micferna.resonate.di.DownloadExecutor
import io.github.micferna.resonate.source.ResonateUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rend des morceaux disponibles sans réseau, et tient la base au courant de leur état.
 *
 * S'appuie sur le gestionnaire de téléchargements de Media3 plutôt que sur une copie de
 * fichier maison : il reprend là où il s'est arrêté après une coupure, respecte les
 * contraintes réseau, survit au redémarrage de l'appareil, et surtout il écrit dans le
 * même cache que celui interrogé à la lecture. Un morceau téléchargé est donc lu par
 * exactement le même chemin de code qu'un morceau diffusé — sans branche « hors-ligne »
 * séparée, qui est toujours celle qu'on oublie de tester.
 */
@Singleton
@OptIn(UnstableApi::class)
class OfflineLibrary @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:DownloadCache private val downloadCache: Cache,
    @param:DownloadDataSource private val downloadDataSourceFactory: DataSource.Factory,
    @param:DownloadExecutor private val downloadExecutor: Executor,
    private val databaseProvider: DatabaseProvider,
    private val trackDao: TrackDao,
    private val settingsStore: SettingsStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            downloadDataSourceFactory,
            downloadExecutor,
        ).apply {
            maxParallelDownloads = MAX_PARALLEL
            addListener(StateSyncListener())
        }
    }

    init {
        // La contrainte réseau est un réglage utilisateur : la relayer au gestionnaire
        // évite qu'un « Wi-Fi uniquement » soit contourné par une reprise automatique.
        settingsStore.settings
            .map { it.downloadPolicy }
            .distinctUntilChanged()
            .onEach { policy -> downloadManager.requirements = policy.toRequirements() }
            .launchIn(scope)
    }

    /** Épingle un morceau pour l'écoute hors-ligne. */
    fun download(track: TrackEntity) {
        val request = DownloadRequest.Builder(track.id, ResonateUri.build(track.sourceId, track.remotePath))
            .setCustomCacheKey(track.id)
            .setMimeType(track.mimeType)
            .build()
        DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, request, false)
        scope.launch {
            trackDao.applyOfflineState(TrackOfflinePatch(track.id, OfflineState.QUEUED))
        }
    }

    fun downloadAll(tracks: List<TrackEntity>) = tracks.forEach(::download)

    /** Libère l'espace occupé par un morceau ; il redevient lisible en streaming. */
    fun remove(trackId: String) {
        DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, trackId, false)
        scope.launch {
            trackDao.applyOfflineState(TrackOfflinePatch(trackId, OfflineState.NONE))
        }
    }

    /** Octets réellement occupés par les morceaux hors-ligne. */
    fun occupiedBytes(): Long = downloadCache.cacheSpace

    private fun NetworkPolicy.toRequirements(): Requirements = when (this) {
        NetworkPolicy.ANY -> Requirements(Requirements.NETWORK)
        NetworkPolicy.UNMETERED_ONLY -> Requirements(Requirements.NETWORK_UNMETERED)
    }

    /**
     * Recopie l'état des téléchargements dans la base, seule source consultée par l'UI.
     *
     * L'index de Media3 sait ce qui est téléchargé, mais l'écran Bibliothèque affiche
     * des morceaux triés et filtrés : il lui faut cette information dans la même requête
     * SQL, pas dans un index parallèle à interroger ligne par ligne.
     */
    private inner class StateSyncListener : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            val state = when (download.state) {
                Download.STATE_QUEUED, Download.STATE_RESTARTING -> OfflineState.QUEUED
                Download.STATE_DOWNLOADING -> OfflineState.DOWNLOADING
                Download.STATE_COMPLETED -> OfflineState.DOWNLOADED
                Download.STATE_FAILED -> OfflineState.FAILED
                Download.STATE_REMOVING, Download.STATE_STOPPED -> OfflineState.NONE
                else -> return
            }
            scope.launch {
                trackDao.applyOfflineState(TrackOfflinePatch(download.request.id, state))
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            scope.launch {
                trackDao.applyOfflineState(TrackOfflinePatch(download.request.id, OfflineState.NONE))
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL = 3
    }
}
