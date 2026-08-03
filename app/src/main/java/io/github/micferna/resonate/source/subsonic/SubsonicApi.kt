package io.github.micferna.resonate.source.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Modèle des réponses Subsonic réellement exploitées.
 *
 * Le protocole en publie bien davantage (podcasts, radios, partages, jukebox) ; ne
 * déclarer que ce qui est utilisé garde le parsing rapide, et `ignoreUnknownKeys`
 * absorbe les extensions propres à Navidrome, Airsonic ou Jellyfin.
 */
val SubsonicJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicResponse(
    val status: String = "failed",
    val version: String = "",
    val type: String = "",
    val serverVersion: String = "",
    val error: SubsonicError? = null,
    val artists: ArtistsContainer? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true)
}

@Serializable
data class SubsonicError(val code: Int = 0, val message: String = "")

@Serializable
data class ArtistsContainer(val index: List<ArtistIndex> = emptyList())

@Serializable
data class ArtistIndex(val name: String = "", val artist: List<ArtistRef> = emptyList())

@Serializable
data class ArtistRef(val id: String, val name: String = "", val albumCount: Int = 0)

@Serializable
data class ArtistDetail(
    val id: String = "",
    val name: String = "",
    val album: List<AlbumRef> = emptyList(),
)

@Serializable
data class AlbumRef(
    val id: String,
    val name: String = "",
    val artist: String = "",
    val year: Int = 0,
    val coverArt: String? = null,
)

@Serializable
data class AlbumDetail(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val year: Int = 0,
    val coverArt: String? = null,
    val song: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicSong(
    val id: String,
    val title: String = "",
    val album: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val track: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String = "",
    /** Durée en secondes, contrairement au reste de l'app qui compte en millisecondes. */
    val duration: Int = 0,
    val size: Long = 0,
    val suffix: String = "",
    val coverArt: String? = null,
    val path: String = "",
)
