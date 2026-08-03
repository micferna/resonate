package io.github.micferna.resonate.data.repo

import io.github.micferna.resonate.core.crypto.CredentialCipher
import io.github.micferna.resonate.data.db.dao.SourceDao
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.source.ProbeResult
import io.github.micferna.resonate.source.SourceRegistry
import io.github.micferna.resonate.sync.WorkScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Ce que l'assistant d'ajout de source collecte, avant chiffrement. */
data class SourceDraft(
    val id: Long = 0,
    val kind: SourceKind = SourceKind.SFTP,
    val displayName: String = "",
    val host: String = "",
    val port: Int = SourceKind.SFTP.defaultPort,
    val username: String = "",
    /** `null` signifie « ne pas modifier le secret existant » lors d'une édition. */
    val secret: String? = null,
    val secretKind: SecretKind = SecretKind.PASSWORD,
    val keyPassphrase: String? = null,
    val rootPath: String = "/",
    val shareName: String = "",
    val useTls: Boolean = true,
    val enabled: Boolean = true,
)

@Singleton
class SourceRepository @Inject constructor(
    private val sourceDao: SourceDao,
    private val trackDao: TrackDao,
    private val cipher: CredentialCipher,
    private val registry: SourceRegistry,
    private val workScheduler: WorkScheduler,
) {

    fun observeSources(): Flow<List<SourceEntity>> = sourceDao.observeAll()

    /** Teste une configuration sans l'enregistrer. */
    suspend fun probe(draft: SourceDraft): ProbeResult {
        val entity = draft.toEntity(existing = draft.id.takeIf { it != 0L }?.let { sourceDao.byId(it) })
        val resolved = registry.resolve(entity)
        return registry.connectorFor(entity.kind).probe(resolved)
    }

    /** Crée ou met à jour une source, puis déclenche son indexation. */
    suspend fun save(draft: SourceDraft, scanImmediately: Boolean = true): Long {
        val existing = draft.id.takeIf { it != 0L }?.let { sourceDao.byId(it) }
        val entity = draft.toEntity(existing)

        val id = if (existing == null) {
            sourceDao.insert(entity)
        } else {
            sourceDao.update(entity)
            // Hôte, identifiants ou partage ont pu changer : les connexions ouvertes
            // pointent peut-être ailleurs, ou ne s'authentifieront plus.
            registry.invalidate(entity.id)
            entity.id
        }

        if (scanImmediately && entity.enabled) workScheduler.scanNow(id)
        return id
    }

    suspend fun setEnabled(source: SourceEntity, enabled: Boolean) {
        sourceDao.update(source.copy(enabled = enabled))
        if (!enabled) registry.invalidate(source.id)
    }

    /**
     * Supprime une source et tous ses morceaux.
     *
     * La cascade retire aussi leurs entrées de playlists : garder des lignes
     * pointant vers une bibliothèque disparue produirait des files de lecture
     * qui échouent silencieusement.
     */
    suspend fun delete(source: SourceEntity) {
        registry.invalidate(source.id)
        trackDao.deleteBySource(source.id)
        sourceDao.delete(source)
    }

    /**
     * Oublie la clé d'hôte SSH mémorisée.
     *
     * À n'utiliser qu'après avoir vérifié soi-même que le serveur a légitimement
     * changé de clé — réinstallation, migration. La prochaine connexion refera
     * confiance à ce qu'elle rencontre.
     */
    suspend fun forgetHostKey(source: SourceEntity) {
        sourceDao.update(source.copy(hostKeyFingerprint = null))
        registry.invalidate(source.id)
    }

    fun rescan(sourceId: Long) = workScheduler.scanNow(sourceId)

    fun rescanAll() = workScheduler.scanNow(null)

    /**
     * Convertit le brouillon en entité, en chiffrant les secrets.
     *
     * Un secret laissé à `null` lors d'une édition conserve celui déjà enregistré :
     * l'écran d'édition n'a ainsi jamais besoin de réafficher un mot de passe pour
     * permettre de changer un port.
     */
    private fun SourceDraft.toEntity(existing: SourceEntity?): SourceEntity = SourceEntity(
        id = existing?.id ?: 0,
        kind = kind,
        // Une source locale n'a pas d'hôte : sans ce cas, elle s'afficherait
        // « LOCAL », le nom interne de l'énumération.
        displayName = displayName.trim().ifBlank {
            if (kind.isLocal) "Musique de l'appareil" else host.ifBlank { kind.name }
        },
        host = host.trim(),
        port = port,
        username = username.trim(),
        secretCipher = secret?.let(cipher::seal) ?: existing?.secretCipher,
        secretKind = secretKind,
        passphraseCipher = keyPassphrase?.let(cipher::seal) ?: existing?.passphraseCipher,
        rootPath = rootPath.trim().ifBlank { "/" },
        shareName = shareName.trim().takeIf { it.isNotEmpty() },
        useTls = useTls,
        // L'empreinte mémorisée n'est jamais réinitialisée par une simple édition :
        // seule une demande explicite d'oubli doit rouvrir la fenêtre de confiance.
        hostKeyFingerprint = existing?.hostKeyFingerprint,
        enabled = enabled,
        lastScanAt = existing?.lastScanAt,
        lastScanError = existing?.lastScanError,
        trackCount = existing?.trackCount ?: 0,
    )
}
