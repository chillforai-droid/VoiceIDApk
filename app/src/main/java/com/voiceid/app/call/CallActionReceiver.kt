package com.voiceid.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voiceid.app.MainActivity
import com.voiceid.app.data.repository.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "VoiceID/CallAction"

/**
 * Handles Accept/Reject tapped directly from the heads-up incoming-call notification
 * (IncomingCallNotificationHelper), whether that notification came from the in-app realtime
 * path (WebRtcCallManager, process alive) or the push path (VoiceIdFirebaseMessagingService,
 * process possibly killed) — this receiver doesn't know or care which, and works for both:
 *
 *  - Always stops the ringtone/vibration/notification immediately, for instant feedback.
 *  - REJECT is resolved right here via goAsync(), independent of any running ViewModel —
 *    rejecting a call doesn't need the app UI or an active WebRTC engine, just one DB write,
 *    so it must not require relaunching the whole app.
 *  - ACCEPT does need the full app (to actually negotiate WebRTC audio), so it also opens
 *    MainActivity with the callId; CallViewModel.handlePendingCallAction() picks that up
 *    once composed. It ALSO fires the internal BROADCAST_ACCEPT, which WebRtcCallManager
 *    already listens for while the app process is alive — that path resolves first and is
 *    a no-op for handlePendingCallAction to redo once the call's status has moved on.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId")
        CallRinger.stop(context)
        IncomingCallNotificationHelper.cancel(context)

        when (intent.action) {
            ACTION_ACCEPT -> {
                context.sendBroadcast(Intent(BROADCAST_ACCEPT).putExtra("callId", callId).setPackage(context.packageName))
                val openApp = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_PENDING_CALL_ACTION, MainActivity.CALL_ACTION_ACCEPT)
                    putExtra(MainActivity.EXTRA_PENDING_CALL_ID, callId)
                }
                context.startActivity(openApp)
            }
            ACTION_REJECT -> {
                context.sendBroadcast(Intent(BROADCAST_REJECT).putExtra("callId", callId).setPackage(context.packageName))
                if (callId != null) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            CallRepository().markRejected(callId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Reject-from-notification failed for call $callId", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.voiceid.app.action.ACCEPT_CALL"
        const val ACTION_REJECT = "com.voiceid.app.action.REJECT_CALL"
        const val BROADCAST_ACCEPT = "com.voiceid.app.broadcast.CALL_ACCEPTED"
        const val BROADCAST_REJECT = "com.voiceid.app.broadcast.CALL_REJECTED"
    }
}
