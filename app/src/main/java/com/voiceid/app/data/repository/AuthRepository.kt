package com.voiceid.app.data.repository

import android.content.Intent
import android.util.Log
import com.voiceid.app.data.model.Profile
import com.voiceid.app.data.remote.SupabaseModule
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "VoiceID/GoogleAuth"

class AuthException(message: String) : Exception(message)

/**
 * Implements API_REFERENCE.md §2 exactly: Supabase Auth is the entire auth API, there is no
 * custom /api/auth layer. Google sign-in uses a native ID-token flow (Credential Manager /
 * One Tap) rather than a browser redirect, per AI_HANDOFF.md §6.1, but must land the user in
 * the identical post-auth onboarding gate.
 */
class AuthRepository {

    private val client = SupabaseModule.client()

    val sessionStatus: StateFlow<SessionStatus> = client.auth.sessionStatus

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    /**
     * ROOT CAUSE FIX for "Email Registration does not complete" / "Not authenticated" on
     * VoiceID claim: previously, callers read currentUserId() synchronously immediately
     * after signUp()/signInWith() returned. supabase-kt's auth plugin propagates a
     * successful sign-in/sign-up into `sessionStatus` (and only then does
     * currentUserOrNull() reflect it) through its own internal auth-event pipeline — this
     * is the exact analogue of the web client's onAuthStateChange subscription
     * (AuthContext.tsx), which is *reactive*, not an immediate synchronous read after
     * calling supabase.auth.signUp()/signInWithPassword(). The web client never races this
     * because AuthContext's `user` is always sourced from the listener, never from the
     * signUp/signIn call's return value directly. This helper replicates that same
     * reactive contract on Android: wait on the StateFlow itself for the Authenticated
     * state to actually land, instead of trusting an immediate point-in-time read.
     *
     * Returns false only if no session actually becomes Authenticated within the timeout —
     * which correctly happens if the account genuinely requires email confirmation, as
     * opposed to a normal timing lag.
     */
    suspend fun awaitAuthenticatedSession(timeoutMillis: Long = 8000): Boolean {
        return try {
            withTimeout(timeoutMillis) {
                client.auth.sessionStatus.first { it is SessionStatus.Authenticated }
            }
            true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "awaitAuthenticatedSession: no Authenticated sessionStatus within ${timeoutMillis}ms " +
                "(current=${client.auth.sessionStatus.value})")
            false
        }
    }

    /**
     * Waits for the Auth plugin to finish resolving whatever persisted session may exist on
     * disk (sessionStatus leaves Initializing), so a cold-start currentUserId() read
     * reflects reality instead of racing the plugin's own async session-restore job. Used
     * for session restoration on app launch — see MainActivity.kt.
     */
    suspend fun awaitSessionResolved(timeoutMillis: Long = 5000) {
        withTimeoutOrNull(timeoutMillis) {
            client.auth.sessionStatus.first { it !is SessionStatus.Initializing }
        }
    }

    /** Google native sign-in: exchange a Google ID token (from Credential Manager) for a Supabase session. */
    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String?) {
        Log.d(TAG, "AuthRepository.signInWithGoogleIdToken: POST .../auth/v1/token?grant_type=id_token " +
            "provider=google nonce_present=${rawNonce != null}")
        try {
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                nonce = rawNonce
            }
            Log.d(TAG, "AuthRepository.signInWithGoogleIdToken: success, session established.")
        } catch (e: Exception) {
            // Re-throw unchanged — AuthViewModel is responsible for surfacing this to the UI.
            // Logged here too so the exact Supabase-layer failure is visible even if a caller
            // upstream is ever added that doesn't log it.
            Log.e(TAG, "AuthRepository.signInWithGoogleIdToken: Supabase rejected the ID token exchange.", e)
            throw e
        }
    }

    /**
     * Custom-scheme deep link Supabase's confirmation email will point at (via
     * emailRedirectTo below). Reuses the SAME intent-filter (com.voiceid.app://auth-callback)
     * already registered in AndroidManifest.xml for the Google OAuth native redirect — no
     * new manifest entry, no Digital Asset Links / App Links verification needed, because a
     * custom scheme (not an https:// App Link) is claimed directly by the app.
     */
    private val authCallbackRedirectUrl = "com.voiceid.app://auth-callback"

    /**
     * Email/password sign up — validation mirrors lib/validation.ts::signUpSchema (name>=2, valid email, password>=8).
     *
     * Verified (2026-08-01): this Supabase project has "Confirm email" enabled — a fresh
     * sign-up does NOT return an active session; the account only becomes usable after the
     * user taps the confirmation link Supabase emails them. redirectUrl here is what makes
     * that link openable by the Android app itself (see handleAuthDeeplink) instead of only
     * landing in a browser the way it would with no redirectUrl set.
     */
    suspend fun signUp(email: String, password: String, fullName: String) {
        require(fullName.trim().length >= 2) { "Name must be at least 2 characters" }
        require(password.length >= 8) { "Password must be at least 8 characters" }
        client.auth.signUpWith(Email, redirectUrl = authCallbackRedirectUrl) {
            this.email = email
            this.password = password
            data = kotlinx.serialization.json.buildJsonObject {
                put("full_name", kotlinx.serialization.json.JsonPrimitive(fullName))
            }
        }
    }

    /**
     * Parses an incoming Intent for a Supabase auth deep link (email confirmation link,
     * magic link, etc.) and imports the resulting session if present. Safe to call with any
     * intent — a no-op if it doesn't contain Supabase auth data. Called from
     * MainActivity.onCreate/onNewIntent via AuthViewModel.handleAuthDeeplink.
     */
    fun handleAuthDeeplink(intent: Intent, onSessionImported: () -> Unit = {}) {
        client.handleDeeplinks(intent) {
            Log.i(TAG, "handleAuthDeeplink: session imported from deep link (e.g. email confirmation).")
            onSessionImported()
        }
    }

    suspend fun signInWithPassword(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun sendPasswordResetEmail(email: String) {
        client.auth.resetPasswordForEmail(email)
    }

    suspend fun updatePassword(newPassword: String) {
        client.auth.updateUser { password = newPassword }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    /**
     * Post-auth onboarding check (API_REFERENCE.md §2 / BACKEND_README.md §2.3):
     * SELECT * FROM profiles WHERE id = auth.uid(); missing row or null username -> needs onboarding.
     *
     * ROOT CAUSE FIX (avatar not appearing for Google-signed-in users): the web client's
     * AuthContext.tsx ("Sync Google avatar if missing") backfills `profiles.avatar_url` from
     * the Supabase Auth user's own `user_metadata.avatar_url` — which Supabase populates from
     * the Google account tied to that email during the OAuth exchange — the very first time a
     * profile with no avatar is loaded. This Kotlin repository had no equivalent: it only ever
     * read `profiles.avatar_url` as-is, so a Google user who never manually picked a photo
     * simply never got one, even though the same account shows an avatar on the web. This
     * mirrors that exact web behavior on Android.
     */
    suspend fun fetchOwnProfile(): Profile? {
        val userId = currentUserId() ?: return null
        val profile = client.from("profiles").select {
            filter { eq("id", userId) }
        }.decodeSingleOrNull<Profile>() ?: return null

        if (profile.avatarUrl.isNullOrBlank()) {
            val googleAvatarUrl = client.auth.currentUserOrNull()
                ?.userMetadata?.get("avatar_url")
                ?.let { runCatching { it.toString().trim('"') }.getOrNull() }
                ?.takeIf { it.isNotBlank() && it != "null" }

            if (googleAvatarUrl != null) {
                client.from("profiles").update({ set("avatar_url", googleAvatarUrl) }) {
                    filter { eq("id", userId) }
                }
                return profile.copy(avatarUrl = googleAvatarUrl)
            }
        }
        return profile
    }

    suspend fun isUsernameAvailable(username: String): Boolean {
        val result = client.from("profiles").select(columns = Columns.list("username")) {
            filter { eq("username", username.lowercase()) }
        }.decodeList<Map<String, String>>()
        return result.isEmpty()
    }

    /** INSERT INTO profiles (id, username) VALUES (auth.uid(), '<chosen>') — ChooseVoiceID.tsx equivalent. */
    suspend fun claimUsername(username: String, displayName: String?) {
        val userId = currentUserId() ?: throw AuthException("Not authenticated")
        require(username.length >= 3) { "Username must be at least 3 characters" }
        val available = isUsernameAvailable(username)
        if (!available) throw AuthException("Username is already taken")
        client.from("profiles").insert(
            Profile(
                id = userId,
                username = username.lowercase(),
                displayName = displayName
            )
        )
    }
}
