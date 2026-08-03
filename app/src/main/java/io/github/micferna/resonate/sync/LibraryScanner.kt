package io.github.micferna.resonate.sync

import android.util.Log
import io.github.micferna.resonate.core.util.AudioFile
import io.github.micferna.resonate.core.util.PathMetadata
import io.github.micferna.resonate.core.util.TrackIdentity
import io.github.micferna.resonate.core.util.buildSearchKey
import io.github.micferna.resonate.data.db.dao.SourceDao
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.dao.TrackMetadataPatch
import io.github.micferna.resonate.data.db.dao.TrackSeenPatch
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.source.RemoteAudioFile
import io.github.micferna.resonate.source.RemoteMetadata
import io.github.micferna.resonate.source.SourceRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** Compte rendu d'une indexation, affiché dans l'écran Sources. */
data class ScanOutcome(
    val sourceId: Long,
    val discovered: Int,
    val removed: Int,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null
}

/**
 * Transforme ce qu'un connecteur a trouvé en lignes de bibliothèque.
 *
 * Deux principes guident cette étape.
 *
 * D'abord, une ré-indexation ne doit rien détruire de ce que l'utilisateur a
 * construit : identifiants déterministes, insertion des seuls nouveaux morceaux, mise
 * à jour ciblée des métadonnées. Les likes, compteurs d'écoute, playlists et
 * téléchargements survivent au renommage d'un dossier comme à un changement de serveur.
 *
 * Ensuite, l'indexation ne bloque pas l'app : les lots sont écrits au fil de l'eau,
 * la musique apparaît donc pendant le balayage plutôt qu'à la fin.
 */
@Singleton
class LibraryScanner @Inject constructor(
    private val registry: SourceRegistry,
    private val sourceDao: SourceDao,
    private val trackDao: TrackDao,
) {

    suspend fun scanAll(onProgress: suspend (SourceEntity, Int) -> Unit = { _, _ -> }): List<ScanOutcome> =
        sourceDao.enabled().map { source -> scan(source, onProgress) }

    suspend fun scan(
        source: SourceEntity,
        onProgress: suspend (SourceEntity, Int) -> Unit = { _, _ -> },
    ): ScanOutcome {
        val stamp = System.currentTimeMillis()
        var discovered = 0

        return try {
            val resolved = registry.resolve(source)
            val connector = registry.connectorFor(source.kind)

            connector.index(resolved) { batch ->
                val prepared = batch.prepare(source, stamp)
                trackDao.reconcile(prepared.rows, prepared.metadataPatches, prepared.seenPatches)
                discovered += batch.size
                onProgress(source, discovered)
            }

            // Ce qui n'a pas été revu lors de ce passage n'existe plus sur la source.
            val removed = trackDao.deleteVanished(source.id, stamp)
            sourceDao.recordScanResult(source.id, stamp, error = null)
            ScanOutcome(source.id, discovered, removed)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Indexation interrompue pour ${source.displayName}", error)
            val message = error.message ?: error::class.java.simpleName
            sourceDao.recordScanResult(source.id, stamp, error = message)
            ScanOutcome(source.id, discovered, removed = 0, error = message)
        }
    }

    private class Prepared(
        val rows: MutableList<TrackEntity> = mutableListOf(),
        val metadataPatches: MutableList<TrackMetadataPatch> = mutableListOf(),
        val seenPatches: MutableList<TrackSeenPatch> = mutableListOf(),
    )

    /**
     * Prépare les lignes à insérer et les correctifs à appliquer aux morceaux connus.
     *
     * La distinction entre les deux types de correctifs est essentielle. Une source
     * Subsonic publie des tags faisant autorité : les rafraîchir à chaque passage est
     * exactement le comportement voulu. Pour un fichier sur SFTP, SMB ou WebDAV, en revanche,
     * les métadonnées initiales ne sont qu'une lecture du chemin ; une fois les vrais
     * tags extraits du conteneur, les réécrire à partir du chemin à la ré-indexation
     * suivante effacerait ce travail. Ces morceaux ne voient donc mettre à jour que
     * leur présence et leur taille.
     */
    private fun List<RemoteAudioFile>.prepare(source: SourceEntity, stamp: Long): Prepared {
        val prepared = Prepared()

        for (file in this) {
            val id = TrackIdentity.of(source.id, file.path)
            val authoritative = file.metadata
            val metadata = authoritative ?: PathMetadata.fromPath(file.path, source.rootPath).let {
                RemoteMetadata(
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    albumArtist = it.artist,
                    trackNumber = it.trackNumber,
                )
            }
            val searchKey = buildSearchKey(metadata.title, metadata.artist, metadata.album)
            val mimeType = AudioFile.mimeTypeOf(file.fileName)

            prepared.rows += TrackEntity(
                id = id,
                sourceId = source.id,
                remotePath = file.path,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                albumArtist = metadata.albumArtist,
                trackNumber = metadata.trackNumber,
                discNumber = metadata.discNumber,
                year = metadata.year,
                genre = metadata.genre,
                durationMs = metadata.durationMs,
                sizeBytes = file.sizeBytes,
                mimeType = mimeType,
                artworkUrl = metadata.artworkUrl,
                tagsResolved = authoritative != null,
                // Un fichier de l'appareil est déjà là : le compter comme
                // disponible hors-ligne évite de le présenter comme du streaming
                // et le fait apparaître dans l'onglet correspondant.
                offlineState = if (source.kind.isLocal) {
                    OfflineState.DOWNLOADED
                } else {
                    OfflineState.NONE
                },
                addedAt = stamp,
                lastSeenAt = stamp,
                searchKey = searchKey,
            )

            if (authoritative != null) {
                prepared.metadataPatches += TrackMetadataPatch(
                    id = id,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    albumArtist = metadata.albumArtist,
                    trackNumber = metadata.trackNumber,
                    discNumber = metadata.discNumber,
                    year = metadata.year,
                    genre = metadata.genre,
                    durationMs = metadata.durationMs,
                    sizeBytes = file.sizeBytes,
                    mimeType = mimeType,
                    artworkUrl = metadata.artworkUrl,
                    tagsResolved = true,
                    searchKey = searchKey,
                    lastSeenAt = stamp,
                )
            } else {
                prepared.seenPatches += TrackSeenPatch(
                    id = id,
                    lastSeenAt = stamp,
                    sizeBytes = file.sizeBytes,
                )
            }
        }
        return prepared
    }

    private companion object {
        const val TAG = "LibraryScanner"
    }
}
