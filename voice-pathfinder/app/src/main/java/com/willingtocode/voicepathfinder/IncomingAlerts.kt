package com.willingtocode.voicepathfinder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * The ring and the notification, which must start and stop together.
 *
 * A CallStyle notification on API 31 and later, because that is the component Android's own
 * dialer uses: it carries Answer and Decline, it takes over the lock screen through a
 * full-screen intent, and it is the shape people already recognise as a phone call. Below 31
 * the same actions are attached to a plain notification, which is the best that exists there.
 *
 * The sound is the system's ringtone rather than a tone of ours, so it follows the ringer mode,
 * the volume keys and Do Not Disturb without this code deciding any of that.
 */
internal object IncomingAlerts {

    private const val CHANNEL = "incoming_calls"
    private const val NOTIFICATION_ID = 4201

    private var ringtone: Ringtone? = null
    private var vibrating = false

    fun ringing(context: Context, caller: String, did: String) {
        startRingtone(context)
        startVibrating(context)
        notify(context, caller, did)
    }

    fun stopRinging(context: Context) {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) {
            runCatching { vibrator(context)?.cancel() }
            vibrating = false
        }
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }

    private fun startRingtone(context: Context) {
        if (ringtone?.isPlaying == true) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Silent means silent: the vibration below is the whole alert in that mode.
        if (manager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        val tone = RingtoneManager.getRingtone(context, sound) ?: return
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { tone.play() }
        ringtone = tone
    }

    private fun startVibrating(context: Context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (manager?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val device = vibrator(context) ?: return
        val pattern = longArrayOf(0, 900, 900)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(pattern, 0)
            }
            vibrating = true
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun notify(context: Context, caller: String, did: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    },
            )
        }
        val answer = action(context, PathfinderService.ACTION_ANSWER, 1)
        val decline = action(context, PathfinderService.ACTION_DECLINE, 2)
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Without this permission the notification is dropped without an error, which leaves a
        // ringing phone with no way to answer. Say so in the log rather than ring silently.
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            android.util.Log.w("VoicePathfinder", "no notification permission: the call can ring but not be answered")
        }
        val title = if (caller.isBlank()) "Incoming call" else caller
        val text = "on ${Provider.normalizeNumber(did).ifBlank { did }}"

        // CallStyle is refused unless the platform considers a full-screen intent usable, so
        // the same two actions are posted as a plain notification when it does not.
        val fullScreenAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            manager.canUseFullScreenIntent()
        val built = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && fullScreenAllowed) {
            val person = android.app.Person.Builder()
                .setName(title)
                .setImportant(true)
                .build()
            android.app.Notification.Builder(context, CHANNEL)
                .setStyle(android.app.Notification.CallStyle.forIncomingCall(person, decline, answer))
                .setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(android.app.Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .addAction(0, "Decline", decline)
                .addAction(0, "Answer", answer)
                .build()
        }
        // A dropped notification has no other symptom, and the call cannot be answered without
        // it, so the reason goes to the log rather than being swallowed.
        try {
            manager.notify(NOTIFICATION_ID, built)
        } catch (fault: Exception) {
            android.util.Log.w("VoicePathfinder", "could not post the call notification", fault)
        }
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context,
            code,
            Intent(context, PathfinderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
