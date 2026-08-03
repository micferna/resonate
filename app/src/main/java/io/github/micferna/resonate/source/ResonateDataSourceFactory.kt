package io.github.micferna.resonate.source

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Aiguille chaque URI `resonate://` vers le connecteur de sa source.
 *
 * Tout ce qui est en amont — file de lecture, cache, téléchargements, extraction de
 * tags — ne manipule qu'un seul type d'URI et ignore complètement qu'il y a derrière
 * du SFTP, du SMB ou du HTTP. Ajouter un protocole revient à écrire un
 * [SourceConnector] et à l'enregistrer : rien d'autre dans l'app ne change.
 */
@OptIn(UnstableApi::class)
class ResonateDataSourceFactory(
    private val registry: SourceRegistry,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutingDataSource(registry)
}

@OptIn(UnstableApi::class)
private class RoutingDataSource(
    private val registry: SourceRegistry,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!ResonateUri.isResonate(dataSpec.uri)) {
            throw IOException("URI non gérée par Resonate : ${dataSpec.uri}")
        }
        val source = registry.require(ResonateUri.sourceIdOf(dataSpec.uri))
        val opened = registry.connectorFor(source).createDataSource(source)
        listeners.forEach(opened::addTransferListener)
        delegate = opened
        return try {
            opened.open(dataSpec)
        } catch (error: Throwable) {
            runCatching { opened.close() }
            delegate = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (delegate ?: throw IOException("DataSource non ouvert")).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}
