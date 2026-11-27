package com.clean.glasshome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android only lets an app read other apps' media sessions when it owns an
 * enabled notification listener, so this class exists purely as that permission anchor.
 * It never reads notifications.
 */
class MediaListenerService : NotificationListenerService()

/** Immutable snapshot of what is currently playing. */
class MediaInfo(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    val accent: Int,
    val playing: Boolean,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val durationMs: Long,
    val canPrev: Boolean,
    val canNext: Boolean,
    val canOpen: Boolean,
    val controller: MediaController
) {
    fun currentPosition(): Long {
        if (!playing) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - updatedAt
        return positionMs + (elapsed * speed).toLong()
    }
}

/**
 * Listens to Android's active media sessions (event driven, no polling). If notification access
 * has not been granted, nothing is reported and the launcher shows its default state.
 */
class MediaMonitor(private val context: Context, private val onChange: (MediaInfo?) -> Unit) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, MediaListenerService::class.java)
    private var registered = false
    private var controller: MediaController? = null

    private var lastKey: String? = null
    private var lastArtSource: Bitmap? = null
    private var lastArt: Bitmap? = null
    private var lastAccent = Catalog.AMBER

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> select(list) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
        override fun onSessionDestroyed() { refresh() }
    }

    fun start() {
        stop()
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, component)
            registered = true
            refresh()
        } catch (_: Exception) {
            // Notification access not granted (SecurityException) or unsupported: default state.
            registered = false
            onChange(null)
        }
    }

    fun stop() {
        try { if (registered) manager.removeOnActiveSessionsChangedListener(sessionListener) } catch (_: Exception) { }
        registered = false
        try { controller?.unregisterCallback(callback) } catch (_: Exception) { }
        controller = null
    }

    private fun refresh() {
        try {
            select(manager.getActiveSessions(component))
        } catch (_: Exception) {
            select(null)
        }
    }

    private fun select(list: List<MediaController>?) {
        var best: MediaController? = null
        if (list != null) {
            for (candidate in list) {
                if (candidate.playbackState?.state == PlaybackState.STATE_PLAYING) { best = candidate; break }
            }
            if (best == null) {
                for (candidate in list) {
                    val state = candidate.playbackState?.state
                    if ((state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING) && candidate.metadata != null) {
                        best = candidate
                        break
                    }
                }
            }
        }
        if (best?.sessionToken != controller?.sessionToken) {
            try { controller?.unregisterCallback(callback) } catch (_: Exception) { }
            controller = best
            try { best?.registerCallback(callback) } catch (_: Exception) { }
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            lastKey = null
            onChange(null)
            return
        }
        val state = c.playbackState
        val meta = c.metadata
        val st = state?.state ?: PlaybackState.STATE_NONE
        if (meta == null || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_STOPPED) {
            onChange(null)
            return
        }
        val pkg = c.packageName
        val pm = context.packageManager
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val key = pkg + "|" + title + "|" + (artist ?: "")

        val source = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (source == null) {
            if (key != lastKey) { lastArt = null; lastArtSource = null; lastAccent = Catalog.AMBER }
        } else if (key != lastKey || source !== lastArtSource || lastArt == null) {
            val scaled = try { Catalog.roundedSquare(source, 360, 0.085f) } catch (_: Throwable) { null }
            lastArtSource = source
            lastArt = scaled
            lastAccent = Catalog.accentFrom(scaled)
        }
        lastKey = key

        var duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = state?.position ?: 0L
        if (position < 0L) duration = 0L
        val actions = state?.actions ?: 0L
        var updated = state?.lastPositionUpdateTime ?: 0L
        if (updated <= 0L) updated = SystemClock.elapsedRealtime()
        val speed = state?.playbackSpeed ?: 1f

        onChange(MediaInfo(
            key = key,
            pkg = pkg,
            appLabel = appLabel,
            title = title,
            artist = artist,
            art = lastArt,
            accent = lastAccent,
            playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING,
            positionMs = position.coerceAtLeast(0L),
            updatedAt = updated,
            speed = if (speed > 0f) speed else 1f,
            durationMs = duration.coerceAtLeast(0L),
            canPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canOpen = pm.getLaunchIntentForPackage(pkg) != null,
            controller = c
        ))
    }
}
