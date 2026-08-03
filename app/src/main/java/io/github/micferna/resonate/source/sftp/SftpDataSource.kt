package io.github.micferna.resonate.source.sftp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.micferna.resonate.source.ResolvedSource
import io.github.micferna.resonate.source.ResonateUri
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import java.io.IOException
import java.util.EnumSet

/**
 * Lit un fichier distant en SFTP pour le compte de Media3.
 *
 * SFTP sait lire à un décalage arbitraire (`SSH_FXP_READ` porte l'offset), ce qui
 * permet d'implémenter un accès réellement aléatoire : déplacer le curseur dans un
 * morceau ne retélécharge pas ce qui précède, et l'extracteur peut aller chercher
 * l'en-tête puis la table des matières d'un conteneur sans tout lire.
 */
@OptIn(UnstableApi::class)
class SftpDataSource(
    private val pool: SftpConnectionPool,
    private val resolve: (Long) -> ResolvedSource,
) : BaseDataSource(/* isNetwork = */ true) {

    private var lease: SftpLease? = null
    private var remoteFile: RemoteFile? = null
    private var currentUri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri

        val source = resolve(ResonateUri.sourceIdOf(dataSpec.uri))
        val path = ResonateUri.remotePathOf(dataSpec.uri)

        val acquired = pool.acquire(source)
        lease = acquired

        val file = try {
            acquired.sftp.open(path, EnumSet.of(OpenMode.READ))
        } catch (error: IOException) {
            closeQuietly()
            throw IOException("Ouverture SFTP impossible : $path", error)
        }
        remoteFile = file

        val total = try {
            file.length()
        } catch (error: IOException) {
            closeQuietly()
            throw IOException("Taille illisible pour $path", error)
        }

        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            total - dataSpec.position
        } else {
            dataSpec.length
        }
        if (bytesRemaining < 0 || dataSpec.position > total) {
            closeQuietly()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val file = remoteFile ?: throw IOException("DataSource SFTP non ouvert")
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = file.read(position, buffer, offset, toRead)
        if (read < 0) {
            // Fin de fichier atteinte avant la longueur annoncée : le fichier a été
            // tronqué côté serveur pendant la lecture.
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpen = opened
        opened = false
        closeQuietly()
        currentUri = null
        if (wasOpen) transferEnded()
    }

    private fun closeQuietly() {
        remoteFile?.let { file -> runCatching { file.close() } }
        remoteFile = null
        lease?.close()
        lease = null
    }

    /** Fabrique injectable côté Media3. */
    class Factory(
        private val pool: SftpConnectionPool,
        private val resolve: (Long) -> ResolvedSource,
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource =
            SftpDataSource(pool, resolve)
    }
}
