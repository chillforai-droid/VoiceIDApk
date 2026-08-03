package com.voiceid.app.data.repository

import com.voiceid.app.data.remote.SupabaseModule
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable

@Serializable
private data class PushTokenUpsert(
    val user_id: String,
    val token: String,
    val platform: String = "android"
)

/**
 * Registers this device's FCM token against the signed-in user, so the backend's
 * /api/send-call-push (triggered by a Supabase DB webhook on `calls` INSERT) knows where to
 * deliver the incoming-call push. Mirrors the `push_tokens` table added by the
 * "create push tokens" Supabase migration — one row per user (last-registered device wins,
 * matching the common single-active-device assumption of this app's call flow).
 */
class PushTokenRepository {
    private val client = SupabaseModule.client()

    suspend fun register(token: String) {
        val userId = SupabaseModule.currentUserId() ?: return
        client.from("push_tokens").upsert(
            PushTokenUpsert(user_id = userId, token = token)
        ) {
            onConflict = "user_id"
        }
    }

    suspend fun clearForCurrentUser() {
        val userId = SupabaseModule.currentUserId() ?: return
        client.from("push_tokens").delete { filter { eq("user_id", userId) } }
    }
}
