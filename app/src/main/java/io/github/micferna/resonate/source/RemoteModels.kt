package io.github.micferna.resonate.source

import android.net.Uri
import androidx.core.net.toUri
import io.github.micferna.resonate.data.db.entity.SourceEntity

/**
 * Une source accompagnée de ses secrets déchiffrés.
 *
 * N'existe qu'en mémoire, le temps d'une connexion. Ne jamais journaliser ce type :
 * son `toString()` est volontairement redéfini pour ne rien laisser fuir.
 */
data class ResolvedSource(
    val entity: SourceEntity,
    val password: String?,
    val privateKey: String?,
    val keyPassphrase: String?,
) {
    val id: Long get() = entity.id

    override fun toString(): String =
        "ResolvedSource(id=$id, kind=${entity.kind}, host=${entity.host}, secrets=masqués)"
}

/** Métadonnées fournies directement par la source, sans avoir à ouvrir le fichier. */
data class RemoteMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String = "",
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
)

/** Un fichier audio repéré lors de l'indexation d'une source. */
data class RemoteAudioFile(
    /** Chemin absolu sur la source, ou identifiant opaque pour Subsonic. */
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    /**
     * Métadonnées faisant autorité quand la source en publie (Subsonic).
     * `null` signifie qu'il faudra lire les tags du conteneur.
     */
    val metadata: RemoteMetadata? = null,

    /**
     * Dossier à afficher, quand il ne se déduit pas du chemin.
     *
     * La source locale adresse ses morceaux par identifiant MediaStore, pas par
     * chemin : sans cette indication, tous ses titres se retrouveraient dans un
     * unique dossier « Racine ». Les autres sources laissent ce champ nul, leur
     * chemin portant déjà l'arborescence.
     */
    val folder: String? = null,
)

/** Résultat d'un test de connexion, affiché dans l'assistant d'ajout de source. */
sealed interface ProbeResult {
    /**
     * @param hostKeyFingerprint empreinte SHA-256 de la clé d'hôte SSH découverte,
     *   à faire confirmer par l'utilisateur puis à mémoriser.
     */
    data class Success(
        val message: String,
        val hostKeyFingerprint: String? = null,
    ) : ProbeResult

    data class Failure(val message: String, val cause: Throwable? = null) : ProbeResult
}

/**
 * Adressage interne des morceaux : `resonate://<idSource>/<chemin>`.
 *
 * Faire transiter les identifiants dans l'URI serait commode mais les exposerait
 * dans les journaux de lecture, les clés de cache et l'index des téléchargements.
 * L'URI ne porte donc qu'une référence, que la fabrique de DataSource résout au
 * dernier moment.
 */
object ResonateUri {
    const val SCHEME = "resonate"

    fun build(sourceId: Long, remotePath: String): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(sourceId.toString())
            .path(remotePath.ensureLeadingSlash())
            .build()

    fun of(sourceId: Long, remotePath: String): String = build(sourceId, remotePath).toString()

    fun sourceIdOf(uri: Uri): Long =
        uri.authority?.toLongOrNull()
            ?: throw IllegalArgumentException("URI Resonate sans identifiant de source : $uri")

    /** Chemin distant, déjà décodé par [Uri]. */
    fun remotePathOf(uri: Uri): String = uri.path.orEmpty().ifEmpty { "/" }

    fun isResonate(uri: Uri): Boolean = uri.scheme == SCHEME

    private fun String.ensureLeadingSlash(): String = if (startsWith('/')) this else "/$this"
}
