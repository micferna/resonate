package io.github.micferna.resonate.source

import android.net.Uri
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import java.io.IOException

/**
 * Adapte une URI interne `resonate://` vers la véritable URL HTTP(S) de la source.
 *
 * Les URI stockées en base ne portent qu'une référence de source et un chemin ; ni
 * l'hôte, ni le jeton d'authentification n'y figurent. C'est ce qui permet de changer
 * l'adresse d'un serveur ou de faire tourner un mot de passe sans réécrire la
 * bibliothèque, et évite que des identifiants se retrouvent dans les clés de cache ou
 * l'index des téléchargements. La traduction n'a lieu qu'ici, juste avant la requête.
 */
@OptIn(UnstableApi::class)
class HttpRemoteDataSource(
    private val callFactory: Call.Factory,
    private val resolve: (Long) -> ResolvedSource,
    private val toHttpUrl: (ResolvedSource, String) -> String,
    private val headersFor: (ResolvedSource) -> Map<String, String>,
) : BaseDataSource(/* isNetwork = */ true) {

    private var delegate: DataSource? = null
    private var currentUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val source = resolve(ResonateUri.sourceIdOf(dataSpec.uri))
        val path = ResonateUri.remotePathOf(dataSpec.uri)
        val target = toHttpUrl(source, path)

        val http = OkHttpDataSource.Factory(callFactory)
            .setDefaultRequestProperties(headersFor(source))
            .createDataSource()

        val length = try {
            http.open(dataSpec.buildUpon().setUri(target.toUri()).build())
        } catch (error: IOException) {
            runCatching { http.close() }
            throw error
        }

        delegate = http
        currentUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = (delegate ?: throw IOException("DataSource HTTP non ouvert"))
            .read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpen = opened
        opened = false
        try {
            delegate?.close()
        } finally {
            delegate = null
            currentUri = null
            if (wasOpen) transferEnded()
        }
    }
}
