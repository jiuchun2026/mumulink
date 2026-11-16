package com.mumulink.remote.core

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.mumulink.remote.model.DeviceInfo
import com.mumulink.remote.model.DisplayMode
import com.mumulink.remote.model.DisplaySpec
import com.mumulink.remote.model.LinkState
import com.mumulink.remote.model.QualityPresets
import com.mumulink.remote.model.QualitySpec
import com.mumulink.remote.model.RemoteKey
import com.mumulink.remote.model.StreamStats
import com.mumulink.remote.net.AgentClient
import com.mumulink.remote.net.AgentException
import com.mumulink.remote.net.ControlDrainer
import com.mumulink.remote.net.StreamException
import com.mumulink.remote.net.StreamSocket
import com.mumulink.remote.net.VideoFrameParser
import com.mumulink.remote.net.VideoFrameReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 串流会话状态机（SPEC 4.7）。
 *
 * ```
 * Idle → Connecting(step) → Streaming(stats) ⇄ Retrying(attempt,reason) → Failed
 *                ↑                                   │
 *                └──── 画质/显示切换（重启会话后重连） ──┘
 * ```
 *
 * 连接顺序严格按服务端行为：hello →（需要时）pair → session/start → **先连视频端口**
 * → 立刻连控制端口（scrcpy server 在推流前会顺序 accept video/control，两条都连上才出流）
 * → 等 CONFIG/IDR → 解码上屏。
 *
 * 线程模型：
 * - 协程（[scope]，由 ViewModel 提供 IO 调度器）负责 HTTP、重连、统计；
 * - 视频/控制 socket 各自一个守护线程做阻塞读；
 * - 解码器创建/释放走独立的单线程 executor（MediaCodec 操作昂贵，不碰主线程）。
 */
class SessionController(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "MuMuLink"
        private const val MAX_RETRIES = 6
        private const val BACKOFF_BASE_MS = 400L
        private const val BACKOFF_MAX_MS = 4_000L
        private const val DEFAULT_VIDEO_PORT = 27182
        private const val DEFAULT_CONTROL_PORT = 27183
        private const val ADAPT_EVAL_MS = 3_000L
        private const val ADAPT_GOOD_MS = 15_000L
        /** 有帧到达但渲染帧这么久没涨，就判定解码/渲染卡死并重建解码器。 */
        private const val RENDER_WATCHDOG_MS = 5_000L
    }

    // ---------------- 对外暴露 ----------------

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /** 当前画质（自适应会改它）。 */
    @Volatile
    var quality: QualitySpec = QualityPresets.BALANCED
        private set

    @Volatile
    private var autoQuality = false

    /**
     * 是否可发送输入。**只看控制通道是否还活着**，不看 LinkState：
     * 中途 SESSION 包、重连退避都会让 state 短暂离开 Streaming，
     * 但此时旧控制 socket 往往仍可写，用状态门控会把用户触摸吃掉。
     */
    val isStreaming: Boolean get() = controlWriter != null && !disconnecting

    /** 配对成功拿到新 token 时回调（ViewModel 落盘用）。 */
    @Volatile
    var onTokenChanged: ((String) -> Unit)? = null

    /** 显示参数被自动回滚（MATCH 失败 → FIT）时回调，ViewModel 负责落盘与刷新 UI。 */
    @Volatile
    var onDisplayChanged: ((DisplaySpec) -> Unit)? = null

    // ---------------- 会话参数 ----------------

    private val agent = AgentClient()

    @Volatile
    private var host: String = ""

    @Volatile
    private var httpPort: Int = 27180

    @Volatile
    private var display: DisplaySpec = DisplaySpec()

    @Volatile
    private var pendingPairingCode: String? = null

    @Volatile
    private var videoPort: Int = DEFAULT_VIDEO_PORT

    @Volatile
    private var controlPort: Int = DEFAULT_CONTROL_PORT

    @Volatile
    private var disconnecting = false

    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var monitorJob: Job? = null

    /** 连接代际：connect()/restart() 每次递增，过期协程的异常不再改状态。 */
    @Volatile
    private var attemptSeq = 0

    // ---------------- 传输层 ----------------

    @Volatile
    private var videoSocket: StreamSocket? = null

    @Volatile
    private var controlSocket: StreamSocket? = null

    @Volatile
    private var controlWriter: ControlWriter? = null

    @Volatile
    private var videoThread: Thread? = null

    @Volatile
    private var controlThread: Thread? = null

    /** 会话代际：每次拆流 +1，旧读线程的回调据此丢弃，避免“重连风暴”。 */
    @Volatile
    private var generation = 0

    private val parser = VideoFrameParser(object : VideoFrameParser.Listener {
        override fun onSession(width: Int, height: Int, clientResize: Boolean) {
            Log.i(TAG, "视频会话：${width}x$height clientResize=$clientResize")
            metaW = width
            metaH = height
            _stats.value = _stats.value.copy(deviceWidth = width, deviceHeight = height)
            // 流中重建编码器：旧解码器输出尺寸不匹配，立刻停喂并释放，等新的 CONFIG
            val old = decoder
            decoder = null
            sessionStale = true
            awaitingKey = true
            if (old != null) decodeExecutor.execute { old.release() }
        }

        override fun onConfig(data: ByteArray, offset: Int, length: Int) {
            val csdBytes = data.copyOfRange(offset, offset + length)
            synchronized(pipeLock) { csd = csdBytes }
            // 必须同步建好解码器：CONFIG 后紧跟的 IDR 帧通常就在同一批 socket 数据里，
            // 异步建 codec 会把这一帧丢掉，awaitingKey 又等不到下一个关键帧（静止画面可能
            // 长时间不发新关键帧），结果就是永久黑屏。建 codec 约 50–200ms，停一下读线程无妨。
            ensureDecoderNow()
        }

        override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {
            framesSeen++
            lastFrameSeenAt = SystemClock.elapsedRealtime()
            // CONFIG 已缓存但 surface 晚到（切后台/重连）时，这里补建，避免整条流被丢
            var d = decoder
            if (d == null) {
                ensureDecoderNow()
                d = decoder
            }
            if (d == null) return
            if (sessionStale) return
            if (awaitingKey) {
                if (!keyFrame) return
                awaitingKey = false
            }
            d.queueFrame(data, offset, length, ptsUs, keyFrame)
            lastFrameAt = SystemClock.elapsedRealtime()
        }
    })

    private val inputMapper = InputMapper({ controlWriter }, { metaW to metaH })

    // ---------------- 解码器 ----------------

    private val pipeLock = Any()
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MuMuLink-decoder-mgr").apply { isDaemon = true }
    }
    private val decoderBusy = AtomicBoolean(false)

    @Volatile
    private var decoder: VideoDecoder? = null

    @Volatile
    private var surface: Surface? = null

    private var csd: ByteArray? = null

    @Volatile
    private var metaW = 0

    @Volatile
    private var metaH = 0

    @Volatile
    private var awaitingKey = true

    /** 收到新 SESSION 后置 true：旧尺寸的帧全部丢弃，直到新解码器就绪。 */
    @Volatile
    private var sessionStale = false

    @Volatile
    private var lastFrameAt = 0L

    /** 收到的视频帧总数（含被丢弃的），用于"有帧但没出画"的自检。 */
    @Volatile
    private var framesSeen = 0L

    /** 最近一次收到帧的时间，自检时用来区分「画面静止没有帧」和「有帧但没渲染」。 */
    @Volatile
    private var lastFrameSeenAt = 0L

    /** 解码器创建重试标记（避免在 synchronized 块里递归调用）。 */
    @Volatile
    private var decoderRetryNeeded = false

    private val decoderLock = Any()

    @Volatile
    private var decoderFailures = 0

    // =====================================================================
    // 公开操作
    // =====================================================================

    /**
     * 建立一条新会话（会先拆掉旧连接）。可在任意线程调用。
     *
     * @param token 上次保存的配对 token；为空时必须有 [pairingCode]
     */
    fun connect(
        host: String,
        httpPort: Int = 27180,
        token: String? = null,
        pairingCode: String? = null,
        quality: QualitySpec = QualityPresets.BALANCED,
        display: DisplaySpec = DisplaySpec(),
    ) {
        // allowDisplayFallback：MATCH 显示被代理拒绝时，自动退回 FIT 再试一次，
        // 避免用户被“持久化的 match + 必然失败”卡死在连接页。
        connectInternal(host, httpPort, token, pairingCode, quality, display, allowDisplayFallback = true)
    }

    private fun connectInternal(
        host: String,
        httpPort: Int,
        token: String?,
        pairingCode: String?,
        quality: QualitySpec,
        display: DisplaySpec,
        allowDisplayFallback: Boolean,
    ) {
        Log.i(TAG, "connect host=$host:$httpPort quality=${quality.label}(${quality.maxSize}p) display=${display.mode}")
        disconnecting = false
        this.host = host
        this.httpPort = httpPort
        this.quality = quality
        this.display = display
        this.pendingPairingCode = pairingCode?.takeIf { it.isNotBlank() }
        agent.token = token?.takeIf { it.isNotBlank() }

        connectJob?.cancel()
        retryJob?.cancel()
        monitorJob?.cancel()
        teardownStreams()
        releaseDecoder()

        _stats.value = StreamStats()
        _state.value = LinkState.Connecting("正在连接代理")
        val seq = ++attemptSeq
        connectJob = scope.launch {
            try {
                val hello = helloOrPair()
                _device.value = hello.device
                _state.value = LinkState.Connecting("正在启动会话")
                val started = startSession()
                videoPort = started.videoPort
                controlPort = started.controlPort
                // 会话启动后刷新生效尺寸/密度（MATCH 会改 wm size）
                refreshDevice(started)
                _state.value = LinkState.Connecting("正在接收画面")
                openStreams()
                startMonitor()
                _state.value = LinkState.Streaming(_stats.value)
            } catch (e: Throwable) {
                // 用户中途断开/切换目标时，旧协程的失败不能把新状态盖成 Failed
                if (disconnecting || seq != attemptSeq) return@launch
                // MATCH 被拒：回滚到 FIT 重试一次（把“必然失败”变成可恢复）
                if (allowDisplayFallback && this@SessionController.display.mode == DisplayMode.MATCH) {
                    val fallback = DisplaySpec(DisplayMode.FIT)
                    Log.w(TAG, "显示匹配失败，自动回滚 FIT：${e.message}")
                    this@SessionController.display = fallback
                    onDisplayChanged?.invoke(fallback)
                    emitToast("显示匹配失败，已恢复「适配屏幕」：${describeFull(e)}")
                    connectInternal(host, httpPort, agent.token, null, quality, fallback, allowDisplayFallback = false)
                    return@launch
                }
                Log.e(TAG, "连接失败：${e.javaClass.simpleName} ${e.message}")
                fail(e)
            }
        }
    }

    fun disconnect(stopRemoteSession: Boolean = true) {
        disconnecting = true
        runCatching { inputMapper.releaseAllPointers() }
        connectJob?.cancel()
        retryJob?.cancel()
        monitorJob?.cancel()
        teardownStreams()
        releaseDecoder()
        inputMapper.reset()
        _state.value = LinkState.Idle
        _stats.value = StreamStats()
        val h = host
        val p = httpPort
        if (stopRemoteSession && h.isNotBlank()) {
            scope.launch { runCatching { agent.stopSession(host = h, httpPort = p) } }
        }
    }

    /** onCleared 调用：断开并回收线程池。 */
    fun shutdown() {
        disconnect(stopRemoteSession = true)
        decodeExecutor.shutdown()
    }

    fun setQuality(q: QualitySpec) {
        if (q == quality) return
        quality = q
        if (isLive()) restart(reason = "正在切换清晰度", qualityChange = true)
    }

    fun setAutoQuality(enabled: Boolean) {
        autoQuality = enabled
    }

    fun setDisplay(spec: DisplaySpec) {
        if (spec == display) return
        val previous = display
        display = spec
        // 未串流时只记住选择，连接/重连时随 /api/session/start 一起下发
        if (_state.value !is LinkState.Streaming) return
        val seq = ++attemptSeq
        scope.launch {
            try {
                // 先让代理真正应用并校验（失败会抛 AgentException，带中文 hint），再重启串流
                agent.setDisplay(host = host, display = spec, httpPort = httpPort)
                if (disconnecting || seq != attemptSeq) return@launch
                restart(reason = "正在调整显示", qualityChange = false)
            } catch (e: Throwable) {
                if (disconnecting || seq != attemptSeq) return@launch
                // 失败要留在串流页：回滚到上一个模式并说明原因，避免用户卡在必然失败的 match 上
                Log.w(TAG, "setDisplay 失败，回滚 ${displayLabel(previous)}：${describeFull(e)}")
                display = previous
                onDisplayChanged?.invoke(previous)
                emitToast("显示模式切换失败，已恢复「${displayLabel(previous)}」：${describeFull(e)}")
                // 代理可能已经把会话/显示改坏了：控制通道没了就按旧显示重连一次
                if (controlWriter == null && isLive()) restart(reason = "正在恢复画面", qualityChange = false)
            }
        }
    }

    /** UI 层 Surface 就绪（主线程调用，只做引用赋值，解码器重建在解码线程完成）。 */
    fun attachSurface(s: Surface) {
        surface = s
        val old = decoder
        decoder = null
        sessionStale = false // 复用已缓存的 csd 重建
        if (old != null) decodeExecutor.execute { old.release() }
        ensureDecoder()
        // 换 Surface/从后台回来时旧解码器已丢，服务端只会继续发 P 帧；
        // 主动请求编码器重置，保证新解码器马上收到 SESSION + SPS/PPS + IDR（否则黑屏）
        controlWriter?.resetVideo()
    }

    fun detachSurface(s: Surface) {
        if (surface === s) {
            surface = null
            val d = decoder
            decoder = null
            sessionStale = true
            if (d != null) decodeExecutor.execute { d.release() }
        }
    }

    fun touch(phase: TouchPhase, pointerId: Long, xNorm: Float, yNorm: Float, pressed: Boolean? = null) {
        inputMapper.touch(phase, pointerId, xNorm, yNorm, pressed)
    }

    fun sendKey(key: RemoteKey) {
        val writer = controlWriter
        if (writer == null || !isStreaming) {
            emitToast("未连接，按键无效")
            return
        }
        when (key) {
            RemoteKey.BACK -> {
                writer.injectKeycode(0, 4)
                writer.injectKeycode(1, 4)
            }
            RemoteKey.HOME -> {
                writer.injectKeycode(0, 3)
                writer.injectKeycode(1, 3)
            }
            RemoteKey.APP_SWITCH -> {
                writer.injectKeycode(0, 187)
                writer.injectKeycode(1, 187)
            }
            RemoteKey.POWER -> {
                writer.injectKeycode(0, 26)
                writer.injectKeycode(1, 26)
            }
            RemoteKey.VOLUME_UP -> {
                writer.injectKeycode(0, 24)
                writer.injectKeycode(1, 24)
            }
            RemoteKey.VOLUME_DOWN -> {
                writer.injectKeycode(0, 25)
                writer.injectKeycode(1, 25)
            }
            RemoteKey.MENU -> {
                writer.injectKeycode(0, 82)
                writer.injectKeycode(1, 82)
            }
            RemoteKey.ROTATE -> writer.rotateDevice()
        }
    }

    /** 文本优先走 HTTP /api/text（SPEC 4.5），失败回落到控制通道 INJECT_TEXT。 */
    fun sendText(text: String) {
        if (text.isBlank()) return
        val h = host
        val p = httpPort
        if (h.isBlank()) {
            emitToast("未连接")
            return
        }
        scope.launch {
            try {
                agent.sendText(host = h, text = text, httpPort = p)
            } catch (e: Throwable) {
                val w = controlWriter
                if (w != null && isStreaming) {
                    w.injectText(text)
                    emitToast("已通过控制通道输入")
                } else {
                    emitToast("发送文本失败：${e.message ?: "网络错误"}")
                }
            }
        }
    }

    // =====================================================================
    // 连接流程
    // =====================================================================

    private suspend fun helloOrPair(): com.mumulink.remote.model.AgentHello {
        var hello = agent.hello(host, httpPort)
        if (!agent.isPaired) {
            val code = pendingPairingCode
                ?: throw AgentException("PAIRING_REQUIRED", "需要配对码", "请在代理网页（http://$host:27180/）查看配对码后输入")
            agent.pair(host, code, httpPort)
            agent.token?.let { onTokenChanged?.invoke(it) }
            hello = agent.hello(host, httpPort)
        }
        return hello
    }

    /** 带 token 失效自动重配对的包装。 */
    private suspend fun <T> withTokenRetry(block: suspend () -> T): T = try {
        block()
    } catch (e: AgentException) {
        if (e.code == "BAD_TOKEN") {
            val code = pendingPairingCode
                ?: throw AgentException("BAD_TOKEN", "配对凭证已失效", "请重新输入代理网页上显示的配对码")
            agent.pair(host, code, httpPort)
            agent.token?.let { onTokenChanged?.invoke(it) }
            block()
        } else {
            throw e
        }
    }

    private suspend fun startSession(): com.mumulink.remote.net.SessionStartResult =
        withTokenRetry { agent.startSession(host = host, quality = quality, display = display, httpPort = httpPort) }

    /**
     * 先视频、后控制。两条 TCP 都握手成功前，scrcpy server 一个字节都不会发。
     */
    private fun openStreams() {
        teardownStreams() // 拆掉旧连接并递增 generation
        parser.reset()
        val gen = generation
        val token = agent.token
            ?: throw StreamException("BAD_TOKEN", "缺少配对 token", "请重新配对")
        val v = StreamSocket(host, videoPort, token)
        v.connect()
        videoSocket = v
        val reader = VideoFrameReader(v.inputStream, parser) { error ->
            onStreamEnded(gen, "视频", error)
        }
        videoThread = Thread(reader, "MuMuLink-video").apply {
            isDaemon = true
            start()
        }
        // 视频线程立刻在阻塞读上等待；这里马不停蹄连控制口，避免 server 等 accept 超时
        val c = StreamSocket(host, controlPort, token)
        c.connect()
        controlSocket = c
        val writer = ControlWriter(c.outputStream) { error -> onStreamEnded(gen, "控制", error) }
        controlWriter = writer
        val drainer = ControlDrainer(c.inputStream) { error -> onStreamEnded(gen, "控制", error) }
        controlThread = Thread(drainer, "MuMuLink-control").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "视频/控制通道已建立（gen=$gen）")
    }

    private fun teardownStreams() {
        generation++
        runCatching { inputMapper.releaseAllPointers() }
        inputMapper.reset()
        runCatching { videoSocket?.close() }
        runCatching { controlSocket?.close() }
        videoSocket = null
        controlSocket = null
        controlWriter?.close()
        controlWriter = null
        videoThread = null
        controlThread = null
    }

    private fun onStreamEnded(gen: Int, channel: String, error: Throwable?) {
        if (gen != generation || disconnecting) return
        val reason = when (error) {
            is StreamException -> error.message ?: "连接断开"
            null -> "连接断开"
            else -> error.message ?: error.javaClass.simpleName
        }
        Log.w(TAG, "$channel 通道结束：$reason")
        if (_state.value is LinkState.Retrying) return
        scheduleReconnect(reason)
    }

    // =====================================================================
    // 重连 / 重启会话
    // =====================================================================

    private fun scheduleReconnect(reason: String) {
        if (retryJob?.isActive == true || disconnecting) return
        monitorJob?.cancel()
        retryJob = scope.launch {
            var lastReason = reason
            var attempt = 0
            while (attempt < MAX_RETRIES && !disconnecting && scope.isActive) {
                attempt++
                _state.value = LinkState.Retrying(attempt, lastReason)
                delay(backoffMs(attempt))
                try {
                    _state.value = LinkState.Connecting("正在重新连接（$attempt/$MAX_RETRIES）")
                    if (agent.token.isNullOrBlank()) {
                        throw AgentException("BAD_TOKEN", "配对凭证丢失", "请重新配对")
                    }
                    if (attempt > 1) {
                        // 第二次起强制重建会话：scrcpy server 在流断开后通常已经退出
                        helloOrPair()
                        val started = startSession()
                        videoPort = started.videoPort
                        controlPort = started.controlPort
                        refreshDevice(started)
                    }
                    openStreams()
                    startMonitor()
                    _state.value = LinkState.Streaming(_stats.value)
                    emitToast("已重新连接")
                    return@launch
                } catch (e: Throwable) {
                    lastReason = describe(e)
                    Log.w(TAG, "重连第 $attempt 次失败：$lastReason")
                }
            }
            if (!disconnecting) {
                teardownStreams()
                releaseDecoder()
                _state.value = LinkState.Failed("连接已断开", "已重试 $MAX_RETRIES 次：$lastReason")
            }
        }
    }

    /**
     * 画质/显示变化：重启会话后重连。
     * [qualityChange] true 时调 /api/session/quality（代理会重启 scrcpy），否则用 /api/session/start
     * 应用新的 wm/显示参数并重启。
     */
    private fun restart(reason: String, qualityChange: Boolean) {
        if (disconnecting) return
        connectJob?.cancel()
        retryJob?.cancel()
        monitorJob?.cancel()
        teardownStreams()
        releaseDecoder()
        _state.value = LinkState.Connecting(reason)
        val seq = ++attemptSeq
        connectJob = scope.launch {
            try {
                if (qualityChange) {
                    withTokenRetry { agent.setQuality(host = host, quality = quality, httpPort = httpPort) }
                } else {
                    withTokenRetry { agent.startSession(host = host, quality = quality, display = display, httpPort = httpPort) }
                }
                // 会话重启会改 wm 尺寸/密度（MATCH、DPI 切换），HUD 必须拿到生效值
                refreshDevice()
                openStreams()
                startMonitor()
                _state.value = LinkState.Streaming(_stats.value)
            } catch (e: Throwable) {
                if (disconnecting || seq != attemptSeq) return@launch
                Log.w(TAG, "$reason 失败：${describe(e)}")
                scheduleReconnect(describe(e))
            }
        }
    }

    /**
     * 会话启动/重启后刷新设备信息：`hello` 读的是**当前生效**的 wm 尺寸与密度，
     * 而 connect 时的 hello 只反映连接前的物理尺寸（MATCH/DPI 切换后会不同）。
     * 失败时退回用 session/start 返回的宽高（拿不到密度就保留旧值）。
     */
    private suspend fun refreshDevice(started: com.mumulink.remote.net.SessionStartResult? = null) {
        val fresh = runCatching { agent.hello(host = host, httpPort = httpPort) }.getOrNull()?.device
        if (fresh != null) {
            _device.value = fresh
            return
        }
        val old = _device.value ?: return
        if (started != null && started.deviceW > 0 && started.deviceH > 0) {
            _device.value = old.copy(width = started.deviceW, height = started.deviceH)
        }
    }

    private fun isLive(): Boolean = when (_state.value) {
        is LinkState.Streaming, is LinkState.Retrying -> true
        else -> false
    }

    private fun backoffMs(attempt: Int): Long =
        (BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(BACKOFF_MAX_MS)

    // =====================================================================
    // 解码器
    // =====================================================================

    /**
     * 同步创建解码器（幂等）。**视频读线程会直接调用**，因此这里不能做长阻塞以外的事；
     * 解码回调本身跑在 VideoDecoder 自己的 HandlerThread 上，不会死锁。
     */
    private fun ensureDecoderNow() {
        synchronized(decoderLock) {
            if (decoder != null) return
            val surf = surface ?: return
            val config = csd ?: return
            val w = metaW
            val h = metaH
            if (w <= 0 || h <= 0) return
            try {
                decoder = VideoDecoder(
                    surface = surf,
                    csd0 = config,
                    width = w,
                    height = h,
                    onSizeChanged = { ow, oh -> onDecodedSize(ow, oh) },
                    onError = { e -> onDecoderError(e) },
                    preferSoftware = decoderFailures > 0,
                )
                sessionStale = false
                awaitingKey = true
                lastFrameAt = SystemClock.elapsedRealtime()
            } catch (e: Throwable) {
                decoderFailures++
                Log.e(TAG, "解码器创建失败（第 $decoderFailures 次）：${e.javaClass.simpleName} ${e.message}")
                if (decoderFailures <= 2) {
                    decoderRetryNeeded = true
                    emitToast("解码器异常，正在尝试备用解码器")
                } else {
                    _state.value = LinkState.Failed(
                        "设备解码失败：${e.message ?: e.javaClass.simpleName}",
                        "MuMu 的 H.264 硬解不可用时请尝试「流畅」画质或更新 MuMu",
                    )
                }
            }
        }
    }

    /**
     * 异步版本（供 Surface 回调等不能阻塞的线程使用）。
     * 失败且还有重试额度时自动换软件解码器再试。
     */
    private fun ensureDecoder() {
        if (!decoderBusy.compareAndSet(false, true)) return
        decodeExecutor.execute {
            try {
                ensureDecoderNow()
            } finally {
                decoderBusy.set(false)
                if (decoderRetryNeeded) {
                    decoderRetryNeeded = false
                    ensureDecoder()
                }
            }
        }
    }

    /**
     * 记录解码器故障。返回 true 表示值得换软件解码器再试一次。
     * 注意：MediaCodec 回调就在解码线程上，这里绝不同步 release（会死在回调里）。
     */
    private fun handleDecoderFailure(e: Throwable): Boolean {
        decoderFailures++
        Log.e(TAG, "解码器故障（第 $decoderFailures 次）：${e.javaClass.simpleName} ${e.message}")
        if (decoderFailures <= 2) {
            emitToast("解码器异常，正在尝试备用解码器")
            return true
        }
        _state.value = LinkState.Failed(
            "设备解码失败：${e.message ?: e.javaClass.simpleName}",
            "MuMu 的 H.264 硬解不可用时请尝试「流畅」画质或更新 MuMu",
        )
        return false
    }

    private fun onDecodedSize(w: Int, h: Int) {
        _stats.value = _stats.value.copy(deviceWidth = w, deviceHeight = h)
        Log.i(TAG, "解码输出尺寸更新：${w}x$h")
    }

    /** 运行期解码器报错（MediaCodec.Callback.onError）：先释放旧实例，再尝试重建。 */
    private fun onDecoderError(e: Throwable) {
        val d = decoder
        decoder = null
        sessionStale = true
        if (d != null) decodeExecutor.execute { d.release() }
        // 保留 decoderFailures 计数，下一步 ensureDecoderNow() 会带着 preferSoftware=true 重试
        val retry = handleDecoderFailure(e)
        if (retry) {
            decodeExecutor.execute { ensureDecoder() }
        }
    }

    private fun releaseDecoder() {
        val d = decoder
        decoder = null
        sessionStale = true
        awaitingKey = true
        decoderFailures = 0
        if (d != null) decodeExecutor.execute { d.release() }
    }

    // =====================================================================
    // 统计 + 自适应画质
    // =====================================================================

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastBytes = parser.bytesConsumed
            var lastFrames = decoder?.decodedFrames ?: 0L
            var lastAt = SystemClock.elapsedRealtime()
            var lastEval = lastAt
            var goodSince = 0L
            // 自检：帧在到、但渲染帧长时间不涨 → 解码/渲染链路卡死，先重置编码器再重建
            var lastDecodedChangeAt = lastAt
            var lastDecodedWatched = lastFrames
            var watchdogHits = 0
            while (isActive && !disconnecting) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - lastAt).coerceAtLeast(1)) / 1000.0
                val bytes = parser.bytesConsumed
                val frames = decoder?.decodedFrames ?: 0L
                val fps = ((frames - lastFrames) / dt).toFloat().coerceAtLeast(0f)
                val mbps = (((bytes - lastBytes) * 8.0) / dt / 1_000_000.0).toFloat().coerceAtLeast(0f)
                val backlog = decoder?.pendingInputFrames ?: 0
                val frameIntervalMs = if (quality.maxFps > 0) 1000f / quality.maxFps else 16f
                val latency = (backlog * frameIntervalMs).toInt()
                val live = decoder
                val st = StreamStats(
                    fps = fps,
                    mbps = mbps,
                    latencyMs = latency,
                    decodedFrames = frames,
                    droppedFrames = live?.droppedFrames ?: 0,
                    deviceWidth = metaW,
                    deviceHeight = metaH,
                    decoderName = live?.codecName.orEmpty(),
                )
                _stats.value = st
                if (_state.value is LinkState.Streaming) _state.value = LinkState.Streaming(st)
                lastBytes = bytes
                lastFrames = frames
                lastAt = now

                // ---- 出画自检：帧还在到、但解码帧 5s 不涨 → 解码/渲染卡死，重启会话拿新 IDR ----
                if (frames != lastDecodedWatched) {
                    lastDecodedWatched = frames
                    lastDecodedChangeAt = now
                    watchdogHits = 0
                } else if (now - lastDecodedChangeAt > RENDER_WATCHDOG_MS &&
                    now - lastFrameSeenAt < 2_000 && framesSeen > 0 &&
                    _state.value is LinkState.Streaming
                ) {
                    // 有帧但是渲染帧不涨：先让服务端重置编码器补一帧 IDR（便宜），
                    // 连续两次还不行才重启会话（贵，但能保证新的编码器实例）
                    watchdogHits++
                    val writer = controlWriter
                    if (writer != null && watchdogHits <= 2) {
                        Log.w(TAG, "自检：${RENDER_WATCHDOG_MS}ms 无渲染帧，请求重置视频编码器（第 $watchdogHits 次）")
                        writer.resetVideo()
                        lastDecodedChangeAt = now
                    } else {
                        Log.w(TAG, "自检：重置编码器无效，重启会话")
                        emitToast("画面卡住，正在恢复…")
                        restart(reason = "正在恢复画面", qualityChange = false)
                        return@launch
                    }
                }

                if (autoQuality && _state.value is LinkState.Streaming) {
                    val degraded = fps < quality.maxFps * 0.8f || latency > 500
                    if (degraded) {
                        goodSince = 0L
                        if (now - lastEval >= ADAPT_EVAL_MS) {
                            lastEval = now
                            val next = stepQuality(down = true)
                            if (next != null) {
                                Log.i(TAG, "自适应降档 → ${next.label}")
                                setQuality(next)
                                emitToast("网络受限，画质已降到「${next.label}」")
                                return@launch
                            }
                        }
                    } else {
                        if (goodSince == 0L) goodSince = now
                        if (now - goodSince >= ADAPT_GOOD_MS && now - lastEval >= ADAPT_EVAL_MS) {
                            lastEval = now
                            goodSince = now
                            val next = stepQuality(down = false)
                            if (next != null) {
                                Log.i(TAG, "自适应升档 → ${next.label}")
                                setQuality(next)
                                emitToast("网络良好，画质已提升到「${next.label}」")
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stepQuality(down: Boolean): QualitySpec? {
        val list = QualityPresets.ALL
        val idx = list.indexOfFirst { it.maxSize == quality.maxSize && it.maxFps == quality.maxFps }
        if (idx < 0) return null
        val next = if (down) idx - 1 else idx + 1
        if (next !in list.indices) return null
        return list[next]
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private fun describe(e: Throwable): String = when (e) {
        is AgentException -> e.message ?: e.code
        is StreamException -> e.message ?: e.code
        else -> e.message ?: e.javaClass.simpleName
    }

    /** message（错误码）＋ hint，用于串流中弹出可操作的失败原因。 */
    private fun describeFull(e: Throwable): String {
        val message = describe(e)
        val hint = when (e) {
            is AgentException -> e.hint
            is StreamException -> e.hint
            else -> ""
        }
        return if (hint.isBlank()) message else "$message（$hint）"
    }

    private fun displayLabel(spec: DisplaySpec): String =
        if (spec.mode == DisplayMode.MATCH) "匹配屏幕" else "适配屏幕"

    private fun fail(e: Throwable) {
        teardownStreams()
        releaseDecoder()
        monitorJob?.cancel()
        val (message, hint) = when (e) {
            is AgentException -> (e.message ?: "连接失败") to e.hint
            is StreamException -> (e.message ?: "连接失败") to e.hint
            else -> ("连接失败：${e.message ?: e.javaClass.simpleName}") to "请检查网络后重试"
        }
        _state.value = LinkState.Failed(message, hint)
    }

    private fun emitToast(text: String) {
        _toast.tryEmit(text)
    }
}
