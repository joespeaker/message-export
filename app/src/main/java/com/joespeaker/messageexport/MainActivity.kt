package com.joespeaker.messageexport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.joespeaker.messageexport.databinding.ActivityMainBinding
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onPermissionsGranted()
        } else {
            binding.statusText.text = "Permissions are required to read and export messages."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantPermissionsButton.setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }
        binding.runNowButton.setOnClickListener {
            ExportScheduler.runNow(this)
            binding.statusText.text = "Export running..."
        }
        observeManualRun()

        if (hasAllPermissions()) {
            onPermissionsGranted()
        } else {
            updateStatusFromLastRun()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) {
            binding.grantPermissionsButton.isEnabled = false
            binding.runNowButton.isEnabled = true
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun onPermissionsGranted() {
        binding.grantPermissionsButton.isEnabled = false
        binding.runNowButton.isEnabled = true
        ExportScheduler.schedulePeriodic(this)
        updateStatusFromLastRun()
    }

    private fun updateStatusFromLastRun() {
        val prefs = getSharedPreferences(ExportManager.PREFS_NAME, MODE_PRIVATE)
        val lastRunMillis = prefs.getLong(ExportManager.PREF_LAST_RUN_MILLIS, -1L)
        if (lastRunMillis <= 0) {
            if (hasAllPermissions()) {
                binding.statusText.text = "Scheduled. No export has run yet."
            }
            return
        }
        val success = prefs.getBoolean(ExportManager.PREF_LAST_SUCCESS, false)
        val when_ = DateFormat.getDateTimeInstance().format(Date(lastRunMillis))
        binding.statusText.text = if (success) {
            val count = prefs.getInt(ExportManager.PREF_LAST_MESSAGE_COUNT, -1)
            "Last export: $when_ - $count messages synced."
        } else {
            val error = prefs.getString(ExportManager.PREF_LAST_ERROR, "unknown error")
            "Last export failed ($when_): $error"
        }
    }

    private fun observeManualRun() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ExportScheduler.MANUAL_WORK_NAME)
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                if (info.state.isFinished) {
                    updateStatusFromLastRun()
                    if (info.state == WorkInfo.State.FAILED) {
                        binding.statusText.text = "Export failed. Will retry on next schedule."
                    }
                }
            }
    }
}
