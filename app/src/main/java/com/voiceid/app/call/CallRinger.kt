package com.voiceid.app.call

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Shared ringtone + vibration for an incoming call, used by both the in-app realtime path
 * (WebRtcCallManager, while the app process is alive) and the push-notification path
 * (VoiceIdFirebaseMessagingService, which can fire even while the app is fully backgrounded).
 * Kept as a single top-level object rather than duplicated in both places, since exactly one
 * of the two paths should ever be "ringing" at a time and both need to stop it the same way.
 */
object CallRinger {
    private var ringtone: Ringtone? = null

    fun start(context: Context) {
        stop(context) // defensive: never let two rings/vibrations overlap

        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.also { it.play() }
        } catch (_: Exception) { /* best-effort — a missing ringtone must not block the call UI */ }

        try {
            val pattern = longArrayOf(0, 800, 600)
            val vibrator = vibratorService(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    fun stop(context: Context) {
        ringtone?.let { if (it.isPlaying) it.stop() }
        ringtone = null
        try {
            vibratorService(context).cancel()
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun vibratorService(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
