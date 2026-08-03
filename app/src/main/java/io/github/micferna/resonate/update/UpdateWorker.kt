package io.github.micferna.resonate.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.micferna.resonate.MainActivity
import io.github.micferna.resonate.R
import io.github.micferna.resonate.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Vérifie périodiquement l'existence d'une nouvelle version et prévient l'utilisateur.
 *
 * La notification se contente d'annoncer ; rien ne se télécharge et rien ne s'installe
 * sans une action explicite. Une app distribuée hors magasin qui se mettrait à jour
 * toute seule serait exactement le comportement qu'on redoute d'un APK sideloadé.
 */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val settingsStore: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settingsStore.settings.first().autoUpdateCheckEnabled) return Result.success()

        val update = try {
            updateChecker.check() ?: return Result.success()
        } catch (_: Exception) {
            // Réseau absent ou quota GitHub atteint : ce n'est pas un échec, on
            // retentera au prochain créneau plutôt que de consommer des reprises.
            return Result.success()
        }

        notify(update)
        return Result.success()
    }

    private fun notify(update: AvailableUpdate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()

        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Resonate ${update.version} est disponible")
            .setContentText("Vous utilisez la version ${updateChecker.installedVersion}.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(update.notes.take(NOTES_LIMIT)))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_channel_description)
            },
        )
    }

    companion object {
        const val NAME = "resonate-update-check"
        const val ACTION_SHOW_UPDATE = "io.github.micferna.resonate.SHOW_UPDATE"
        private const val CHANNEL_ID = "resonate.updates"
        private const val NOTIFICATION_ID = 0x5E02
        private const val REQUEST_CODE = 0x5E02
        private const val NOTES_LIMIT = 400
    }
}
