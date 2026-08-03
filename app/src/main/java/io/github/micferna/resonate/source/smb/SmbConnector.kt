package io.github.micferna.resonate.source.smb

import androidx.media3.datasource.DataSource
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.share.DiskShare
import io.github.micferna.resonate.core.util.AudioFile
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.source.INDEX_BATCH_SIZE
import io.github.micferna.resonate.source.MAX_SCAN_DEPTH
import io.github.micferna.resonate.source.ProbeResult
import io.github.micferna.resonate.source.RemoteAudioFile
import io.github.micferna.resonate.source.ResolvedSource
import io.github.micferna.resonate.source.SourceConnector
import io.github.micferna.resonate.source.sftp.readableMessage
import io.github.micferna.resonate.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class SmbConnector @Inject constructor(
    private val pool: SmbConnectionPool,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceConnector {

    override val kind: SourceKind = SourceKind.SMB

    override suspend fun probe(source: ResolvedSource): ProbeResult = withContext(ioDispatcher) {
        try {
            pool.acquire(source).use { lease ->
                val root = source.entity.rootPath.toSmbPath()
                val entries = lease.share.list(root)
                val audioCount = entries.count { !it.isDirectory() && AudioFile.isSupported(it.fileName) }
                ProbeResult.Success(
                    "Partage « ${source.entity.shareName} » accessible. " +
                        "${entries.size} entrée(s) à la racine, dont $audioCount fichier(s) audio.",
                )
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
        pool.acquire(source).use { lease ->
            val batch = ArrayList<RemoteAudioFile>(INDEX_BATCH_SIZE)
            walk(lease.share, source.entity.rootPath.toSmbPath()) { file ->
                batch += file
                if (batch.size >= INDEX_BATCH_SIZE) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList())
        }
    }

    override fun createDataSource(source: ResolvedSource): DataSource =
        SmbDataSource(pool) { source }

    override fun invalidate(sourceId: Long) = pool.invalidate(sourceId)

    private suspend fun walk(
        share: DiskShare,
        root: String,
        emit: suspend (RemoteAudioFile) -> Unit,
    ) {
        val pending = ArrayDeque<Pair<String, Int>>()
        pending += root to 0

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (path, depth) = pending.removeFirst()
            if (depth > MAX_SCAN_DEPTH) continue

            val entries = try {
                share.list(path)
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                continue
            }

            for (entry in entries) {
                coroutineContext.ensureActive()
                val name = entry.fileName
                if (name == "." || name == "..") continue
                val childPath = if (path.isEmpty()) name else "$path\\$name"
                when {
                    entry.isDirectory() -> pending += childPath to depth + 1
                    AudioFile.isSupported(name) -> emit(
                        RemoteAudioFile(
                            // Les chemins sont normalisés en `/` : ils traversent la
                            // base et les URI, où l'antislash devrait être échappé.
                            path = "/" + childPath.replace('\\', '/'),
                            fileName = name,
                            sizeBytes = entry.endOfFile,
                        ),
                    )
                }
            }
        }
    }

    private fun FileIdBothDirectoryInformation.isDirectory(): Boolean =
        fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
}
