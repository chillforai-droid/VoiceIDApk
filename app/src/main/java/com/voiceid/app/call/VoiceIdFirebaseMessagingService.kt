package com.voiceid.app.call

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.voiceid.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "VoiceID/Push"

/**
 * Wakes the app for an incoming call even when it's fully backgrounded or killed — the piece
 * that pure Supabase Realtime (used elsewhere in this app) structurally cannot do, since a
 * websocket subscription only stays alive while the process does. The backend side (Supabase
 * DB webhook on `calls` INSERT -> Vercel /api/send-call-push -> Firebase Admin SDK) is
 * documented in supabase/migrations and api/send-call-push.ts in the web repo.
 *
 * Data-only messages (no `notification` block) are used deliberately: they're the only kind
 * that reliably invokes onMessageReceived() while the app is backgrounded/killed on Android,
 * which is required so we can show our OWN call-style notification (with working Accept/
 * Reject actions) via IncomingCallNotificationHelper instead of a generic system one.
 */
class VoiceIdFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch {
            try {
                AppContainer.pushTokenRepository.register(token)
            } catch (e: Exception) {
                Log.e(TAG, "onNewToken: failed to register token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] != "incoming_call") return

        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"

        CallRinger.start(applicationContext)
        IncomingCallNotificationHelper.show(applicationContext, callId, callerName)
        // Deliberately does NOT touch WebRtcCallManager/CallState here — this service can run
        // in a fresh process with no CallViewModel/WebRtcCallManager instance yet. Tapping
        // Answer/Decline on the resulting notification opens MainActivity with the callId,
        // where CallViewModel.handlePendingCallAction() picks it up, fetches the live call
        // row, and drives the normal accept/reject + WebRTC signaling flow from there.
    }
}
