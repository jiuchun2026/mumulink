package com.mumulink.remote.net

import android.util.Log
import com.mumulink.remote.model.AgentHello
import com.mumulink.remote.model.DeviceInfo
import com.mumulink.remote.model.DisplayMode
import com.mumulink.remote.model.DisplaySpec
import com.mumulink.remote.model.QualitySpec
import com.mumulink.remote.model.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 代理 HTTP 业务错误（`{"ok":false,"error":"CODE","message":"..."}`）。 */
class AgentException(
    val code: String,
    message: String,
    val hint: String = "",
    val httpStatus: Int = 0,
) : IOException(message)

/** /api/session/start 的返回。 */
data class SessionStartResult(
    val deviceW: Int,
    val deviceH: Int,
    val videoPort: Int,
    val controlPort: Int,
)

/** /api/display 的返回。 */
data class DisplayApplied(val width: Int, val height: Int, val density: Int)

/** /api/logs 的返回。 */
data class LogsResult(val seq: Long, val lines: List<String>)

/**
 * PC 代理（agent.js）的 HTTP 客户端，SPEC 3.3。
 *
 * - 同步 OkHttp 调用 + `suspend` 包装（内部 `Dispatchers.IO`），调用方不要在主线程直接调。
 * - 所有超时 5s；除 hello/pair 外都带 `X-MuMuLink-Token`。
 * - 失败统一抛 [AgentException]（code + 中文 message + hint），鉴权失败标记 [isAuthError]。
 * - JSON 解析用 Android 内置 org.json（不引入额外序列化库）。
 */
class AgentClient {

    companion object {
        private const val TAG = "MuMuLink"
        private const val TIMEOUT_MS = 5_000L
        private const val HEADER_TOKEN = "X-MuMuLink-Token"
        private const val JSON = "application/json; charset=utf-8"
        private val JSON_TYPE = JSON.toMediaType()
    }

    /** 已配对 token（配对成功返回后写入；空 = 未配对待连接）。 */
    @Volatile
    var token: String? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    val isPaired: Boolean get() = !token.isNullOrBlank()

    // ---------------- API ----------------

    suspend fun hello(host: String, httpPort: Int = 27180): AgentHello = request(
        host, httpPort, "GET", "/api/hello", null, auth = false,
    ) { json ->
        val magic = json.optString("magic")
        if (magic.isNotEmpty() && magic != "MUMULINK") {
            throw AgentException("BAD_PROTOCOL", "对方不是 MuMuLink 代理（magic=$magic）", "请检查 IP/端口")
        }
        AgentHello(
            name = json.optString("name", "MuMuLink 代理"),
            host = host,
            version = json.optString("agentVersion", ""),
            paired = json.optBoolean("paired", false),
            device = json.optJSONObject("device")?.let { d ->
                DeviceInfo(
                    model = d.optString("model", "未知设备"),
                    android = d.optString("android", ""),
                    width = d.optInt("width", 0),
                    height = d.optInt("height", 0),
                    density = d.optInt("density", 0),
                )
            },
            session = json.optJSONObject("session")?.let { s ->
                SessionInfo(
                    running = s.optBoolean("running", false),
                    quality = s.optJSONObject("quality")?.let { q ->
                        QualitySpec(
                            maxSize = q.optInt("maxSize", 0),
                            maxFps = q.optInt("maxFps", 0),
                            bitRate = q.optInt("bitRate", 0),
                        )
                    },
                    deviceW = s.optInt("deviceW", 0),
                    deviceH = s.optInt("deviceH", 0),
                )
            },
        )
    }

    /** 配对：成功返回 token 并写入 [token]；配对码错误抛 BAD_CODE（401）。 */
    suspend fun pair(host: String, code: String, httpPort: Int = 27180): String {
        val json: JSONObject = request(
            host, httpPort, "POST", "/api/pair",
            JSONObject().put("code", code.trim()).toString(), auth = false,
        ) { it }
        val t = json.optString("token")
        if (t.isBlank()) throw AgentException("BAD_PROTOCOL", "配对应答缺少 token", "请升级代理")
        token = t
        Log.i(TAG, "配对成功 host=$host")
        return t
    }

    suspend fun startSession(
        host: String,
        quality: QualitySpec,
        display: DisplaySpec,
        httpPort: Int = 27180,
    ): SessionStartResult {
        val body = JSONObject()
            .put(
                "quality", JSONObject()
                    .put("maxSize", quality.maxSize)
                    .put("maxFps", quality.maxFps)
                    .put("bitRate", quality.bitRate),
            )
            .put(
                "display", JSONObject()
                    .put("mode", if (display.mode == DisplayMode.MATCH) "match" else "fit")
                    .put("width", display.width)
                    .put("height", display.height)
                    .put("density", display.density),
            )
            .toString()
        return request(host, httpPort, "POST", "/api/session/start", body) { json ->
            SessionStartResult(
                deviceW = json.optInt("deviceW", 0),
                deviceH = json.optInt("deviceH", 0),
                videoPort = json.optInt("videoPort", 27182),
                controlPort = json.optInt("controlPort", 27183),
            )
        }
    }

    suspend fun stopSession(host: String, httpPort: Int = 27180) {
        request<Unit>(host, httpPort, "POST", "/api/session/stop", "{}") { }
    }

    /** 改画质：代理会重启 scrcpy 会话，App 必须重连 socket。 */
    suspend fun setQuality(host: String, quality: QualitySpec, httpPort: Int = 27180) {
        val body = JSONObject()
            .put("maxSize", quality.maxSize)
            .put("maxFps", quality.maxFps)
            .put("bitRate", quality.bitRate)
            .toString()
        request<Unit>(host, httpPort, "POST", "/api/session/quality", body) { }
    }

    /** 改显示（DPI/分辨率适配）。mode=fit 时由代理恢复原始 wm。 */
    suspend fun setDisplay(
        host: String,
        display: DisplaySpec,
        httpPort: Int = 27180,
    ): DisplayApplied {
        val body = JSONObject()
            .put("mode", if (display.mode == DisplayMode.MATCH) "match" else "fit")
            .put("width", display.width)
            .put("height", display.height)
            .put("density", display.density)
            .toString()
        return request(host, httpPort, "POST", "/api/display", body) { json ->
            val a = json.optJSONObject("applied")
            DisplayApplied(
                width = a?.optInt("width", 0) ?: display.width,
                height = a?.optInt("height", 0) ?: display.height,
                density = a?.optInt("density", 0) ?: display.density,
            )
        }
    }

    suspend fun resetDisplay(host: String, httpPort: Int = 27180) {
        request<Unit>(host, httpPort, "POST", "/api/display/reset", "{}") { }
    }

    /** 文本输入走 HTTP（>300 字节由代理/服务端限制，调用方先分批）。 */
    suspend fun sendText(host: String, text: String, httpPort: Int = 27180) {
        val body = JSONObject().put("text", text).toString()
        request<Unit>(host, httpPort, "POST", "/api/text", body) { }
    }

    suspend fun logs(host: String, since: Long = 0, httpPort: Int = 27180): LogsResult =
        request(host, httpPort, "GET", "/api/logs?since=$since", null) { json ->
            val arr = json.optJSONArray("lines")
            val lines = ArrayList<String>(arr?.length() ?: 0)
            if (arr != null) for (i in 0 until arr.length()) lines.add(arr.optString(i))
            LogsResult(seq = json.optLong("seq", since), lines = lines)
        }

    // ---------------- 内部 ----------------

    private fun endpoint(host: String, httpPort: Int): String {
        val h = host.trim()
        return if (h.startsWith("http://") || h.startsWith("https://")) "$h:$httpPort"
        else "http://$h:$httpPort"
    }

    private suspend fun <T> request(
        host: String,
        httpPort: Int,
        method: String,
        path: String,
        body: String?,
        auth: Boolean = true,
        parse: (JSONObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(host, httpPort) + path)
        val bearer = token
        if (auth && !bearer.isNullOrBlank()) builder.header(HEADER_TOKEN, bearer)
        if (method == "GET") {
            builder.get()
        } else {
            builder.method(method, (body ?: "{}").toRequestBody(JSON_TYPE))
        }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: SocketTimeoutException) {
            throw AgentException("CONNECT_TIMEOUT", ErrorText.message("CONNECT_TIMEOUT"), ErrorText.hint("CONNECT_TIMEOUT"))
        } catch (e: ConnectException) {
            throw AgentException("CONNECT_REFUSED", "代理拒绝连接（$host:$httpPort）", ErrorText.hint("CONNECT_REFUSED"))
        } catch (e: UnknownHostException) {
            throw AgentException("BAD_HOST", "找不到主机 $host", ErrorText.hint("BAD_HOST"))
        } catch (e: IOException) {
            throw AgentException("NETWORK", "网络不可用：${e.message ?: e.javaClass.simpleName}", ErrorText.hint("NETWORK"))
        }
        response.use { resp ->
            val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val ok = json?.optBoolean("ok", resp.isSuccessful) ?: resp.isSuccessful
            if (!ok || json == null && !resp.isSuccessful) {
                val code = json?.optString("error").orEmpty().ifBlank {
                    when (resp.code) {
                        401 -> "BAD_TOKEN"
                        404 -> "BAD_REQUEST"
                        else -> "HTTP_${resp.code}"
                    }
                }
                val msg = json?.optString("message").orEmpty().ifBlank { ErrorText.message(code) }
                throw AgentException(code, msg, ErrorText.hint(code), resp.code)
            }
            if (json == null) throw AgentException("BAD_PROTOCOL", "代理返回的不是 JSON", "请检查 IP/端口是否正确")
            parse(json)
        }
    }
}
