package io.github.micferna.resonate.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    // ------------------------------------------------------------------ lecture

    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, discNumber, trackNumber")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeById(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TrackEntity>

    /**
     * Morceaux d'une file, remis dans l'ordre demandé.
     *
     * SQLite rend les lignes dans l'ordre qui l'arrange, pas dans celui de la clause
     * `IN`. Or une file de lecture *est* un ordre : le reconstituer ici évite que la
     * restauration ne mélange les morceaux. Les identifiants sans correspondance —
     * fichiers supprimés depuis — disparaissent simplement de la file.
     */
    suspend fun byIdsInOrder(ids: List<String>): List<TrackEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = byIds(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    @Query(
        """
        SELECT * FROM tracks
        WHERE artist = :artist
        ORDER BY album COLLATE NOCASE, discNumber, trackNumber
        """,
    )
    fun observeByArtist(artist: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE album = :album AND albumArtist = :albumArtist
        ORDER BY discNumber, trackNumber
        """,
    )
    fun observeByAlbum(album: String, albumArtist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE rating = :rating ORDER BY lastPlayedAt DESC, addedAt DESC")
    fun observeByRating(rating: Rating): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE offlineState = :state ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE")
    fun observeByOfflineState(state: OfflineState): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE playCount > 0
        ORDER BY playCount DESC, lastPlayedAt DESC
        LIMIT :limit
        """,
    )
    fun observeMostPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<TrackEntity>>

    /**
     * Dernières écoutes, utilisées pour reconstituer une file quand la lecture est
     * relancée depuis une surface qui n'a rien sélectionné — le bouton Lecture d'un
     * autoradio au démarrage du véhicule, typiquement.
     */
    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recentlyPlayed(limit: Int): List<TrackEntity>

    /**
     * Recherche par sous-chaîne sur [TrackEntity.searchKey], qui agrège titre, artiste et
     * album déjà normalisés en minuscules. Les morceaux dont le titre correspond
     * remontent avant ceux qui ne matchent que sur l'album.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE searchKey LIKE '%' || :needle || '%'
        ORDER BY
            CASE
                WHEN LOWER(title) LIKE :needle || '%' THEN 0
                WHEN LOWER(artist) LIKE :needle || '%' THEN 1
                WHEN LOWER(title) LIKE '%' || :needle || '%' THEN 2
                ELSE 3
            END,
            artist COLLATE NOCASE, album COLLATE NOCASE, trackNumber
        LIMIT :limit
        """,
    )
    fun search(needle: String, limit: Int = 300): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT artist AS artist,
               COUNT(*) AS trackCount,
               COUNT(DISTINCT album) AS albumCount
        FROM tracks
        GROUP BY artist
        ORDER BY artist COLLATE NOCASE
        """,
    )
    fun observeArtists(): Flow<List<ArtistSummary>>

    @Query(
        """
        SELECT album AS album,
               albumArtist AS albumArtist,
               COUNT(*) AS trackCount,
               MAX(year) AS year,
               MAX(artworkUrl) AS artworkUrl
        FROM tracks
        GROUP BY album, albumArtist
        ORDER BY albumArtist COLLATE NOCASE, year DESC, album COLLATE NOCASE
        """,
    )
    fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query(
        """
        SELECT genre AS genre, COUNT(*) AS trackCount
        FROM tracks
        WHERE genre != ''
        GROUP BY genre
        ORDER BY genre COLLATE NOCASE
        """,
    )
    fun observeGenres(): Flow<List<GenreSummary>>

    @Query("SELECT * FROM tracks WHERE genre = :genre ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE")
    fun observeByGenre(genre: String): Flow<List<TrackEntity>>

    /**
     * Dossiers contenant de la musique.
     *
     * Le regroupement porte sur une colonne indexée plutôt que sur une expression
     * calculée : c'est plus rapide, et surtout cela fonctionne pour la source
     * locale, dont le chemin n'est qu'un identifiant MediaStore sans arborescence.
     */
    @Query(
        """
        SELECT sourceId AS sourceId, folderPath AS folder, COUNT(*) AS trackCount
        FROM tracks
        GROUP BY sourceId, folderPath
        ORDER BY folderPath COLLATE NOCASE
        """,
    )
    fun observeFolders(): Flow<List<FolderSummary>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE sourceId = :sourceId AND folderPath = :folder
        ORDER BY discNumber, trackNumber, title COLLATE NOCASE
        """,
    )
    fun observeByFolder(sourceId: Long, folder: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT COUNT(*) AS trackCount,
               COUNT(DISTINCT artist) AS artistCount,
               COUNT(DISTINCT album) AS albumCount,
               COALESCE(SUM(rating = 'LIKED'), 0) AS likedCount,
               COALESCE(SUM(offlineState = 'DOWNLOADED'), 0) AS downloadedCount,
               COALESCE(SUM(durationMs), 0) AS totalDurationMs
        FROM tracks
        """,
    )
    fun observeStats(): Flow<LibraryStats>

    /** Sélection aléatoire, utilisée par « lecture aléatoire de la bibliothèque ». */
    @Query("SELECT * FROM tracks ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomTracks(limit: Int): List<TrackEntity>

    /** Morceaux dont les tags n'ont jamais été lus, à traiter par l'indexeur de tags. */
    @Query("SELECT * FROM tracks WHERE tagsResolved = 0 LIMIT :limit")
    suspend fun awaitingTagResolution(limit: Int): List<TrackEntity>

    /**
     * Morceaux portant une trace de l'utilisateur : appréciation ou écoutes.
     * Les autres n'ont rien à sauvegarder — une ré-indexation les recrée à
     * l'identique.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE sourceId = :sourceId AND (rating != 'NEUTRAL' OR playCount > 0)
        """,
    )
    suspend fun exportableTracks(sourceId: Long): List<TrackEntity>

    /**
     * Réapplique appréciation et compteur d'écoute à un morceau restauré.
     * Renvoie 0 si le morceau n'est pas encore indexé.
     */
    @Query("UPDATE tracks SET rating = :rating, playCount = :playCount WHERE id = :id")
    suspend fun restoreUserData(id: String, rating: Rating, playCount: Int): Int

    // ------------------------------------------------------------------ écriture

    /**
     * N'insère que les morceaux inconnus. Les lignes existantes sont laissées
     * intactes pour préserver appréciation, compteurs et état hors-ligne ;
     * leurs métadonnées sont ensuite rafraîchies par [refreshMetadata].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(tracks: List<TrackEntity>)

    @Update(entity = TrackEntity::class)
    suspend fun refreshMetadata(patches: List<TrackMetadataPatch>)

    @Update(entity = TrackEntity::class)
    suspend fun markSeen(patches: List<TrackSeenPatch>)

    @Update(entity = TrackEntity::class)
    suspend fun applyTags(patch: TrackTagPatch)

    @Update(entity = TrackEntity::class)
    suspend fun applyOfflineState(patch: TrackOfflinePatch)

    @Query("UPDATE tracks SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Rating)

    @Query(
        """
        UPDATE tracks
        SET playCount = playCount + 1, lastPlayedAt = :at
        WHERE id = :id
        """,
    )
    suspend fun recordPlay(id: String, at: Long)

    @Query("UPDATE tracks SET skipCount = skipCount + 1 WHERE id = :id")
    suspend fun recordSkip(id: String)

    /** Supprime les morceaux d'une source absents du dernier balayage. */
    @Query("DELETE FROM tracks WHERE sourceId = :sourceId AND lastSeenAt < :stamp")
    suspend fun deleteVanished(sourceId: Long, stamp: Long): Int

    @Query("DELETE FROM tracks WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /**
     * Réconcilie en une transaction le résultat d'un lot d'indexation.
     *
     * [metadataPatches] ne concerne que les sources publiant des métadonnées faisant
     * autorité ; les autres morceaux ne reçoivent qu'un [seenPatches], afin que les
     * tags déjà extraits de leurs fichiers ne soient pas remplacés par une déduction
     * tirée du chemin.
     */
    @Transaction
    suspend fun reconcile(
        discovered: List<TrackEntity>,
        metadataPatches: List<TrackMetadataPatch>,
        seenPatches: List<TrackSeenPatch>,
    ) {
        insertNew(discovered)
        if (metadataPatches.isNotEmpty()) refreshMetadata(metadataPatches)
        if (seenPatches.isNotEmpty()) markSeen(seenPatches)
    }
}
