package com.joespeaker.messageexport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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
            setStatus("Permissions are required to read and export messages.", StatusIcon.ERROR)
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
            setStatus("Export running...", StatusIcon.RUNNING)
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
            binding.grantPermissionsButton.visibility = View.GONE
            binding.runNowButton.isEnabled = true
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun onPermissionsGranted() {
        binding.grantPermissionsButton.visibility = View.GONE
        binding.runNowButton.isEnabled = true
        ExportScheduler.schedulePeriodic(this)
        updateStatusFromLastRun()
    }

    private fun updateStatusFromLastRun() {
        val prefs = getSharedPreferences(ExportManager.PREFS_NAME, MODE_PRIVATE)
        val lastRunMillis = prefs.getLong(ExportManager.PREF_LAST_RUN_MILLIS, -1L)
        if (lastRunMillis <= 0) {
            if (hasAllPermissions()) {
                setStatus("Scheduled. No export has run yet.", StatusIcon.IDLE)
            }
            return
        }
        val success = prefs.getBoolean(ExportManager.PREF_LAST_SUCCESS, false)
        val when_ = DateFormat.getDateTimeInstance().format(Date(lastRunMillis))
        if (success) {
            val count = prefs.getInt(ExportManager.PREF_LAST_MESSAGE_COUNT, -1)
            setStatus("Last export: $when_ - $count messages synced.", StatusIcon.SUCCESS)
        } else {
            val error = prefs.getString(ExportManager.PREF_LAST_ERROR, "unknown error")
            setStatus("Last export failed ($when_): $error", StatusIcon.ERROR)
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
                        setStatus("Export failed. Will retry on next schedule.", StatusIcon.ERROR)
                    }
                }
            }
    }

    private enum class StatusIcon { IDLE, RUNNING, SUCCESS, ERROR }

    private fun setStatus(text: String, icon: StatusIcon) {
        binding.statusText.text = text
        val (drawableRes, tintRes) = when (icon) {
            StatusIcon.IDLE -> R.drawable.ic_cloud_upload to R.color.primary
            StatusIcon.RUNNING -> R.drawable.ic_sync to R.color.primary
            StatusIcon.SUCCESS -> R.drawable.ic_check_circle to R.color.success
            StatusIcon.ERROR -> R.drawable.ic_error to R.color.error
        }
        binding.statusIcon.setImageResource(drawableRes)
        binding.statusIcon.imageTintList = ContextCompat.getColorStateList(this, tintRes)
    }
}
