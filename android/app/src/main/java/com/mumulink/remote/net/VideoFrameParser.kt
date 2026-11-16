package com.mumulink.remote.net

/**
 * scrcpy 视频裸字节流解析器（纯 JVM 实现，不依赖 Android，方便离线/单元验证）。
 *
 * 字节布局（权威：scrcpy 5.0.1 device/Streamer.java + DesktopConnection，配合
 * send_dummy_byte=false send_device_meta=false send_stream_meta=true send_frame_meta=true）：
 *
 *   codecId        4B  big-endian int，0x68323634 = "h264"（0/1 表示服务端禁用/配置错误）
 *   sessionMeta   12B  int32 flags + int32 width + int32 height
 *                  flags 最高位恒为 1（PACKET_FLAG_SESSION），最低位 = isClientResize
 *   循环帧头       12B  int64 ptsAndFlags + int32 payloadSize
 *                       bit63=SESSION(流中改分辨率), bit62=CONFIG(SPS/PPS), bit61=KEY_FRAME
 *                       CONFIG 包 pts 部分整体等于 PACKET_FLAG_CONFIG
 *   payload        N B  Annex-B 码流
 *
 * 关键点：**运行中分辨率会变**。server 在显示旋转/改 wm size 时会重建编码器，并在流中间
 * 插入一个 12B 的 SESSION 包（没有帧头、没有 payload）。客户端读到 12B 包头时若 bit63 置位，
 * 说明这 12B 其实是 meta：低 32 位 = width，随后读到的 4B“size”= height。
 *
 * 线程模型：feed() 只允许单线程调用（视频 socket 读线程）；listener 回调在该线程同步执行，
 * 回调里拿到的 data 是解析器的复用缓冲区，**必须同步消费（拷贝），不可保存引用**。
 */
class VideoFrameParser(private val listener: Listener) {

    interface Listener {
        /** 流第一包：编码格式。仅 H.264 支持。 */
        fun onCodecId(codecId: Int) {}

        /** 会话尺寸（首包或流中重建）。width/height 为设备侧采集尺寸。 */
        fun onSession(width: Int, height: Int, clientResize: Boolean) {}

        /** 编码器配置包（Annex-B SPS/PPS），用于 MediaFormat 的 csd-0。 */
        fun onConfig(data: ByteArray, offset: Int, length: Int) {}

        /** 一个视频帧。 */
        fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {}
    }

    companion object {
        const val CODEC_H264 = 0x68323634
        const val PACKET_FLAG_SESSION = 1L shl 63
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61

        /** 单帧上限保护（异常流/脏数据时不至于吃爆内存）。 */
        private const val MAX_PACKET_SIZE = 16 * 1024 * 1024
        private const val INITIAL_BUFFER = 64 * 1024
    }

    private enum class State { CODEC, META, FRAME_HEADER, FRAME_PAYLOAD }

    private var state = State.CODEC
    private var buf = ByteArray(INITIAL_BUFFER)
    private var head = 0
    private var tail = 0

    // 当前帧头解析结果
    private var ptsAndFlags = 0L
    private var packetSize = 0

    // 已收到的元信息（供调试/统计）
    var codecId: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** 解析器已消费的总字节数（统计码率用；读线程写、统计协程读）。 */
    @Volatile
    var bytesConsumed: Long = 0L
        private set

    fun feed(src: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureSpace(tail + length)
        System.arraycopy(src, offset, buf, tail, length)
        tail += length
        parse()
    }

    fun reset() {
        state = State.CODEC
        head = 0
        tail = 0
        codecId = 0
        width = 0
        height = 0
        ptsAndFlags = 0L
        packetSize = 0
    }

    private fun parse() {
        while (true) {
            when (state) {
                State.CODEC -> {
                    if (tail - head < 4) return
                    val id = readInt(head)
                    head += 4
                    bytesConsumed += 4
                    codecId = id
                    if (id != CODEC_H264) {
                        val msg = when (id) {
                            0 -> "服务端未启用视频流（codec=0）"
                            1 -> "服务端配置错误，无法推流（codec=1）"
                            else -> "视频编码格式不支持：0x${Integer.toHexString(id)}（只支持 H.264）"
                        }
                        throw StreamException("BAD_CODEC", msg, "请重启代理或检查 scrcpy 启动参数（video_codec=h264）")
                    }
                    listener.onCodecId(id)
                    state = State.META
                }

                State.META -> {
                    if (tail - head < 12) return
                    val flags = readInt(head)
                    val w = readInt(head + 4)
                    val h = readInt(head + 8)
                    head += 12
                    bytesConsumed += 12
                    applySession(flags, w, h)
                }

                State.FRAME_HEADER -> {
                    if (tail - head < 12) return
                    val ptsFlags = readLong(head)
                    val size = readInt(head + 8)
                    head += 12
                    bytesConsumed += 12
                    if (ptsFlags and PACKET_FLAG_SESSION != 0L) {
                        // 流中 SESSION 包：宽度在 pts 低 32 位，"size" 字段其实是高度，没有 payload。
                        val w = (ptsFlags and 0xFFFF_FFFFL).toInt()
                        val flags = (ptsFlags ushr 32).toInt()
                        applySession(flags, w, size)
                        continue
                    }
                    if (size < 0 || size > MAX_PACKET_SIZE) {
                        throw StreamException("BAD_STREAM", "视频帧长度异常：$size", "请重启会话")
                    }
                    packetSize = size
                    ptsAndFlags = ptsFlags
                    state = State.FRAME_PAYLOAD
                }

                State.FRAME_PAYLOAD -> {
                    if (tail - head < packetSize) return
                    val off = head
                    val len = packetSize
                    val pts = ptsAndFlags
                    head += len
                    bytesConsumed += len.toLong()
                    state = State.FRAME_HEADER
                    dispatch(off, len, pts)
                    // dispatch 后可能 reset/关闭，继续循环是安全的
                }
            }
        }
    }

    private fun dispatch(off: Int, len: Int, ptsFlags: Long) {
        when {
            ptsFlags and PACKET_FLAG_CONFIG != 0L -> listener.onConfig(buf, off, len)
            else -> {
                val ptsUs = ptsFlags and 0x1FFF_FFFF_FFFF_FFFFL // 去掉高 3 位标志
                listener.onFrame(buf, off, len, ptsUs, ptsFlags and PACKET_FLAG_KEY_FRAME != 0L)
            }
        }
    }

    private fun applySession(flags: Int, w: Int, h: Int) {
        val clientResize = (flags and 1) != 0
        if (w <= 0 || h <= 0) {
            throw StreamException("BAD_STREAM", "会话尺寸非法：${w}x$h", "请重启会话")
        }
        width = w
        height = h
        listener.onSession(w, h, clientResize)
        state = State.FRAME_HEADER
    }

    private fun ensureSpace(need: Int) {
        if (need <= buf.size) {
            if (head > 0 && need > buf.size - head) {
                // 头尾压缩
                System.arraycopy(buf, head, buf, 0, tail - head)
                tail -= head
                head = 0
            }
            return
        }
        // 压缩后仍不够（通常是单帧特别大），扩容。
        val keep = tail - head
        if (head > 0) {
            System.arraycopy(buf, head, buf, 0, keep)
            tail = keep
            head = 0
        }
        if (need > buf.size) {
            var cap = buf.size
            while (cap < need) cap = cap shl 1
            buf = buf.copyOf(cap)
        }
    }

    private fun readInt(pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)

    private fun readLong(pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
        return v
    }
}
