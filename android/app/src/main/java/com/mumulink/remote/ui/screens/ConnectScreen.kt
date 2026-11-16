package com.mumulink.remote.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mumulink.remote.BuildConfig
import com.mumulink.remote.model.AgentHello
import com.mumulink.remote.model.LinkState
import com.mumulink.remote.model.UiState
import com.mumulink.remote.ui.components.ActionPill
import com.mumulink.remote.ui.components.ActionTone
import com.mumulink.remote.ui.components.AuroraBackground
import com.mumulink.remote.ui.components.EmptyState
import com.mumulink.remote.ui.components.GlassCard
import com.mumulink.remote.ui.components.GlassDivider
import com.mumulink.remote.ui.components.IconPill
import com.mumulink.remote.ui.components.SignalOrb
import com.mumulink.remote.ui.components.StatusDot
import com.mumulink.remote.ui.components.OrbTone
import com.mumulink.remote.ui.components.tactileClickable
import com.mumulink.remote.ui.theme.Danger
import com.mumulink.remote.ui.theme.FieldShape
import com.mumulink.remote.ui.theme.MonoFamily
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.NumericSmall
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Secondary
import com.mumulink.remote.ui.theme.Space
import com.mumulink.remote.ui.theme.Tertiary
import com.mumulink.remote.ui.theme.glassBorder

private const val DEFAULT_PORT = 27180
private const val PREFS = "mumulink.ui"
private const val KEY_RECENT = "recent_hosts"

/** 首页历史记录：只用 SharedPreferences，不新增依赖，也不改动其它层。 */
private object RecentStore {
    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, null)
            ?.split("|")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun push(context: Context, host: String): List<String> {
        val next = (listOf(host) + load(context)).distinct().take(4)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT, next.joinToString("|"))
            .apply()
        return next
    }

    fun clear(context: Context): List<String> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECENT).apply()
        return emptyList()
    }
}

/** 端口不是默认值时把 "host:port" 一起交给 vm.connect（vm 只收 host 字符串）。 */
private fun endpointHost(host: String, port: Int): String =
    if (port == DEFAULT_PORT) host else "$host:$port"

/**
 * 连接页：品牌头 + 扫描状态 + 发现列表 + 历史 + 手动连接 + 底部配对提示。
 * 只在本地维护输入框/展开态这类纯 UI 状态；连接与扫描全部走回调。
 */
@Composable
fun ConnectScreen(
    ui: UiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (host: String, pairingCode: String?) -> Unit,
) {
    val context = LocalContext.current
    var recent by remember { mutableStateOf(RecentStore.load(context)) }
    var manualOpen by remember { mutableStateOf(false) }
    var ipText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(DEFAULT_PORT.toString()) }
    var pairText by remember { mutableStateOf("") }
    var pendingPair by remember { mutableStateOf<AgentHello?>(null) }
    var pendingCode by remember { mutableStateOf("") }
    var lastHost by remember { mutableStateOf<String?>(null) }

    val link = ui.link
    val connecting = link is LinkState.Connecting
    val failed = link is LinkState.Failed
    val retrying = link is LinkState.Retrying

    fun attempt(host: String, code: String?) {
        lastHost = host
        recent = RecentStore.push(context, host)
        onConnect(host, code?.takeIf { it.isNotBlank() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.xl)
                    .padding(bottom = Space.xl),
            ) {
                Spacer(Modifier.height(Space.m))
                BrandHeader(
                    scanning = ui.scanning,
                    onToggleScan = { if (ui.scanning) onStopScan() else onStartScan() },
                )

                Spacer(Modifier.height(Space.xl))

                when {
                    connecting -> ConnectingPanel(link as LinkState.Connecting)
                    failed || retrying -> FailedPanel(
                        message = if (failed) (link as LinkState.Failed).message else "连接中断，正在重连…",
                        hint = if (failed) (link as LinkState.Failed).hint else "第 ${(link as LinkState.Retrying).attempt} 次尝试",
                        onRetry = {
                            val host = lastHost ?: ui.agents.firstOrNull()?.host
                            if (host != null) onConnect(host, null) else onStartScan()
                        },
                        onManual = { manualOpen = true },
                    )
                    else -> ScanPanel(
                        scanning = ui.scanning,
                        count = ui.agents.size,
                    )
                }

                Spacer(Modifier.height(Space.l))

                if (!connecting && !failed && !retrying) {
                    if (ui.agents.isEmpty()) {
                        EmptyState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.l),
                            icon = Icons.Outlined.WifiOff,
                            title = if (ui.scanning) "正在寻找电脑…" else "还没有找到电脑",
                            body = "确认电脑端 MuMuLink 已启动\n手机与电脑连在同一个 Wi-Fi\n防火墙放行 27180 / 27181",
                        )
                        Spacer(Modifier.height(Space.s))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            IconPill(
                                icon = Icons.Outlined.Refresh,
                                onClick = onStartScan,
                                label = if (ui.scanning) "重新扫描" else "扫描局域网",
                                contentDescription = "扫描局域网",
                                size = 38.dp,
                            )
                        }
                    } else {
                        SectionLabel("发现 ${ui.agents.size} 台电脑")
                        ui.agents.forEach { agent ->
                            AgentRow(
                                agent = agent,
                                onClick = {
                                    if (agent.paired) {
                                        attempt(endpointHost(agent.host, DEFAULT_PORT), null)
                                    } else {
                                        pendingCode = ""
                                        pendingPair = agent
                                    }
                                },
                            )
                            Spacer(Modifier.height(Space.s))
                        }
                    }
                }

                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(Space.l))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("最近连接", modifier = Modifier.weight(1f))
                        Text(
                            text = "清除",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                            modifier = Modifier
                                .clip(PillShape)
                                .tactileClickable(onClick = { recent = RecentStore.clear(context) })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        recent.forEach { host ->
                            RecentChip(host = host, onClick = { attempt(host, null) })
                        }
                    }
                }

                Spacer(Modifier.height(Space.xl))

                ManualPanel(
                    open = manualOpen,
                    onToggle = { manualOpen = !manualOpen },
                    ip = ipText,
                    onIp = { ipText = it },
                    port = portText,
                    onPort = { portText = it.take(5) },
                    code = pairText,
                    onCode = { pairText = it.filter { ch -> ch.isDigit() }.take(6) },
                    onConnect = {
                        val host = ipText.trim()
                        if (host.isNotEmpty()) {
                            attempt(endpointHost(host, portText.toIntOrNull() ?: DEFAULT_PORT), pairText)
                        }
                    },
                )

                Spacer(Modifier.height(Space.xl))
                Footer(
                    version = BuildConfig.VERSION_NAME,
                    agentHost = ui.agents.firstOrNull()?.host,
                )
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        pendingPair?.let { agent ->
            PairingDialog(
                agent = agent,
                code = pendingCode,
                onCode = { pendingCode = it.filter { ch -> ch.isDigit() }.take(6) },
                onDismiss = { pendingPair = null },
                onConfirm = {
                    pendingPair = null
                    attempt(endpointHost(agent.host, DEFAULT_PORT), pendingCode)
                },
            )
        }
    }
}

@Composable
private fun BrandHeader(scanning: Boolean, onToggleScan: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = 44.dp)
        Spacer(Modifier.width(Space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "MuMuLink",
                style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.2).sp),
                color = OnInk,
            )
            Text(
                text = "局域网串流 · 低延迟",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        IconPill(
            icon = Icons.Outlined.Refresh,
            onClick = onToggleScan,
            label = if (scanning) "停止" else "扫描",
            contentDescription = if (scanning) "停止扫描" else "开始扫描",
            selected = scanning,
            size = 36.dp,
        )
    }
}

/** 自绘 logo：双弧环 + 轨道点，不用图片资源。 */
@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(r, r)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Primary.copy(alpha = 0.22f), Color.Transparent),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )
        rotate(-60f, pivot = c) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Primary, Secondary, Primary),
                    center = c,
                ),
                startAngle = 0f,
                sweepAngle = 292f,
                useCenter = false,
                topLeft = Offset(r * 0.16f, r * 0.16f),
                size = Size(r * 1.68f, r * 1.68f),
                style = Stroke(width = r * 0.12f, cap = StrokeCap.Round),
            )
        }
        drawArc(
            color = Primary.copy(alpha = 0.35f),
            startAngle = 210f,
            sweepAngle = 210f,
            useCenter = false,
            topLeft = Offset(r * 0.42f, r * 0.42f),
            size = Size(r * 1.16f, r * 1.16f),
            style = Stroke(width = r * 0.09f, cap = StrokeCap.Round),
        )
        drawCircle(color = Secondary, radius = r * 0.12f, center = Offset(r + r * 0.42f, r - r * 0.70f))
    }
}

@Composable
private fun ConnectingPanel(step: LinkState.Connecting) {
    val transition = rememberInfiniteTransition(label = "connecting")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "progress",
    )
    GlassCard(glow = Primary, contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.xl)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SignalOrb(size = 132.dp, tone = OrbTone.Connecting, progress = progress)
            Spacer(Modifier.height(Space.m))
            Text(
                text = step.step.ifBlank { "正在连接" },
                style = MaterialTheme.typography.titleMedium,
                color = OnInk,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "请保持手机与电脑在同一网络",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun FailedPanel(
    message: String,
    hint: String,
    onRetry: () -> Unit,
    onManual: () -> Unit,
) {
    GlassCard(glow = Danger, contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = Danger, size = 6.dp, halo = false)
            Spacer(Modifier.width(Space.s))
            Text(text = "连接失败", style = MaterialTheme.typography.titleMedium, color = OnInk)
        }
        Spacer(Modifier.height(Space.s))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = OnInk.copy(alpha = 0.85f))
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(Space.xs))
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PrimaryPill(text = "重试", onClick = onRetry)
            GhostPill(text = "手动连接", onClick = onManual)
        }
    }
}

@Composable
private fun ScanPanel(scanning: Boolean, count: Int) {
    GlassCard(glow = if (scanning) Primary else null, contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (scanning) Primary else Secondary,
                size = 6.dp,
                pulse = scanning,
            )
            Spacer(Modifier.width(Space.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (scanning) "正在寻找电脑…" else "局域网已就绪",
                    style = MaterialTheme.typography.titleSmall,
                    color = OnInk,
                )
                Text(
                    text = if (scanning) {
                        "UDP 27181 广播中 · 已发现 $count 台"
                    } else {
                        if (count > 0) "$count 台可连接" else "点右上角重新扫描"
                    },
                    style = NumericSmall.copy(fontSize = 10.sp),
                    color = Muted,
                )
            }
            if (count > 0) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFamily),
                    color = Primary,
                )
            }
        }
        if (scanning) {
            Spacer(Modifier.height(Space.m))
            ScanLine()
        }
    }
}

/** 扫描中的能量流动画，2dp 高的渐变线。 */
@Composable
private fun ScanLine() {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "shift",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.05f)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val start = shift * w
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Primary, Secondary, Color.Transparent),
                    startX = start,
                    endX = start + w * 0.55f,
                ),
                topLeft = Offset(start, 0f),
                size = Size(w * 0.55f, size.height),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = Space.s, top = Space.xs),
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
    )
}

@Composable
private fun AgentRow(agent: AgentHello, onClick: () -> Unit) {
    GlassCard(
        onClick = onClick,
        onClickLabel = "连接到 ${agent.name}",
        glow = if (agent.paired) Primary else Tertiary,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = if (agent.paired) Primary else Tertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Space.m))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent.name.ifBlank { "未命名电脑" },
                        style = MaterialTheme.typography.titleSmall,
                        color = OnInk,
                        maxLines = 1,
                    )
                    if (agent.session?.running == true) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "串流中",
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(agent.host)
                        append(":")
                        append(DEFAULT_PORT)
                        if (agent.version.isNotBlank()) append(" · v${agent.version}")
                    },
                    style = NumericSmall.copy(fontSize = 10.sp),
                    color = Muted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    color = if (agent.paired) Secondary else Tertiary,
                    size = 4.dp,
                    halo = false,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (agent.paired) "已配对" else "配对",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (agent.paired) Secondary else Tertiary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentChip(host: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), PillShape)
            .tactileClickable(onClick = onClick, onClickLabel = "连接 $host")
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = Muted, size = 4.dp, halo = false)
        Spacer(Modifier.width(6.dp))
        Text(text = host, style = NumericSmall.copy(fontSize = 10.sp), color = OnInk.copy(alpha = 0.85f))
    }
}

/** 手动连接面板：收起时只是一行入口，展开后是 IP / 端口 / 配对码。 */
@Composable
private fun ManualPanel(
    open: Boolean,
    onToggle: () -> Unit,
    ip: String,
    onIp: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PillShape)
                .tactileClickable(onClick = onToggle, onClickLabel = "手动连接")
                .padding(vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "手动连接",
                style = MaterialTheme.typography.labelLarge,
                color = OnInk.copy(alpha = 0.9f),
            )
            Spacer(Modifier.width(Space.s))
            Text(
                text = "知道电脑 IP 时用",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(240)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
        ) {
            GlassCard(
                accent = Secondary,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.l),
            ) {
                Text(text = "电脑 IP", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassField(
                        value = ip,
                        onValueChange = { onIp(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = "192.168.1.20",
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    )
                    Spacer(Modifier.width(Space.s))
                    GlassField(
                        value = port,
                        onValueChange = onPort,
                        modifier = Modifier.width(96.dp),
                        placeholder = "27180",
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    )
                }
                Spacer(Modifier.height(Space.m))
                Text(text = "配对码（可选）", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                GlassField(
                    value = code,
                    onValueChange = onCode,
                    placeholder = "6 位数字",
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(Space.l))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryPill(
                        text = if (code.isBlank()) "连接" else "配对并连接",
                        onClick = onConnect,
                        enabled = ip.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = MonoFamily,
            fontSize = 15.sp,
            color = OnInk,
        ),
        cursorBrush = SolidColor(Primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(FieldShape)
                    .background(Color.White.copy(alpha = 0.045f))
                    .border(1.dp, glassBorder(accent = Secondary), FieldShape)
                    .padding(horizontal = Space.m),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = TextStyle(fontFamily = MonoFamily, fontSize = 15.sp, color = Muted.copy(alpha = 0.7f)),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ActionPill(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = ActionTone.Primary,
    )
}

@Composable
private fun GhostPill(text: String, onClick: () -> Unit) {
    ActionPill(text = text, onClick = onClick, tone = ActionTone.Ghost)
}

/** 未配对电脑的配对弹层：6 位码，自动聚焦输入，配对码来自电脑端网页。 */
@Composable
private fun PairingDialog(
    agent: AgentHello,
    code: String,
    onCode: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .tactileClickable(onClick = onDismiss, onClickLabel = "取消配对"),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier
                .padding(horizontal = Space.xl)
                .tactileClickable(onClick = {}, onClickLabel = null),
            glow = Primary,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.xl),
        ) {
            Text(
                text = "与 ${agent.name.ifBlank { agent.host }} 配对",
                style = MaterialTheme.typography.titleMedium,
                color = OnInk,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "在电脑上打开 http://${agent.host}:${DEFAULT_PORT} 获取 6 位配对码",
                style = NumericSmall.copy(fontSize = 10.sp),
                color = Muted,
            )
            Spacer(Modifier.height(Space.l))
            GlassField(
                value = code,
                onValueChange = onCode,
                placeholder = "000000",
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                GhostPill(text = "取消", onClick = onDismiss)
                Box(modifier = Modifier.weight(1f)) {
                    PrimaryPill(
                        text = "配对并连接",
                        onClick = onConfirm,
                        enabled = code.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(Space.s))
            Text(
                text = "配对一次后，下次自动连接",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Footer(version: String, agentHost: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassDivider()
        Spacer(Modifier.height(Space.m))
        Text(
            text = "在电脑上打开 http://${agentHost ?: "<电脑IP>"}:$DEFAULT_PORT 完成配对",
            style = NumericSmall.copy(fontSize = 10.sp),
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "MuMuLink $version · 端到端加密局域网直连",
            style = MaterialTheme.typography.labelSmall,
            color = Muted.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}
