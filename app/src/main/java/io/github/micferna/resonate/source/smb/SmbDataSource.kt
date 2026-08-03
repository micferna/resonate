package io.github.micferna.resonate.source.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import io.github.micferna.resonate.source.ResolvedSource
import io.github.micferna.resonate.source.ResonateUri
import java.io.IOException
import java.util.EnumSet
import com.hierynomus.smbj.share.File as SmbFile

/**
 * Lit un fichier d'un partage SMB pour le compte de Media3.
 *
 * `SMB2 READ` porte le décalage dans la requête : comme en SFTP, on obtient un accès
 * aléatoire natif, sans avoir à relire depuis le début pour se déplacer dans un morceau.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(
    private val pool: SmbConnectionPool,
    private val resolve: (Long) -> ResolvedSource,
) : BaseDataSource(/* isNetwork = */ true) {

    private var lease: SmbLease? = null
    private var file: SmbFile? = null
    private var currentUri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri

        val source = resolve(ResonateUri.sourceIdOf(dataSpec.uri))
        val path = ResonateUri.remotePathOf(dataSpec.uri).toSmbPath()

        val acquired = pool.acquire(source)
        lease = acquired

        val opened0 = try {
            acquired.share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (error: Exception) {
            closeQuietly()
            throw IOException("Ouverture SMB impossible : $path", error)
        }
        file = opened0

        val total = try {
            opened0.fileInformation.standardInformation.endOfFile
        } catch (error: Exception) {
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

        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val handle = file ?: throw IOException("DataSource SMB non ouvert")
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = try {
            handle.read(buffer, position, offset, toRead)
        } catch (error: Exception) {
            throw IOException("Lecture SMB interrompue", error)
        }
        if (read < 0) {
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
        file?.let { handle -> runCatching { handle.close() } }
        file = null
        lease?.close()
        lease = null
    }

    class Factory(
        private val pool: SmbConnectionPool,
        private val resolve: (Long) -> ResolvedSource,
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource =
            SmbDataSource(pool, resolve)
    }
}
