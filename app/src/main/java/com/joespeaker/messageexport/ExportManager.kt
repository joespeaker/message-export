package com.joespeaker.messageexport

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Runs one full export: read all SMS/MMS, build the JSON payload, and overwrite the
 * single file in Drive. Records the outcome for the UI to display. */
class ExportManager(private val context: Context) {

    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun runExport(): Result {
        return try {
            val messages = MessageReader(appContext.contentResolver).readAll()
            val jsonBytes = messages.toJsonArray().toString().toByteArray(Charsets.UTF_8)

            val auth = ServiceAccountAuth(appContext, httpClient)
            val accessToken = auth.getAccessToken()

            val uploader = DriveUploader(appContext, httpClient)
            uploader.uploadOrReplace(accessToken, jsonBytes)

            recordOutcome(success = true, messageCount = messages.size, error = null)
            Result.Success(messages.size)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            recordOutcome(success = false, messageCount = null, error = e.message)
            Result.Failure(e)
        }
    }

    private fun recordOutcome(success: Boolean, messageCount: Int?, error: String?) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PREF_LAST_SUCCESS, success)
            .putLong(PREF_LAST_RUN_MILLIS, System.currentTimeMillis())
            .putInt(PREF_LAST_MESSAGE_COUNT, messageCount ?: -1)
            .putString(PREF_LAST_ERROR, error)
            .apply()
    }

    sealed class Result {
        data class Success(val messageCount: Int) : Result()
        data class Failure(val error: Exception) : Result()
    }

    companion object {
        private const val TAG = "ExportManager"
        const val PREFS_NAME = "export_status_prefs"
        const val PREF_LAST_SUCCESS = "last_success"
        const val PREF_LAST_RUN_MILLIS = "last_run_millis"
        const val PREF_LAST_MESSAGE_COUNT = "last_message_count"
        const val PREF_LAST_ERROR = "last_error"
    }
}
