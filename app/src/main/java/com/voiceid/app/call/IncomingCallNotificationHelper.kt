package com.voiceid.app.call

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voiceid.app.MainActivity
import com.voiceid.app.VoiceIdApplication

/**
 * ROOT CAUSE FIX for "notification shows an incoming call but there's no way to answer it,
 * and the other phone never rings": WebRtcCallManager.setIncomingCall() previously only set
 * in-memory CallState — it never posted an actual system notification. The ONLY thing that
 * ever showed the incoming call was the in-app IncomingCallScreen Composable in NavGraph.kt,
 * which is only visible while that screen happens to be on top AND the app is in the
 * foreground. With the screen off / app backgrounded, there was nothing: no ringtone, no
 * heads-up notification, no Accept/Reject action — the call just silently expired after the
 * caller's 30s timeout. This builds a real heads-up call notification with working Accept/
 * Reject actions (wired to CallActionReceiver, which WebRtcCallManager listens for) and a
 * full-screen intent so it can wake the screen like a normal phone call.
 *
 * NOTE: this only helps while the app PROCESS is alive (foreground or lightly backgrounded) —
 * the same constraint the Realtime signaling subscription already has. A fully killed app
 * still can't be woken for a call without a push-notification (FCM) trigger, which this
 * project doesn't currently integrate; that would be a separate, larger addition.
 */
object IncomingCallNotificationHelper {
    private const val NOTIFICATION_ID = 5502

    fun show(context: Context, callId: String, callerName: String) {
        val fullScreenIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            piFlags()
        )
        val acceptIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_ACCEPT).putExtra("callId", callId),
            piFlags()
        )
        val rejectIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_REJECT).putExtra("callId", callId),
            piFlags()
        )

        val notification = NotificationCompat.Builder(context, VoiceIdApplication.CHANNEL_CALLS)
            .setContentTitle(callerName)
            .setContentText("Incoming VoiceID call…")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI as Uri?)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", rejectIntent)
            .addAction(android.R.drawable.sym_action_call, "Answer", acceptIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
}
