package com.mumulink.remote.core

import android.util.Log

/**
 * 触摸相位 → scrcpy 的 MotionEvent action 值（与 AndroidMotionEvent.ACTION_* 一致）。
 * 这里硬编码，避免 core 层依赖 android.view 便于阅读协议。
 */
enum class TouchPhase(val action: Int) {
    DOWN(0),
    UP(1),
    MOVE(2),
    CANCEL(3),
    POINTER_DOWN(5),
    POINTER_UP(6),
}

/**
 * 归一化触摸 → 设备像素 → 控制消息（SPEC 4.5）。
 *
 * 坐标约定：`xNorm/yNorm ∈ 0..1`，相对**视频内容矩形**（letterbox 黑边已由 UI 层扣除）。
 * 设备像素一律取当前 session meta 的宽高（显示旋转后会更新）。
 *
 * 多点触控：维护活动 pointer 集合，`buttons` = 仍有手指按下 ? 1 : 0；
 * 最后一根手指抬起才发 UP，否则发 POINTER_UP（与原生 MotionEvent 语义一致）。
 */
class InputMapper(
    private val writerProvider: () -> ControlWriter?,
    private val deviceSize: () -> Pair<Int, Int>,
) {

    companion object {
        private const val TAG = "MuMuLink"
        private const val BUTTON_PRIMARY = 1
    }

    private val activePointers = HashSet<Long>()
    private val lock = Any()

    fun touch(phase: TouchPhase, pointerId: Long, xNorm: Float, yNorm: Float, pressed: Boolean? = null) {
        val writer = writerProvider() ?: return
        val (w, h) = deviceSize()
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "触摸忽略：会话尺寸未知")
            return
        }
        val x = (xNorm.coerceIn(0f, 1f) * (w - 1)).toInt().coerceIn(0, w - 1)
        val y = (yNorm.coerceIn(0f, 1f) * (h - 1)).toInt().coerceIn(0, h - 1)

        val buttons: Int
        synchronized(lock) {
            when (phase) {
                TouchPhase.DOWN, TouchPhase.POINTER_DOWN -> activePointers.add(pointerId)
                TouchPhase.UP, TouchPhase.POINTER_UP -> activePointers.remove(pointerId)
                TouchPhase.CANCEL -> activePointers.clear()
                else -> Unit
            }
            buttons = if (activePointers.isNotEmpty() || pressed == true) BUTTON_PRIMARY else 0
        }
        // scrcpy 服务端（Controller.injectTouch）在指针数 >1 时会把 ACTION_DOWN/UP
        // 自动改写为 ACTION_POINTER_DOWN/UP | (pointerIndex << 8)，且 pointerIndex 由
        // 服务端按到达顺序分配。因此客户端对所有手指都必须只发原始 DOWN(0)/UP(1)，
        // 不能自己拼 POINTER_*(5/6)——否则 index 位恒为 0，第二根手指会指向错误指针。
        val action = when (phase) {
            TouchPhase.POINTER_DOWN -> TouchPhase.DOWN.action
            TouchPhase.POINTER_UP -> TouchPhase.UP.action
            else -> phase.action
        }
        writer.injectTouch(
            action = action,
            pointerId = pointerId,
            x = x,
            y = y,
            screenWidth = w,
            screenHeight = h,
            actionButton = buttons,
            buttons = buttons,
        )
    }

    /** 简单点击（测试/悬浮球用）：DOWN + UP。 */
    fun tap(xNorm: Float, yNorm: Float) {
        val id = 0L
        touch(TouchPhase.DOWN, id, xNorm, yNorm, pressed = true)
        touch(TouchPhase.UP, id, xNorm, yNorm, pressed = false)
    }

    /** 滚轮（设备像素坐标）。 */
    fun scroll(xNorm: Float, yNorm: Float, hScroll: Float, vScroll: Float) {
        val writer = writerProvider() ?: return
        val (w, h) = deviceSize()
        if (w <= 0 || h <= 0) return
        val x = (xNorm.coerceIn(0f, 1f) * (w - 1)).toInt()
        val y = (yNorm.coerceIn(0f, 1f) * (h - 1)).toInt()
        writer.scroll(x, y, w, h, hScroll, vScroll)
    }

    /**
     * 断开/重连前把仍按着的手指逐根抬起（发 UP）。
     *
     * 为什么必须发而不是只清本地集合：scrcpy 服务端只负责转发，真正记住“哪些指针按着”
     * 的是被控端 InputDispatcher。如果控制通道在手指按下期间断掉（切后台、Wi-Fi 抖动、
     * 会话被重启），被控端会永久残留这些指针，之后所有注入触摸都会以
     * `Inconsistent event / Invalid DOWN event - pointers already down` 被丢弃
     * ——表现就是“画面正常、按键能用，但怎么点都没反应”，只能重启被控端才能恢复。
     * 因此这里在关闭控制通道之前，把本地记录的每根手指都补一个 UP。
     */
    fun releaseAllPointers() {
        val ids = synchronized(lock) { activePointers.toList() }
        val writer = writerProvider()
        val (w, h) = deviceSize()
        if (writer != null && ids.isNotEmpty() && w > 0 && h > 0) {
            ids.forEach { id ->
                runCatching {
                    writer.injectTouch(
                        action = TouchPhase.UP.action,
                        pointerId = id,
                        x = 0,
                        y = 0,
                        screenWidth = w,
                        screenHeight = h,
                        actionButton = 0,
                        buttons = 0,
                    )
                }
            }
            Log.i(TAG, "断开前补发 ${ids.size} 个 UP：$ids")
        }
        synchronized(lock) { activePointers.clear() }
    }

    /** 断开/重连时清空活动手指，避免 buttons 残留为 1。 */
    fun reset() {
        synchronized(lock) { activePointers.clear() }
    }
}
