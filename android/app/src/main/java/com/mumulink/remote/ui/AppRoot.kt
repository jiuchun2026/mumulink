package com.mumulink.remote.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mumulink.remote.model.LinkState
import com.mumulink.remote.model.UiState
import com.mumulink.remote.ui.components.ActionPill
import com.mumulink.remote.ui.components.ActionTone
import com.mumulink.remote.ui.components.BallHiddenHint
import com.mumulink.remote.ui.components.FloatingBallOverlay
import com.mumulink.remote.ui.components.GlassCard
import com.mumulink.remote.ui.components.StatusDot
import com.mumulink.remote.ui.components.tactileClickable
import com.mumulink.remote.ui.screens.ConnectScreen
import com.mumulink.remote.ui.screens.SettingsSheet
import com.mumulink.remote.ui.screens.StreamScreen
import com.mumulink.remote.ui.theme.Danger
import com.mumulink.remote.ui.theme.Ink
import com.mumulink.remote.ui.theme.MuMuLinkTheme
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.glassBorder
import com.mumulink.remote.ui.video.RemoteVideoSurface
import kotlinx.coroutines.delay

/**
 * 应用根节点：只负责「在哪一屏 / 显不显示浮层 / 返回键怎么走」，画面本身交给两个 screen。
 * 所有业务状态都来自 [LinkViewModel]，这里只持有纯 UI 状态（HUD、面板、确认框）。
 */
@Composable
fun AppRoot(vm: LinkViewModel) {
    val ui by vm.ui.collectAsState()
    MuMuLinkTheme {
        RootContent(vm = vm, ui = ui)
    }
}

@Composable
private fun RootContent(vm: LinkViewModel, ui: UiState) {
    val streaming = ui.link is LinkState.Streaming
    val idle = ui.link is LinkState.Idle

    var hadStream by remember { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }
    var sheetOpen by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf<String?>(null) }

    // 一旦进入串流就记住「这轮会话有没有画面」，断线重连时才能留在串流页
    LaunchedEffect(streaming) {
        if (streaming) {
            hadStream = true
            hudVisible = true
        }
    }
    LaunchedEffect(idle) {
        if (idle) {
            hadStream = false
            sheetOpen = false
            confirmDisconnect = false
        }
    }

    // HUD 3 秒自动淡出；设置面板开着时不计时
    LaunchedEffect(hudVisible, sheetOpen, streaming) {
        if (hudVisible && streaming && !sheetOpen) {
            delay(3000)
            hudVisible = false
        }
    }

    // Toast：2 秒后自动收起并清掉 vm 里的消息
    LaunchedEffect(ui.toast) {
        val message = ui.toast
        if (message != null) {
            toastText = message
            delay(2000)
            toastText = null
            vm.consumeToast()
        }
    }

    // 串流时沉进全屏：保留状态栏可下滑唤出，离开串流后恢复
    // （sheetOpen 也作为 key：弹层窗口可能把系统栏带回来，关掉后重新沉浸）
    val view = LocalView.current
    LaunchedEffect(streaming, sheetOpen) {
        view.keepScreenOn = streaming
        val activity = view.context.findActivity() ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        // 全站深色：状态栏/导航栏图标始终用亮色，避免浅色系统主题下图标看不清
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (streaming) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose { view.keepScreenOn = false }
    }

    // 串流页：正在播，或曾经播过（重连 / 切画质期间不要掉回首页）
    val showStream = streaming ||
        (hadStream && (ui.link is LinkState.Connecting || ui.link is LinkState.Retrying))

    BackHandler(enabled = showStream) {
        if (hudVisible) hudVisible = false else confirmDisconnect = true
    }
    BackHandler(enabled = confirmDisconnect) { confirmDisconnect = false }

    Box(modifier = Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = showStream,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (fadeIn(tween(durationMillis = 260, delayMillis = 40)) +
                    scaleIn(initialScale = 0.985f, animationSpec = tween(durationMillis = 260)))
                    .togetherWith(fadeOut(tween(durationMillis = 180)))
            },
            label = "root",
        ) { stream ->
            if (stream) {
                StreamScreen(
                    ui = ui,
                    hudVisible = hudVisible,
                    switching = hadStream && ui.link is LinkState.Connecting,
                    onTapSurface = { /* 点屏幕只透传给被控端，不再唤出 HUD */ },
                    onBack = {
                        if (hudVisible) hudVisible = false else confirmDisconnect = true
                    },
                    onOpenSettings = {
                        hudVisible = true
                        sheetOpen = true
                    },
                    video = { RemoteVideoSurface(vm = vm, modifier = Modifier.fillMaxSize()) },
                    overlay = {
                        if (streaming && !ui.ballVisible) {
                            BallHiddenHint(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .navigationBarsPadding()
                                    .padding(20.dp),
                                onShow = { vm.showBall() },
                            )
                        }
                        FloatingBallOverlay(
                            visible = ui.ballVisible && hadStream,
                            connected = streaming,
                            fps = ui.stats.fps,
                            latencyMs = ui.stats.latencyMs,
                            quality = ui.quality,
                            autoQuality = ui.autoQuality,
                            displayMode = ui.display.mode,
                            onSelectQuality = { vm.setQuality(it) },
                            onAutoQuality = { vm.setAutoQuality(it) },
                            onDisplayMode = { vm.setDisplayMode(it) },
                            onKey = { vm.sendKey(it) },
                            onHide = { vm.hideBall() },
                            onDisconnect = { confirmDisconnect = true },
                        )
                    },
                )
            } else {
                ConnectScreen(
                    ui = ui,
                    onStartScan = { vm.startScan() },
                    onStopScan = { vm.stopScan() },
                    onConnect = { host, pairingCode -> vm.connect(host, pairingCode) },
                )
            }
        }

        AnimatedVisibility(
            visible = toastText != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 44.dp),
            enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(240),
            ),
            exit = fadeOut(tween(180)),
        ) {
            ToastCapsule(text = toastText.orEmpty())
        }

        if (confirmDisconnect) {
            DisconnectConfirm(
                onDismiss = { confirmDisconnect = false },
                onConfirm = {
                    confirmDisconnect = false
                    sheetOpen = false
                    vm.disconnect()
                },
            )
        }
    }

    if (sheetOpen) {
        SettingsSheet(
            ui = ui,
            onDismiss = { sheetOpen = false },
            onQuality = { vm.setQuality(it) },
            onAutoQuality = { vm.setAutoQuality(it) },
            onFps = { fps -> vm.setQuality(ui.quality.copy(maxFps = fps)) },
            onMaxSize = { size -> vm.setQuality(ui.quality.copy(maxSize = size)) },
            onBitrate = { bps -> vm.setQuality(ui.quality.copy(bitRate = bps * 1_000_000)) },
            onDisplayMode = { vm.setDisplayMode(it) },
            onKey = { vm.sendKey(it) },
            onToggleBall = { visible -> if (visible) vm.showBall() else vm.hideBall() },
            onDisconnect = {
                sheetOpen = false
                vm.disconnect()
            },
        )
    }
}

@Composable
private fun ToastCapsule(text: String) {
    Row(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(PillShape)
            .background(Ink.copy(alpha = 0.94f))
            .border(1.dp, glassBorder(accent = Primary), PillShape)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        StatusDot(color = Primary, size = 5.dp, halo = false)
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OnInk.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DisconnectConfirm(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.72f))
            .tactileClickable(onClick = onDismiss, onClickLabel = "取消"),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .tactileClickable(onClick = {}, onClickLabel = null),
            glow = Danger,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        ) {
            Text(
                text = "断开串流？",
                style = MaterialTheme.typography.headlineSmall,
                color = OnInk,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "电脑端会回到桌面，可随时重新连接。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(
                    text = "继续串流",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    tone = ActionTone.Ghost,
                )
                ActionPill(
                    text = "断开",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    tone = ActionTone.Danger,
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}
