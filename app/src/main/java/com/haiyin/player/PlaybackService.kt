package com.haiyin.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder

/** Foreground service with MediaSession + notification/lockscreen media controls. */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "haiyin_playback"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.haiyin.START"
        const val ACTION_TOGGLE = "com.haiyin.TOGGLE"
        const val ACTION_NEXT = "com.haiyin.NEXT"
        const val ACTION_PREV = "com.haiyin.PREV"

        private var mediaSession: android.support.v4.media.session.MediaSessionCompat? = null

        fun hasNotifPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun pi(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        fun buildNotif(context: Context, largeIcon: android.graphics.Bitmap? = null): Notification {
            val st = Player.state.value
            val song = st.queue.getOrNull(st.index)
            val style = androidx.media.app.NotificationCompat.MediaStyle()
            mediaSession?.let { style.setMediaSession(it.sessionToken) }
            val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "上一曲", pi(context, ACTION_PREV))
                .addAction(
                    if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    "播放/暂停",
                    pi(context, ACTION_TOGGLE)
                )
                .addAction(android.R.drawable.ic_media_next, "下一曲", pi(context, ACTION_NEXT))
                .setStyle(style.setShowActionsInCompactView(0, 1, 2))
            if (largeIcon != null) builder.setLargeIcon(largeIcon)
            if (song != null) {
                builder.setContentTitle(song.name).setContentText(song.singer)
            } else {
                builder.setContentTitle("海音")
            }
            return builder.build()
        }

        fun ensureMediaSession(context: Context) {
            if (mediaSession != null) return
            mediaSession = android.support.v4.media.session.MediaSessionCompat(context, "HaiYin").apply {
                isActive = true
                setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        if (!Player.state.value.playing) Player.toggle()
                        refresh(context)
                    }
                    override fun onPause() {
                        if (Player.state.value.playing) Player.toggle()
                        refresh(context)
                    }
                    override fun onSkipToNext() { Player.next(); refresh(context) }
                    override fun onSkipToPrevious() { Player.previous(); refresh(context) }
                    override fun onSeekTo(pos: Long) {
                        val st = Player.state.value
                        val dur = st.durationMs
                        if (dur > 0) {
                            Player.seekTo((pos.coerceIn(0, dur).toFloat() / dur).coerceIn(0f, 1f))
                        }
                        refresh(context)
                    }
                })
            }
        }

        /** Push a fresh notification reflecting current Player state. */
        fun refresh(context: Context) {
            if (!hasNotifPermission(context)) return
            try {
                ensureChannel(context)
                ensureMediaSession(context)
                updateSession(null)
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotif(context))
                // artwork is loaded async: patch session art + notification icon when ready
                val song = Player.state.value.queue.getOrNull(Player.state.value.index)
                if (song != null && song.img.isNotBlank()) {
                    Thread {
                        try {
                            val bmp = CoverLoader.get(song.img) ?: return@Thread
                            val cur = Player.state.value.queue.getOrNull(Player.state.value.index)
                            if (cur?.id != song.id) return@Thread  // user moved on
                            updateSession(bmp)
                            nm.notify(NOTIF_ID, buildNotif(context, bmp))
                        } catch (_: Exception) {}
                    }.also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {}
        }

        /**
         * Feed title/artist/artwork + playback state into the MediaSession.
         * Android 11+ renders both the notification-shade media card and the
         * lock-screen card from this metadata — without it the lock screen
         * shows an empty card.
         */
        private fun updateSession(art: android.graphics.Bitmap?) {
            val session = mediaSession ?: return
            val st = Player.state.value
            val song = st.queue.getOrNull(st.index)
            try {
                val mm = android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(
                        android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                        song?.name ?: "海音"
                    )
                    .putString(
                        android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                        song?.singer ?: ""
                    )
                    .putString(
                        android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM,
                        song?.album ?: ""
                    )
                    .putLong(
                        android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                        st.durationMs.coerceAtLeast(0)
                    )
                if (art != null) {
                    mm.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, art)
                }
                session.setMetadata(mm.build())
                val pb = android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setActions(
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO
                    )
                    .setState(
                        if (st.playing) android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                        else android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,
                        st.positionMs.coerceAtLeast(0),
                        if (st.playing) 1.0f else 0f,
                        android.os.SystemClock.elapsedRealtime()
                    )
                    .build()
                session.setPlaybackState(pb)
            } catch (_: Exception) {}
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> Player.toggle()
                ACTION_NEXT -> Player.next()
                ACTION_PREV -> Player.previous()
            }
            refresh(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureMediaSession(this)
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE); addAction(ACTION_NEXT); addAction(ACTION_PREV)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            ensureMediaSession(this)
            // Claim foreground status unconditionally. On Android 13+ the
            // notification is merely hidden when POST_NOTIFICATIONS is denied —
            // the service still gets foreground priority. Gating this on the
            // permission left us a plain background service that the system may
            // kill minutes after the app is backgrounded; with the process dead,
            // the DLNA proxy (this phone is the HTTP server) stops feeding the
            // renderer and playback goes silent mid-song while the renderer
            // still reports PLAYING.
            try {
                ensureChannel(this)
                startForeground(NOTIF_ID, buildNotif(this))
            } catch (_: Exception) {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Player.release()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
