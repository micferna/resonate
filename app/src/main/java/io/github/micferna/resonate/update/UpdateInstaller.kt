package io.github.micferna.resonate.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.micferna.resonate.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Télécharge et installe une nouvelle version de l'app.
 *
 * Trois garde-fous se superposent, et le dernier est le plus important :
 *
 * 1. le téléchargement passe par HTTPS vers les serveurs de GitHub ;
 * 2. si la Release publie une empreinte SHA-256 à côté de l'APK, elle est vérifiée
 *    avant toute installation — un fichier tronqué ou altéré est rejeté ;
 * 3. Android refuse de remplacer une app installée par un APK signé avec une autre
 *    clé. Même un APK malveillant parfaitement formé ne peut donc pas se substituer
 *    à Resonate : c'est la signature du build, et elle seule, qui fait autorité.
 *
 * L'utilisateur garde le dernier mot : le système affiche systématiquement une
 * demande de confirmation avant d'installer.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callFactory: Call.Factory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
    val progress: StateFlow<UpdateProgress> = _progress.asStateFlow()

    /** L'installation d'APK tiers doit être autorisée pour cette app dans les réglages Android. */
    fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Intention à lancer pour ouvrir l'écran système d'autorisation. */
    fun installPermissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())

    suspend fun install(update: AvailableUpdate): Result<Unit> = withContext(ioDispatcher) {
        val apkFile = File(context.cacheDir, "update-${update.tag}.apk")
        try {
            _progress.value = UpdateProgress.Downloading(0, update.apkSizeBytes)
            download(update.apkUrl, apkFile, update.apkSizeBytes)

            _progress.value = UpdateProgress.Verifying
            update.checksumUrl?.let { url ->
                val expected = fetchExpectedDigest(url)
                val actual = apkFile.sha256()
                if (!expected.equals(actual, ignoreCase = true)) {
                    throw IOException(
                        "L'empreinte du fichier téléchargé ne correspond pas à celle publiée. " +
                            "Installation annulée.",
                    )
                }
            }

            _progress.value = UpdateProgress.AwaitingConfirmation
            commitSession(apkFile)
            Result.success(Unit)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            apkFile.delete()
            _progress.value = UpdateProgress.Failed(error.message ?: "Mise à jour impossible.")
            Result.failure(error)
        }
    }

    fun resetProgress() {
        _progress.value = UpdateProgress.Idle
    }

    // ------------------------------------------------------------------ interne

    private suspend fun download(url: String, target: File, expectedSize: Long) {
        val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Téléchargement impossible (code ${response.code}).")
            }
            val total = response.body.contentLength().takeIf { it > 0 } ?: expectedSize
            var written = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        _progress.value = UpdateProgress.Downloading(written, total)
                    }
                }
            }
        }
    }

    private fun fetchExpectedDigest(url: String): String {
        val request = Request.Builder().url(url).build()
        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Empreinte de vérification indisponible (code ${response.code}).")
            }
            // Format `sha256sum` : "<empreinte>  <nom de fichier>".
            return response.body.string().trim().substringBefore(' ').trim()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Remet l'APK au gestionnaire de paquets du système.
     *
     * On passe par `PackageInstaller` plutôt que par une intention `ACTION_VIEW` sur
     * un fichier : pas de `FileProvider` à exposer, pas d'URI partagée avec d'autres
     * applications, et le résultat de l'installation revient à l'app.
     */
    private fun commitSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(WRITE_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            session.commit(statusIntent(sessionId).intentSender)
        }
        apk.delete()
    }

    private fun statusIntent(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(UpdateInstallReceiver.ACTION).setPackage(context.packageName),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val WRITE_NAME = "resonate-update"
    }
}

/**
 * Reçoit le verdict du gestionnaire de paquets.
 *
 * Le système répond d'abord `STATUS_PENDING_USER_ACTION` : il faut alors afficher
 * l'écran de confirmation qu'il fournit. Sans cette étape, l'installation resterait
 * silencieusement en attente — c'est volontaire, aucune app ne peut s'installer
 * seule sans consentement explicite.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation =
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }

            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Mise à jour installée.")

            else -> Log.w(
                TAG,
                "Installation refusée : " +
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(),
            )
        }
    }

    companion object {
        const val ACTION = "io.github.micferna.resonate.UPDATE_INSTALL_STATUS"
        private const val TAG = "UpdateInstall"
    }
}
