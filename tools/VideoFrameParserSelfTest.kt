// 纯 JVM 自测：直接编译生产代码 net/VideoFrameParser.kt + 本文件运行。
// 目的：验证 scrcpy 5.x 视频流的增量解析（codec id → session meta → 帧头 → payload），
// 包括流中途的 SESSION 包（分辨率切换）和任意分片读取（socket read 不保证对齐）。
// 编译运行见 tools/run-video-parser-test.sh

import com.mumulink.remote.net.StreamException
import com.mumulink.remote.net.VideoFrameParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun be32(v: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

private fun be64(v: Long): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

private fun meta(frameFlags: Long, w: Int, h: Int): ByteArray =
    be32((frameFlags ushr 32).toInt()) + be32(w) + be32(h)

private fun frame(ptsAndFlags: Long, payload: ByteArray): ByteArray =
    be64(ptsAndFlags) + be32(payload.size) + payload

private const val CODEC_ID = 0x68323634
private const val FLAG_SESSION = 1L shl 63
private const val FLAG_CONFIG = 1L shl 62
private const val FLAG_KEY = 1L shl 61

private class Recorder : VideoFrameParser.Listener {
    val codecIds = mutableListOf<Int>()
    val sessions = mutableListOf<Pair<Int, Int>>()
    val configs = mutableListOf<Int>()
    val frames = mutableListOf<Triple<Long, Int, Boolean>>() // pts, size, key

    override fun onCodecId(codecId: Int) { codecIds += codecId }
    override fun onSession(width: Int, height: Int, clientResize: Boolean) {
        sessions += width to height
    }
    override fun onConfig(data: ByteArray, offset: Int, length: Int) { configs += length }
    override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {
        frames += Triple(ptsUs, length, keyFrame)
    }
}

private fun buildStream(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(be32(CODEC_ID))
    out.write(meta(FLAG_SESSION, 1080, 606))
    val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    out.write(frame(FLAG_CONFIG, csd))
    out.write(frame(FLAG_KEY or 1L, ByteArray(27311) { (it and 0xFF).toByte() }))
    out.write(frame(2L, ByteArray(137)))
    // 流中途分辨率切换：SESSION 包夹在帧之间
    out.write(meta(FLAG_SESSION, 606, 1080))
    out.write(frame(FLAG_CONFIG, csd))
    out.write(frame(FLAG_KEY or 3L, ByteArray(1024)))
    return out.toByteArray()
}

fun main() {
    val stream = buildStream()
    var failures = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("PASS $name") else { failures++; println("FAIL $name $detail") }
    }

    // 1) 一次性喂
    val rec = Recorder()
    VideoFrameParser(rec).feed(stream, 0, stream.size)
    check("codecId", rec.codecIds == listOf(CODEC_ID), "${rec.codecIds}")
    check("sessions", rec.sessions == listOf(1080 to 606, 606 to 1080), "${rec.sessions}")
    check("configs", rec.configs == listOf(8, 8), "${rec.configs}")
    check("frames", rec.frames.size == 3 &&
        rec.frames[0].third && rec.frames[0].second == 27311 &&
        rec.frames[0].first == 1L && rec.frames[2].second == 1024, "${rec.frames}")

    // 2) 乱序分片（模拟 socket 读不齐）
    val rec2 = Recorder()
    val parser2 = VideoFrameParser(rec2)
    var off = 0
    val chunkPattern = intArrayOf(1, 5, 3, 7, 2, 11, 4)
    var i = 0
    while (off < stream.size) {
        val n = minOf(chunkPattern[i++ % chunkPattern.size], stream.size - off)
        parser2.feed(stream, off, n)
        off += n
    }
    check("chunked sessions", rec2.sessions == rec.sessions, "${rec2.sessions}")
    check("chunked frames", rec2.frames.size == 3, "${rec2.frames.size}")

    // 3) 坏 codec id 必须抛 StreamException（中文可读）
    try {
        VideoFrameParser(Recorder()).feed(be32(0x12345678), 0, 4)
        check("bad codec throws", false)
    } catch (e: StreamException) {
        check("bad codec throws", e.code == "BAD_CODEC", e.code)
    }

    // 4) 截断帧头不崩、补全后继续
    val rec4 = Recorder()
    val parser4 = VideoFrameParser(rec4)
    val head = stream.copyOfRange(0, 4 + 12 + 12)
    parser4.feed(head, 0, head.size)
    val rest = stream.copyOfRange(head.size, stream.size)
    parser4.feed(rest, 0, rest.size)
    check("resume after split header", rec4.frames.size == 3, "${rec4.frames.size}")

    if (failures == 0) println("ALL PASS") else { println("FAILURES=$failures"); kotlin.system.exitProcess(1) }
}
