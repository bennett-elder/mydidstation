package com.willingtocode.voicepathfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive so a SIP registration survives the screen going off.
 *
 * This is the first thing in the project with no Phase 0 measurement behind it. The probe
 * held the screen on, which hides every question that matters here: does the registration
 * refresh while the screen is off, does the app survive doze, does it recover when the
 * network moves from Wi-Fi to cellular. Those are the questions the product lives or dies
 * on, and none of them is answered by a call that lasts twenty seconds.
 *
 * The controller stays in [PathfinderApp], so the service owns an identity rather than a
 * second copy of the engine. Its jobs are to hold the process at foreground priority and to
 * say in the notification what is happening.
 */
class PathfinderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var refreshing: Job? = null

    private val pathfinder: Pathfinder
        get() = (application as PathfinderApp).pathfinder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Answer and decline come from the ringing notification. Nothing else should be able to
        // take a call, which is why it arrives as a service action rather than a broadcast.
        when (intent?.action) {
            ACTION_ANSWER -> {
                pathfinder.answerIncoming()
                return START_STICKY
            }
            ACTION_DECLINE -> {
                pathfinder.declineIncoming()
                return START_STICKY
            }
        }
        if (intent?.action == ACTION_PROMOTE || intent?.action == ACTION_DEMOTE) {
            // A live call needs the microphone type, because recording in the background is
            // refused without it, and the phoneCall type, because this app is the call stack.
            // The registration is still special use, so the type is a set rather than a swap.
            val types = if (intent.action == ACTION_PROMOTE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            val notification = build(pathfinder.callState.value ?: pathfinder.state.value)
            fun apply(kind: Int): Throwable? = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, kind)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }.exceptionOrNull()

            // The microphone type is refused when the app is not in a while-in-use state, which
            // is exactly the case for a call arriving on a locked phone. phoneCall alone needs
            // no such state, so it is the fallback rather than the surrender.
            var refused = apply(types)
            if (refused != null && intent.action == ACTION_PROMOTE) {
                pathfinder.noteFromElsewhere("microphone type refused, asking for phoneCall alone: ${refused.message}")
                refused = apply(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            }
            pathfinder.noteFromElsewhere(
                if (refused == null) {
                    "foreground type is now " + when (intent.action) {
                        ACTION_PROMOTE -> "phoneCall and microphone"
                        else -> "special use"
                    }
                } else {
                    "the platform refused the foreground type: " +
                        (refused.message ?: refused.javaClass.simpleName)
                },
            )
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // Stopping the service while a call is up used to leave the call running with
            // nothing showing it. Stop means end what is happening, then stop.
            pathfinder.hangUp()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = build(pathfinder.callState.value ?: pathfinder.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        pathfinder.warn("foreground service started; the registration now outlives the screen")
        watch()
        // Re-read the account while the service runs. Without this, a number added or
        // re-routed in the portal stays invisible until the app is next launched, which is
        // the difference between a phone that follows the account and one that mirrors a
        // snapshot of it.
        refreshing?.cancel()
        refreshing = scope.launch {
            while (isActive) {
                delay(ACCOUNT_REFRESH_MILLIS)
                pathfinder.refreshAccount()
            }
        }
        // Sticky on purpose: after a low-memory kill the registration is worth restoring
        // without the user having to notice that it went.
        return START_STICKY
    }

    override fun onDestroy() {
        refreshing?.cancel()
        watching?.cancel()
        scope.cancel()
        pathfinder.warn("foreground service stopped")
        super.onDestroy()
    }

    private fun watch() {
        watching?.cancel()
        watching = scope.launch {
            // Two flows, one line: the registration says whether this phone answers, and the
            // call says what it is doing. Registration alone would leave the notification
            // claiming to be idle during a call, which is what happened when they shared a flow
            // and then stopped being one.
            combine(pathfinder.state, pathfinder.callState) { registered, call -> call ?: registered }
                .collectLatest { state ->
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, build(state))
                }
        }
    }

    private fun build(state: PathState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PathfinderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle("Voice")
            .setContentText(summary(state))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** One line, because a notification is not the log. */
    private fun summary(state: PathState): String = when (state) {
        PathState.Unregistered -> "Not registered"
        is PathState.Registering -> "Registering"
        is PathState.Registered -> "Registered, ${state.grantedSeconds} s"
        is PathState.RegistrationFailed -> "Registration failed: ${state.reason.take(60)}"
        is PathState.Dialing -> "Dialling"
        is PathState.Ringing -> "Ringing"
        is PathState.Connected ->
            if (state.encrypted) "In call, ${state.codec}, encrypted" else "In call, ${state.codec}"
        is PathState.Ended -> "Call ended: ${state.reason.take(40)}"
        is PathState.Failed -> "Failed: ${state.reason.take(60)}"
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Registration", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Keeps the SIP registration alive in the background."
                    setShowBadge(false)
                },
        )
    }

    companion object {
        const val ACTION_STOP = "com.willingtocode.voicepathfinder.STOP"

        /** Asks for the call foreground type. Called when a call starts, idempotently. */
        const val ACTION_PROMOTE = "com.willingtocode.voicepathfinder.PROMOTE"

        /** Hands the service back to registration duty when the call ends. */
        const val ACTION_DEMOTE = "com.willingtocode.voicepathfinder.DEMOTE"

        /** Both are safe to call when the service is not running: it starts, then obeys. */
        fun promote(context: Context) = ask(context, ACTION_PROMOTE)

        fun demote(context: Context) = ask(context, ACTION_DEMOTE)

        private fun ask(context: Context, action: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PathfinderService::class.java).setAction(action),
                )
            }
        }

        /** Carries the ringing notification's Answer and Decline. */
        const val ACTION_ANSWER = "com.willingtocode.voicepathfinder.ANSWER"
        const val ACTION_DECLINE = "com.willingtocode.voicepathfinder.DECLINE"
        private const val CHANNEL_ID = "registration"
        private const val NOTIFICATION_ID = 1

        /** How often the service re-reads the account while it is running. */
        private const val ACCOUNT_REFRESH_MILLIS = 15 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PathfinderService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PathfinderService::class.java))
        }
    }
}
