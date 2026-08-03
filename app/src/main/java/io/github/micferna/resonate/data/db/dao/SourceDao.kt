package io.github.micferna.resonate.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.micferna.resonate.data.db.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun enabled(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observeById(id: Long): Flow<SourceEntity?>

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("UPDATE sources SET hostKeyFingerprint = :fingerprint WHERE id = :id")
    suspend fun rememberHostKey(id: Long, fingerprint: String)

    @Query(
        """
        UPDATE sources
        SET lastScanAt = :at,
            lastScanError = :error,
            trackCount = (SELECT COUNT(*) FROM tracks WHERE tracks.sourceId = sources.id)
        WHERE id = :id
        """,
    )
    suspend fun recordScanResult(id: Long, at: Long, error: String?)
}
