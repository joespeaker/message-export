package com.joespeaker.messageexport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the periodic export job after a reboot, since WorkManager's own persistence can
 * be delayed until something else touches it. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ExportScheduler.schedulePeriodic(context)
        }
    }
}
