package io.github.micferna.resonate.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import dagger.hilt.android.AndroidEntryPoint
import io.github.micferna.resonate.R
import javax.inject.Inject

/**
 * Service de premier plan qui porte les téléchargements hors-ligne.
 *
 * Android tue sans préavis un processus en arrière-plan qui consomme du réseau. Le
 * passage en premier plan avec notification est ce qui permet à un album de plusieurs
 * centaines de mégaoctets d'arriver au bout, écran éteint. Le planificateur reprend le
 * travail interrompu — batterie faible, Wi-Fi perdu, redémarrage — sans intervention.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class OfflineDownloadService : DownloadService(
    /* foregroundNotificationId = */ NOTIFICATION_ID,
    /* foregroundNotificationUpdateInterval = */ DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    /* channelId = */ CHANNEL_ID,
    /* channelNameResourceId = */ R.string.download_channel_name,
    /* channelDescriptionResourceId = */ R.string.download_channel_description,
) {

    @Inject lateinit var offlineLibrary: OfflineLibrary

    private val notificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun getDownloadManager(): DownloadManager = offlineLibrary.downloadManager

    override fun getScheduler(): Scheduler = WorkManagerScheduler(this, WORK_NAME)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = notificationHelper.buildProgressNotification(
        /* context = */ this,
        /* smallIcon = */ R.drawable.ic_download,
        /* contentIntent = */ null,
        /* message = */ null,
        /* downloads = */ downloads,
        /* notMetRequirements = */ notMetRequirements,
    )

    /**
     * `DownloadService` crée le canal au moment d'entrer en premier plan, mais le
     * planificateur peut relancer le service alors que le canal a été supprimé par
     * l'utilisateur. On le rétablit à chaque démarrage, l'opération étant idempotente.
     */
    private fun ensureChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 0x5E01
        const val CHANNEL_ID = "resonate.downloads"
        const val WORK_NAME = "resonate-download-scheduler"
    }
}
