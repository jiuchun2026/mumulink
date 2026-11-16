package com.mumulink.remote.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 连接/握手阶段的错误。code 与代理 HTTP 的错误码保持同一套（BAD_TOKEN/CONNECT_TIMEOUT/...）。
 */
class StreamException(
    val code: String,
    message: String,
    val hint: String = "",
) : IOException(message)

/**
 * 视频/控制 TCP 通道（SPEC 3.4）。
 *
 * 连接流程：TCP connect（5s 超时）→ 发一行 `MUMULINK1 <token>\n` → 读回 `MUMULINK1 OK\n`，
 * 之后进入裸字节管道。失败时代理回 `MUMULINK1 ERR <CODE>\n` 并关闭。
 */
class StreamSocket(
    private val host: String,
    private val port: Int,
    private val token: String,
) {

    companion object {
        private const val TAG = "MuMuLink"
        private const val HANDSHAKE_PREFIX = "MUMULINK1"
        private const val MAX_LINE = 256
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SO_RCVBUF = 1 shl 20 // 1MB，SPEC 5
    }

    private var socket: Socket? = null

    val inputStream: InputStream
        get() = socket?.inputStream ?: throw IOException("视频通道未连接")

    val outputStream: OutputStream
        get() = socket?.outputStream ?: throw IOException("控制通道未连接")

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    /** 同步连接 + 握手；任何失败都抛 [StreamException]（中文 message + hint）。 */
    fun connect() {
        close()
        val s = Socket()
        try {
            // SO_RCVBUF 必须在 connect 前设置才生效（视频码流突发需要大缓冲）
            s.tcpNoDelay = true
            s.receiveBufferSize = SO_RCVBUF
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = 0 // 握手后进入阻塞读，读线程自己管理生命周期
            val out = s.getOutputStream()
            out.write("$HANDSHAKE_PREFIX $token\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val line = readLine(s.getInputStream())
            when {
                line == null -> throw StreamException(
                    "CLOSED", "代理在握手时关闭了连接", "请确认代理已启动且端口 $port 未被占用",
                )
                line == "$HANDSHAKE_PREFIX OK" -> Unit
                line.startsWith("$HANDSHAKE_PREFIX ERR") -> {
                    val errCode = line.removePrefix("$HANDSHAKE_PREFIX ERR").trim().ifEmpty { "UNKNOWN" }
                    throw StreamException(errCode, ErrorText.message(errCode), ErrorText.hint(errCode))
                }
                else -> throw StreamException("BAD_PROTOCOL", "代理握手应答无法识别：$line", "请确认 App 与代理版本匹配")
            }
            socket = s
            Log.i(TAG, "StreamSocket 已连接 $host:$port 并握手成功")
        } catch (e: StreamException) {
            closeQuietly(s)
            throw e
        } catch (e: SocketTimeoutException) {
            closeQuietly(s)
            throw StreamException("CONNECT_TIMEOUT", "连接代理超时（$host:$port）", "检查手机与电脑是否在同一 Wi-Fi、防火墙是否放行")
        } catch (e: ConnectException) {
            closeQuietly(s)
            throw StreamException("CONNECT_REFUSED", "代理端口 $port 拒绝连接", "代理可能未启动；或会话尚未开始（请先 start 会话）")
        } catch (e: UnknownHostException) {
            closeQuietly(s)
            throw StreamException("BAD_HOST", "找不到主机 $host", "请检查 IP 地址")
        } catch (e: IOException) {
            closeQuietly(s)
            throw StreamException("NETWORK", "网络错误：${e.message ?: e.javaClass.simpleName}", "请重试")
        }
    }

    /**
     * 带超时地读一行（最多 [MAX_LINE] 字节，忽略 \r）。
     * 注意：这里逐字节读，握手后不能继续用它读裸流。
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw StreamException("BAD_PROTOCOL", "握手应答过长", "请确认连接的是 MuMuLink 代理")
        }
    }

    fun close() {
        val s = socket ?: return
        socket = null
        closeQuietly(s)
    }

    private fun closeQuietly(s: Socket) {
        runCatching { s.close() }
    }
}

/**
 * 视频流读取循环：阻塞读 → [VideoFrameParser]。
 * 放在独立线程里跑（不占用协程调度器线程，读阻塞不会影响其它任务）。
 */
class VideoFrameReader(
    private val input: InputStream,
    private val parser: VideoFrameParser,
    private val onEnded: (Throwable?) -> Unit,
) : Runnable {

    @Volatile
    private var running = true

    fun stop() {
        running = false
    }

    override fun run() {
        val tag = "MuMuLink"
        val buf = ByteArray(256 * 1024)
        var error: Throwable? = null
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) {
                    error = StreamException("STREAM_END", "视频流被对端关闭", "会话已停止，正在尝试重连")
                    break
                }
                if (n == 0) continue
                parser.feed(buf, 0, n)
            }
        } catch (e: Throwable) {
            if (running) {
                error = e
                Log.w(tag, "视频读线程异常：${e.javaClass.simpleName} ${e.message}")
            }
        } finally {
            onEnded(error)
        }
    }
}

/**
 * 控制通道读端：只做“存活检测 + 排空”。
 * 设备消息（clipboard / ack / uhid 输出）目前业务上不需要，直接丢弃但必须持续读，
 * 否则 TCP 接收窗口填满后服务端写入阻塞，控制指令会延迟。
 */
class ControlDrainer(
    private val input: InputStream,
    private val onEnded: (Throwable?) -> Unit,
) : Runnable {

    @Volatile
    private var running = true

    fun stop() {
        running = false
    }

    override fun run() {
        val buf = ByteArray(4096)
        var error: Throwable? = null
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) {
                    error = StreamException("CONTROL_END", "控制通道被对端关闭", "正在尝试重连")
                    break
                }
            }
        } catch (e: Throwable) {
            if (running) error = e
        } finally {
            onEnded(error)
        }
    }
}

/** 错误码 → 中文提示（SPEC 4.7）。供 net 层与 UI 层复用。 */
object ErrorText {
    fun message(code: String): String = when (code.uppercase()) {
        "BAD_TOKEN" -> "配对凭证无效"
        "BAD_CODE" -> "配对码不正确"
        "PAIRING_REQUIRED" -> "代理要求先配对"
        "CONNECT_TIMEOUT" -> "连接超时"
        "CONNECT_REFUSED" -> "代理拒绝连接"
        "NO_DEVICE" -> "未发现 MuMu 模拟器"
        "DEVICE_OFFLINE" -> "模拟器离线"
        "SESSION_NOT_RUNNING" -> "会话尚未开始"
        "BUSY" -> "代理忙，请稍后重试"
        "BAD_REQUEST" -> "请求参数有误"
        "BAD_CODEC" -> "视频编码格式不支持"
        "STREAM_END", "CLOSED", "CONTROL_END" -> "连接已断开"
        else -> "操作失败（$code）"
    }

    fun hint(code: String): String = when (code.uppercase()) {
        "BAD_TOKEN" -> "请重新输入代理网页上显示的配对码"
        "BAD_CODE" -> "配对码在代理网页（http://电脑IP:27180/）首页显示"
        "PAIRING_REQUIRED" -> "请在连接页填写配对码"
        "CONNECT_TIMEOUT", "CONNECT_REFUSED", "NETWORK", "BAD_HOST" ->
            "确认手机与电脑在同一 Wi-Fi、代理已启动、防火墙放行 27180-27183"
        "NO_DEVICE", "DEVICE_OFFLINE" -> "请先启动 MuMu 模拟器并确认 adb devices 能看到它"
        "SESSION_NOT_RUNNING" -> "请回到连接页重新连接"
        "BUSY" -> "等几秒再试；或重启代理"
        "BAD_CODEC" -> "只支持 H.264，请检查代理的 scrcpy 启动参数"
        "STREAM_END", "CLOSED", "CONTROL_END" -> "网络闪断时会自动重连（最多 6 次）"
        else -> ""
    }
}
