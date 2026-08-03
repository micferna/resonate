package io.github.micferna.resonate.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.micferna.resonate.data.prefs.NetworkPolicy
import io.github.micferna.resonate.data.prefs.Settings
import io.github.micferna.resonate.update.UpdateWorker
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Décide quand tournent les travaux de fond.
 *
 * Tout est calé sur des contraintes réseau et sur les réglages de l'utilisateur :
 * indexer une bibliothèque de plusieurs milliers de titres en 4G alors que
 * « Wi-Fi uniquement » est demandé serait une facture surprise.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** (Re)programme l'indexation périodique selon les réglages courants. */
    fun schedulePeriodicScan(settings: Settings) {
        if (!settings.autoScanEnabled) {
            workManager.cancelUniqueWork(ScanWorker.NAME_PERIODIC)
            return
        }

        val request = PeriodicWorkRequestBuilder<ScanWorker>(
            settings.autoScanIntervalHours,
            TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints(settings.streamingPolicy))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ScanWorker.NAME_PERIODIC,
            // UPDATE et non CANCEL_AND_REENQUEUE : changer l'intervalle ne doit pas
            // relancer immédiatement un balayage complet.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Lance une indexation immédiate, de toutes les sources ou d'une seule. */
    fun scanNow(sourceId: Long? = null) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(ScanWorker.KEY_SOURCE_ID, sourceId ?: ScanWorker.NO_SOURCE)
                    .build(),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            // L'utilisateur vient d'appuyer sur « Analyser » : le travail doit démarrer
            // sans attendre un créneau système.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(ScanWorker.NAME_ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Programme la résolution des tags. Le travail traite un lot puis s'arrête ; la
     * périodicité assure la progression jusqu'à épuisement de la file.
     */
    fun scheduleTagResolution(settings: Settings) {
        val request = PeriodicWorkRequestBuilder<TagResolutionWorker>(TAG_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraints(settings.streamingPolicy))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            TagResolutionWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleUpdateCheck(settings: Settings) {
        if (!settings.autoUpdateCheckEnabled) {
            workManager.cancelUniqueWork(UpdateWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(UPDATE_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            UpdateWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun networkConstraints(policy: NetworkPolicy): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                when (policy) {
                    NetworkPolicy.ANY -> NetworkType.CONNECTED
                    NetworkPolicy.UNMETERED_ONLY -> NetworkType.UNMETERED
                },
            )
            .setRequiresBatteryNotLow(true)
            .build()

    private companion object {
        const val BACKOFF_MINUTES = 5L
        const val TAG_INTERVAL_MINUTES = 20L
        const val UPDATE_INTERVAL_HOURS = 12L
    }
}
