package io.github.micferna.resonate.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.github.micferna.resonate.data.db.dao.PlaylistDao
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arborescence exposée aux clients qui *parcourent* la bibliothèque : Android Auto,
 * Wear OS, l'Assistant.
 *
 * Contrairement à l'interface de l'app, ces clients ne savent pas naviguer : ils
 * demandent les enfants d'un nœud et affichent ce qu'on leur rend. Deux contraintes
 * commandent donc la forme de cet arbre.
 *
 * D'abord, la profondeur. Android Auto limite le nombre d'appuis pendant la conduite
 * et masque une partie des listes au-delà d'un certain temps de trajet ; l'arbre reste
 * volontairement à trois niveaux au maximum, avec en tête les entrées qui demandent
 * zéro réflexion — ce qu'on aime, ce qui est déjà téléchargé.
 *
 * Ensuite, l'absence de réseau. En voiture, la connexion est fluctuante. « Hors-ligne »
 * est donc mis en avant, et les listes se construisent uniquement depuis la base
 * locale : parcourir ne déclenche jamais un appel réseau qui ferait patienter le
 * conducteur devant un écran vide.
 */
@Singleton
@OptIn(UnstableApi::class)
class MediaLibraryTree @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
) {

    /** Racine parcourable présentée au client. */
    fun root(): MediaItem = browsableItem(
        mediaId = ROOT,
        title = "Resonate",
        // Les catégories s'affichent en liste : leurs libellés sont plus parlants
        // que des vignettes, et la liste se lit d'un coup d'œil.
        browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
    )

    /** Extras de la racine, qui décrivent le style d'affichage attendu. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }

    /**
     * Enfants d'un nœud. Renvoie `null` si l'identifiant est inconnu, ce que
     * l'appelant traduit en erreur pour le client.
     */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> categories()

        parentId == CATEGORY_LIKED ->
            trackDao.observeByRating(Rating.LIKED).first().map(TrackEntity::toMediaItem)

        parentId == CATEGORY_OFFLINE ->
            trackDao.observeByOfflineState(OfflineState.DOWNLOADED).first().map(TrackEntity::toMediaItem)

        parentId == CATEGORY_RECENT ->
            trackDao.observeRecentlyAdded(BROWSE_LIMIT).first().map(TrackEntity::toMediaItem)

        parentId == CATEGORY_MOST_PLAYED ->
            trackDao.observeMostPlayed(BROWSE_LIMIT).first().map(TrackEntity::toMediaItem)

        parentId == CATEGORY_ARTISTS -> trackDao.observeArtists().first().map { artist ->
            browsableItem(
                mediaId = ARTIST_PREFIX + artist.artist,
                title = artist.artist,
                subtitle = "${artist.trackCount} titre(s)",
            )
        }

        parentId == CATEGORY_ALBUMS -> trackDao.observeAlbums().first().map { album ->
            browsableItem(
                mediaId = ALBUM_PREFIX + album.albumArtist + SEPARATOR + album.album,
                title = album.album,
                subtitle = album.albumArtist,
                artworkUrl = album.artworkUrl,
            )
        }

        parentId == CATEGORY_PLAYLISTS -> playlistDao.observeSummaries().first().map { playlist ->
            browsableItem(
                mediaId = PLAYLIST_PREFIX + playlist.id,
                title = playlist.name,
                subtitle = "${playlist.trackCount} titre(s)",
            )
        }

        parentId.startsWith(ARTIST_PREFIX) ->
            trackDao.observeByArtist(parentId.removePrefix(ARTIST_PREFIX)).first()
                .map(TrackEntity::toMediaItem)

        parentId.startsWith(ALBUM_PREFIX) -> {
            val (albumArtist, album) = parentId.removePrefix(ALBUM_PREFIX).split(SEPARATOR, limit = 2)
                .let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            trackDao.observeByAlbum(album, albumArtist).first().map(TrackEntity::toMediaItem)
        }

        parentId.startsWith(PLAYLIST_PREFIX) -> {
            val id = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull() ?: return null
            playlistDao.tracks(id).map(TrackEntity::toMediaItem)
        }

        else -> null
    }

    /** Résout un élément isolé, parcourable ou lisible. */
    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId in CATEGORY_IDS -> categories().firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith(ARTIST_PREFIX) ->
            browsableItem(mediaId, mediaId.removePrefix(ARTIST_PREFIX))
        mediaId.startsWith(ALBUM_PREFIX) ->
            browsableItem(mediaId, mediaId.removePrefix(ALBUM_PREFIX).substringAfter(SEPARATOR))
        mediaId.startsWith(PLAYLIST_PREFIX) -> {
            val id = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
            id?.let { playlistDao.observeById(it).first()?.let { p -> browsableItem(mediaId, p.name) } }
        }
        // Sans préfixe connu, c'est un identifiant de morceau.
        else -> trackDao.byId(mediaId)?.toMediaItem()
    }

    /**
     * Reconstitue des éléments lisibles à partir de ce que renvoie le client.
     *
     * Android Auto ne retransmet que les identifiants — ni URI, ni métadonnées, pour
     * ne pas laisser fuir d'adresse entre applications. C'est ici qu'on retrouve le
     * morceau complet, avec son URI et donc de quoi être lu.
     */
    suspend fun resolveForPlayback(mediaItems: List<MediaItem>): List<MediaItem> =
        mediaItems.mapNotNull { requested ->
            trackDao.byId(requested.mediaId)?.toMediaItem()
        }

    /** Résultats de recherche, y compris pour « Écoute … » dicté à l'Assistant. */
    suspend fun search(query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return trackDao.search(needle, BROWSE_LIMIT).first().map(TrackEntity::toMediaItem)
    }

    /**
     * File proposée quand le conducteur appuie sur Lecture sans avoir rien choisi —
     * au démarrage de la voiture, typiquement. On reprend ce qui a été écouté en
     * dernier, faute de quoi les morceaux aimés.
     */
    suspend fun resumptionQueue(): List<TrackEntity> =
        trackDao.recentlyPlayed(RESUMPTION_LIMIT)
            .ifEmpty { trackDao.observeByRating(Rating.LIKED).first().take(RESUMPTION_LIMIT) }

    // ------------------------------------------------------------------ interne

    private fun categories(): List<MediaItem> = listOf(
        browsableItem(CATEGORY_LIKED, "Mes favoris", "Les morceaux que vous avez aimés"),
        browsableItem(CATEGORY_OFFLINE, "Hors-ligne", "Disponible sans réseau"),
        browsableItem(CATEGORY_PLAYLISTS, "Playlists"),
        browsableItem(CATEGORY_ARTISTS, "Artistes"),
        browsableItem(CATEGORY_ALBUMS, "Albums"),
        browsableItem(CATEGORY_RECENT, "Ajoutés récemment"),
        browsableItem(CATEGORY_MOST_PLAYED, "Les plus écoutés"),
    )

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        browsableStyle: Int? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.toUri())
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .apply {
                if (browsableStyle != null) {
                    setExtras(
                        Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsableStyle)
                        },
                    )
                }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val ROOT = "resonate:root"

        private const val CATEGORY_LIKED = "resonate:cat:liked"
        private const val CATEGORY_OFFLINE = "resonate:cat:offline"
        private const val CATEGORY_PLAYLISTS = "resonate:cat:playlists"
        private const val CATEGORY_ARTISTS = "resonate:cat:artists"
        private const val CATEGORY_ALBUMS = "resonate:cat:albums"
        private const val CATEGORY_RECENT = "resonate:cat:recent"
        private const val CATEGORY_MOST_PLAYED = "resonate:cat:mostplayed"

        private val CATEGORY_IDS = setOf(
            CATEGORY_LIKED, CATEGORY_OFFLINE, CATEGORY_PLAYLISTS,
            CATEGORY_ARTISTS, CATEGORY_ALBUMS, CATEGORY_RECENT, CATEGORY_MOST_PLAYED,
        )

        private const val ARTIST_PREFIX = "resonate:artist:"
        private const val ALBUM_PREFIX = "resonate:album:"
        private const val PLAYLIST_PREFIX = "resonate:playlist:"

        /** Séparateur ASCII « unit separator », absent des noms d'albums réels. */
        private const val SEPARATOR = ""

        /**
         * Android Auto n'affiche de toute façon qu'une fraction d'une longue liste
         * pendant la conduite ; renvoyer la bibliothèque entière ne ferait
         * qu'alourdir la transaction entre processus.
         */
        private const val BROWSE_LIMIT = 300
        private const val RESUMPTION_LIMIT = 50
    }
}
