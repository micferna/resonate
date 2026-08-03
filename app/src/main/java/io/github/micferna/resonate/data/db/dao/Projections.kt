package io.github.micferna.resonate.data.db.dao

import androidx.room.ColumnInfo
import io.github.micferna.resonate.data.db.entity.OfflineState

/** Ligne d'un regroupement par artiste sur l'écran Bibliothèque. */
data class ArtistSummary(
    val artist: String,
    @ColumnInfo(name = "trackCount") val trackCount: Int,
    @ColumnInfo(name = "albumCount") val albumCount: Int,
)

/** Ligne d'un regroupement par album. */
data class AlbumSummary(
    val album: String,
    val albumArtist: String,
    @ColumnInfo(name = "trackCount") val trackCount: Int,
    @ColumnInfo(name = "year") val year: Int,
    @ColumnInfo(name = "artworkUrl") val artworkUrl: String?,
)

/** Ligne d'un regroupement par genre. */
data class GenreSummary(
    val genre: String,
    @ColumnInfo(name = "trackCount") val trackCount: Int,
)

/**
 * Un dossier de la bibliothèque, tel qu'il existe sur la source.
 *
 * Une arborescence de fichiers porte souvent une organisation que les tags ne
 * reflètent pas : bootlegs, compilations personnelles, classements par soirée.
 * Pouvoir la parcourir telle quelle évite de dépendre de tags parfois absents.
 */
data class FolderSummary(
    val sourceId: Long,
    val folder: String,
    @ColumnInfo(name = "trackCount") val trackCount: Int,
)

/** Compteurs affichés sur l'écran Réglages. */
data class LibraryStats(
    @ColumnInfo(name = "trackCount") val trackCount: Int,
    @ColumnInfo(name = "artistCount") val artistCount: Int,
    @ColumnInfo(name = "albumCount") val albumCount: Int,
    @ColumnInfo(name = "likedCount") val likedCount: Int,
    @ColumnInfo(name = "downloadedCount") val downloadedCount: Int,
    @ColumnInfo(name = "totalDurationMs") val totalDurationMs: Long,
)

/**
 * Sous-ensemble de colonnes réécrit lors d'une indexation.
 *
 * Utilisé avec `@Update(entity = TrackEntity::class)` pour rafraîchir les métadonnées
 * d'un morceau déjà connu sans écraser ce que l'utilisateur y a ajouté (appréciation,
 * compteurs de lecture, état hors-ligne).
 */
data class TrackMetadataPatch(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val genre: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val artworkUrl: String?,
    val tagsResolved: Boolean,
    val searchKey: String,
    val lastSeenAt: Long,
    val folderPath: String,
)

/** Patch appliqué quand seule la présence du fichier est reconfirmée. */
data class TrackSeenPatch(
    val id: String,
    val lastSeenAt: Long,
    val sizeBytes: Long,
)

/** Patch appliqué après lecture des tags réels du conteneur. */
data class TrackTagPatch(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val genre: String,
    val durationMs: Long,
    val tagsResolved: Boolean,
    val searchKey: String,
    val artworkUrl: String?,
    val replayGainDb: Float,
)

/** Patch de l'état hors-ligne, piloté par le gestionnaire de téléchargements. */
data class TrackOfflinePatch(
    val id: String,
    val offlineState: OfflineState,
)
