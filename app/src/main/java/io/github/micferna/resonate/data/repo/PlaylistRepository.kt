package io.github.micferna.resonate.data.repo

import io.github.micferna.resonate.data.db.dao.PlaylistDao
import io.github.micferna.resonate.data.db.dao.PlaylistSummary
import io.github.micferna.resonate.data.db.entity.PlaylistEntity
import io.github.micferna.resonate.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
) {

    fun observePlaylists(): Flow<List<PlaylistSummary>> = playlistDao.observeSummaries()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = playlistDao.observeById(id)

    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>> = playlistDao.observeTracks(playlistId)

    suspend fun tracks(playlistId: Long): List<TrackEntity> = playlistDao.tracks(playlistId)

    suspend fun create(name: String, description: String = ""): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insert(
            PlaylistEntity(
                name = name.trim().ifBlank { "Nouvelle playlist" },
                description = description.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun rename(playlist: PlaylistEntity, name: String, description: String) {
        playlistDao.update(
            playlist.copy(
                name = name.trim().ifBlank { playlist.name },
                description = description.trim(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) = playlistDao.delete(id)

    suspend fun add(playlistId: Long, trackIds: List<String>) =
        playlistDao.append(playlistId, trackIds, System.currentTimeMillis())

    /**
     * Ajoute sans créer de doublon. C'est le comportement attendu du geste
     * « ajouter à une playlist » depuis un menu contextuel ; la duplication
     * volontaire reste possible en réordonnant depuis l'écran de la playlist.
     */
    suspend fun addUnique(playlistId: Long, trackIds: List<String>): Int {
        val missing = trackIds.filterNot { playlistDao.contains(playlistId, it) }
        if (missing.isNotEmpty()) add(playlistId, missing)
        return missing.size
    }

    suspend fun reorder(playlistId: Long, orderedTrackIds: List<String>) =
        playlistDao.reorder(playlistId, orderedTrackIds, System.currentTimeMillis())

    suspend fun removeAt(playlistId: Long, index: Int) {
        val current = playlistDao.tracks(playlistId).map { it.id }.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        reorder(playlistId, current)
    }
}
