package io.github.micferna.resonate.update

import io.github.micferna.resonate.BuildConfig
import io.github.micferna.resonate.data.prefs.SettingsStore
import io.github.micferna.resonate.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interroge les Releases GitHub du dépôt public pour savoir si une version plus
 * récente existe.
 *
 * L'API publique ne demande aucun jeton — c'est ce qui permet à l'app d'être
 * distribuée hors magasin sans infrastructure. Elle est en revanche limitée à
 * soixante requêtes par heure et par adresse IP : la vérification est donc
 * périodique et espacée, jamais déclenchée à chaque ouverture de l'app.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val callFactory: Call.Factory,
    private val settingsStore: SettingsStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Version actuellement installée, telle que gravée dans le build. */
    val installedVersion: SemanticVersion =
        SemanticVersion.parseOrNull(BuildConfig.VERSION_NAME) ?: SemanticVersion(0, 0, 0)

    /**
     * Renvoie la mise à jour disponible, ou `null` si l'app est à jour.
     *
     * @param includeDismissed ignore le refus déjà exprimé par l'utilisateur.
     *   L'appel automatique le respecte ; une vérification manuelle depuis les
     *   réglages doit au contraire tout montrer.
     */
    suspend fun check(includeDismissed: Boolean = false): AvailableUpdate? = withContext(ioDispatcher) {
        val settings = settingsStore.settings.first()
        val releases = fetchReleases()

        val candidate = releases
            .asSequence()
            .filterNot { it.draft }
            .filter { settings.includePrereleases || !it.prerelease }
            .mapNotNull { release ->
                val version = SemanticVersion.parseOrNull(release.tagName) ?: return@mapNotNull null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: return@mapNotNull null
                val checksum = release.assets
                    .firstOrNull { it.name.equals("${apk.name}.sha256", ignoreCase = true) }
                AvailableUpdate(
                    version = version,
                    tag = release.tagName,
                    title = release.name.ifBlank { release.tagName },
                    notes = release.body,
                    apkUrl = apk.downloadUrl,
                    apkSizeBytes = apk.size,
                    checksumUrl = checksum?.downloadUrl,
                    releaseUrl = release.htmlUrl,
                    isPrerelease = release.prerelease,
                )
            }
            .filter { it.version > installedVersion }
            .maxByOrNull { it.version }

        settingsStore.setLastUpdateCheckAt(System.currentTimeMillis())

        when {
            candidate == null -> null
            !includeDismissed && candidate.tag == settings.dismissedUpdateTag -> null
            else -> candidate
        }
    }

    private fun fetchReleases(): List<GitHubRelease> {
        val url = "https://api.github.com/repos/" +
            "${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}/releases?per_page=20"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Resonate/${BuildConfig.VERSION_NAME}")
            .build()

        callFactory.newCall(request).execute().use { response ->
            if (response.code == 404) {
                throw IOException("Dépôt de mises à jour introuvable ou privé.")
            }
            if (response.code == 403) {
                throw IOException("Quota de l'API GitHub atteint. Réessayez dans une heure.")
            }
            if (!response.isSuccessful) {
                throw IOException("GitHub a répondu ${response.code}.")
            }
            return GitHubJson.decodeFromString<List<GitHubRelease>>(response.body.string())
        }
    }
}
