package com.joespeaker.messageexport

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Keeps a single JSON file in a Google Drive folder up to date, overwriting the same
 * file (by ID) on every run instead of creating timestamped duplicates.
 */
class DriveUploader(context: Context, private val httpClient: OkHttpClient) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Uploads [jsonBytes] as the backup file's content, creating the file on first run
     * and overwriting it (same file ID) on every subsequent run. Returns the file ID used. */
    fun uploadOrReplace(accessToken: String, jsonBytes: ByteArray): String {
        var fileId = prefs.getString(PREF_FILE_ID, null)

        if (fileId != null && !fileExists(accessToken, fileId)) {
            Log.w(TAG, "Stored Drive file ID $fileId no longer valid; will recreate")
            fileId = null
        }

        return if (fileId != null) {
            updateFileContent(accessToken, fileId, jsonBytes)
            fileId
        } else {
            val newFileId = createFile(accessToken, jsonBytes)
            prefs.edit().putString(PREF_FILE_ID, newFileId).apply()
            newFileId
        }
    }

    private fun fileExists(accessToken: String, fileId: String): Boolean {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=id,trashed"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            return !json.optBoolean("trashed", false)
        }
    }

    private fun updateFileContent(accessToken: String, fileId: String, jsonBytes: ByteArray) {
        val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .patch(jsonBytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ExportException("Drive file update failed (${response.code}): $body")
            }
        }
    }

    private fun createFile(accessToken: String, jsonBytes: ByteArray): String {
        val metadata = JSONObject().apply {
            put("name", FILE_NAME)
            put("parents", org.json.JSONArray().put(DRIVE_FOLDER_ID))
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.MIXED)
            .addPart(
                MultipartBody.Part.create(
                    metadata.toString().toRequestBody(JSON_MEDIA_TYPE)
                )
            )
            .addPart(
                MultipartBody.Part.create(
                    jsonBytes.toRequestBody(JSON_MEDIA_TYPE)
                )
            )
            .build()

        val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ExportException("Drive file creation failed (${response.code}): $body")
            }
            return JSONObject(body).getString("id")
        }
    }

    companion object {
        private const val TAG = "DriveUploader"
        private const val PREFS_NAME = "drive_uploader_prefs"
        private const val PREF_FILE_ID = "drive_file_id"
        private const val FILE_NAME = "all-messages.json"
        private const val DRIVE_FOLDER_ID = "1d64WeKvcvZduR_AaIsdKbNXbYQj_LOd7"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
