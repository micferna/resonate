package io.github.micferna.resonate.source.subsonic

import androidx.media3.datasource.DataSource
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.source.HttpRemoteDataSource
import io.github.micferna.resonate.source.INDEX_BATCH_SIZE
import io.github.micferna.resonate.source.ProbeResult
import io.github.micferna.resonate.source.RemoteAudioFile
import io.github.micferna.resonate.source.RemoteMetadata
import io.github.micferna.resonate.source.ResolvedSource
import io.github.micferna.resonate.source.SourceConnector
import io.github.micferna.resonate.source.sftp.readableMessage
import io.github.micferna.resonate.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Client pour les serveurs parlant l'API Subsonic : Navidrome, Airsonic, Gonic,
 * Jellyfin via son greffon, et Subsonic lui-même.
 *
 * C'est la source la plus confortable des quatre : le serveur a déjà indexé la
 * bibliothèque, l'app récupère donc des métadonnées propres et des pochettes sans
 * ouvrir un seul fichier. Là où un balayage SFTP demande des minutes et la lecture
 * des tags de chaque morceau, quelques requêtes suffisent ici.
 */
@Singleton
class SubsonicConnector @Inject constructor(
    private val callFactory: Call.Factory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceConnector {

    override val kind: SourceKind = SourceKind.SUBSONIC

    override suspend fun probe(source: ResolvedSource): ProbeResult = withContext(ioDispatcher) {
        try {
            val response = request(source, "ping")
            if (response.isOk) {
                ProbeResult.Success(
                    "Connecté à ${response.type.ifBlank { "un serveur Subsonic" }} " +
                        "${response.serverVersion} (protocole ${response.version}).",
                )
            } else {
                ProbeResult.Failure(response.error.describe())
            }
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            ProbeResult.Failure(error.readableMessage(), error)
        }
    }

    override suspend fun index(
        source: ResolvedSource,
        onBatch: suspend (List<RemoteAudioFile>) -> Unit,
    ) = withContext(ioDispatcher) {
        val batch = ArrayList<RemoteAudioFile>(INDEX_BATCH_SIZE)

        val artists = request(source, "getArtists").artists
            ?.index?.flatMap { it.artist }
            ?: throw IOException("Le serveur n'a renvoyé aucun artiste.")

        for (artist in artists) {
            coroutineContext.ensureActive()
            val albums = runCatching {
                request(source, "getArtist", mapOf("id" to artist.id)).artist?.album.orEmpty()
            }.getOrDefault(emptyList())

            for (album in albums) {
                coroutineContext.ensureActive()
                val detail = runCatching {
                    request(source, "getAlbum", mapOf("id" to album.id)).album
                }.getOrNull() ?: continue

                for (song in detail.song) {
                    batch += song.toRemoteAudioFile(source)
                    if (batch.size >= INDEX_BATCH_SIZE) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    override fun createDataSource(source: ResolvedSource): DataSource =
        HttpRemoteDataSource(
            callFactory = callFactory,
            resolve = { source },
            toHttpUrl = { resolved, path ->
                endpoint(resolved, "stream", mapOf("id" to path.trim('/'))).toString()
            },
            // L'authentification Subsonic voyage dans la requête, pas dans les en-têtes.
            headersFor = { emptyMap() },
        )

    override fun invalidate(sourceId: Long) {
        // OkHttp recycle seul ses connexions ; rien à libérer explicitement.
    }

    /** URL de pochette signée, directement consommable par le chargeur d'images. */
    fun coverArtUrl(source: ResolvedSource, coverArtId: String, size: Int = 512): String =
        endpoint(source, "getCoverArt", mapOf("id" to coverArtId, "size" to size.toString())).toString()

    // ------------------------------------------------------------------ interne

    private fun SubsonicSong.toRemoteAudioFile(source: ResolvedSource): RemoteAudioFile =
        RemoteAudioFile(
            path = "/$id",
            fileName = path.substringAfterLast('/').ifBlank { "$title.${suffix.ifBlank { "mp3" }}" },
            sizeBytes = size,
            metadata = RemoteMetadata(
                title = title.ifBlank { "Sans titre" },
                artist = artist.ifBlank { albumArtist },
                album = album,
                albumArtist = albumArtist.ifBlank { artist },
                trackNumber = track,
                discNumber = discNumber,
                year = year,
                genre = genre,
                durationMs = duration * 1000L,
                artworkUrl = coverArt?.let { coverArtUrl(source, it) },
            ),
        )

    private fun request(
        source: ResolvedSource,
        method: String,
        params: Map<String, String> = emptyMap(),
    ): SubsonicResponse {
        val request = Request.Builder().url(endpoint(source, method, params)).build()
        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Le serveur a répondu ${response.code} pour $method.")
            }
            val body = response.body.string()
            val parsed = runCatching { SubsonicJson.decodeFromString<SubsonicEnvelope>(body) }
                .getOrElse { throw IOException("Réponse Subsonic illisible pour $method.", it) }
            val payload = parsed.response
            if (!payload.isOk) throw IOException(payload.error.describe())
            return payload
        }
    }

    private fun endpoint(
        source: ResolvedSource,
        method: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val builder = "${source.entity.httpBaseUrl}/rest/$method.view".toHttpUrl().newBuilder()

        // Authentification par jeton salé : le mot de passe ne circule jamais, et le
        // sel change à chaque requête, ce qui rend le jeté inutilisable. Le MD5 est
        // imposé par la spécification Subsonic ; ce n'est pas un choix côté client.
        val salt = newSalt()
        val password = source.password.orEmpty()
        builder.addQueryParameter("u", source.entity.username)
        builder.addQueryParameter("t", md5Hex(password + salt))
        builder.addQueryParameter("s", salt)
        builder.addQueryParameter("v", PROTOCOL_VERSION)
        builder.addQueryParameter("c", CLIENT_NAME)
        builder.addQueryParameter("f", "json")
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /**
     * Une seule instance, partagée : construire un `SecureRandom` par requête
     * repaie à chaque fois le coût d'ensemencement, sans rien apporter — l'instance
     * se réensemence elle-même au fil des tirages.
     */
    private val secureRandom = SecureRandom()

    private fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun SubsonicError?.describe(): String = when (this?.code) {
        null -> "Le serveur Subsonic a refusé la requête."
        40 -> "Identifiant ou mot de passe incorrect."
        41 -> "Ce serveur exige une authentification LDAP, non prise en charge."
        50 -> "Cet utilisateur n'a pas le droit de lire la bibliothèque."
        60 -> "Ce serveur requiert un abonnement Subsonic Premium actif."
        else -> message.ifBlank { "Erreur Subsonic $code." }
    }

    private companion object {
        const val PROTOCOL_VERSION = "1.16.1"
        const val CLIENT_NAME = "Resonate"
        const val SALT_BYTES = 8
    }
}
