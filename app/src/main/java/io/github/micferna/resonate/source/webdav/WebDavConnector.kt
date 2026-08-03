package io.github.micferna.resonate.source.webdav

import android.util.Xml
import androidx.media3.datasource.DataSource
import io.github.micferna.resonate.core.util.AudioFile
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.source.HttpRemoteDataSource
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
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Une entrée renvoyée par un PROPFIND. */
private data class DavEntry(
    val href: String,
    val displayName: String,
    val isCollection: Boolean,
    val contentLength: Long,
)

/**
 * Explore une arborescence WebDAV (Nextcloud, ownCloud, Seafile, Apache mod_dav…).
 *
 * L'indexation passe par des requêtes `PROPFIND` de profondeur 1, répétées dossier par
 * dossier : une profondeur infinie serait plus rapide mais la plupart des serveurs la
 * refusent, et sur une grosse bibliothèque elle produirait une réponse XML de plusieurs
 * dizaines de mégaoctets à garder en mémoire.
 *
 * La lecture, elle, est du HTTP GET ordinaire avec en-tête `Range` : c'est le protocole
 * le mieux servi par les caches et les proxys.
 */
@Singleton
class WebDavConnector @Inject constructor(
    private val callFactory: Call.Factory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceConnector {

    override val kind: SourceKind = SourceKind.WEBDAV

    override suspend fun probe(source: ResolvedSource): ProbeResult = withContext(ioDispatcher) {
        try {
            val entries = propfind(source, source.entity.httpBaseUrl)
            val audioCount = entries.count { !it.isCollection && AudioFile.isSupported(it.displayName) }
            val folderCount = entries.count { it.isCollection }
            ProbeResult.Success(
                "Serveur WebDAV joignable. $folderCount dossier(s) et $audioCount fichier(s) audio à la racine.",
            )
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            ProbeResult.Failure(error.readableMessage(), error)
        }
    }

    override suspend fun index(
        source: ResolvedSource,
        onBatch: suspend (List<RemoteAudioFile>) -> Unit,
    ) = withContext(ioDispatcher) {
        val base = source.entity.httpBaseUrl
        val batch = ArrayList<RemoteAudioFile>(INDEX_BATCH_SIZE)
        val pending = ArrayDeque<Pair<String, Int>>()
        val visited = HashSet<String>()
        pending += base to 0

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (url, depth) = pending.removeFirst()
            if (depth > MAX_SCAN_DEPTH || !visited.add(url)) continue

            val entries = try {
                propfind(source, url)
            } catch (_: IOException) {
                coroutineContext.ensureActive()
                continue
            }

            for (entry in entries) {
                coroutineContext.ensureActive()
                val absolute = absoluteUrl(source, entry.href)
                if (absolute.trimEnd('/') == url.trimEnd('/')) continue // le dossier lui-même
                when {
                    entry.isCollection -> pending += absolute to depth + 1
                    AudioFile.isSupported(entry.displayName) -> {
                        batch += RemoteAudioFile(
                            path = relativePath(source, absolute),
                            fileName = entry.displayName,
                            sizeBytes = entry.contentLength,
                        )
                        if (batch.size >= INDEX_BATCH_SIZE) {
                            onBatch(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    override fun createDataSource(source: ResolvedSource): DataSource =
        HttpRemoteDataSource(
            callFactory = callFactory,
            resolve = { source },
            toHttpUrl = { resolved, path -> resolved.entity.httpBaseUrl + encodePath(path) },
            headersFor = ::authHeaders,
        )

    override fun invalidate(sourceId: Long) {
        // Rien à libérer : OkHttp gère lui-même son pool de connexions et le recycle.
    }

    // ------------------------------------------------------------------ interne

    private fun propfind(source: ResolvedSource, url: String): List<DavEntry> {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { authHeaders(source).forEach { (name, value) -> header(name, value) } }
            .build()

        callFactory.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw IOException("Authentification refusée par le serveur WebDAV (code ${response.code}).")
            }
            if (!response.isSuccessful && response.code != MULTI_STATUS) {
                throw IOException("Le serveur WebDAV a répondu ${response.code}.")
            }
            return parseMultiStatus(response.body.byteStream())
        }
    }

    /**
     * Analyse la réponse `multistatus`.
     *
     * Les serveurs WebDAV ne s'accordent pas sur le préfixe de namespace — `D:`, `d:`,
     * `lp1:`… — on ne compare donc que les noms locaux, comme le font les clients qui
     * interopèrent réellement.
     */
    private fun parseMultiStatus(stream: java.io.InputStream): List<DavEntry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, null)
        }

        val entries = mutableListOf<DavEntry>()
        var href: String? = null
        var displayName: String? = null
        var isCollection = false
        var contentLength = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        href = null; displayName = null; isCollection = false; contentLength = 0
                    }
                    "href" -> href = parser.nextText().trim()
                    "displayname" -> displayName = parser.nextText().trim()
                    "collection" -> isCollection = true
                    "getcontentlength" -> contentLength = parser.nextText().trim().toLongOrNull() ?: 0
                }

                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "response") {
                    val resolvedHref = href
                    if (!resolvedHref.isNullOrEmpty()) {
                        entries += DavEntry(
                            href = resolvedHref,
                            displayName = displayName?.takeIf { it.isNotEmpty() }
                                ?: resolvedHref.trimEnd('/').substringAfterLast('/').decodeSegment(),
                            isCollection = isCollection,
                            contentLength = contentLength,
                        )
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun authHeaders(source: ResolvedSource): Map<String, String> {
        val user = source.entity.username
        val password = source.password
        if (user.isBlank() && password.isNullOrBlank()) return emptyMap()
        return mapOf("Authorization" to Credentials.basic(user, password.orEmpty()))
    }

    /** Un `href` peut être absolu ou relatif à la racine du serveur. */
    private fun absoluteUrl(source: ResolvedSource, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val entity = source.entity
        val scheme = if (entity.useTls) "https" else "http"
        val defaultPort = if (entity.useTls) 443 else 80
        val authority = if (entity.port == defaultPort) entity.host else "${entity.host}:${entity.port}"
        return "$scheme://$authority" + if (href.startsWith('/')) href else "/$href"
    }

    /** Chemin relatif à la racine configurée, décodé et normalisé avec des `/`. */
    private fun relativePath(source: ResolvedSource, absoluteUrl: String): String {
        val base = source.entity.httpBaseUrl
        val raw = absoluteUrl.removePrefix(base)
        return "/" + raw.trim('/').split('/').joinToString("/") { it.decodeSegment() }
    }

    private fun encodePath(path: String): String =
        "/" + path.trim('/').split('/').joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }

    private fun String.decodeSegment(): String =
        runCatching { URLDecoder.decode(this, Charsets.UTF_8.name()) }.getOrDefault(this)

    private companion object {
        const val MULTI_STATUS = 207

        /**
         * On ne demande que les quatre propriétés réellement utilisées. Un PROPFIND
         * `allprop` ferait renvoyer aux serveurs Nextcloud des dizaines de champs
         * (partages, favoris, aperçus) pour rien.
         */
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:getcontentlength/>
                <d:getcontenttype/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
