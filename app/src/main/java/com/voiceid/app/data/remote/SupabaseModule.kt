package com.voiceid.app.data.remote

import android.content.Context
import com.voiceid.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.flow.StateFlow

/**
 * Single Supabase client instance for the whole app, wired against the SAME Supabase
 * project the web client uses (SUPABASE_URL / SUPABASE_ANON_KEY — see BACKEND_README.md §1).
 * Never point this at a different backend or bypass RLS from the client.
 */
object SupabaseModule {

    @Volatile
    private var instance: SupabaseClient? = null

    fun client(context: Context? = null): SupabaseClient {
        return instance ?: synchronized(this) {
            instance ?: createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY
            ) {
                install(Auth) {
                    // Native deep-link redirect target registered in AndroidManifest.xml
                    // (scheme "com.voiceid.app", host "auth-callback") — mirrors the web
                    // app's hardcoded https://voiceid.online/auth/callback redirect, per
                    // BACKEND_README.md §2.1 / AI_HANDOFF.md §6.1.
                    scheme = "com.voiceid.app"
                    host = "auth-callback"
                }
                install(Postgrest)
                install(Realtime)
                install(Storage)
            }.also { instance = it }
        }
    }

    fun auth(context: Context? = null) = client(context).auth
    fun postgrest(context: Context? = null) = client(context).postgrest
    fun realtime(context: Context? = null) = client(context).realtime

    fun sessionStatus(): StateFlow<SessionStatus> = client().auth.sessionStatus

    fun currentUserId(): String? = client().auth.currentUserOrNull()?.id

    /** @deprecated for media/avatar uploads — use freshAccessToken() instead. Kept for call
     * sites (Postgrest, Realtime) where the SDK itself attaches/refreshes the token internally. */
    fun currentAccessToken(): String? = client().auth.currentAccessTokenOrNull()

    /**
     * ROOT CAUSE FIX (2026-08-02): Web calls `await supabase.auth.getSession()` immediately
     * before every /api/media/* request (see src/pages/ChatPage.tsx) — the JS SDK's
     * getSession() checks the cached session's expiry and transparently refreshes it first
     * if it's stale, so the token Web sends is always guaranteed-fresh at the moment of use.
     * Android's currentAccessTokenOrNull() is a plain cached read with no such check, which
     * is the likely cause of media uploads getting 401 Unauthorized while Postgrest calls
     * (which the SDK refreshes internally on its own) keep working fine. This mirrors Web's
     * behavior: decode the cached token's own exp claim locally (no guessing at unfamiliar
     * supabase-kt API surface — same safe technique as MediaApi's 401 diagnostic logging),
     * and force a refresh through supabase-kt's Auth.refreshCurrentSession() if it's expired
     * or about to be. Same caveat as handleDeeplinks() earlier: I can't verify this exact
     * method name against the actual supabase-kt library source from here — if this specific
     * call fails to compile, tell me the exact error and I'll correct the method name.
     */
    suspend fun freshAccessToken(): String? {
        val cached = client().auth.currentAccessTokenOrNull() ?: return null
        if (!isExpiredOrExpiringSoon(cached)) return cached
        return try {
            android.util.Log.i("VoiceID/TokenRefresh", "freshAccessToken: cached token expired/near-expiry, forcing refresh before media call")
            client().auth.refreshCurrentSession()
            client().auth.currentAccessTokenOrNull() ?: cached
        } catch (e: Exception) {
            android.util.Log.e("VoiceID/TokenRefresh", "freshAccessToken: refresh failed, falling back to cached (possibly stale) token", e)
            cached
        }
    }

    /** Pure local JWT decode — no supabase-kt API involved, so this can't be wrong about a library method name. */
    private fun isExpiredOrExpiringSoon(token: String, thresholdSeconds: Long = 30): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return false // not a JWT we can inspect — let the request try as-is
            val payloadJson = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            val claims = com.google.gson.Gson().fromJson(payloadJson, Map::class.java)
            val exp = (claims["exp"] as? Double)?.toLong() ?: return false
            val nowSeconds = System.currentTimeMillis() / 1000
            exp <= nowSeconds + thresholdSeconds
        } catch (e: Exception) {
            false // can't determine — don't block the request on a diagnostic failure
        }
    }
}
