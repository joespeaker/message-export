package com.joespeaker.messageexport

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager entry point for both the periodic nightly export and the manual "run now"
 * trigger. Kept synchronous (Worker, not CoroutineWorker) since the whole export - reading
 * the message providers and one Drive upload - is a short, self-contained blocking op. */
class ExportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return when (ExportManager(applicationContext).runExport()) {
            is ExportManager.Result.Success -> Result.success()
            is ExportManager.Result.Failure -> Result.retry()
        }
    }
}
