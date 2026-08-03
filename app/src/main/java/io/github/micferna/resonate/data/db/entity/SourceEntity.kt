package io.github.micferna.resonate.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Protocole utilisé pour joindre une bibliothèque distante. */
enum class SourceKind {
    SFTP,
    SMB,
    WEBDAV,
    SUBSONIC,
    ;

    val defaultPort: Int
        get() = when (this) {
            SFTP -> 22
            SMB -> 445
            WEBDAV, SUBSONIC -> 443
        }

    /** Un partage SMB doit être nommé ; les autres protocoles n'en ont pas. */
    val requiresShare: Boolean get() = this == SMB

    /** Ces protocoles indexent via HTTP(S) et acceptent donc un basculement TLS. */
    val supportsTls: Boolean get() = this == WEBDAV || this == SUBSONIC
}

/** Nature du secret stocké pour une source. */
enum class SecretKind {
    /** Mot de passe (ou jeton d'API pour Subsonic). */
    PASSWORD,

    /** Clé privée OpenSSH, éventuellement protégée par une phrase de passe. */
    SSH_PRIVATE_KEY,
}

/**
 * Une bibliothèque distante configurée par l'utilisateur.
 *
 * Les secrets ne sont jamais stockés en clair : [secretCipher] contient le résultat de
 * [io.github.micferna.resonate.core.crypto.CredentialCipher], scellé par une clé qui ne
 * quitte jamais le KeyStore matériel de l'appareil.
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val kind: SourceKind,

    /** Nom affiché dans l'UI. */
    val displayName: String,

    val host: String,
    val port: Int,
    val username: String,

    /** Secret chiffré, ou `null` pour un accès anonyme. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val secretCipher: ByteArray?,

    val secretKind: SecretKind = SecretKind.PASSWORD,

    /** Phrase de passe chiffrée de la clé privée, si [secretKind] vaut SSH_PRIVATE_KEY. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val passphraseCipher: ByteArray? = null,

    /** Racine à explorer. Pour SMB, chemin *à l'intérieur* du partage. */
    val rootPath: String = "/",

    /** Nom du partage SMB (`\\serveur\<share>`). Inutilisé pour les autres protocoles. */
    val shareName: String? = null,

    /** HTTPS pour WebDAV/Subsonic. */
    val useTls: Boolean = true,

    /**
     * Empreinte SHA-256 de la clé d'hôte SSH, mémorisée à la première connexion (TOFU).
     * Toute connexion ultérieure présentant une autre clé est refusée.
     */
    val hostKeyFingerprint: String? = null,

    val enabled: Boolean = true,
    val lastScanAt: Long? = null,
    val lastScanError: String? = null,
    val trackCount: Int = 0,
) {
    /** Base HTTP(S) pour les sources WebDAV/Subsonic, sans slash final. */
    val httpBaseUrl: String
        get() {
            val scheme = if (useTls) "https" else "http"
            val defaultPort = if (useTls) 443 else 80
            val authority = if (port == defaultPort) host else "$host:$port"
            val path = rootPath.trim('/')
            return if (path.isEmpty()) "$scheme://$authority" else "$scheme://$authority/$path"
        }

    // Room génère `equals`/`hashCode` à partir de tous les champs ; les tableaux d'octets
    // se compareraient alors par référence, ce qui rendrait deux lectures d'une même ligne
    // « différentes ». On les compare donc par contenu.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceEntity) return false
        return id == other.id &&
            kind == other.kind &&
            displayName == other.displayName &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            secretCipher.contentEquals(other.secretCipher) &&
            secretKind == other.secretKind &&
            passphraseCipher.contentEquals(other.passphraseCipher) &&
            rootPath == other.rootPath &&
            shareName == other.shareName &&
            useTls == other.useTls &&
            hostKeyFingerprint == other.hostKeyFingerprint &&
            enabled == other.enabled &&
            lastScanAt == other.lastScanAt &&
            lastScanError == other.lastScanError &&
            trackCount == other.trackCount
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + secretCipher.contentHashCode()
        result = 31 * result + secretKind.hashCode()
        result = 31 * result + passphraseCipher.contentHashCode()
        result = 31 * result + rootPath.hashCode()
        result = 31 * result + (shareName?.hashCode() ?: 0)
        result = 31 * result + useTls.hashCode()
        result = 31 * result + (hostKeyFingerprint?.hashCode() ?: 0)
        result = 31 * result + enabled.hashCode()
        result = 31 * result + (lastScanAt?.hashCode() ?: 0)
        result = 31 * result + (lastScanError?.hashCode() ?: 0)
        result = 31 * result + trackCount
        return result
    }
}
