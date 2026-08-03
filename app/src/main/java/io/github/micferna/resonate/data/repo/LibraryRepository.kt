package io.github.micferna.resonate.data.repo

import io.github.micferna.resonate.data.db.dao.AlbumSummary
import io.github.micferna.resonate.data.db.dao.ArtistSummary
import io.github.micferna.resonate.data.db.dao.FolderSummary
import io.github.micferna.resonate.data.db.dao.GenreSummary
import io.github.micferna.resonate.data.db.dao.LibraryStats
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.prefs.SettingsStore
import io.github.micferna.resonate.player.OfflineLibrary
import io.github.micferna.resonate.source.SourceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Accès en lecture à la bibliothèque, et actions portées sur un morceau. */
@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val offlineLibrary: OfflineLibrary,
    private val settingsStore: SettingsStore,
    private val registry: SourceRegistry,
) {

    fun observeAllTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()

    fun observeArtists(): Flow<List<ArtistSummary>> = trackDao.observeArtists()

    fun observeAlbums(): Flow<List<AlbumSummary>> = trackDao.observeAlbums()

    fun observeGenres(): Flow<List<GenreSummary>> = trackDao.observeGenres()

    fun observeFolders(): Flow<List<FolderSummary>> = trackDao.observeFolders()

    fun observeTracksOfGenre(genre: String): Flow<List<TrackEntity>> =
        trackDao.observeByGenre(genre)

    fun observeTracksOfFolder(sourceId: Long, folder: String): Flow<List<TrackEntity>> =
        trackDao.observeByFolder(sourceId, folder)

    fun observeTracksOfArtist(artist: String): Flow<List<TrackEntity>> =
        trackDao.observeByArtist(artist)

    fun observeTracksOfAlbum(album: String, albumArtist: String): Flow<List<TrackEntity>> =
        trackDao.observeByAlbum(album, albumArtist)

    fun observeLiked(): Flow<List<TrackEntity>> = trackDao.observeByRating(Rating.LIKED)

    fun observeDownloaded(): Flow<List<TrackEntity>> =
        trackDao.observeByOfflineState(OfflineState.DOWNLOADED)

    fun observeRecentlyAdded(limit: Int = 50): Flow<List<TrackEntity>> =
        trackDao.observeRecentlyAdded(limit)

    fun observeMostPlayed(limit: Int = 50): Flow<List<TrackEntity>> =
        trackDao.observeMostPlayed(limit)

    fun observeStats(): Flow<LibraryStats> = trackDao.observeStats()

    /** Une recherche vide ne doit pas ramener toute la bibliothèque. */
    fun search(query: String): Flow<List<TrackEntity>> {
        val needle = query.trim().lowercase()
        return if (needle.isEmpty()) flowOf(emptyList()) else trackDao.search(needle)
    }

    fun observeTrack(id: String): Flow<TrackEntity?> = trackDao.observeById(id)

    suspend fun shuffleSeed(limit: Int = 200): List<TrackEntity> = trackDao.randomTracks(limit)

    /** Appliquer deux fois la même appréciation la retire. */
    suspend fun cycleRating(track: TrackEntity, target: Rating) {
        val next = if (track.rating == target) Rating.NEUTRAL else target
        trackDao.setRating(track.id, next)

        when {
            // Rejeter un morceau libère l'espace qu'il occupait : on ne garde pas
            // sur l'appareil ce que l'utilisateur vient de dire ne pas vouloir écouter.
            next == Rating.DISLIKED && track.offlineState == OfflineState.DOWNLOADED ->
                offlineLibrary.remove(track.id)

            // Retirer un like, en revanche, ne supprime rien : on peut vouloir garder
            // un morceau hors-ligne sans l'aimer pour autant.
            next == Rating.LIKED && track.offlineState == OfflineState.NONE &&
                isPinnable(track) && settingsStore.settings.first().autoDownloadLiked ->
                offlineLibrary.download(track)
        }
    }

    fun downloadAll(tracks: List<TrackEntity>) = offlineLibrary.downloadAll(tracks)

    fun offlineBytes(): Long = offlineLibrary.occupiedBytes()

    /**
     * Un morceau local est déjà sur l'appareil : le « télécharger » en recopierait
     * les octets dans le cache pour rien. L'action est donc sans effet pour lui.
     */
    fun isPinnable(track: TrackEntity): Boolean =
        registry.find(track.sourceId)?.entity?.kind?.isLocal != true

    fun toggleOffline(track: TrackEntity) {
        if (!isPinnable(track)) return
        when (track.offlineState) {
            OfflineState.DOWNLOADED, OfflineState.DOWNLOADING, OfflineState.QUEUED ->
                offlineLibrary.remove(track.id)

            OfflineState.NONE, OfflineState.FAILED -> offlineLibrary.download(track)
        }
    }
}
