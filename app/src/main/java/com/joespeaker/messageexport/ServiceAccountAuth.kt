package com.joespeaker.messageexport

import android.content.Context
import android.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Authenticates as a Google Cloud service account using a bundled key file, entirely
 * unattended (no interactive consent screen). Signs a self-issued JWT with the service
 * account's private key and exchanges it for a short-lived OAuth2 access token.
 */
class ServiceAccountAuth(context: Context, private val httpClient: OkHttpClient) {

    private val appContext = context.applicationContext

    private var cachedToken: String? = null
    private var cachedTokenExpiryMillis: Long = 0

    /** Returns a valid bearer access token, fetching a new one if the cached token is
     * missing or close to expiry. */
    @Synchronized
    fun getAccessToken(): String {
        val now = System.currentTimeMillis()
        val token = cachedToken
        if (token != null && now < cachedTokenExpiryMillis - TOKEN_REFRESH_MARGIN_MILLIS) {
            return token
        }
        return fetchAccessToken().also {
            cachedToken = it
            cachedTokenExpiryMillis = now + DEFAULT_TOKEN_LIFETIME_MILLIS
        }
    }

    private fun fetchAccessToken(): String {
        val credentials = loadServiceAccountCredentials()
        val jwt = buildSignedJwt(credentials)

        val requestBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()

        val request = Request.Builder()
            .url(credentials.tokenUri)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ExportException("Token exchange failed (${response.code}): $bodyString")
            }
            val json = JSONObject(bodyString)
            return json.getString("access_token")
        }
    }

    private fun buildSignedJwt(credentials: ServiceAccountCredentials): String {
        val nowSeconds = System.currentTimeMillis() / 1000
        val expirySeconds = nowSeconds + 3600

        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }
        val claims = JSONObject().apply {
            put("iss", credentials.clientEmail)
            put("scope", DRIVE_FILE_SCOPE)
            put("aud", credentials.tokenUri)
            put("iat", nowSeconds)
            put("exp", expirySeconds)
        }

        val encodedHeader = base64UrlEncode(header.toString().toByteArray(Charsets.UTF_8))
        val encodedClaims = base64UrlEncode(claims.toString().toByteArray(Charsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedClaims"

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(credentials.privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
        }.sign()

        val encodedSignature = base64UrlEncode(signature)
        return "$signingInput.$encodedSignature"
    }

    private fun loadServiceAccountCredentials(): ServiceAccountCredentials {
        val jsonText = appContext.assets.open(SERVICE_ACCOUNT_ASSET_NAME).use { it.readBytes() }
            .toString(Charsets.UTF_8)
        val json = JSONObject(jsonText)

        val privateKeyPem = json.getString("private_key")
        val privateKey = decodePrivateKey(privateKeyPem)

        return ServiceAccountCredentials(
            clientEmail = json.getString("client_email"),
            tokenUri = json.optString("token_uri", "https://oauth2.googleapis.com/token"),
            privateKey = privateKey
        )
    }

    private fun decodePrivateKey(pem: String): PrivateKey {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .trim()
        val keyBytes = Base64.decode(cleaned, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private data class ServiceAccountCredentials(
        val clientEmail: String,
        val tokenUri: String,
        val privateKey: PrivateKey
    )

    companion object {
        const val SERVICE_ACCOUNT_ASSET_NAME = "service-account.json"
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val DEFAULT_TOKEN_LIFETIME_MILLIS = 3600 * 1000L
        private const val TOKEN_REFRESH_MARGIN_MILLIS = 60 * 1000L
    }
}

class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
