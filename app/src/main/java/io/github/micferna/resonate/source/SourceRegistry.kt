package io.github.micferna.resonate.source

import android.util.Log
import io.github.micferna.resonate.core.crypto.CredentialCipher
import io.github.micferna.resonate.data.db.dao.SourceDao
import io.github.micferna.resonate.di.ApplicationScope
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Levée quand une URI référence une source disparue ou désactivée. */
class UnknownSourceException(sourceId: Long) :
    IllegalStateException("Source $sourceId introuvable : elle a probablement été supprimée.")

/**
 * Point d'accès unique aux sources déchiffrées et à leur connecteur.
 *
 * Les threads de lecture de Media3 ont besoin d'obtenir une source *sans suspendre* :
 * une requête en base au milieu d'un `open()` bloquerait le décodage. Le registre tient
 * donc un instantané en mémoire, tenu à jour par observation de la table, et l'expose
 * de façon synchrone.
 *
 * Ce cache contient des secrets en clair : il n'est jamais sérialisé ni journalisé, et
 * disparaît avec le processus.
 */
@Singleton
class SourceRegistry @Inject constructor(
    private val sourceDao: SourceDao,
    private val cipher: CredentialCipher,
    private val connectors: Set<@JvmSuppressWildcards SourceConnector>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    @Volatile
    private var snapshot: Map<Long, ResolvedSource> = emptyMap()

    private val connectorByKind: Map<SourceKind, SourceConnector> =
        connectors.associateBy { it.kind }

    init {
        sourceDao.observeAll()
            .onEach { entities -> snapshot = entities.associate { it.id to resolve(it) } }
            .launchIn(scope)

        // Une clé d'hôte découverte doit être mémorisée pour que les connexions
        // suivantes puissent détecter un changement. On l'écrit hors du chemin de
        // connexion, qui est bloquant.
        connectors.filterIsInstance<HostKeyAware>().forEach { aware ->
            aware.onHostKeyDiscovered = { sourceId, fingerprint ->
                scope.launch {
                    runCatching { sourceDao.rememberHostKey(sourceId, fingerprint) }
                        .onFailure { Log.w(TAG, "Empreinte de clé d'hôte non enregistrée", it) }
                }
            }
        }
    }

    /** Instantané synchrone, sans accès disque. */
    fun find(sourceId: Long): ResolvedSource? = snapshot[sourceId]

    fun require(sourceId: Long): ResolvedSource = find(sourceId) ?: throw UnknownSourceException(sourceId)

    fun connectorFor(kind: SourceKind): SourceConnector =
        connectorByKind[kind] ?: error("Aucun connecteur enregistré pour $kind")

    fun connectorFor(source: ResolvedSource): SourceConnector = connectorFor(source.entity.kind)

    /** Déchiffre les secrets d'une entité, y compris avant son enregistrement en base. */
    fun resolve(entity: SourceEntity): ResolvedSource {
        val secret = cipher.open(entity.secretCipher)
        // Le même champ chiffré porte soit un mot de passe, soit une clé privée.
        // On l'expose dans le seul rôle déclaré, pour qu'une clé ne puisse jamais
        // partir comme mot de passe sur le réseau.
        return ResolvedSource(
            entity = entity,
            password = secret.takeIf { entity.secretKind == SecretKind.PASSWORD },
            privateKey = secret.takeIf { entity.secretKind == SecretKind.SSH_PRIVATE_KEY },
            keyPassphrase = cipher.open(entity.passphraseCipher),
        )
    }

    /** Coupe les connexions maintenues pour une source modifiée ou supprimée. */
    fun invalidate(sourceId: Long) {
        connectors.forEach { it.invalidate(sourceId) }
    }

    private companion object {
        const val TAG = "SourceRegistry"
    }
}

/**
 * Implémenté par les connecteurs qui découvrent une identité de serveur à mémoriser.
 * Seul SSH en a besoin : HTTPS s'appuie sur les autorités de certification du système,
 * et SMB sur la signature de session.
 */
interface HostKeyAware {
    var onHostKeyDiscovered: ((sourceId: Long, fingerprint: String) -> Unit)?
}
