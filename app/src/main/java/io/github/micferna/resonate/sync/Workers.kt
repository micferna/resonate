package io.github.micferna.resonate.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.micferna.resonate.R
import io.github.micferna.resonate.data.db.dao.SourceDao

/**
 * Indexe les sources en tâche de fond.
 *
 * Un balayage SFTP sur une grosse bibliothèque prend plusieurs minutes : le confier à
 * WorkManager plutôt qu'à une coroutine d'écran garantit qu'il survit à la fermeture de
 * l'app, et qu'il reprend après un redémarrage ou une perte de réseau.
 */
@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: LibraryScanner,
    private val sourceDao: SourceDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val targetSource = inputData.getLong(KEY_SOURCE_ID, NO_SOURCE)

        val outcomes = if (targetSource == NO_SOURCE) {
            scanner.scanAll { source, count -> reportProgress(source.displayName, count) }
        } else {
            val source = sourceDao.byId(targetSource) ?: return Result.failure(
                Data.Builder().putString(KEY_ERROR, "Source introuvable.").build(),
            )
            listOf(scanner.scan(source) { entity, count -> reportProgress(entity.displayName, count) })
        }

        val failures = outcomes.filterNot { it.succeeded }
        val discovered = outcomes.sumOf { it.discovered }

        return when {
            // Aucune source n'a répondu : très probablement un problème réseau
            // passager, que WorkManager saura retenter avec un délai croissant.
            failures.size == outcomes.size && outcomes.isNotEmpty() -> Result.retry()

            else -> Result.success(
                Data.Builder()
                    .putInt(KEY_DISCOVERED, discovered)
                    .putInt(KEY_FAILED_SOURCES, failures.size)
                    .build(),
            )
        }
    }

    private suspend fun reportProgress(sourceName: String, count: Int) {
        setProgress(
            Data.Builder()
                .putString(KEY_CURRENT_SOURCE, sourceName)
                .putInt(KEY_DISCOVERED, count)
                .build(),
        )
    }

    /**
     * Requis dès qu'un travail est demandé en accéléré : jusqu'à Android 11,
     * WorkManager l'exécute dans un service de premier plan et réclame cette
     * notification. Sans elle, l'appui sur « Analyser » ferait échouer le travail.
     *
     * Elle a aussi son utilité propre : un balayage SFTP de plusieurs milliers de
     * fichiers dure des minutes, et l'utilisateur mérite de voir que ça avance.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.scan_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.scan_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.scan_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val NAME_PERIODIC = "resonate-scan-periodic"
        const val NAME_ONE_SHOT = "resonate-scan-now"
        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_DISCOVERED = "discovered"
        const val KEY_FAILED_SOURCES = "failedSources"
        const val KEY_CURRENT_SOURCE = "currentSource"
        const val KEY_ERROR = "error"
        const val NO_SOURCE = -1L
        private const val CHANNEL_ID = "resonate.scan"
        private const val NOTIFICATION_ID = 0x5E03
    }
}

/**
 * Lit les vrais tags des morceaux, par petits lots.
 *
 * Reprogrammé tant qu'il reste du travail : chaque exécution traite un lot puis rend
 * la main, plutôt que de monopoliser le réseau pendant une heure sur une grosse
 * bibliothèque.
 */
@HiltWorker
class TagResolutionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tagResolver: TagResolver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val resolved = tagResolver.resolvePending()
        return Result.success(Data.Builder().putInt(KEY_RESOLVED, resolved).build())
    }

    companion object {
        const val NAME = "resonate-tags"
        const val KEY_RESOLVED = "resolved"
    }
}
