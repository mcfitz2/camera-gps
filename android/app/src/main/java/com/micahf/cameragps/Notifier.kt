package com.micahf.cameragps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.DateFormat
import java.util.Date

/**
 * All of the app's notifications: the ongoing one required while
 * [CameraService] or [ShutterService] runs, plus one-off status events in their own channels so
 * each kind can be silenced separately in system settings.
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ONGOING, "Camera link (while connected)", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_STATUS, "Connected / disconnected", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_PROBLEMS, "Problems", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_FILM, "Film frames", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
        // Replaced by CHANNEL_FILM: a channel's importance can't be raised once created.
        manager.deleteNotificationChannel("film")
    }

    fun ongoing(status: Status): Notification {
        val sent = status.lastSent
        val text = status.message ?: when (sent) {
            null -> null
            else -> "Last sent ±%.0f m at %s · %d this session".format(
                sent.accuracyM, time(sent.atMillis), status.sendsThisSession,
            )
        }
        return builder(CHANNEL_ONGOING)
            .setContentTitle(status.link.label.replaceFirstChar(Char::uppercase))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Shown while [ShutterService] collects shots. */
    fun collecting(): Notification = builder(CHANNEL_ONGOING)
        .setContentTitle("Logging film frame")
        .setOngoing(true)
        .build()

    /** The latest frames logged, e.g. "Frame 12 · Portra Chicago · ±6 m". */
    fun frames(title: String, text: String?) = event(CHANNEL_FILM, title, text ?: "")

    fun connected() = event(CHANNEL_STATUS, "Camera connected", "Photos will be tagged with your location")

    fun disconnected(sends: Int) =
        event(CHANNEL_STATUS, "Camera disconnected", "Sent location $sends times", timeoutMs = STATUS_TIMEOUT_MS)

    fun problem(title: String, text: String) = event(CHANNEL_PROBLEMS, title, text)

    /** Clear a problem once it's resolved. */
    fun clearProblem() = manager.cancel(ID_PROBLEM)

    private fun event(channel: String, title: String, text: String, timeoutMs: Long = 0) {
        val n = builder(channel)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
            .build()
        // One slot per channel, so events replace each other instead of piling up.
        val id = when (channel) {
            CHANNEL_PROBLEMS -> ID_PROBLEM
            CHANNEL_FILM -> ID_FILM
            else -> ID_STATUS
        }
        manager.notify(id, n)
    }

    private fun builder(channel: String): Notification.Builder {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
    }

    private fun time(millis: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    companion object {
        const val ID_ONGOING = 1
        /** [ShutterService] can run alongside [CameraService], so needs its own. */
        const val ID_SHUTTER = 5
        private const val ID_STATUS = 2
        private const val ID_PROBLEM = 3
        private const val ID_FILM = 4
        private const val CHANNEL_ONGOING = "link"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_PROBLEMS = "problems"
        /** Alerts on every frame, so the user knows the shot was logged. */
        private const val CHANNEL_FILM = "film_frames"
        /** "Disconnected" is only interesting for a while. */
        private const val STATUS_TIMEOUT_MS = 30 * 60_000L
    }
}
