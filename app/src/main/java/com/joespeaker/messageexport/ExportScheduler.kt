package com.joespeaker.messageexport

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

/** Owns WorkManager scheduling for the export job: one periodic 24h job plus an on-demand
 * manual run, both funneled through [ExportWorker]. */
object ExportScheduler {

    private const val PERIODIC_WORK_NAME = "sms_export_periodic"
    const val MANUAL_WORK_NAME = "sms_export_manual"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Ensures the nightly export job is scheduled. Safe to call repeatedly (e.g. on every
     * app launch and after boot) - KEEP means an already-scheduled job is left alone so its
     * 24h anchor doesn't reset every time this is called. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExportWorker>(24, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Triggers an immediate one-off run, e.g. from the manual "Run Export Now" button. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setConstraints(networkConstraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
