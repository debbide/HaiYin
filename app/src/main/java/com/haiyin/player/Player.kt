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

    /**
     * Remove a song from the queue. If it was before the current song the index
     * shifts down; if it was the current song, playback continues with the song
     * now at that position (or stops when the queue is empty).
     */
    fun removeAt(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return
        val newQueue = st.queue.toMutableList().also { it.removeAt(index) }
        when {
            newQueue.isEmpty() -> {
                try { mediaPlayer?.pause() } catch (_: Exception) {}
                val dev = castDevice
                if (dev != null) castExecutor.execute { try { Dlna.stop(dev) } catch (_: Exception) {} }
                _state.value = st.copy(
                    queue = emptyList(), index = 0,
                    playing = false, prepared = false, positionMs = 0
                )
            }
            index < st.index ->
                _state.value = st.copy(queue = newQueue, index = st.index - 1)
            index == st.index -> {
                val newIndex = index.coerceAtMost(newQueue.lastIndex)
                _state.value = st.copy(queue = newQueue, index = newIndex, error = null)
                resolveAndPlay()
            }
            else ->
                _state.value = st.copy(queue = newQueue)
        }
        appContext?.let { PlaybackService.refresh(it) }
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

    // guard: a pause/resume is in flight on castExecutor; the 2s poller must not
    // fight it, and toggle taps are ignored until it settles
    @Volatile private var castBusy = false

    fun toggle() {
        // while casting, the remote renderer is the real player
        val dev = castDevice
        if (dev != null) {
            // ignore taps while a pause/resume is still in flight: mashing the
            // button mid-transition is how renderers get wedged
            if (castBusy) {
                DlnaTrace.log("toggle: IGNORED (busy)")
                return
            }
            lastCastActionAt = System.currentTimeMillis()
            if (_state.value.playing) {
                // pause: flip UI now, confirm against the device on the worker
                DlnaTrace.log("toggle: pause requested")
                _state.value = _state.value.copy(playing = false, error = null)
                castExecutor.execute { pauseCastPlayback(dev) }
            } else {
                // resume: don't claim playing until the device confirms it;
                // every failure path reports the real reason in state.error
                DlnaTrace.log("toggle: resume requested")
                _state.value = _state.value.copy(error = null)
                castExecutor.execute { resumeCastPlayback(dev) }
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

    /**
     * Pause the renderer and CONFIRM it actually paused.
     * The old Stop fallback is gone: it destroyed the media session, which made
     * resume depend on a full re-push. If the device won't pause, say so instead
     * of silently wedging the session.
     */
    private fun pauseCastPlayback(dev: Dlna.Device) {
        castBusy = true
        try {
            DlnaTrace.log("pause: sending Pause")
            try { Dlna.pause(dev) } catch (_: Exception) {}
            val endState = waitForTransportStates(dev, setOf("PAUSED_PLAYBACK", "STOPPED"), 5000)
            if (castDevice !== dev) return
            DlnaTrace.log("pause: confirmed $endState")
            if (endState == "PAUSED_PLAYBACK" || endState == "STOPPED") {
                _state.value = _state.value.copy(playing = false, error = null)
            } else {
                // device still playing (or unreachable): revert UI, report it
                _state.value = _state.value.copy(
                    playing = true,
                    error = "DLNA 设备暂停失败" + (endState?.let { "（$it）" } ?: "")
                )
            }
            appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
        } finally {
            castBusy = false
        }
    }

    /**
     * Resume renderer playback robustly:
     *  1. try Play (covers PAUSED_PLAYBACK on well-behaved renderers);
     *  2. TRANSITIONING gets extra time — interrupting it with SetAVTransportURI
     *     aborts playback on strict renderers;
     *  3. otherwise do a full re-push (SetAVTransportURI + Play + seek back);
     *  4. every failure path reports the real reason in state.error and leaves
     *     playing=false — never silently pretend it worked.
     */
    private fun resumeCastPlayback(dev: Dlna.Device) {
        castBusy = true
        try {
            DlnaTrace.log("resume: sending Play")
            try { Dlna.resume(dev) } catch (_: Exception) {}
            var ok = waitForTransportState(dev, "PLAYING", 5000)
            if (!ok && castDevice === dev) {
                val s = tracedTransportState(dev)
                DlnaTrace.log("resume: Play did not start playback (now $s)")
                if (s == "TRANSITIONING") {
                    DlnaTrace.log("resume: TRANSITIONING, waiting it out")
                    ok = waitForTransportState(dev, "PLAYING", 5000)
                }
            }
            if (!ok && castDevice === dev) {
                DlnaTrace.log("resume: falling back to full re-push")
                ok = repushCast(dev)
            }
            if (castDevice !== dev) return
            DlnaTrace.log("resume: result ok=$ok")
            if (ok) {
                _state.value = _state.value.copy(playing = true, prepared = true, error = null)
                appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
                // The renderer CLAIMED playing — make sure it isn't lying. Some
                // renderers report PLAYING while the stream is stalled (silent).
                // On a frozen position, re-push transparently instead of making
                // the user tap play a second time.
                if (!verifyPlaybackAdvancing(dev)) {
                    _state.value = _state.value.copy(playing = false)
                    appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
                }
            }
            // on failure repushCast already set state.error with the real reason
            appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
        } finally {
            castBusy = false
        }
    }

    /**
     * Returns true when the renderer's position actually advances within 3s of
     * a PLAYING claim. On a frozen position (stalled/silent stream) does a full
     * re-push and returns its result. Runs on castExecutor with castBusy held.
     */
    private fun verifyPlaybackAdvancing(dev: Dlna.Device): Boolean {
        val st = _state.value
        // too close to the end to tell: the track may legitimately finish mid-check
        if (st.durationMs > 10_000 && st.positionMs >= st.durationMs - 8000) {
            DlnaTrace.log("resume: near track end, skipping position verify")
            return true
        }
        val p1 = try { Dlna.positionMs(dev) } catch (_: Exception) { null }
        try { Thread.sleep(3000) } catch (_: InterruptedException) { return false }
        if (castDevice !== dev) return false
        val p2 = try { Dlna.positionMs(dev) } catch (_: Exception) { null }
        DlnaTrace.log("resume: verify position ${p1 ?: "?"} -> ${p2 ?: "?"}")
        if (p1 == null || p2 == null) {
            DlnaTrace.log("resume: position unreadable, trusting PLAYING")
            return true
        }
        if (p2 > p1) return true
        DlnaTrace.log("resume: position FROZEN, re-pushing")
        return repushCast(dev)
    }

    /**
     * Full re-push of the current track to the renderer. Returns true when the
     * renderer reaches PLAYING. Sets state.error with the real reason on failure.
     *
     * Cheap renderers process SetAVTransportURI asynchronously and silently drop
     * a Play that arrives while they're still busy — so we wait for a stable
     * state before Play, and retry Play once before giving up.
     */
    private fun repushCast(dev: Dlna.Device): Boolean {
        val song = currentSong()
        if (song == null) {
            _state.value = _state.value.copy(error = "恢复失败：播放队列为空")
            return false
        }
        val url = try { Sources.resolveUrl(song, _state.value.quality) } catch (_: Exception) { null }
        if (url == null) {
            val reason = SourceScripts.lastResolveError
            _state.value = _state.value.copy(
                error = if (reason.isNullOrBlank()) "恢复失败：解析播放地址失败"
                else "恢复失败：$reason"
            )
            return false
        }
        val resumePos = _state.value.positionMs
        // pipe through the local proxy: the renderer gets a stable URL with
        // correct UA/Range handling; picky direct links stop failing on TVs.
        val proxiedUrl = try { StreamProxy.proxy(url) } catch (_: Exception) { url }
        DlnaTrace.log("repush: sending SetAVTransportURI")
        val uriOk = try { Dlna.setUri(dev, proxiedUrl, song.name, song.singer) } catch (_: Exception) { false }
        if (castDevice !== dev) return false  // user left cast mode mid-resume
        if (!uriOk) {
            DlnaTrace.log("repush: SetAVTransportURI rejected")
            _state.value = _state.value.copy(error = "恢复失败：设备拒绝播放地址")
            return false
        }
        // let the renderer digest the URI before Play: a Play sent mid-transition
        // is silently dropped by strict renderers (the "first play does nothing,
        // second play works" symptom)
        val settled = waitForTransportStates(
            dev,
            setOf("STOPPED", "PAUSED_PLAYBACK", "PLAYING", "NO_MEDIA_PRESENT"),
            3000
        )
        DlnaTrace.log("repush: settled at $settled, sending Play")
        if (castDevice !== dev) return false
        repeat(2) {
            try { Dlna.resume(dev) } catch (_: Exception) {}
            if (waitForTransportState(dev, "PLAYING", 6000)) {
                // Seek only after PLAYING is confirmed — blind seeks mid-transition are
                // rejected (or worse, abort playback) on strict renderers.
                if (resumePos > 2000) {
                    try { Dlna.seek(dev, resumePos) } catch (_: Exception) {}
                }
                DlnaTrace.log("repush: PLAYING, seek to $resumePos")
                return true
            }
            DlnaTrace.log("repush: Play attempt did not start playback, retrying")
        }
        val lastState = tracedTransportState(dev)
        DlnaTrace.log("repush: FAILED last=$lastState")
        _state.value = _state.value.copy(
            error = "恢复失败：设备无响应" + (lastState?.let { "（$it）" } ?: "")
        )
        return false
    }

    /** Poll GetTransportInfo until [want] or timeout. Runs on castExecutor. */
    private fun waitForTransportState(dev: Dlna.Device, want: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (castDevice !== dev) return false
            val s = tracedTransportState(dev)
            if (s == want) return true
            try { Thread.sleep(500) } catch (_: Exception) { return false }
        }
        return false
    }

    /** Poll GetTransportInfo until the state lands in [wants]; returns the last seen state. */
    private fun waitForTransportStates(
        dev: Dlna.Device,
        wants: Set<String>,
        timeoutMs: Long
    ): String? {
        var last: String? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (castDevice !== dev) return last
            last = tracedTransportState(dev)
            if (last != null && last in wants) return last
            try { Thread.sleep(500) } catch (_: Exception) { return last }
        }
        return last
    }

    // last transport state written to DlnaTrace; transitions are the interesting part
    @Volatile private var lastTracedTransport: String? = null

    /** GetTransportInfo with transition logging for the DLNA diagnostics view. */
    private fun tracedTransportState(dev: Dlna.Device): String? {
        val s = try { Dlna.transportState(dev) } catch (_: Exception) { null }
        val prev = lastTracedTransport
        if (s != prev) {
            DlnaTrace.log("transport: ${prev ?: "?"} -> ${s ?: "?"}")
            lastTracedTransport = s
        }
        return s
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
                val reason = SourceScripts.lastResolveError
                _state.value = _state.value.copy(
                    resolving = false,
                    error = if (reason.isNullOrBlank()) "解析失败：${song.name}"
                    else "解析失败：${song.name}（$reason）"
                )
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
            // fetch total duration from renderer for the progress bar; many
            // renderers report nothing usable for proxied streams, so fall back
            // to the search-result duration (Song.interval, "m:ss")
            val dur = (try { Dlna.durationMs(dev) } catch (_: Exception) { null })
                .takeIf { it != null && it > 0 } ?: parseIntervalMs(song.interval)
            _state.value = _state.value.copy(
                resolving = false, prepared = true, playing = true,
                positionMs = 0, durationMs = dur,
            )
            appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
            startCastPoller()
        }
    }

    /** Parse a "m:ss" / "h:mm:ss" duration string (Song.interval) to ms, or 0. */
    private fun parseIntervalMs(interval: String): Long {
        val parts = interval.split(":")
        return try {
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000
                else -> 0L
            }
        } catch (_: Exception) { 0L }
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
                    // a pause/resume is in flight: don't fight it (it owns state)
                    if (castBusy) return
                    val stateStr = tracedTransportState(dev) ?: return
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
                                DlnaTrace.log("poller: end ($stateStr), advancing ${st.index} -> $nextIdx")
                                _state.value = st.copy(index = nextIdx, playing = true, prepared = true, positionMs = 0)
                                appContext?.let { try { PlaybackService.refresh(it) } catch (_: Exception) {} }
                                castPushNow()
                            } else {
                                DlnaTrace.log("poller: end ($stateStr), queue exhausted, stopping")
                                _state.value = _state.value.copy(playing = false, prepared = false)
                                castStop()
                            }
                            return
                        } else {
                            DlnaTrace.log("poller: $stateStr but pos=${st.positionMs}/${st.durationMs} not near end, ignoring")
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
