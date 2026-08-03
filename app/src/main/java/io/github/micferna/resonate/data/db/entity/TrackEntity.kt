package io.github.micferna.resonate.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Appréciation portée par l'utilisateur sur un morceau. */
enum class Rating {
    DISLIKED,
    NEUTRAL,
    LIKED,
}

/** Où en est un morceau dans le cycle de mise à disposition hors-ligne. */
enum class OfflineState {
    /** Uniquement en streaming. Peut malgré tout être servi depuis le cache LRU. */
    NONE,

    /** Marqué pour téléchargement, en attente d'une fenêtre réseau. */
    QUEUED,

    DOWNLOADING,

    /** Présent intégralement dans le cache, épinglé : jamais évincé. */
    DOWNLOADED,

    FAILED,
}

/**
 * Un morceau indexé sur une source distante.
 *
 * [id] est déterministe (dérivé de la source et du chemin) : ré-indexer une bibliothèque
 * mérge les lignes existantes au lieu de les dupliquer, ce qui préserve likes, compteurs
 * de lecture et appartenance aux playlists.
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index("artist"),
        Index("album"),
        Index("rating"),
        Index("offlineState"),
        Index("searchKey"),
        Index("folderPath"),
        Index("genre"),
        Index(value = ["sourceId", "remotePath"], unique = true),
    ],
)
data class TrackEntity(
    @PrimaryKey
    val id: String,

    val sourceId: Long,

    /** Chemin sur la source, ou identifiant opaque pour Subsonic. */
    val remotePath: String,

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

    /** URL de pochette (Subsonic), ou `null` : l'art embarqué est alors extrait à la lecture. */
    val artworkUrl: String? = null,

    /** `true` une fois les tags du conteneur réellement lus (et non déduits du chemin). */
    val tagsResolved: Boolean = false,

    val rating: Rating = Rating.NEUTRAL,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val addedAt: Long,

    /**
     * Horodatage de la dernière indexation ayant vu ce fichier sur la source.
     * Après un balayage, les lignes restées en arrière sont celles des fichiers
     * disparus : elles sont supprimées en une requête, sans avoir à comparer des
     * dizaines de milliers de chemins.
     */
    val lastSeenAt: Long,

    val offlineState: OfflineState = OfflineState.NONE,

    /** Concaténation minuscule de titre/artiste/album, pour une recherche `LIKE` indexée. */
    val searchKey: String,

    /**
     * Gain ReplayGain du morceau, en décibels, tel qu'inscrit dans ses tags.
     *
     * Zéro signifie « pas d'information », ce qui est aussi la valeur neutre : un
     * morceau sans tag est joué tel quel. Mélanger un album masterisé fort et un
     * enregistrement discret produit sinon des écarts de volume d'un titre à
     * l'autre — d'autant plus marqués que les sources sont hétérogènes.
     */
    val replayGainDb: Float = 0f,

    /**
     * Dossier tel qu'il sera affiché, séparateurs compris.
     *
     * Stocké plutôt que recalculé : la source locale adresse ses fichiers par
     * identifiant MediaStore, son chemin ne contient donc aucune arborescence.
     * Une colonne dédiée fait cohabiter les deux familles de sources dans la même
     * requête de regroupement, et se laisse indexer.
     */
    val folderPath: String = "/",
)
