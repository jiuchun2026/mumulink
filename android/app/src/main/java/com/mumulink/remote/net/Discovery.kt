package com.mumulink.remote.net

import android.util.Log
import com.mumulink.remote.model.AgentHello
import com.mumulink.remote.model.DeviceInfo
import com.mumulink.remote.model.QualitySpec
import com.mumulink.remote.model.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * UDP 局域网发现（SPEC 4.7 / 架构图 27181）。
 *
 * 发送：每 2s 向 255.255.255.255:27181 **以及本机各网卡的广播地址** 各发一份
 *   `{"magic":"MUMULINK_DISCOVER","v":1}`
 * 接收：`{"magic":"MUMULINK_ANNOUNCE", name, version, paired, device{...}, session{...}}`
 *   host 取 datagram 来源 IP（不信任报文里的地址字段）。
 * 列表：同一 host 去重；10s 内没再收到 ANNOUNCE 就从列表移除。
 *
 * 全部跑在 [Dispatchers.IO] 上的独立协程里；[stop] 后释放 socket。
 */
class Discovery {

    companion object {
        private const val TAG = "MuMuLink"
        private const val PORT = 27181
        private const val SEND_INTERVAL_MS = 2_000L
        private const val EXPIRE_MS = 10_000L
        private const val DISCOVER_JSON = """{"magic":"MUMULINK_DISCOVER","v":1}"""
        private const val RECV_BUF = 4 * 1024
    }

    private class Entry(val hello: AgentHello, val lastSeenAt: Long)

    private val _agents = MutableStateFlow<List<AgentHello>>(emptyList())
    val agents: StateFlow<List<AgentHello>> = _agents.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var jobs = mutableListOf<Job>()
    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Any()

    val isRunning: Boolean get() = scope != null

    /** 最近连接过的电脑 IP：跨网段时通过对它单播探测来发现（广播过不了路由器）。 */
    @Volatile
    private var probeHosts: List<String> = emptyList()

    /** 幂等启动：重复调用不会重复开 socket。 */
    fun start(knownHosts: List<String> = emptyList()) {
        probeHosts = knownHosts.mapNotNull { it.trim().substringBefore(':').ifBlank { null } }.distinct()
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        val sock = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 800
                reuseAddress = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "发现：创建 UDP socket 失败", e)
            newScope.cancel()
            scope = null
            return
        }
        socket = sock
        jobs += newScope.launch { sendLoop(sock) }
        jobs += newScope.launch { receiveLoop(sock) }
        jobs += newScope.launch { pruneLoop() }
        Log.i(TAG, "UDP 发现已启动（27181）")
    }

    fun stop() {
        val s = socket
        socket = null
        runCatching { s?.close() }
        jobs.forEach { runCatching { it.cancel() } }
        jobs.clear()
        scope?.cancel()
        scope = null
        synchronized(lock) { entries.clear() }
        _agents.value = emptyList()
        Log.i(TAG, "UDP 发现已停止")
    }

    private suspend fun sendLoop(sock: DatagramSocket) {
        val payload = DISCOVER_JSON.toByteArray(Charsets.UTF_8)
        while (scope?.isActive == true) {
            val targets = broadcastTargets()
            for (addr in targets) {
                runCatching {
                    sock.send(DatagramPacket(payload, payload.size, addr, PORT))
                }.onFailure { if (it !is SocketException) Log.w(TAG, "发现广播失败 $addr：${it.message}") }
            }
            // 广播过不了网段：对「最近连过的电脑」单播探测，校园网宿舍/教室不同网段也能找到
            for (h in probeHosts) {
                runCatching {
                    val addr = InetAddress.getByName(h)
                    if (!addr.isLoopbackAddress) sock.send(DatagramPacket(payload, payload.size, addr, PORT))
                }.onFailure { if (it !is SocketException) Log.w(TAG, "发现单播失败 $h：${it.message}") }
            }
            delay(SEND_INTERVAL_MS)
        }
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(RECV_BUF)
        while (scope?.isActive == true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                // socket 被 stop() 关闭是正常退出
                if (scope?.isActive == true) Log.w(TAG, "发现接收异常：${e.message}")
                break
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val hello = parseAnnounce(text, packet.address?.hostAddress ?: continue) ?: continue
            synchronized(lock) {
                entries[hello.host] = Entry(hello, System.currentTimeMillis())
                publishLocked()
            }
            Log.i(TAG, "发现代理 ${hello.host}（${hello.name}）")
        }
    }

    private suspend fun pruneLoop() {
        while (scope?.isActive == true) {
            delay(1_000)
            val now = System.currentTimeMillis()
            var changed = false
            synchronized(lock) {
                val it = entries.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (now - e.value.lastSeenAt > EXPIRE_MS) {
                        it.remove()
                        changed = true
                        Log.i(TAG, "发现列表移除（超时）${e.key}")
                    }
                }
                if (changed) publishLocked()
            }
        }
    }

    private fun publishLocked() {
        _agents.value = entries.values.map { it.hello }
    }

    private fun parseAnnounce(text: String, host: String): AgentHello? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val magic = json.optString("magic")
        // 代理按 SPEC 广播 magic=MUMULINK；兼容旧版 MUMULINK_ANNOUNCE
        if (magic != "MUMULINK" && magic != "MUMULINK_ANNOUNCE") return null
        val device = json.optJSONObject("device")?.let { d ->
            DeviceInfo(
                model = d.optString("model", "未知设备"),
                android = d.optString("android", ""),
                width = d.optInt("width", 0),
                height = d.optInt("height", 0),
                density = d.optInt("density", 0),
            )
        }
        val session = json.optJSONObject("session")?.let { s ->
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
        }
        return AgentHello(
            name = json.optString("name", "MuMuLink 代理"),
            host = host,
            version = json.optString("version", json.optString("agentVersion", "")),
            paired = json.optBoolean("paired", false),
            device = device,
            session = session,
        )
    }

    /** 255.255.255.255 + 本机每个网卡的广播地址（去重）。 */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashMap<String, InetAddress>()
        runCatching {
            out["255.255.255.255"] = InetAddress.getByName("255.255.255.255")
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    out[b.hostAddress ?: continue] = b
                }
            }
        }.onFailure { Log.w(TAG, "枚举网卡广播地址失败：${it.message}") }
        return out.values.toList()
    }
}
