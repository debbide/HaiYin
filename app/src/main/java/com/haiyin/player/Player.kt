package com.haiyin.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback queue + controller. Owns MediaPlayer, exposes reactive state for UI.
 * Generation-token guards against stale callbacks on released players.
 */
object Player {

    data class State(
        val queue: List<Sources.Song> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val prepared: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val quality: String = "320k",
        val resolving: Boolean = false,
        val error: String? = null,
        val playMode: PlayMode = PlayMode.SEQUENCE,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var mediaPlayer: MediaPlayer? = null
    private var progressTicker: java.util.Timer? = null
    private var appContext: Context? = null
    // increments on every resolveAndPlay; stale callbacks compare & bail
    private var generation: Long = 0

    var quality: String
        get() = _state.value.quality
        set(value) { _state.value = _state.value.copy(quality = value) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** For Dlna multicast lock etc. */
    fun appContextPublic(): Context = appContext ?: throw IllegalStateException("Player not initialized")

    /** Replace queue and start at index. */
    fun playQueue(songs: List<Sources.Song>, startIndex: Int) {
        _state.value = _state.value.copy(queue = songs, index = startIndex, error = null)
        resolveAndPlay()
    }

    fun playAt(index: Int) {
        if (index in _state.value.queue.indices) {
            _state.value = _state.value.copy(index = index, error = null)
            appContext?.let { PlaybackService.refresh(it) }
            resolveAndPlay()
        }
    }

    enum class PlayMode { SEQUENCE, REPEAT_ONE, SHUFFLE }

    val playMode: PlayMode get() = _state.value.playMode

    fun setPlayMode(m: PlayMode) {
        _state.value = _state.value.copy(playMode = m)
    }

    fun next() = advance(+1)
    fun previous() = advance(-1)

    private fun advance(dir: Int) {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val mode = st.playMode
        val idx = when {
            mode == PlayMode.REPEAT_ONE && dir > 0 -> st.index   // same track again
            mode == PlayMode.SHUFFLE && dir > 0 -> {
                if (st.queue.size <= 1) st.index
                else {
                    var n = st.index
                    while (n == st.index) n = (0 until st.queue.size).random()
                    n
                }
            }
            else -> st.index + dir
        }
        if (idx in st.queue.indices) {
            playAt(idx)
        } else if (dir > 0) {
            // end of queue in sequence mode: stop
            _state.value = _state.value.copy(playing = false)
        }
    }

    // guard: user action grace window to prevent poller race
    @Volatile private var lastCastActionAt = 0L

    fun toggle() {
        // while casting, the remote renderer is the real player
        val dev = castDevice
        if (dev != null) {
            val newPlaying = !_state.value.playing
            _state.value = _state.value.copy(playing = newPlaying)
            lastCastActionAt = System.currentTimeMillis()
            castExecutor.execute {
                try {
                    if (newPlaying) {
                        // 1. Try standard UPnP Play action first
                        val ok = Dlna.resume(dev)
                        try { Thread.sleep(300) } catch (_: Exception) {}
                        val state = Dlna.transportState(dev)
                        // 2. If device cannot resume directly (e.g. state is STOPPED or action failed),
                        // re-cast the stream and seek to the saved timestamp
                        if (!ok || state != "PLAYING") {
                            val song = currentSong() ?: return@execute
                            val qualityNow = _state.value.quality
                            val url = try { Sources.resolveUrl(song, qualityNow) } catch (_: Exception) { null } ?: return@execute
                            val proxiedUrl = try { StreamProxy.proxy(url) } catch (_: Exception) { url }
                            Dlna.cast(dev, proxiedUrl, song.name, song.singer)
                            val resumePos = _state.value.positionMs
                            if (resumePos > 2000) {
                                try { Thread.sleep(400) } catch (_: Exception) {}
                                Dlna.seek(dev, resumePos)
                            }
                        }
                    } else {
                        // Pause; if device rejects Pause, fallback to Stop
                        val ok = Dlna.pause(dev)
                        if (!ok) {
                            Dlna.stop(dev)
                        }
                    }
                } catch (_: Exception) {}
            }
            appContext?.let { PlaybackService.refresh(it) }
            return
        }
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                _state.value = _state.value.copy(playing = false)
            } else {
                mp.start()
                _state.value = _state.value.copy(playing = true)
            }
            appContext?.let { PlaybackService.refresh(it) }
        } catch (_: Exception) {}
    }

    fun seekTo(fraction: Float) {
        // casting: forward seek to renderer
        val dev = castDevice
        if (dev != null) {
            val dur = _state.value.durationMs
            if (dur > 0) {
                val targetMs = (dur * fraction).toLong().coerceIn(0, dur)
                castExecutor.execute {
                    try { Dlna.seek(dev, targetMs) } catch (_: Exception) {}
                }
                _state.value = _state.value.copy(positionMs = targetMs)
            }
            return
        }
        val mp = mediaPlayer ?: return
        val dur = _state.value.durationMs
        if (dur > 0) {
            try {
                mp.seekTo((dur * fraction).toInt().coerceIn(0, dur.toInt()))
            } catch (_: Exception) {}
        }
    }

    fun currentSong(): Sources.Song? =
        _state.value.queue.getOrNull(_state.value.index)

    @Synchronized
    private fun resolveAndPlay() {
        val song = currentSong() ?: return
        val gen = ++generation
        _state.value = _state.value.copy(resolving = true, playing = false, prepared = false, positionMs = 0, durationMs = 0)
        Thread {
            // casting mode: mirror to renderer only, don't spin up local playback
            if (castDevice != null) {
                _state.value = _state.value.copy(resolving = false)
                castPushNow()
                return@Thread
            }
            val url = try { Sources.resolveUrl(song, _state.value.quality) } catch (e: Exception) { null }
            if (gen != generation) return@Thread   // user moved on
            if (url == null) {
                _state.value = _state.value.copy(resolving = false, error = "解析失败：${song.name}")
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return@Thread }
                if (gen != generation) return@Thread
                if (currentSong() == song) next()
                return@Thread
            }
            startPlayer(url, song, gen)
        }.start()
    }

    @Synchronized
    private fun startPlayer(url: String, song: Sources.Song, gen: Long) {
        // kill previous player BEFORE creating the new one
        stopTicker()
        val old = mediaPlayer
        mediaPlayer = null
        try { old?.release() } catch (_: Exception) {}

        val mp = try { MediaPlayer() } catch (e: Exception) {
            _state.value = _state.value.copy(resolving = false, error = "播放器初始化失败")
            return
        }
        mediaPlayer = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (gen != generation) return@setOnPreparedListener
                try {
                    it.start()
                } catch (_: IllegalStateException) { return@setOnPreparedListener }
                val dur = try { it.duration.toLong() } catch (_: Exception) { 0L }
                _state.value = _state.value.copy(
                    resolving = false, playing = true, prepared = true,
                    durationMs = if (dur > 0) dur else 0L
                )
                startTicker(gen)
                appContext?.let { ctx ->
                    try { PlaybackService.refresh(ctx) } catch (_: Exception) {}
                }
                // if cast mode was entered mid-flight (song resolved before cast started),
                // silence local: renderer owns playback now
                if (castDevice != null) {
                    try { it.pause() } catch (_: Exception) {}
                    _state.value = _state.value.copy(playing = false)
                }
            }
            mp.setOnCompletionListener {
                if (gen != generation) return@setOnCompletionListener
                _state.value = _state.value.copy(playing = false, prepared = false)
                stopTicker()
                next()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (gen != generation) return@setOnErrorListener true
                _state.value = _state.value.copy(resolving = false, playing = false, error = "播放错误 ($what/$extra)")
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            if (gen != generation) return
            try { mp.release() } catch (_: Exception) {}
            if (mediaPlayer === mp) mediaPlayer = null
            _state.value = _state.value.copy(resolving = false, error = "播放失败: ${e.message}")
        }
    }

    /** 500ms progress ticker; duration may arrive late (streaming ogg). */
    private fun startTicker(gen: Long) {
        stopTicker()
        progressTicker = java.util.Timer("haiyin-ticker", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    if (gen != generation) { cancel(); return }
                    try {
                        val mp = mediaPlayer ?: return
                        val pos = mp.currentPosition.toLong()
                        // refresh duration if it arrived late
                        val curDur = _state.value.durationMs
                        val dur = try { mp.duration.toLong() } catch (_: Exception) { 0L }
                        val st = _state.value
                        val newDur = if (dur > 0 && dur != curDur) dur else curDur
                        _state.value = st.copy(
                            positionMs = pos,
                            durationMs = if (st.durationMs > 0) st.durationMs else newDur
                        )
                    } catch (_: Exception) {}
                }
            }, 400, 500)
        }
    }

    private fun stopTicker() {
        progressTicker?.cancel()
        progressTicker = null
    }

    // ---------- DLNA cast-follow ----------

    /** When non-null, playback changes are mirrored to this renderer. */
    @Volatile var castDevice: Dlna.Device? = null

    /** True if currently casting (follow mode). */
    val casting: Boolean get() = castDevice != null

    private val castExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dlna-follow").apply { isDaemon = true }
    }

    /** Enter cast-follow mode: renderer becomes the player, local playback silenced. */
    @Synchronized
    fun enterCastMode(dev: Dlna.Device) {
        castDevice = dev
        // kill local playback immediately & bump generation so stale callbacks die
        generation++
        stopTicker()
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        if (currentSong() != null) {
            castPushNow()
        }
    }

    /** Leave cast mode: stop renderer, resume local playback of current track. */
    @Synchronized
    fun exitCastMode() {
        castStop()
        val idx = _state.value.index
        if (idx >= 0 && idx < _state.value.queue.size) {
            resolveAndPlay()
        }
    }

    // guard: don't treat renderer STOPPED as "track ended" within this window after a push
    @Volatile private var lastCastPushAt = 0L

    /** Push current track to the renderer. Safe to call repeatedly. */
    fun castPushNow() {
        val dev = castDevice ?: return
        val song = currentSong() ?: return
        val qualityNow = _state.value.quality
        lastCastPushAt = System.currentTimeMillis()
        castExecutor.execute {
            val url = try { Sources.resolveUrl(song, qualityNow) } catch (_: Exception) { null }
            if (url == null) return@execute
            if (castDevice !== dev) return@execute
            // pipe through the local proxy: the renderer gets a stable URL with
            // correct UA/Range handling; picky direct links stop failing on TVs.
            val proxiedUrl = try { StreamProxy.proxy(url) } catch (_: Exception) { url }
            val ok = try { Dlna.cast(dev, proxiedUrl, song.name, song.singer) } catch (_: Exception) { false }
            if (!ok || castDevice !== dev) return@execute
            // fetch total duration from renderer for the progress bar
            val dur = try { Dlna.durationMs(dev) } catch (_: Exception) { null }
            _state.value = _state.value.copy(
                resolving = false, prepared = true, playing = true,
                positionMs = 0, durationMs = dur ?: 0L,
            )
            appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
            startCastPoller()
        }
    }

    /** Mirror play/pause to renderer. */
    fun castSyncPlayback() {
        val dev = castDevice ?: return
        val playing = _state.value.playing
        castExecutor.execute {
            try {
                if (playing) Dlna.resume(dev) else Dlna.pause(dev)
            } catch (_: Exception) {}
        }
    }

    fun castStop() {
        val dev = castDevice ?: return
        castDevice = null
        stopCastPoller()
        castExecutor.execute {
            try { Dlna.stop(dev) } catch (_: Exception) {}
        }
    }

    private fun maybeCastFollow() {
        if (castDevice == null) return
        castPushNow()
    }

    // poll renderer position while casting: auto-advance at end
    @Volatile private var castPoller: java.util.Timer? = null

    private fun startCastPoller() {
        stopCastPoller()
        castPoller = java.util.Timer("dlna-poll", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    val dev = castDevice ?: run { cancel(); return }
                    val stateStr = try { Dlna.transportState(dev) } catch (_: Exception) { null } ?: return
                    val st = _state.value

                    // 1. If user paused playback, never treat STOPPED/PAUSED as "track finished"
                    if (!st.playing) {
                        val pos = try { Dlna.positionMs(dev) } catch (_: Exception) { null }
                        if (pos != null && pos > 0) {
                            _state.value = st.copy(positionMs = pos)
                        }
                        return
                    }

                    // 2. Grace window right after a push: renderer is mid-transition, skip end-detection
                    val inPushGrace = System.currentTimeMillis() - lastCastPushAt < 8000
                    if (inPushGrace) return

                    // 3. Genuine track end detection: renderer is STOPPED while playing AND near duration end
                    if (stateStr == "STOPPED" || stateStr == "NO_MEDIA_PRESENT") {
                        val isNearEnd = if (st.durationMs > 10_000) {
                            st.positionMs >= (st.durationMs - 6000).coerceAtLeast(0) ||
                                (st.positionMs.toFloat() / st.durationMs) > 0.88f
                        } else {
                            true
                        }

                        if (isNearEnd) {
                            val nextIdx = when (st.playMode) {
                                PlayMode.REPEAT_ONE -> st.index
                                PlayMode.SHUFFLE -> {
                                    if (st.queue.size <= 1) st.index
                                    else {
                                        var n = st.index
                                        while (n == st.index) n = (0 until st.queue.size).random()
                                        n
                                    }
                                }
                                PlayMode.SEQUENCE -> st.index + 1
                            }

                            if (nextIdx in st.queue.indices) {
                                _state.value = st.copy(index = nextIdx, playing = true, prepared = true, positionMs = 0)
                                appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
                                castPushNow()
                            } else {
                                _state.value = _state.value.copy(playing = false, prepared = false)
                                castStop()
                            }
                            return
                        }
                    }

                    // 4. Normal playback progress & state sync
                    val pos = try { Dlna.positionMs(dev) } catch (_: Exception) { null } ?: return
                    val inActionGrace = System.currentTimeMillis() - lastCastActionAt < 3500
                    val isPlayingNow = if (inActionGrace) _state.value.playing else (stateStr == "PLAYING")
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        playing = isPlayingNow,
                        prepared = true,
                    )
                }
            }, 2000, 2000)
        }
    }

    private fun stopCastPoller() {
        castPoller?.cancel()
        castPoller = null
    }

    // ---------- sleep timer ----------

    @Volatile private var sleepTimer: java.util.Timer? = null

    /** Schedule pause after [minutes]. 0 cancels. Returns the deadline description. */
    fun setSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        sleepTimer = null
        if (minutes <= 0) {
            _state.value = _state.value.copy(error = null)
            return
        }
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        sleepTimer = java.util.Timer("haiyin-sleep", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        val mp = mediaPlayer
                        if (mp != null && mp.isPlaying) mp.pause()
                        _state.value = _state.value.copy(playing = false)
                    } catch (_: Exception) {}
                }
            }, minutes * 60_000L)
        }
    }

    fun sleepRemainingMs(): Long {
        return 0L // simplified: one-shot timer, no countdown display in v0.5
    }

    fun release() {
        generation++
        stopTicker()
        stopCastPoller()
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.release() } catch (_: Exception) {}
    }
}
