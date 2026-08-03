package io.github.micferna.resonate.player

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.source.ResonateUri

/**
 * Traduit un morceau de la bibliothèque en [MediaItem].
 *
 * `mediaId` et `customCacheKey` portent tous deux l'identifiant du morceau : le premier
 * permet de recoller un élément de la file à sa ligne en base (pour incrémenter un
 * compteur de lecture, appliquer un like), le second garantit qu'un morceau déjà
 * téléchargé est reconnu dans le cache même si l'URL du serveur a changé entre-temps.
 */
@OptIn(UnstableApi::class)
fun TrackEntity.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(ResonateUri.of(sourceId, remotePath))
        .setCustomCacheKey(id)
        .setMimeType(mimeType)
        .setMediaMetadata(toMediaMetadata())
        .build()

@OptIn(UnstableApi::class)
fun TrackEntity.toMediaMetadata(): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setAlbumArtist(albumArtist)
        .setTrackNumber(trackNumber.takeIf { it > 0 })
        .setDiscNumber(discNumber.takeIf { it > 0 })
        .setRecordingYear(year.takeIf { it > 0 })
        .setGenre(genre.takeIf { it.isNotBlank() })
        .setDurationMs(durationMs.takeIf { it > 0 })
        .setArtworkUri(artworkUrl?.toUri())
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        // Remonté jusqu'à la notification et aux surfaces système (Android Auto,
        // écran de verrouillage), qui savent afficher un cœur.
        .setUserRating(
            androidx.media3.common.HeartRating(rating == Rating.LIKED),
        )
        .setExtras(
            android.os.Bundle().apply {
                putString(EXTRA_RATING, rating.name)
                putString(EXTRA_OFFLINE_STATE, offlineState.name)
                putLong(EXTRA_SOURCE_ID, sourceId)
            },
        )
        .build()

const val EXTRA_RATING = "io.github.micferna.resonate.RATING"
const val EXTRA_OFFLINE_STATE = "io.github.micferna.resonate.OFFLINE_STATE"
const val EXTRA_SOURCE_ID = "io.github.micferna.resonate.SOURCE_ID"

/** Relit l'appréciation transportée par un [MediaItem], pour l'afficher sans requête. */
fun MediaItem.ratingOrNeutral(): Rating =
    mediaMetadata.extras?.getString(EXTRA_RATING)
        ?.let { runCatching { Rating.valueOf(it) }.getOrNull() }
        ?: Rating.NEUTRAL
