package com.mumulink.remote.ui

import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.mumulink.remote.core.SessionController
import com.mumulink.remote.core.TouchPhase
import com.mumulink.remote.model.DisplayMode
import com.mumulink.remote.model.DisplaySpec
import com.mumulink.remote.model.LinkState
import com.mumulink.remote.model.QualityPresets
import com.mumulink.remote.model.QualitySpec
import com.mumulink.remote.model.RemoteKey
import com.mumulink.remote.model.UiState
import com.mumulink.remote.net.Discovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 连接/串流的状态中枢（SPEC 4.3）。UI 只读 [ui] 并调用这里的公开方法。
 *
 * 职责边界：
 * - 发现（UDP）→ [Discovery]；
 * - 连接/解码/统计/重连 → [SessionController]（跑在独立 IO scope，绝不碰主线程）；
 * - 本类只做「把多个流拼成一个 UiState」+ 持久化 + 屏幕参数换算。
 */
class LinkViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MuMuLink"
        private const val PREFS = "mumulink_prefs"
        private const val KEY_HOST = "last_host"
        private const val KEY_TOKEN = "token"
        private const val KEY_QUALITY = "quality_max_size"
        private const val KEY_DISPLAY = "display_mode"
    }

    private val prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val discovery = Discovery()
    private val controller = SessionController(ioScope)

    private val _ui = MutableStateFlow(
        UiState(
            quality = restoreQuality(),
            display = restoreDisplay(),
            pairedOnce = prefs.getString(KEY_TOKEN, null) != null,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // 配对成功后立刻落盘 token，下次启动免配对
        controller.onTokenChanged = { token ->
            prefs.edit().putString(KEY_TOKEN, token).apply()
            _ui.update { it.copy(pairedOnce = true) }
        }
        // 串流中 MATCH 失败被控制器自动回滚成 FIT 时，同步 UI 与持久化，
        // 否则下次连接会带着必然失败的 match 再撞一次
        controller.onDisplayChanged = { spec ->
            Log.i(TAG, "显示模式被回滚为 ${spec.mode}")
            _ui.update { it.copy(display = spec) }
            prefs.edit().putString(KEY_DISPLAY, spec.mode.name).apply()
        }
        mainScope.launch {
            discovery.agents.collect { list ->
                _ui.update { it.copy(agents = list) }
            }
        }
        mainScope.launch {
            controller.state.collect { state ->
                _ui.update { s ->
                    s.copy(
                        link = state,
                        stats = when (state) {
                            is LinkState.Streaming -> state.stats
                            else -> s.stats
                        },
                    )
                }
                if (state is LinkState.Streaming) {
                    prefs.edit().putString(KEY_HOST, lastHost).apply()
                }
            }
        }
        mainScope.launch {
            controller.stats.collect { stats -> _ui.update { it.copy(stats = stats) } }
        }
        mainScope.launch {
            controller.device.collect { device -> _ui.update { it.copy(device = device) } }
        }
        mainScope.launch {
            controller.toast.collect { text -> _ui.update { it.copy(toast = text) } }
        }
    }

    // ---------------- 发现 ----------------

    fun startScan() {
        val recent = runCatching {
            getApplication<Application>().getSharedPreferences("mumulink.ui", Application.MODE_PRIVATE)
                .getString("recent_hosts", null)?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        }.getOrDefault(emptyList())
        val known = (recent + listOfNotNull(lastHost.ifBlank { null })).distinct()
        discovery.start(known)
        _ui.update { it.copy(scanning = true) }
    }

    fun stopScan() {
        discovery.stop()
        _ui.update { it.copy(scanning = false) }
    }

    // ---------------- 连接 ----------------

    /** 上次成功连接的原始地址（含端口），供 UI 预填。 */
    private var lastHost: String = prefs.getString(KEY_HOST, "") ?: ""

    /**
     * @param host 纯 IP 或 `ip:port`（手动指定非默认 HTTP 端口时）
     * @param pairingCode 首次连接/PIN 过期时由 UI 传入
     */
    fun connect(host: String, pairingCode: String? = null) {
        val (h, port) = parseHostPort(host)
        if (h.isBlank()) {
            _ui.update { it.copy(toast = "请输入代理地址") }
            return
        }
        lastHost = if (port == 27180) h else "$h:$port"
        val token = prefs.getString(KEY_TOKEN, null)
        Log.i(TAG, "connect $h:$port（token=${if (token.isNullOrBlank()) "无" else "有"}）")
        controller.connect(
            host = h,
            httpPort = port,
            token = token,
            pairingCode = pairingCode,
            quality = _ui.value.quality,
            display = _ui.value.display,
        )
    }

    fun disconnect() {
        controller.disconnect()
    }

    // ---------------- 画质/显示 ----------------

    fun setQuality(q: QualitySpec) {
        _ui.update { it.copy(quality = q) }
        prefs.edit().putInt(KEY_QUALITY, q.maxSize).apply()
        controller.setQuality(q)
    }

    fun setAutoQuality(enabled: Boolean) {
        _ui.update { it.copy(autoQuality = enabled) }
        controller.setAutoQuality(enabled)
    }

    fun setDisplayMode(mode: DisplayMode) {
        val spec = when (mode) {
            DisplayMode.FIT -> DisplaySpec(DisplayMode.FIT)
            DisplayMode.MATCH -> {
                // MATCH：把手机真实分辨率/密度下发给代理，让被控端 wm size 与之匹配
                val dm = getApplication<Application>().resources.displayMetrics
                DisplaySpec(DisplayMode.MATCH, dm.widthPixels, dm.heightPixels, dm.densityDpi)
            }
        }
        Log.i(TAG, "显示模式 → ${mode.name} ${spec.width}x${spec.height}@${spec.density}")
        _ui.update { it.copy(display = spec) }
        prefs.edit().putString(KEY_DISPLAY, mode.name).apply()
        controller.setDisplay(spec)
    }

    /**
     * 连接页的「重置显示模式」入口：无论之前持久化了什么，强制回到 FIT。
     * 用于 MATCH 在该设备上必然失败时自救（否则用户会被卡在连接失败）。
     */
    fun resetDisplayMode() {
        Log.i(TAG, "重置显示模式 → FIT")
        prefs.edit().putString(KEY_DISPLAY, DisplayMode.FIT.name).apply()
        _ui.update { it.copy(display = DisplaySpec(DisplayMode.FIT)) }
        controller.setDisplay(DisplaySpec(DisplayMode.FIT))
        _ui.update { it.copy(toast = "已恢复「适配屏幕」") }
    }

    // ---------------- 交互 ----------------

    fun sendKey(key: RemoteKey) = controller.sendKey(key)

    fun sendText(text: String) = controller.sendText(text)

    fun showBall() {
        _ui.update { it.copy(ballVisible = true) }
    }

    fun hideBall() {
        _ui.update { it.copy(ballVisible = false) }
    }

    fun consumeToast() {
        _ui.update { it.copy(toast = null) }
    }

    fun currentQuality(): QualitySpec = _ui.value.quality

    // ---------------- 视频层（RemoteVideoSurface 调用） ----------------

    fun attachSurface(surface: Surface) = controller.attachSurface(surface)

    fun detachSurface(surface: Surface) = controller.detachSurface(surface)

    fun touch(phase: TouchPhase, pointerId: Long, xNorm: Float, yNorm: Float) {
        controller.touch(phase, pointerId, xNorm, yNorm)
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { controller.shutdown() }
        runCatching { discovery.stop() }
        ioScope.cancel()
        mainScope.cancel()
    }

    // ---------------- 内部 ----------------

    /**
     * 解析 `ip` / `ip:port` / `[ipv6]:port`（app-ui 手动填端口时用 `ip:port` 传进来）。
     */
    private fun parseHostPort(raw: String): Pair<String, Int> {
        val s = raw.trim()
        if (s.isEmpty()) return "" to 27180
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end > 0) {
                val h = s.substring(1, end)
                val p = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: 27180
                return h to p
            }
        }
        val idx = s.lastIndexOf(':')
        if (idx > 0 && s.indexOf(':') == idx) {
            val port = s.substring(idx + 1).toIntOrNull()
            if (port != null && port in 1..65535) return s.substring(0, idx) to port
        }
        return s to 27180
    }

    private fun restoreQuality(): QualitySpec {
        val maxSize = prefs.getInt(KEY_QUALITY, QualityPresets.BALANCED.maxSize)
        return QualityPresets.ALL.firstOrNull { it.maxSize == maxSize } ?: QualityPresets.BALANCED
    }

    private fun restoreDisplay(): DisplaySpec {
        val mode = runCatching {
            DisplayMode.valueOf(prefs.getString(KEY_DISPLAY, null) ?: DisplayMode.FIT.name)
        }.getOrDefault(DisplayMode.FIT)
        return if (mode == DisplayMode.MATCH) {
            val dm = getApplication<Application>().resources.displayMetrics
            DisplaySpec(DisplayMode.MATCH, dm.widthPixels, dm.heightPixels, dm.densityDpi)
        } else {
            DisplaySpec(DisplayMode.FIT)
        }
    }
}
