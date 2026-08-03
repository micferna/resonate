package io.github.micferna.resonate.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.micferna.resonate.data.db.entity.PlaylistEntity
import io.github.micferna.resonate.data.db.entity.PlaylistTrackEntity
import io.github.micferna.resonate.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Playlist accompagnée de ses compteurs, pour l'affichage en liste. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val description: String,
    val updatedAt: Long,
    val trackCount: Int,
    val totalDurationMs: Long,
)

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               p.description AS description,
               p.updatedAt AS updatedAt,
               COUNT(pt.trackId) AS trackCount,
               COALESCE(SUM(t.durationMs), 0) AS totalDurationMs
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        LEFT JOIN tracks t ON t.id = pt.trackId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.description AS description,
               p.updatedAt AS updatedAt,
               COUNT(pt.trackId) AS trackCount,
               COALESCE(SUM(t.durationMs), 0) AS totalDurationMs
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        LEFT JOIN tracks t ON t.id = pt.trackId
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE
        """,
    )
    suspend fun observeSummariesOnce(): List<PlaylistSummary>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """,
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """,
    )
    suspend fun tracks(playlistId: Long): List<TrackEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId)")
    suspend fun contains(playlistId: Long, trackId: String): Boolean

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Query("UPDATE playlists SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    /** Ajoute des morceaux à la suite, en conservant l'ordre fourni. */
    @Transaction
    suspend fun append(playlistId: Long, trackIds: List<String>, at: Long) {
        if (trackIds.isEmpty()) return
        val start = nextPosition(playlistId)
        insertEntries(
            trackIds.mapIndexed { index, trackId ->
                PlaylistTrackEntity(playlistId, trackId, start + index)
            },
        )
        touch(playlistId, at)
    }

    /**
     * Réécrit intégralement l'ordre d'une playlist. Passer par un vidage puis une
     * réinsertion évite les collisions transitoires de la clé primaire (playlistId,
     * position) qu'un simple décalage de positions provoquerait.
     */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedTrackIds: List<String>, at: Long) {
        clearEntries(playlistId)
        insertEntries(
            orderedTrackIds.mapIndexed { index, trackId ->
                PlaylistTrackEntity(playlistId, trackId, index)
            },
        )
        touch(playlistId, at)
    }
}
