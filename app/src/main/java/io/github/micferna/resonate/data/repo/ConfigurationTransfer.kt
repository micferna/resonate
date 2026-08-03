package io.github.micferna.resonate.data.repo

import android.content.Context
import android.net.Uri
import io.github.micferna.resonate.core.crypto.CredentialCipher
import io.github.micferna.resonate.data.db.dao.PlaylistDao
import io.github.micferna.resonate.data.db.dao.SourceDao
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.entity.PlaylistEntity
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fichier d'export.
 *
 * Le format est du JSON lisible, versionné, et volontairement indépendant du schéma
 * de la base : celui-ci évoluera, alors qu'un fichier d'export doit rester lisible
 * par une version ultérieure de l'app.
 */
@Serializable
data class ConfigurationBackup(
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("app_version") val appVersion: String,
    val sources: List<SourceBackup> = emptyList(),
    val playlists: List<PlaylistBackup> = emptyList(),
    val ratings: List<RatingBackup> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/**
 * Une source, secrets compris.
 *
 * Les mots de passe et clés privées sont **déchiffrés** ici, puis rechiffrés à
 * l'import par la clé du nouvel appareil. Exporter la forme scellée serait inutile :
 * la clé qui la déverrouille ne quitte jamais le KeyStore d'origine, le fichier
 * serait illisible ailleurs — c'est-à-dire exactement là où on en a besoin.
 *
 * Le fichier produit contient donc des identifiants en clair. L'app le dit à
 * l'utilisateur au moment de l'export, sans détour.
 */
@Serializable
data class SourceBackup(
    val kind: String,
    @SerialName("display_name") val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val secret: String? = null,
    @SerialName("secret_kind") val secretKind: String,
    @SerialName("key_passphrase") val keyPassphrase: String? = null,
    @SerialName("root_path") val rootPath: String,
    @SerialName("share_name") val shareName: String? = null,
    @SerialName("use_tls") val useTls: Boolean,
    val enabled: Boolean,
)

@Serializable
data class PlaylistBackup(
    val name: String,
    val description: String = "",
    /** Chemins `<idSource>|<cheminDistant>` : les identifiants internes ne survivent pas. */
    val tracks: List<String> = emptyList(),
)

@Serializable
data class RatingBackup(
    @SerialName("source_index") val sourceIndex: Int,
    @SerialName("remote_path") val remotePath: String,
    val rating: String,
    @SerialName("play_count") val playCount: Int = 0,
)

/** Ce qu'un import a effectivement rétabli. */
data class ImportOutcome(
    val sourcesAdded: Int,
    val playlistsAdded: Int,
    val ratingsRestored: Int,
    val ratingsSkipped: Int,
)

/**
 * Export et import de la configuration.
 *
 * Les sauvegardes automatiques d'Android sont désactivées pour cette app : elles ne
 * transporteraient que des secrets scellés par une clé propre à l'appareil, donc
 * indéchiffrables une fois restaurés ailleurs. Ce transfert explicite est la
 * contrepartie de ce choix — sans lui, changer de téléphone signifierait tout
 * reconfigurer et perdre ses favoris.
 *
 * Les morceaux eux-mêmes ne sont pas exportés : ils vivent sur les serveurs, une
 * ré-indexation les retrouve. Ce qui compte et ne se retrouve pas tout seul, c'est
 * la configuration des sources et ce que l'utilisateur y a ajouté — appréciations,
 * playlists, compteurs d'écoute.
 *
 * Les appréciations sont référencées par (source, chemin distant) plutôt que par
 * l'identifiant interne du morceau : celui-ci dépend de l'identifiant de la source,
 * qui sera différent sur le nouvel appareil.
 */
@Singleton
class ConfigurationTransfer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sourceDao: SourceDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val cipher: CredentialCipher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Nom de fichier proposé au sélecteur du système. */
    fun suggestedFileName(): String = "resonate-configuration.json"

    suspend fun export(target: Uri, appVersion: String): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val sources = sourceDao.observeAllOnce()
            val sourceIndexById = sources.withIndex().associate { (index, s) -> s.id to index }

            val ratings = sources.flatMap { source ->
                trackDao.exportableTracks(source.id).map { track ->
                    RatingBackup(
                        sourceIndex = sourceIndexById.getValue(source.id),
                        remotePath = track.remotePath,
                        rating = track.rating.name,
                        playCount = track.playCount,
                    )
                }
            }

            val playlists = playlistDao.observeSummariesOnce().map { summary ->
                PlaylistBackup(
                    name = summary.name,
                    description = summary.description,
                    tracks = playlistDao.tracks(summary.id).map { track ->
                        "${sourceIndexById[track.sourceId] ?: -1}|${track.remotePath}"
                    },
                )
            }

            val backup = ConfigurationBackup(
                exportedAt = System.currentTimeMillis(),
                appVersion = appVersion,
                sources = sources.map { it.toBackup() },
                playlists = playlists,
                ratings = ratings,
            )

            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                output.write(json.encodeToString(backup).toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Impossible d'écrire dans le fichier choisi.")

            sources.size
        }
    }

    suspend fun import(source: Uri): Result<ImportOutcome> = withContext(ioDispatcher) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IOException("Impossible de lire le fichier choisi.")

            val backup = json.decodeFromString<ConfigurationBackup>(text)
            if (backup.formatVersion > ConfigurationBackup.FORMAT_VERSION) {
                throw IOException(
                    "Ce fichier vient d'une version plus récente de Resonate " +
                        "(format ${backup.formatVersion}). Mettez l'app à jour.",
                )
            }

            // Les sources sont recréées d'abord : tout le reste s'y rattache.
            val newSourceIds = backup.sources.map { sourceDao.insert(it.toEntity()) }

            var restored = 0
            var skipped = 0
            for (entry in backup.ratings) {
                val sourceId = newSourceIds.getOrNull(entry.sourceIndex)
                if (sourceId == null) {
                    skipped++
                    continue
                }
                // Le morceau n'existe pas encore : la bibliothèque n'a pas été
                // indexée. L'appréciation est écrite dès que l'indexation le crée,
                // via son identifiant déterministe.
                val trackId = io.github.micferna.resonate.core.util.TrackIdentity
                    .of(sourceId, entry.remotePath)
                val rating = runCatching { Rating.valueOf(entry.rating) }.getOrDefault(Rating.NEUTRAL)
                val applied = trackDao.restoreUserData(trackId, rating, entry.playCount)
                if (applied > 0) restored++ else skipped++
            }

            val now = System.currentTimeMillis()
            var playlistsAdded = 0
            for (playlist in backup.playlists) {
                val id = playlistDao.insert(
                    PlaylistEntity(
                        name = playlist.name,
                        description = playlist.description,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val trackIds = playlist.tracks.mapNotNull { reference ->
                    val index = reference.substringBefore('|').toIntOrNull() ?: return@mapNotNull null
                    val path = reference.substringAfter('|')
                    newSourceIds.getOrNull(index)?.let { sourceId ->
                        io.github.micferna.resonate.core.util.TrackIdentity.of(sourceId, path)
                    }
                }
                if (trackIds.isNotEmpty()) playlistDao.append(id, trackIds, now)
                playlistsAdded++
            }

            ImportOutcome(
                sourcesAdded = newSourceIds.size,
                playlistsAdded = playlistsAdded,
                ratingsRestored = restored,
                ratingsSkipped = skipped,
            )
        }
    }

    // ------------------------------------------------------------------ conversions

    private fun SourceEntity.toBackup() = SourceBackup(
        kind = kind.name,
        displayName = displayName,
        host = host,
        port = port,
        username = username,
        secret = cipher.open(secretCipher),
        secretKind = secretKind.name,
        keyPassphrase = cipher.open(passphraseCipher),
        rootPath = rootPath,
        shareName = shareName,
        useTls = useTls,
        enabled = enabled,
    )

    private fun SourceBackup.toEntity() = SourceEntity(
        kind = runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.SFTP),
        displayName = displayName,
        host = host,
        port = port,
        username = username,
        secretCipher = cipher.seal(secret),
        secretKind = runCatching { SecretKind.valueOf(secretKind) }.getOrDefault(SecretKind.PASSWORD),
        passphraseCipher = cipher.seal(keyPassphrase),
        rootPath = rootPath,
        shareName = shareName,
        useTls = useTls,
        // La clé d'hôte n'est pas transportée : elle atteste de ce qu'un *appareil*
        // a vu. La réapprendre sur le nouveau téléphone est le comportement correct.
        hostKeyFingerprint = null,
        enabled = enabled,
    )
}
