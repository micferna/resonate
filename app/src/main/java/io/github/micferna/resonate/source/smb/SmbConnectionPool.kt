package io.github.micferna.resonate.source.smb

import android.util.Log
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.micferna.resonate.source.ResolvedSource
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Un partage SMB emprunté au pool. Doit être refermé. */
class SmbLease internal constructor(
    val share: DiskShare,
    private val onClose: () -> Unit,
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

/**
 * Maintient une session SMB par source et partage le même `DiskShare` entre lecteurs.
 *
 * Une connexion SMB négocie dialecte, signature et chiffrement, puis authentifie la
 * session : la refaire à chaque morceau introduirait un blanc audible. SMB2 multiplexe
 * les requêtes sur une même connexion, plusieurs fichiers peuvent donc être lus
 * simultanément à travers un unique partage.
 */
@Singleton
class SmbConnectionPool @Inject constructor() {

    private class Holder(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    ) {
        var leases: Int = 0
    }

    private val lock = ReentrantLock()
    private val holders = HashMap<Long, Holder>()

    @Throws(IOException::class)
    fun acquire(source: ResolvedSource): SmbLease {
        val holder = lock.withLock {
            val existing = holders[source.id]
            if (existing != null && existing.connection.isConnected && existing.share.isConnected) {
                existing
            } else {
                existing?.let { closeQuietly(it) }
                holders.remove(source.id)
                null
            }
        } ?: connectAndRegister(source)

        lock.withLock { holder.leases++ }
        return SmbLease(holder.share) { release(source.id) }
    }

    fun invalidate(sourceId: Long) {
        val holder = lock.withLock { holders.remove(sourceId) } ?: return
        closeQuietly(holder)
    }

    fun invalidateAll() {
        val all = lock.withLock { holders.values.toList().also { holders.clear() } }
        all.forEach { closeQuietly(it) }
    }

    private fun release(sourceId: Long) {
        val toClose = lock.withLock {
            val holder = holders[sourceId] ?: return@withLock null
            holder.leases--
            if (holder.leases <= 0) {
                holders.remove(sourceId)
                holder
            } else {
                null
            }
        }
        toClose?.let { closeQuietly(it) }
    }

    private fun connectAndRegister(source: ResolvedSource): Holder {
        val fresh = connect(source)
        return lock.withLock {
            val racing = holders[source.id]
            if (racing != null && racing.connection.isConnected) {
                closeQuietly(fresh)
                racing
            } else {
                holders[source.id] = fresh
                fresh
            }
        }
    }

    @Throws(IOException::class)
    private fun connect(source: ResolvedSource): Holder {
        val entity = source.entity
        val shareName = entity.shareName?.trim()?.trim('/', '\\')
        if (shareName.isNullOrEmpty()) {
            throw IOException("Aucun partage renseigné pour ${entity.displayName}.")
        }

        val config = SmbConfig.builder()
            .withTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SOCKET_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            // Le chiffrement SMB3 est négocié quand le serveur le propose ; la
            // signature reste activée pour empêcher l'altération des réponses.
            .withEncryptData(false)
            .withSigningEnabled(true)
            .withDfsEnabled(true)
            .build()

        val client = SMBClient(config)
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = client.connect(entity.host, entity.port)
            // `utilisateur@domaine` ou `DOMAINE\utilisateur` sont les deux écritures
            // que les utilisateurs saisissent naturellement.
            val (user, domain) = splitDomain(entity.username)
            val auth = if (user.isEmpty() && source.password.isNullOrEmpty()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(user, source.password.orEmpty().toCharArray(), domain)
            }
            session = connection.authenticate(auth)
            val share = session.connectShare(shareName) as? DiskShare
                ?: throw IOException("« $shareName » n'est pas un partage de fichiers.")
            return Holder(client, connection, session, share)
        } catch (error: Throwable) {
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            throw error
        }
    }

    private fun splitDomain(raw: String): Pair<String, String?> = when {
        '\\' in raw -> raw.substringAfter('\\') to raw.substringBefore('\\').ifBlank { null }
        '@' in raw -> raw.substringBefore('@') to raw.substringAfter('@').ifBlank { null }
        else -> raw to null
    }

    private fun closeQuietly(holder: Holder) {
        runCatching { holder.share.close() }
            .onFailure { Log.d(TAG, "Fermeture du partage sans incidence", it) }
        runCatching { holder.session.close() }
        runCatching { holder.connection.close() }
        runCatching { holder.client.close() }
    }

    private companion object {
        const val TAG = "SmbPool"
        const val SOCKET_TIMEOUT_SECONDS = 30L
    }
}

/** SMB adresse ses chemins avec des antislashs, relativement à la racine du partage. */
internal fun String.toSmbPath(): String =
    trim().replace('/', '\\').trim('\\')
