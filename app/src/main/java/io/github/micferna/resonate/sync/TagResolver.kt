package io.github.micferna.resonate.sync

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.micferna.resonate.core.util.PathMetadata
import io.github.micferna.resonate.core.util.buildSearchKey
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.dao.TrackTagPatch
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.di.IoDispatcher
import io.github.micferna.resonate.player.toMediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remplace les métadonnées devinées d'après le chemin par les tags réels des fichiers.
 *
 * Media3 n'a besoin que de l'en-tête et de la table des matières du conteneur pour
 * livrer les tags : sur un fichier FLAC de 40 Mo, quelques dizaines de kilo-octets
 * suffisent. C'est ce qui rend l'opération envisageable sur une bibliothèque distante,
 * là où télécharger les fichiers entiers serait hors de question.
 *
 * Le travail est volontairement incrémental et interruptible. Une bibliothèque de
 * 20 000 titres n'est pas traitée d'un bloc : chaque passage en résout un lot, et
 * l'app reste utilisable — avec des noms approximatifs — dès la fin de l'indexation.
 */
@Singleton
@OptIn(UnstableApi::class)
class TagResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:io.github.micferna.resonate.di.PlaybackDataSource
    private val dataSourceFactory: DataSource.Factory,
    private val trackDao: TrackDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Traite jusqu'à [limit] morceaux en attente de lecture de tags.
     *
     * @return le nombre de morceaux **traités**, succès comme échecs. Un fichier
     *   illisible est marqué comme résolu pour ne pas revenir indéfiniment : compter
     *   les seuls succès ferait croire à une file vide alors qu'il reste du travail,
     *   et arrêterait prématurément la tâche périodique.
     */
    suspend fun resolvePending(limit: Int = DEFAULT_BATCH): Int = withContext(ioDispatcher) {
        val pending = trackDao.awaitingTagResolution(limit)
        for (track in pending) {
            currentCoroutineContext().ensureActive()
            resolve(track)
        }
        pending.size
    }

    private suspend fun resolve(track: TrackEntity): Boolean {
        val (metadata, durationUs) = try {
            read(track)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "Tags illisibles pour ${track.remotePath}", error)
            // Le morceau est marqué résolu malgré l'échec : sans cela, un fichier
            // corrompu ou un format exotique serait retenté à chaque passage et
            // bloquerait indéfiniment la file d'attente.
            trackDao.applyTags(track.fallbackPatch())
            return false
        }

        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: track.title
        val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata.albumArtist?.toString()?.takeIf { it.isNotBlank() }
            ?: track.artist
        val album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: track.album
        val albumArtist = metadata.albumArtist?.toString()?.takeIf { it.isNotBlank() } ?: artist

        trackDao.applyTags(
            TrackTagPatch(
                id = track.id,
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                trackNumber = metadata.trackNumber ?: track.trackNumber,
                discNumber = metadata.discNumber ?: track.discNumber,
                year = metadata.recordingYear ?: metadata.releaseYear ?: track.year,
                genre = metadata.genre?.toString().orEmpty().ifBlank { track.genre },
                durationMs = if (durationUs > 0) durationUs / 1000 else track.durationMs,
                tagsResolved = true,
                searchKey = buildSearchKey(title, artist, album),
            ),
        )
        return true
    }

    private suspend fun read(track: TrackEntity): Pair<MediaMetadata, Long> {
        val retriever = MetadataRetriever.Builder(context, track.toMediaItem())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        return retriever.use { reader ->
            val trackGroups = reader.retrieveTrackGroups().await()
            val builder = MediaMetadata.Builder()
            for (groupIndex in 0 until trackGroups.length) {
                val group = trackGroups.get(groupIndex)
                for (formatIndex in 0 until group.length) {
                    group.getFormat(formatIndex).metadata?.let(builder::populateFromMetadata)
                }
            }
            val durationUs = runCatching { reader.retrieveDurationUs().await() }.getOrDefault(0L)
            builder.build() to durationUs
        }
    }

    /** Conserve ce que le chemin laissait deviner, mais cesse de réessayer. */
    private fun TrackEntity.fallbackPatch(): TrackTagPatch {
        val guessed = PathMetadata.fromPath(remotePath)
        return TrackTagPatch(
            id = id,
            title = title.ifBlank { guessed.title },
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre,
            durationMs = durationMs,
            tagsResolved = true,
            searchKey = buildSearchKey(title, artist, album),
        )
    }

    private companion object {
        const val TAG = "TagResolver"
        const val DEFAULT_BATCH = 60
    }
}
