package io.github.micferna.resonate.source.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.micferna.resonate.source.ResonateUri
import java.io.IOException

/**
 * Lit un fichier audio de l'appareil, désigné par son identifiant `MediaStore`.
 *
 * La bibliothèque ne stocke pas de chemin de fichier mais un identifiant : un morceau
 * déplacé d'un dossier à l'autre, ou passé de la mémoire interne à la carte SD, reste
 * le même morceau — avec ses likes, ses compteurs et sa place dans les playlists.
 * Les chemins bruts, eux, ne survivent ni au déplacement ni au cloisonnement du
 * stockage imposé par Android.
 */
@OptIn(UnstableApi::class)
class LocalContentDataSource(context: Context) : DataSource {

    private val delegate = ContentDataSource(context)

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val mediaStoreId = ResonateUri.remotePathOf(dataSpec.uri).trim('/').toLongOrNull()
            ?: throw IOException("Identifiant MediaStore illisible : ${dataSpec.uri}")
        val contentUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            mediaStoreId,
        )
        return delegate.open(dataSpec.buildUpon().setUri(contentUri).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun close() = delegate.close()
}
