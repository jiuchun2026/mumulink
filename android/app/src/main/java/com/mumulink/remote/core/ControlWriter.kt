package com.mumulink.remote.core

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * scrcpy 控制消息序列化 + 单线程发送队列（SPEC 3/4.5，布局权威来自 scrcpy 5.0.1
 * server/control/ControlMessageReader.java，全部 **big-endian**）。
 *
 * 消息布局速查：
 * ```
 * 0  INJECT_KEYCODE      type(1) action(1) keycode(4) repeat(4) metaState(4)
 * 1  INJECT_TEXT         type(1) len(4) utf8(len)
 * 2  INJECT_TOUCH_EVENT  type(1) action(1) pointerId(8) x(4) y(4) w(2) h(2)
 *                        pressure(2, u16fp) actionButton(4) buttons(4)
 * 3  INJECT_SCROLL_EVENT type(1) x(4) y(4) w(2) h(2) hScroll(2, i16fp) vScroll(2) buttons(4)
 * 4  BACK_OR_SCREEN_ON   type(1) action(1)
 * 9  SET_CLIPBOARD       type(1) sequence(8) paste(1) len(4) utf8(len)
 * 10 SET_DISPLAY_POWER   type(1) on(1)
 * 11 ROTATE_DEVICE       type(1)
 * 5/6/7/15/17            type(1)（无负载）
 * ```
 * 注意：scrcpy 的 action 字段是 **1 字节无符号**（不是 4 字节 int），pressure 是
 * u16 定点（0xFFFF = 1.0），scroll 是 i16 定点（0x7FFF = 1.0）。
 *
 * 并发：所有写入都进 [queue]，由唯一的 [writer] 线程按顺序落 socket，避免多指事件
 * 交错导致的乱序/半包。写入失败通过 [onError] 上报（上层触发重连）。
 */
class ControlWriter(
    private val output: OutputStream,
    private val onError: ((Throwable) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "MuMuLink"

        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_EXPAND_SETTINGS_PANEL = 6
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_SET_DISPLAY_POWER = 10
        const val TYPE_ROTATE_DEVICE = 11

        /** scrcpy 5.x：无负载，让服务端重置视频编码器（会补发 SESSION + SPS/PPS + IDR）。 */
        const val TYPE_RESET_VIDEO = 17

        /** scrcpy server 单条 INJECT_TEXT 上限（ControlMessageReader.INJECT_TEXT_MAX_LENGTH）。 */
        const val INJECT_TEXT_MAX_BYTES = 300

        private const val PRESSURE_MAX = 0xFFFF
        private val SENTINEL = ByteArray(0)

        private fun buffer(size: Int): ByteBuffer =
            ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        /** INJECT_KEYCODE */
        fun buildKeycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray =
            buffer(14).apply {
                put(TYPE_INJECT_KEYCODE.toByte())
                put((action and 0xFF).toByte())
                putInt(keycode)
                putInt(repeat)
                putInt(metaState)
            }.array()

        /** INJECT_TEXT（UTF-8，超过 [INJECT_TEXT_MAX_BYTES] 会被截断到合法字符边界） */
        fun buildText(text: String): ByteArray {
            val bytes = truncateUtf8(text, INJECT_TEXT_MAX_BYTES)
            return buffer(5 + bytes.size).apply {
                put(TYPE_INJECT_TEXT.toByte())
                putInt(bytes.size)
                put(bytes)
            }.array()
        }

        /** INJECT_TOUCH_EVENT（坐标为设备像素） */
        fun buildTouchEvent(
            action: Int,
            pointerId: Long,
            x: Int,
            y: Int,
            screenWidth: Int,
            screenHeight: Int,
            pressure: Float = 1f,
            actionButton: Int,
            buttons: Int,
        ): ByteArray = buffer(1 + 1 + 8 + 12 + 2 + 4 + 4).apply {
            put(TYPE_INJECT_TOUCH_EVENT.toByte())
            put((action and 0xFF).toByte())
            putLong(pointerId)
            putInt(x)
            putInt(y)
            putShort(screenWidth.toShort())
            putShort(screenHeight.toShort())
            putShort(encodePressure(pressure))
            putInt(actionButton)
            putInt(buttons)
        }.array()

        /** INJECT_SCROLL_EVENT */
        fun buildScrollEvent(
            x: Int,
            y: Int,
            screenWidth: Int,
            screenHeight: Int,
            hScroll: Float,
            vScroll: Float,
            buttons: Int = 0,
        ): ByteArray = buffer(1 + 12 + 2 + 2 + 4).apply {
            put(TYPE_INJECT_SCROLL_EVENT.toByte())
            putInt(x)
            putInt(y)
            putShort(screenWidth.toShort())
            putShort(screenHeight.toShort())
            putShort(encodeScroll(hScroll))
            putShort(encodeScroll(vScroll))
            putInt(buttons)
        }.array()

        /** BACK_OR_SCREEN_ON */
        fun buildBackOrScreenOn(action: Int): ByteArray =
            byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), (action and 0xFF).toByte())

        /** SET_DISPLAY_POWER */
        fun buildDisplayPower(on: Boolean): ByteArray =
            byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0)

        /** ROTATE_DEVICE */
        fun buildRotateDevice(): ByteArray = byteArrayOf(TYPE_ROTATE_DEVICE.toByte())

        /** RESET_VIDEO（仅 1 字节 type，服务端重开编码器并补发关键帧） */
        fun buildResetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

        fun buildEmpty(type: Int): ByteArray = byteArrayOf(type.toByte())

        /** float(0..1) → u16 定点；1.0 用 0xFFFF（与 scrcpy 一致） */
        internal fun encodePressure(value: Float): Short {
            val v = value.coerceIn(0f, 1f)
            if (v >= 1f) return PRESSURE_MAX.toShort()
            return (v * PRESSURE_MAX).toInt().toShort()
        }

        /** float(-1..1) → i16 定点；1.0 用 0x7FFF */
        internal fun encodeScroll(value: Float): Short {
            val v = value.coerceIn(-1f, 1f)
            if (v >= 1f) return 0x7FFF
            if (v <= -1f) return (-0x8000).toShort()
            return (v * 0x8000).toInt().toShort()
        }

        /** 按 UTF-8 字节上限截断，且不切断多字节字符。 */
        internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
            val raw = text.toByteArray(Charsets.UTF_8)
            if (raw.size <= maxBytes) return raw
            var end = maxBytes
            // 回退到字符边界：UTF-8 续字节形如 10xxxxxx
            while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
            return raw.copyOf(end)
        }
    }

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val writer = Thread({ writeLoop() }, "MuMuLink-ctl-writer").apply {
        isDaemon = true
        start()
    }

    val isClosed: Boolean get() = closed.get()

    /** 线程安全：任何线程都可以调用，消息按入队顺序发送。 */
    fun write(message: ByteArray) {
        if (closed.get()) {
            // 排查用：关闭后仍有人写，说明上层拿到的是过期 writer
            Log.w(TAG, "控制通道已关闭，丢弃 ${message.size}B（type=${message[0].toInt() and 0xFF}）")
            return
        }
        // D 级：控制消息逐条打印，量级与触控帧率同阶（release 里保留，便于现场排查触摸问题）
        Log.d(TAG, "TX type=${message[0].toInt() and 0xFF} size=${message.size}")
        queue.offer(message)
    }

    fun injectKeycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0) =
        write(buildKeycode(action, keycode, repeat, metaState))

    fun injectText(text: String) = write(buildText(text))

    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        actionButton: Int,
        buttons: Int,
        pressure: Float = 1f,
    ) = write(buildTouchEvent(action, pointerId, x, y, screenWidth, screenHeight, pressure, actionButton, buttons))

    fun scroll(x: Int, y: Int, screenWidth: Int, screenHeight: Int, hScroll: Float, vScroll: Float, buttons: Int = 0) =
        write(buildScrollEvent(x, y, screenWidth, screenHeight, hScroll, vScroll, buttons))

    fun backOrScreenOn(action: Int) = write(buildBackOrScreenOn(action))

    fun rotateDevice() = write(buildRotateDevice())

    /**
     * 请求服务端重置视频编码器：SCREEN 采集会重新出 SESSION + CONFIG + IDR。
     * 用于「换 Surface / 从后台回来 / 解码器自检失败」——这些场景旧解码器已丢，
     * 而 H.264 流中途没有新的关键帧，不主动要一帧就会一直黑屏。
     */
    fun resetVideo() = write(buildResetVideo())

    fun setDisplayPower(on: Boolean) = write(buildDisplayPower(on))

    fun collapsePanels() = write(buildEmpty(TYPE_COLLAPSE_PANELS))

    fun expandNotificationPanel() = write(buildEmpty(TYPE_EXPAND_NOTIFICATION_PANEL))

    fun close() {
        if (closed.compareAndSet(false, true)) {
            queue.offer(SENTINEL)
            writer.interrupt()
        }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val msg = queue.take()
                if (msg === SENTINEL || closed.get() && queue.isEmpty()) return
                if (msg.isEmpty()) continue
                output.write(msg)
                // socket 的 OutputStream 无缓冲，write 即发送；不需要 flush
            }
        } catch (e: InterruptedException) {
            // close() 主动打断
        } catch (e: IOException) {
            if (!closed.get()) {
                Log.w(TAG, "控制通道写入失败：${e.message}")
                closed.set(true)
                onError?.invoke(e)
            }
        } catch (e: Throwable) {
            if (!closed.get()) {
                Log.w(TAG, "控制写入线程异常：${e.javaClass.simpleName} ${e.message}")
                closed.set(true)
                onError?.invoke(e)
            }
        }
    }
}
