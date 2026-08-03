package io.github.micferna.resonate.source.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.di.IoDispatcher
import io.github.micferna.resonate.source.INDEX_BATCH_SIZE
import io.github.micferna.resonate.source.ProbeResult
import io.github.micferna.resonate.source.RemoteAudioFile
import io.github.micferna.resonate.source.RemoteMetadata
import io.github.micferna.resonate.source.ResolvedSource
import io.github.micferna.resonate.source.SourceConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * La musique déjà présente sur le téléphone.
 *
 * Techniquement la plus simple des sources, et pourtant celle qui change le plus la
 * nature de l'app : elle fonctionne sans aucun serveur, sans réseau, immédiatement
 * après l'installation. C'est aussi la seule dont les fichiers sont déjà sur
 * l'appareil — rien à télécharger, rien à mettre en cache.
 *
 * L'indexation passe par `MediaStore`, l'index audio que le système tient à jour tout
 * seul : les tags sont déjà extraits, les pochettes déjà associées, et une chanson
 * copiée depuis un ordinateur apparaît sans que l'app ait à surveiller quoi que ce soit.
 */
@Singleton
class LocalConnector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceConnector {

    override val kind: SourceKind = SourceKind.LOCAL

    /** Autorisation nécessaire pour lire l'audio de l'appareil, selon la version d'Android. */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED

    override suspend fun probe(source: ResolvedSource): ProbeResult = withContext(ioDispatcher) {
        if (!hasPermission()) {
            return@withContext ProbeResult.Failure(
                "Resonate n'a pas encore l'autorisation de lire les fichiers audio de " +
                    "l'appareil. Accordez-la, puis relancez le test.",
            )
        }
        val count = countTracks()
        if (count == 0) {
            ProbeResult.Failure(
                "Aucun fichier audio trouvé sur l'appareil. Copiez de la musique dans " +
                    "le dossier Musique, ou choisissez une autre source.",
            )
        } else {
            ProbeResult.Success("$count morceau(x) détecté(s) sur l'appareil.")
        }
    }

    override suspend fun index(
        source: ResolvedSource,
        onBatch: suspend (List<RemoteAudioFile>) -> Unit,
    ) = withContext(ioDispatcher) {
        if (!hasPermission()) {
            throw SecurityException("Autorisation d'accès aux fichiers audio non accordée.")
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )

        // `IS_MUSIC` écarte sonneries, notifications et sons d'alarme, qui n'ont rien
        // à faire dans une bibliothèque musicale. Le filtre sur la durée évite les
        // fragments d'une seconde que laissent certaines applications.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())

        val batch = ArrayList<RemoteAudioFile>(INDEX_BATCH_SIZE)

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.ALBUM} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()

                val id = cursor.getLong(idColumn)
                val artist = cursor.getString(artistColumn).orUnknown(UNKNOWN_ARTIST)
                val albumArtist = cursor.getString(albumArtistColumn)?.takeIf { it.isNotBlank() } ?: artist
                // MediaStore encode le numéro de piste comme `disque * 1000 + piste`
                // dès qu'un album comporte plusieurs disques.
                val rawTrack = cursor.getInt(trackColumn)
                val discNumber = if (rawTrack > 1000) rawTrack / 1000 else 0
                val trackNumber = if (rawTrack > 1000) rawTrack % 1000 else rawTrack

                batch += RemoteAudioFile(
                    path = "/$id",
                    fileName = cursor.getString(nameColumn).orUnknown("$id"),
                    sizeBytes = cursor.getLong(sizeColumn),
                    metadata = RemoteMetadata(
                        title = cursor.getString(titleColumn).orUnknown("Sans titre"),
                        artist = artist,
                        album = cursor.getString(albumColumn).orUnknown(UNKNOWN_ALBUM),
                        albumArtist = albumArtist,
                        trackNumber = trackNumber,
                        discNumber = discNumber,
                        year = cursor.getInt(yearColumn),
                        genre = "",
                        durationMs = cursor.getLong(durationColumn),
                        artworkUrl = albumArtUri(cursor.getLong(albumIdColumn)),
                    ),
                )

                if (batch.size >= INDEX_BATCH_SIZE) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
        }

        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    override fun createDataSource(source: ResolvedSource): DataSource =
        LocalContentDataSource(context)

    override fun invalidate(sourceId: Long) {
        // Aucune connexion à maintenir : les fichiers sont sur l'appareil.
    }

    private fun countTracks(): Int =
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { it.count } ?: 0

    /**
     * Pochette d'album servie par le système.
     *
     * L'URI est un vestige des premières versions d'Android, mais il reste le seul
     * moyen d'obtenir une pochette d'album sans ouvrir soi-même chaque fichier ; les
     * appareils récents continuent de le servir.
     */
    private fun albumArtUri(albumId: Long): String? =
        albumId.takeIf { it > 0 }
            ?.let { ContentUris.withAppendedId(ALBUM_ART_BASE, it).toString() }

    private fun String?.orUnknown(fallback: String): String =
        this?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback

    private companion object {
        const val UNKNOWN_ARTIST = "Artiste inconnu"
        const val UNKNOWN_ALBUM = "Album inconnu"

        /** Écarte les fragments sonores trop courts pour être de la musique. */
        const val MIN_DURATION_MS = 20_000

        val ALBUM_ART_BASE: android.net.Uri =
            "content://media/external/audio/albumart".toUri()
    }
}
