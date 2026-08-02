package com.voiceid.app.data.remote

import com.google.gson.annotations.SerializedName
import com.voiceid.app.BuildConfig
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class UploadResponse(@SerializedName("objectKey") val objectKey: String)
data class UploadAuthResponse(
    @SerializedName("url") val url: String,
    @SerializedName("objectKey") val objectKey: String
)
data class DownloadAuthResponse(@SerializedName("url") val url: String)
data class SimpleSuccess(@SerializedName("success") val success: Boolean)
data class ApiErrorBody(
    @SerializedName("error") val error: String?,
    @SerializedName("stage") val stage: String? = null,
    @SerializedName("message") val message: String? = null
)

class MediaApiException(val code: Int, val body: ApiErrorBody?) :
    Exception(body?.error ?: "Media API error ($code)")

/**
 * Thin client for the frozen `/api/media` endpoints contract documented in API_REFERENCE.md §1.
 * This talks to the EXISTING VoiceID backend (Express/Vercel handlers) — it does not
 * reimplement any server logic. Base URL points at the deployed backend (BuildConfig.API_BASE_URL).
 *
 * ROOT CAUSE FIX (2026-08-01): every function here used to be a plain blocking `fun` doing a
 * synchronous OkHttp `.execute()` call, invoked directly from suspend functions in
 * MessageRepository/ProfileRepository with no withContext(Dispatchers.IO) at any call site.
 * Since those repositories are called from viewModelScope (Dispatchers.Main.immediate), the
 * blocking network I/O — including large voice/image file uploads — was running directly on
 * the UI thread. That's the "spinner stuck, no error, app looks frozen" bug: the thread doing
 * the network work was the SAME thread that draws the spinner, so nothing could visibly
 * update until the call finished or hit OkHttp's timeout. Every function below is now
 * `suspend` and internally hops to Dispatchers.IO, matching how Retrofit/Ktor normally handle
 * this for you automatically — callers don't need to change anything, they already call these
 * from suspend functions.
 */
class MediaApi(private val baseUrl: String = BuildConfig.API_BASE_URL) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = com.google.gson.Gson()

    private fun authHeader(token: String) = "Bearer $token"

    /**
     * TEMPORARY DIAGNOSTIC (2026-08-02b): server-side [MediaAuth] logs show
     * authorizationPresent=false for the media upload/download endpoints while the SAME app
     * session's /api/cloudinary-sign call DID carry a valid Authorization header. Since
     * uploadRaw()/requestDownloadAuth()/etc. below all call .header("Authorization", ...)
     * unconditionally, that should be impossible — so log here, on the Android side,
     * immediately after the OkHttp Request is built and before it's sent, to see what Android
     * itself thinks it's sending. Compare this against the Vercel [MediaAuth] log for the same
     * request.
     */
    private fun logPreflight(request: Request) {
        val h = request.header("Authorization")
        Log.i(
            TAG_401,
            "PRE-FLIGHT ${request.method} ${request.url} — Authorization header on OkHttp Request: present=${h != null} length=${h?.length ?: 0}"
        )
    }

    /** POST /api/media/upload — raw binary body, matches API_REFERENCE.md §1.1 exactly. */
    suspend fun uploadRaw(token: String, file: File, mimeType: String): UploadResponse = withContext(Dispatchers.IO) {
        val body = file.asRequestBody(mimeType.toMediaTypeOrNull())
        val request = Request.Builder()
            .url("$baseUrl/api/media/upload")
            .header("Authorization", authHeader(token))
            .header("Content-Type", mimeType)
            .post(body)
            .build()
        logPreflight(request) // TEMPORARY DIAGNOSTIC
        execute(request, UploadResponse::class.java)
    }

    /** POST /api/media/upload-auth — presigned direct-to-B2 PUT URL, §1.2. Preferred for large files. */
    suspend fun requestUploadAuth(token: String, mimeType: String): UploadAuthResponse = withContext(Dispatchers.IO) {
        val json = gson.toJson(mapOf("mimeType" to mimeType))
        val request = Request.Builder()
            .url("$baseUrl/api/media/upload-auth")
            .header("Authorization", authHeader(token))
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        logPreflight(request) // TEMPORARY DIAGNOSTIC
        execute(request, UploadAuthResponse::class.java)
    }

    /** Direct PUT of raw bytes to the presigned B2 URL returned above. */
    suspend fun putToPresignedUrl(url: String, file: File, mimeType: String) = withContext(Dispatchers.IO) {
        val body = file.asRequestBody(mimeType.toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", mimeType)
            .put(body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw MediaApiException(resp.code, null)
        }
    }

    /** POST /api/media/download-auth — presigned GET URL, §1.3. */
    suspend fun requestDownloadAuth(token: String, messageId: String): DownloadAuthResponse = withContext(Dispatchers.IO) {
        val json = gson.toJson(mapOf("messageId" to messageId))
        val request = Request.Builder()
            .url("$baseUrl/api/media/download-auth")
            .header("Authorization", authHeader(token))
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        logPreflight(request) // TEMPORARY DIAGNOSTIC
        execute(request, DownloadAuthResponse::class.java)
    }

    /** Fetch the actual bytes from a presigned B2 URL. */
    suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw MediaApiException(resp.code, null)
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * POST /api/media/ack — §1.4. Fire-and-forget per API_REFERENCE.md: a 403 here means the
     * caller is the message's own sender, which is expected/non-fatal, not a bug to retry.
     */
    suspend fun ackDelivery(token: String, messageId: String) = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("messageId" to messageId))
            val request = Request.Builder()
                .url("$baseUrl/api/media/ack")
                .header("Authorization", authHeader(token))
                .header("Content-Type", "application/json")
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            logPreflight(request) // TEMPORARY DIAGNOSTIC
            http.newCall(request).execute().close()
        } catch (_: Exception) {
            // best-effort cleanup signal only, per API_REFERENCE.md §1.4 — never surfaced to the user
        }
    }

    /** DELETE /api/media/delete/:objectKey — §1.5, called after the messages row is already deleted. */
    suspend fun deleteObject(token: String, objectKey: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/media/delete/$objectKey")
            .header("Authorization", authHeader(token))
            .delete()
            .build()
        logPreflight(request) // TEMPORARY DIAGNOSTIC
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw MediaApiException(resp.code, null)
        }
    }

    private fun <T> execute(request: Request, clazz: Class<T>): T {
        http.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errorBody = runCatching { gson.fromJson(bodyStr, ApiErrorBody::class.java) }.getOrNull()
                if (resp.code == 401) {
                    logAuthDiagnostics(request)
                }
                throw MediaApiException(resp.code, errorBody)
            }
            return gson.fromJson(bodyStr, clazz)
        }
    }

    /**
     * DIAGNOSTIC ONLY (2026-08-02): server rejects media requests with 401 despite the same
     * session working fine for text messages (Postgrest/RLS). To find the real cause without
     * guessing, decode the JWT's own exp/iss/aud claims locally — no supabase-kt API calls
     * involved, so this can't itself be wrong about a library method name. Filter logcat for
     * "VoiceID/MediaAuth401" after reproducing the bug.
     */
    private fun logAuthDiagnostics(request: Request) {
        val authHeader = request.header("Authorization") ?: run {
            Log.e(TAG_401, "401 on ${request.url} — Authorization header was MISSING from the request entirely.")
            return
        }
        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isBlank()) {
            Log.e(TAG_401, "401 on ${request.url} — Authorization header present but token string was blank.")
            return
        }
        val parts = token.split(".")
        if (parts.size != 3) {
            Log.e(TAG_401, "401 on ${request.url} — token doesn't look like a JWT (expected 3 dot-separated parts, got ${parts.size}). First 20 chars: ${token.take(20)}")
            return
        }
        try {
            val payloadJson = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val claims = gson.fromJson(payloadJson, Map::class.java)
            val exp = (claims["exp"] as? Double)?.toLong()
            val nowSeconds = System.currentTimeMillis() / 1000
            val expStatus = if (exp != null) {
                if (exp < nowSeconds) "EXPIRED ${nowSeconds - exp}s ago" else "valid for ${exp - nowSeconds}s more"
            } else "no exp claim found"
            Log.e(
                TAG_401,
                "401 on ${request.url} — token claims: iss=${claims["iss"]} aud=${claims["aud"]} " +
                    "role=${claims["role"]} sub=${claims["sub"]} exp=$exp ($expStatus, device clock now=$nowSeconds)"
            )
        } catch (e: Exception) {
            Log.e(TAG_401, "401 on ${request.url} — failed to decode JWT payload for diagnostics", e)
        }
    }
}

private const val TAG_401 = "VoiceID/MediaAuth401"
