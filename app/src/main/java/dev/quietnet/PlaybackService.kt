package dev.quietnet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Keeps YouTube playing with the screen off or the app in the background, and
 * provides the notification, lock-screen and headset controls.
 */
class PlaybackService : Service() {
    companion object {
        private const val ACTION_PLAY = "dev.quietnet.PLAY"
        private const val ACTION_PAUSE = "dev.quietnet.PAUSE"
        private const val ACTION_FORWARD = "dev.quietnet.FORWARD"
        private const val ACTION_BACK = "dev.quietnet.BACK"
        private const val ACTION_CLOSE = "dev.quietnet.CLOSE"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 7
        private const val IDLE_STOP_MS = 10L * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var idleStop: Job? = null
    private var thumbFor = ""
    private var thumb: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Player.serviceRunning = true
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        session = MediaSession(this, "QuietNet").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Player.command("play")
                override fun onPause() = Player.command("pause")
                override fun onStop() = Player.command("pause")
                override fun onSkipToNext() = Player.command("forward")
                override fun onSkipToPrevious() = Player.command("back")
                override fun onFastForward() = Player.command("forward")
                override fun onRewind() = Player.command("back")
                override fun onSeekTo(pos: Long) = Player.command("seek:${pos / 1000.0}")
            })
            setSessionActivity(openScreen())
            isActive = true
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietNet:playback")
            .apply { setReferenceCounted(false) }

        val first = build(Player.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, first)
        }
        scope.launch { Player.state.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> Player.command("play")
            ACTION_PAUSE -> Player.command("pause")
            ACTION_FORWARD -> Player.command("forward")
            ACTION_BACK -> Player.command("back")
            ACTION_CLOSE -> {
                Player.command("pause")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Player.serviceRunning = false
        scope.cancel()
        wakeLock?.release()
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // Media playback notifications don't need the notification permission on Android 13+.
    @SuppressLint("NotificationPermission")
    private fun render(s: Player.State) {
        if (s.videoId.isNotEmpty() && s.videoId != thumbFor) loadThumbnail(s.videoId)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title.ifEmpty { "YouTube" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YouTube")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (s.duration * 1000).toLong())
                .apply { thumb?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    (s.position * 1000).toLong(),
                    if (s.playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        notifications.notify(NOTIFICATION_ID, build(s))

        idleStop?.cancel()
        if (s.playing) {
            wakeLock?.acquire(4L * 60 * 60 * 1000)
        } else {
            wakeLock?.release()
            // Don't keep a notification around for a video nobody is coming back to.
            idleStop = scope.launch {
                delay(IDLE_STOP_MS)
                if (!Player.state.value.playing) stopSelf()
            }
        }
    }

    private fun build(s: Player.State): Notification {
        val playPause = if (s.playing) {
            action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
        } else {
            action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(s.title.ifEmpty { "YouTube" })
            .setContentText("No ads, by QuietNet")
            .apply { thumb?.let { setLargeIcon(it) } }
            .setContentIntent(openScreen())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE))
            .setOngoing(s.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_rew, "Back 10 seconds", ACTION_BACK))
            .addAction(playPause)
            .addAction(action(android.R.drawable.ic_media_ff, "Forward 10 seconds", ACTION_FORWARD))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, act: String) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(act)).build()

    private fun serviceIntent(act: String) = PendingIntent.getService(
        this, act.hashCode(), Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openScreen() = PendingIntent.getActivity(
        this, 0, Intent(this, YouTubeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun loadThumbnail(videoId: String) {
        thumbFor = videoId
        thumb = null
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").openStream().use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) {
                    null
                }
            }
            if (thumbFor == videoId && bmp != null) {
                thumb = bmp
                render(Player.state.value)
            }
        }
    }
}
