package com.mumulink.remote.ui.video

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mumulink.remote.core.TouchPhase
import com.mumulink.remote.ui.LinkViewModel

/**
 * 远端画面 + 触摸转发。
 *
 * - `SurfaceView`（低延迟、零拷贝）通过 SurfaceHolder 把 Surface 交给解码器；
 * - 画面按设备真实宽高做 **aspect-fit**（letterbox），黑边由外层居中排列自然产生，
 *   因此触摸坐标可以直接用“视频内容矩形”归一化，不需要再扣黑边；
 * - 触摸用 `pointerInput` 在内容矩形上收集（Overlay 层，**不 consume**），
 *   这样外层 HUD 的 Final-pass 点击观察仍然有效（app-ui 的 observeTaps）。
 * - 中途 meta 变化（旋转/改 wm size）时 Compose 重排 → SurfaceView 尺寸随之变化，
 *   同时显式 `requestLayout()`，绝不在主线程等锁。
 */
@Composable
fun RemoteVideoSurface(vm: LinkViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsState()
    val deviceW = ui.stats.deviceWidth
    val deviceH = ui.stats.deviceHeight

    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val contentSize = fitSize(deviceW, deviceH)
        Box(modifier = Modifier.size(contentSize)) {
            val tracker = remember { SurfaceTracker(vm) }
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        // 触摸由上层 Compose overlay 处理，SurfaceView 不参与
                        isClickable = false
                        isFocusable = false
                        holder.addCallback(tracker)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // meta 中途变化时重新布局（尺寸由 Compose 驱动，这里只补一刀）
                    view.post { view.requestLayout() }
                },
            )
            DisposableEffect(tracker) {
                onDispose { tracker.detach() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(vm) {
                        val active = HashSet<Long>()
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                for (change in event.changes) {
                                    val nx = (change.position.x / w).coerceIn(0f, 1f)
                                    val ny = (change.position.y / h).coerceIn(0f, 1f)
                                    when {
                                        change.changedToDown() -> {
                                            val first = active.isEmpty()
                                            active.add(change.id.value)
                                            vm.touch(
                                                if (first) TouchPhase.DOWN else TouchPhase.POINTER_DOWN,
                                                change.id.value, nx, ny,
                                            )
                                        }
                                        change.changedToUp() -> {
                                            active.remove(change.id.value)
                                            vm.touch(
                                                if (active.isEmpty()) TouchPhase.UP else TouchPhase.POINTER_UP,
                                                change.id.value, nx, ny,
                                            )
                                        }
                                        change.pressed && change.positionChanged() -> {
                                            vm.touch(TouchPhase.MOVE, change.id.value, nx, ny)
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
        }
    }
}

/**
 * 按设备画面宽高在可用区域内做 aspect-fit（等比缩放 + 居中）。
 * 宽高未知时先铺满，等 SESSION meta 到达再收敛。
 */
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.fitSize(
    deviceW: Int,
    deviceH: Int,
): DpSize {
    if (deviceW <= 0 || deviceH <= 0 || maxWidth <= 0.dp || maxHeight <= 0.dp) {
        return DpSize(maxWidth, maxHeight)
    }
    val ratio = deviceW.toFloat() / deviceH.toFloat()
    val boxRatio = maxWidth.value / maxHeight.value
    return if (boxRatio > ratio) {
        // 可用区域比视频更宽 → 高度受限，左右留黑边
        DpSize(maxHeight * ratio, maxHeight)
    } else {
        // 更窄 → 宽度受限，上下留黑边
        DpSize(maxWidth, maxWidth / ratio)
    }
}

/** 持有当前 Surface，并把生命周期转给 LinkViewModel（surfaceCreated/Destroyed 成对出现）。 */
private class SurfaceTracker(private val vm: LinkViewModel) : SurfaceHolder.Callback {

    private var surface: Surface? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        val s = holder.surface ?: return
        surface = s
        vm.attachSurface(s)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Surface 本身没变（尺寸由 SurfaceView 自己管），解码器按 meta 重建，这里无需操作
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detach()
    }

    fun detach() {
        val s = surface ?: return
        surface = null
        vm.detachSurface(s)
    }
}
