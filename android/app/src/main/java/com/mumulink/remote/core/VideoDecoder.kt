package com.mumulink.remote.core

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 硬解 → 外部 Surface（SPEC 4.4）。
 *
 * 实现要点：
 * - **异步模式**（setCallback）跑在独立 HandlerThread 上，绝不占用主线程；输入缓冲由
 *   [queueFrame] 入队、由 [onInputBufferAvailable] 回调按 codec 提供的 buffer 喂入。
 * - CONFIG 帧（SPS/PPS，Annex-B）作为 MediaFormat 的 `csd-0`，在构造时 configure，
 *   因此调用方必须先拿到 CONFIG 再 new VideoDecoder。
 * - pts 直接沿用 scrcpy 的设备 pts（微秒），但**渲染时间戳要换到本机时钟域**：
 *   设备 pts 与本机 nanoTime 基准不同，直接用会超前/滞后几十小时导致画面不刷。
 *   这里以“首帧 pts ↔ 收到首帧时的 nanoTime”为锚点做线性映射，并强制单调递增。
 * - 丢帧策略：待解码队列超过 [MAX_PENDING] 帧时丢弃非关键帧（保证低延迟）；
 *   关键帧到达且积压严重时直接丢掉积压（解码器本来就会在 IDR 处重同步）。
 * - [release] 幂等，可在任意线程调用（内部切到解码线程执行并等待完成）。
 */
class VideoDecoder(
    private val surface: Surface,
    csd0: ByteArray,
    width: Int,
    height: Int,
    private val onSizeChanged: (Int, Int) -> Unit,
    private val onError: (Throwable) -> Unit,
    /** 上一次硬解失败后改为优先选软件解码器（x86_64 MuMu 上硬解可能不可用）。 */
    private val preferSoftware: Boolean = false,
) {

    companion object {
        private const val TAG = "MuMuLink"
        private const val MIME_AVC = "video/avc"

        /** 允许积压的帧数上限（约 100ms@60fps），超过就丢非关键帧。 */
        private const val MAX_PENDING = 6

        /** 渲染时间戳最多允许超前本机时钟这么多（防止 pts 抖动造成长时间不刷）。 */
        private const val MAX_LEAD_NS = 500_000_000L

        /**
         * 选解码器：默认 createDecoderByType（系统按硬件优先选）；
         * preferSoftware 或默认失败时，从 MediaCodecList 里挑一个软件解码器（名字含 google/sw）。
         */
        private fun createCodec(preferSoftware: Boolean): MediaCodec {
            if (!preferSoftware) {
                try {
                    return MediaCodec.createDecoderByType(MIME_AVC)
                } catch (e: Throwable) {
                    Log.w(TAG, "默认解码器创建失败，改用软件解码器：${e.message}")
                }
            }
            val candidates = ArrayList<String>()
            runCatching {
                for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                    if (info.isEncoder) continue
                    if (info.supportedTypes.none { it.equals(MIME_AVC, ignoreCase = true) }) continue
                    candidates.add(info.name)
                }
            }
            // 软件解码器优先（c2.android / OMX.google）
            val sorted = candidates.sortedBy { name ->
                when {
                    name.contains("android", true) || name.contains("google", true) -> 0
                    name.contains("sw", true) -> 1
                    else -> 2
                }
            }
            for (name in sorted) {
                runCatching { return MediaCodec.createByCodecName(name) }
            }
            return MediaCodec.createDecoderByType(MIME_AVC)
        }
    }

    private class PendingFrame(
        val data: ByteArray,
        val renderNs: Long,
        val keyFrame: Boolean,
    )

    private val lock = Any()
    private val pending = ArrayDeque<PendingFrame>()
    private val freeInputs = ArrayDeque<Int>()
    private val drainPosted = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    private val thread: HandlerThread
    private val handler: Handler
    private val codec: MediaCodec

    // 统计（跨线程读，用 @Volatile；只有解码线程写）
    @Volatile
    var decodedFrames: Long = 0L
        private set

    @Volatile
    var droppedFrames: Int = 0
        private set

    /** 当前待解码积压帧数（延迟估计用）。 */
    val pendingInputFrames: Int
        get() = synchronized(lock) { pending.size }

    /** 实际选中的解码器名（如 c2.android.avc.decoder），用于展示/排障。 */
    @Volatile
    var codecName: String = "unknown"
        private set

    private var lastRenderNs = 0L
    private var basePtsUs = -1L
    private var baseLocalNs = 0L

    init {
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height)
        // Annex-B 的 SPS+PPS 整体塞进 csd-0（scrcpy 的 CONFIG 包格式）
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))

        val created = createCodec(preferSoftware)
        codec = created
        codecName = created.name
        thread = HandlerThread("MuMuLink-decode").apply { start() }
        handler = Handler(thread.looper)

        val cb = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                if (released.get()) return
                synchronized(lock) { freeInputs.addLast(index) }
                drainInput()
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released.get()) return
                try {
                    if (info.size > 0) {
                        // 队列里的 pts 已经是本机时钟域的渲染时间戳（ns）
                        mc.releaseOutputBuffer(index, info.presentationTimeUs)
                        decodedFrames++
                    } else {
                        mc.releaseOutputBuffer(index, false)
                    }
                } catch (e: IllegalStateException) {
                    // release 与回调竞争：忽略
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                fail(e)
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                val w = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                Log.i(TAG, "解码输出格式：${w}x$h（codec=$codecName）")
                if (w > 0 && h > 0) onSizeChanged(w, h)
            }
        }

        try {
            created.setCallback(cb, handler)
            created.configure(format, surface, null, 0)
            created.start()
            Log.i(TAG, "VideoDecoder 已启动：$codecName ${width}x$height csd=${csd0.size}B")
        } catch (e: Throwable) {
            Log.e(TAG, "配置/启动解码器失败", e)
            runCatching { created.release() }
            thread.quitSafely()
            throw e
        }
    }

    /**
     * 送入一帧 Annex-B 数据。可从视频读线程直接调用（只做拷贝+入队，不阻塞）。
     * 注意：[data] 是解析器的复用缓冲区，这里会立刻拷贝。
     */
    fun queueFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {
        if (released.get() || failed.get()) return
        val frame = PendingFrame(data.copyOfRange(offset, offset + length), mapRenderNs(ptsUs), keyFrame)
        var needDrain = false
        synchronized(lock) {
            if (keyFrame && pending.size >= MAX_PENDING) {
                // 积压时来了 IDR：丢掉积压（解码器在 IDR 处重同步，不会花屏）
                droppedFrames += pending.size
                pending.clear()
            } else if (!keyFrame && pending.size >= MAX_PENDING) {
                droppedFrames++
                return
            }
            pending.addLast(frame)
            needDrain = freeInputs.isNotEmpty()
        }
        if (needDrain && drainPosted.compareAndSet(false, true)) {
            handler.post { drainInput() }
        }
    }

    /**
     * 清空积压并 flush codec（换流/重连时用）。flush 后必须等下一个关键帧。
     * 相比重建 codec，flush 更快，但部分设备的解码器 flush 后 csd 会失效——
     * 因此调用方若拿到新的 CONFIG，更稳妥的是 release 后重建。
     */
    fun flush() {
        synchronized(lock) {
            droppedFrames += pending.size
            pending.clear()
            freeInputs.clear()
        }
        handler.post {
            if (released.get()) return@post
            runCatching { codec.flush() }
            basePtsUs = -1L
        }
    }

    /** 幂等释放；非解码线程调用时会等待解码线程收尾（最多 2s，避免泄漏）。 */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(lock) {
            pending.clear()
            freeInputs.clear()
        }
        if (Thread.currentThread().id == thread.id) {
            // 在解码线程内部（MediaCodec 回调）不能 stop/release 后继续用 handler：
            // 这里直接收尾并退出 looper 所在的线程。
            doRelease()
            thread.quitSafely()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            doRelease()
            done.countDown()
        }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }

    private fun doRelease() {
        try {
            codec.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "codec.stop 异常（可忽略）：${e.message}")
        }
        try {
            codec.release()
        } catch (e: Throwable) {
            Log.w(TAG, "codec.release 异常：${e.message}")
        }
        Log.i(TAG, "VideoDecoder 已释放（解码 $decodedFrames 帧，丢 $droppedFrames）")
    }

    // ---------------- 内部 ----------------

    private fun drainInput() {
        drainPosted.set(false)
        if (released.get() || failed.get()) return
        while (true) {
            val index: Int
            val frame: PendingFrame
            synchronized(lock) {
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                index = freeInputs.removeFirst()
                frame = pending.removeFirst()
            }
            try {
                val buffer: ByteBuffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(frame.data)
                codec.queueInputBuffer(index, 0, frame.data.size, frame.renderNs, 0)
            } catch (e: IllegalStateException) {
                // codec 正在 stop/flush：把这帧记丢
                droppedFrames++
                return
            } catch (e: Throwable) {
                fail(e)
                return
            }
        }
    }

    /**
     * 设备 pts(µs) → 本机 nanoTime 域的渲染时间。
     * 以“首帧 pts / 首帧到达时刻”为锚点线性映射（保留帧间距，不跨越时钟域）；
     * 同时强制单调递增，并对超前量做上限保护。
     */
    private fun mapRenderNs(ptsUs: Long): Long {
        val now = System.nanoTime()
        var ns: Long = if (ptsUs > 0) {
            synchronized(lock) {
                if (basePtsUs < 0) {
                    basePtsUs = ptsUs
                    baseLocalNs = now
                }
                baseLocalNs + (ptsUs - basePtsUs) * 1000L
            }
        } else {
            now
        }
        if (ns > now + MAX_LEAD_NS) {
            // 设备 pts 跳变（换流/重建编码器）：重新锚定，避免长时间不刷
            synchronized(lock) {
                basePtsUs = ptsUs
                baseLocalNs = now
            }
            ns = now
        }
        if (ns <= lastRenderNs) ns = lastRenderNs + 1_000L
        lastRenderNs = ns
        return ns
    }

    private fun fail(e: Throwable) {
        if (released.get() || !failed.compareAndSet(false, true)) return
        Log.e(TAG, "解码器错误：${e.javaClass.simpleName} ${e.message}")
        onError(e)
    }
}
