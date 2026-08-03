package io.github.micferna.resonate.source.sftp

import androidx.media3.datasource.DataSource
import io.github.micferna.resonate.core.util.AudioFile
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.source.INDEX_BATCH_SIZE
import io.github.micferna.resonate.source.MAX_SCAN_DEPTH
import io.github.micferna.resonate.source.ProbeResult
import io.github.micferna.resonate.source.RemoteAudioFile
import io.github.micferna.resonate.source.ResolvedSource
import io.github.micferna.resonate.source.HostKeyAware
import io.github.micferna.resonate.source.SourceConnector
import io.github.micferna.resonate.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class SftpConnector @Inject constructor(
    private val pool: SftpConnectionPool,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceConnector, HostKeyAware {

    override val kind: SourceKind = SourceKind.SFTP

    /** Relayé au pool, seul endroit où la clé d'hôte est effectivement observée. */
    override var onHostKeyDiscovered: ((sourceId: Long, fingerprint: String) -> Unit)?
        get() = pool.onHostKeyDiscovered
        set(value) { pool.onHostKeyDiscovered = value }

    override suspend fun probe(source: ResolvedSource): ProbeResult = withContext(ioDispatcher) {
        var discoveredFingerprint: String? = null
        val previous = pool.onHostKeyDiscovered
        pool.onHostKeyDiscovered = { _, fingerprint -> discoveredFingerprint = fingerprint }
        try {
            pool.acquire(source).use { lease ->
                val root = source.entity.rootPath.ifBlank { "/" }
                val entries = lease.sftp.ls(root)
                val audioCount = entries.count { it.isRegularFile && AudioFile.isSupported(it.name) }
                ProbeResult.Success(
                    message = "Connecté. ${entries.size} entrée(s) à la racine, " +
                        "dont $audioCount fichier(s) audio directement lisible(s).",
                    hostKeyFingerprint = discoveredFingerprint,
                )
            }
        } catch (error: HostKeyMismatchException) {
            ProbeResult.Failure(error.message.orEmpty(), error)
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            ProbeResult.Failure(error.readableMessage(), error)
        } finally {
            pool.onHostKeyDiscovered = previous
        }
    }

    override suspend fun index(
        source: ResolvedSource,
        onBatch: suspend (List<RemoteAudioFile>) -> Unit,
    ) = withContext(ioDispatcher) {
        pool.acquire(source).use { lease ->
            val batch = ArrayList<RemoteAudioFile>(INDEX_BATCH_SIZE)
            walk(lease.sftp, source.entity.rootPath.ifBlank { "/" }, depth = 0) { file ->
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
        SftpDataSource(pool) { source }

    override fun invalidate(sourceId: Long) = pool.invalidate(sourceId)

    /**
     * Parcours en profondeur, itératif : une bibliothèque profondément imbriquée
     * ferait déborder la pile d'un parcours récursif, et la récursion se marie mal
     * avec la vérification d'annulation à chaque niveau.
     */
    private suspend fun walk(
        sftp: SFTPClient,
        root: String,
        depth: Int,
        emit: suspend (RemoteAudioFile) -> Unit,
    ) {
        val pending = ArrayDeque<Pair<String, Int>>()
        pending += root to depth
        val visited = HashSet<String>()

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (path, currentDepth) = pending.removeFirst()
            if (currentDepth > MAX_SCAN_DEPTH) continue

            // Les liens symboliques peuvent former des cycles ; on canonicalise pour
            // reconnaître un répertoire déjà visité par un autre chemin.
            val canonical = runCatching { sftp.canonicalize(path) }.getOrDefault(path)
            if (!visited.add(canonical)) continue

            val entries = try {
                sftp.ls(path)
            } catch (_: Exception) {
                // Un répertoire illisible (droits insuffisants) ne doit pas interrompre
                // l'indexation du reste de la bibliothèque.
                coroutineContext.ensureActive()
                continue
            }

            for (entry in entries) {
                coroutineContext.ensureActive()
                if (entry.name == "." || entry.name == "..") continue
                when {
                    entry.isDirectory -> pending += entry.path to currentDepth + 1
                    entry.isAudioFile() -> emit(
                        RemoteAudioFile(
                            path = entry.path,
                            fileName = entry.name,
                            sizeBytes = entry.attributes.size,
                        ),
                    )
                }
            }
        }
    }

    private fun RemoteResourceInfo.isAudioFile(): Boolean =
        (isRegularFile || attributes.type == FileMode.Type.SYMLINK) && AudioFile.isSupported(name)
}

/** Message technique traduit en quelque chose d'actionnable dans l'UI. */
internal fun Exception.readableMessage(): String = when {
    this is HostKeyMismatchException -> message.orEmpty()
    message?.contains("Exhausted available authentication methods", ignoreCase = true) == true ->
        "Authentification refusée : vérifiez l'identifiant, le mot de passe ou la clé privée."
    message?.contains("UnresolvedAddress", ignoreCase = true) == true ||
        this is java.net.UnknownHostException ->
        "Hôte introuvable : vérifiez l'adresse du serveur et votre connexion réseau."
    this is java.net.SocketTimeoutException ->
        "Délai d'attente dépassé : le serveur n'a pas répondu."
    this is java.net.ConnectException ->
        "Connexion refusée : vérifiez le port et que le service est démarré."
    else -> message ?: this::class.java.simpleName
}
