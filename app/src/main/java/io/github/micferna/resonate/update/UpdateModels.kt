package io.github.micferna.resonate.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val GitHubJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

/** Une version publiée, plus récente que celle installée. */
data class AvailableUpdate(
    val version: SemanticVersion,
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** URL du fichier d'empreinte accompagnant l'APK, s'il a été publié. */
    val checksumUrl: String?,
    val releaseUrl: String,
    val isPrerelease: Boolean,
)

/** État de l'installation, remonté à l'écran Réglages. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data object Checking : UpdateProgress
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateProgress {
        val fraction: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data object Verifying : UpdateProgress
    data object AwaitingConfirmation : UpdateProgress
    data class Failed(val message: String) : UpdateProgress
}

/**
 * Comparaison de versions au format `MAJEUR.MINEUR.CORRECTIF`, avec suffixe éventuel.
 *
 * L'app compare la version installée au tag de la dernière Release. Un tag mal formé
 * ne doit surtout pas être interprété comme « plus récent » : il est simplement ignoré,
 * pour éviter de proposer en boucle une mise à jour inexistante.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String = "",
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // Selon la convention SemVer, `1.2.0` est postérieure à `1.2.0-beta.1` :
        // l'absence de suffixe désigne la version définitive.
        return when {
            suffix == other.suffix -> 0
            suffix.isEmpty() -> 1
            other.suffix.isEmpty() -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (suffix.isEmpty()) "" else "-$suffix"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+](.+))?$""")

        /** Renvoie `null` si la chaîne n'est pas une version reconnaissable. */
        fun parseOrNull(raw: String): SemanticVersion? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: 0,
                suffix = match.groupValues[4],
            )
        }
    }
}
