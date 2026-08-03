package io.github.micferna.resonate.source.sftp

import android.util.Log
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.source.ResolvedSource
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Empreinte de clé d'hôte au format OpenSSH : `SHA256:<base64 sans remplissage>`. */
internal fun fingerprintOf(key: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** Levée quand le serveur présente une clé d'hôte différente de celle mémorisée. */
class HostKeyMismatchException(
    val expected: String,
    val actual: String,
) : IOException(
    "La clé d'hôte SSH a changé. Attendue $expected, reçue $actual. " +
        "Connexion refusée : le serveur a peut-être été réinstallé, ou la connexion est interceptée.",
)

/**
 * Vérificateur « confiance à la première utilisation ».
 *
 * Tant qu'aucune empreinte n'est mémorisée, la première clé rencontrée est acceptée et
 * transmise à [onFirstContact] pour être enregistrée. Ensuite, toute clé différente fait
 * échouer la connexion. C'est le modèle d'OpenSSH, et il est indispensable : accepter
 * n'importe quelle clé rendrait le mot de passe interceptable par un serveur usurpé.
 */
private class TofuHostKeyVerifier(
    private val expectedFingerprint: String?,
    private val onFirstContact: (String) -> Unit,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val actual = fingerprintOf(key)
        val expected = expectedFingerprint
        if (expected.isNullOrBlank()) {
            onFirstContact(actual)
            return true
        }
        if (expected != actual) throw HostKeyMismatchException(expected, actual)
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/**
 * Une session SFTP empruntée au pool. Doit être refermée, y compris en cas d'erreur.
 */
class SftpLease internal constructor(
    val sftp: SFTPClient,
    private val onClose: () -> Unit,
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { sftp.close() }
        onClose()
    }
}

/**
 * Maintient une connexion SSH par source, et ouvre un canal SFTP par emprunteur.
 *
 * Rétablir une session SSH complète coûte plusieurs allers-retours et une négociation
 * cryptographique — inacceptable entre deux morceaux. SSH sait en revanche multiplexer
 * plusieurs canaux sur une même connexion : lecture, indexation et téléchargement
 * hors-ligne peuvent donc progresser en parallèle sans se gêner ni payer la poignée
 * de main à chaque fois.
 *
 * La connexion sous-jacente est comptée par références et fermée dès que le dernier
 * canal se referme, pour ne pas laisser une socket ouverte en arrière-plan.
 */
@Singleton
class SftpConnectionPool @Inject constructor() {

    /** Notifié quand une empreinte de clé d'hôte est découverte pour la première fois. */
    var onHostKeyDiscovered: ((sourceId: Long, fingerprint: String) -> Unit)? = null

    private class Holder(val client: SSHClient) {
        var leases: Int = 0
    }

    private val lock = ReentrantLock()
    private val holders = HashMap<Long, Holder>()

    /**
     * Emprunte un canal SFTP, en réutilisant la connexion SSH existante si elle est
     * toujours vivante. Bloquant : appelé depuis les threads d'E/S de Media3.
     */
    @Throws(IOException::class)
    fun acquire(source: ResolvedSource): SftpLease {
        val holder = lock.withLock {
            val existing = holders[source.id]
            if (existing != null && existing.client.isConnected && existing.client.isAuthenticated) {
                existing
            } else {
                existing?.let { closeQuietly(it.client) }
                holders.remove(source.id)
                null
            }
        } ?: connectAndRegister(source)

        return try {
            val sftp = holder.client.newSFTPClient()
            lock.withLock { holder.leases++ }
            SftpLease(sftp) { release(source.id) }
        } catch (error: IOException) {
            // Le canal n'a pas pu s'ouvrir : la connexion est probablement morte
            // entre la vérification et maintenant. On la jette pour que le prochain
            // emprunt reparte sur une base saine.
            lock.withLock {
                if (holders[source.id] === holder && holder.leases == 0) {
                    holders.remove(source.id)
                    closeQuietly(holder.client)
                }
            }
            throw error
        }
    }

    /** Ferme la connexion d'une source (identifiants modifiés, source supprimée). */
    fun invalidate(sourceId: Long) {
        val holder = lock.withLock { holders.remove(sourceId) } ?: return
        closeQuietly(holder.client)
    }

    fun invalidateAll() {
        val all = lock.withLock { holders.values.toList().also { holders.clear() } }
        all.forEach { closeQuietly(it.client) }
    }

    private fun release(sourceId: Long) {
        val toClose = lock.withLock {
            val holder = holders[sourceId] ?: return@withLock null
            holder.leases--
            if (holder.leases <= 0) {
                holders.remove(sourceId)
                holder.client
            } else {
                null
            }
        }
        toClose?.let { closeQuietly(it) }
    }

    private fun connectAndRegister(source: ResolvedSource): Holder {
        val client = connect(source)
        return lock.withLock {
            // Une connexion concurrente a pu aboutir pendant la poignée de main :
            // on garde la première enregistrée et on referme la nôtre.
            val racing = holders[source.id]
            if (racing != null && racing.client.isConnected) {
                closeQuietly(client)
                racing
            } else {
                Holder(client).also { holders[source.id] = it }
            }
        }
    }

    @Throws(IOException::class)
    private fun connect(source: ResolvedSource): SSHClient {
        val entity = source.entity
        val client = SSHClient(DefaultConfig())
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = READ_TIMEOUT_MS
        client.addHostKeyVerifier(
            TofuHostKeyVerifier(entity.hostKeyFingerprint) { fingerprint ->
                onHostKeyDiscovered?.invoke(entity.id, fingerprint)
            },
        )

        try {
            client.connect(entity.host, entity.port)
            client.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_SECONDS
            authenticate(client, source)
        } catch (error: Throwable) {
            closeQuietly(client)
            throw error
        }
        return client
    }

    private fun authenticate(client: SSHClient, source: ResolvedSource) {
        val entity = source.entity
        when (entity.secretKind) {
            SecretKind.SSH_PRIVATE_KEY -> {
                val privateKey = source.privateKey
                    ?: throw IOException("Clé privée absente ou illisible pour ${entity.displayName}.")
                val finder = source.keyPassphrase
                    ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                val provider = client.loadKeys(privateKey, null, finder)
                client.authPublickey(entity.username, provider)
            }

            SecretKind.PASSWORD -> {
                val password = source.password
                    ?: throw IOException("Mot de passe absent ou illisible pour ${entity.displayName}.")
                client.authPassword(entity.username, password)
            }
        }
    }

    private fun closeQuietly(client: SSHClient) {
        runCatching { client.disconnect() }
            .onFailure { Log.d(TAG, "Fermeture SSH sans incidence", it) }
    }

    private companion object {
        const val TAG = "SftpPool"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        // Un paquet par minute suffit à traverser les NAT domestiques (délai
        // d'expiration typique : plusieurs minutes) tout en divisant par deux les
        // réveils de la radio pendant une lecture longue.
        const val KEEP_ALIVE_SECONDS = 60
    }
}
