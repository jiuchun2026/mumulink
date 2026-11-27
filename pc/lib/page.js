'use strict';

// 图形化控制台（离线单页，无 CDN）：概览 / 会话 / 设置 / 诊断
// 配对模型：浏览器首次输入配对码 → /api/pair 拿 token → localStorage 保存 → 之后所有 /api/* 带 X-MuMuLink-Token。

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function renderPage(config, opts = {}) {
  const boot = {
    name: config.name,
    httpPort: config.httpPort,
    videoPort: config.videoPort,
    audioPort: config.audioPort,
    controlPort: config.controlPort,
    udpPort: config.udpPort,
    pairingCode: opts.pairingCode === undefined ? config.pairingCode : opts.pairingCode,
    agentVersion: opts.agentVersion || '1.1.0',
    mode: config.mode || 'normal'
  };
  const bootJson = JSON.stringify(boot).replace(/</g, '\\u003c');

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MuMuLink 控制台 - ${escapeHtml(config.name)}</title>
<style>
  :root { color-scheme: dark; --bg:#0b0e14; --card:#121724; --card2:#171d2c; --line:#232b3d; --ink:#e9edf7; --muted:#8a93a8; --primary:#7c9cff; --ok:#57d9a3; --bad:#ff6b6b; --warn:#ffc857; }
  * { box-sizing: border-box; }
  body { margin:0; background:radial-gradient(1200px 600px at 20% -10%, #1a2340 0%, var(--bg) 55%); color:var(--ink);
         font:14px/1.55 -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; min-height:100vh; }
  .wrap { max-width:1080px; margin:0 auto; padding:20px 18px 60px; }
  header { display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin-bottom:16px; }
  .logo { width:40px; height:40px; border-radius:12px; background:linear-gradient(135deg,#7c9cff,#58e1c1); box-shadow:0 6px 24px rgba(124,156,255,.35); }
  h1 { font-size:19px; margin:0; }
  .sub { color:var(--muted); font-size:12.5px; }
  .pill { padding:4px 10px; border-radius:999px; border:1px solid var(--line); background:var(--card); font-size:12px; color:var(--muted); }
  .pill.on { color:var(--ok); border-color:rgba(87,217,163,.4); }
  .pill.game { color:var(--warn); border-color:rgba(255,200,87,.45); }
  .tabs { display:flex; gap:8px; margin:14px 0 16px; flex-wrap:wrap; }
  .tab { padding:8px 14px; border-radius:10px; border:1px solid var(--line); background:var(--card); color:var(--muted); cursor:pointer; font-size:13.5px; }
  .tab.active { color:var(--ink); border-color:rgba(124,156,255,.55); background:linear-gradient(180deg, rgba(124,156,255,.18), rgba(124,156,255,.06)); }
  .grid { display:grid; gap:14px; }
  @media (min-width: 820px) { .grid.two { grid-template-columns: 1.1fr .9fr; } }
  .card { background:linear-gradient(180deg, rgba(255,255,255,.045), rgba(255,255,255,.015)); border:1px solid var(--line); border-radius:16px; padding:16px; }
  .card h2 { font-size:14px; margin:0 0 10px; color:var(--ink); font-weight:600; }
  .row { display:flex; justify-content:space-between; gap:12px; padding:6px 0; border-bottom:1px dashed rgba(255,255,255,.05); }
  .row:last-child { border-bottom:0; }
  .row .k { color:var(--muted); }
  .row .v { text-align:right; word-break:break-all; font-variant-numeric: tabular-nums; }
  .mono { font-family:ui-monospace, Consolas, monospace; }
  .ok { color:var(--ok); } .bad { color:var(--bad); } .warn { color:var(--warn); }
  .addr { display:flex; justify-content:space-between; align-items:center; gap:10px; padding:9px 11px; border:1px solid var(--line); border-radius:10px; margin-bottom:8px; background:var(--card2); }
  button { appearance:none; border:1px solid var(--line); background:var(--card2); color:var(--ink); border-radius:10px; padding:9px 12px; font-size:13.5px; cursor:pointer; }
  button:hover { border-color:#38445e; }
  button.primary { background:linear-gradient(180deg,#5f83f0,#4a6ad0); border-color:#5f83f0; }
  button.danger { background:#3d1d22; border-color:#6b2b32; color:#ffb4b4; }
  button.ghost { background:transparent; }
  button:disabled { opacity:.45; cursor:not-allowed; }
  .btns { display:flex; gap:8px; flex-wrap:wrap; margin-top:12px; }
  input, select { width:100%; padding:9px 11px; background:#0d1119; border:1px solid var(--line); border-radius:9px; color:var(--ink); font-size:13.5px; }
  label.f { display:block; margin-bottom:10px; }
  label.f > span { display:block; color:var(--muted); font-size:12px; margin-bottom:5px; }
  .inline { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .logs { font:11.5px/1.5 ui-monospace, Consolas, monospace; color:#9aa3b5; background:#080b10; border-radius:10px; padding:10px; max-height:340px; overflow:auto; white-space:pre-wrap; word-break:break-all; }
  .deps { display:grid; gap:8px; }
  .dep { display:flex; gap:10px; align-items:flex-start; padding:9px 11px; border:1px solid var(--line); border-radius:10px; background:var(--card2); }
  .dep .st { width:18px; flex:0 0 18px; text-align:center; }
  .dep .txt { flex:1; min-width:0; }
  .dep .txt .d { color:var(--muted); font-size:12px; word-break:break-all; }
  .pairbox { max-width:420px; margin:8vh auto; }
  .pairbox .code { font:700 40px/1.2 ui-monospace, Consolas, monospace; letter-spacing:8px; text-align:center; color:#7ee0a8; margin:10px 0; }
  .toast { position:fixed; right:16px; bottom:16px; background:#1b2434; border:1px solid var(--line); border-radius:12px; padding:10px 14px; font-size:13px; opacity:0; transform:translateY(8px); transition:.18s; pointer-events:none; max-width:340px; }
  .toast.show { opacity:1; transform:none; }
  .hintline { color:var(--muted); font-size:12px; margin-top:8px; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo"></div>
    <div style="flex:1;min-width:200px">
      <h1 id="hName">MuMuLink 控制台</h1>
      <div class="sub" id="hSub">正在连接本机代理…</div>
    </div>
    <span class="pill" id="pDevice">设备 —</span>
    <span class="pill" id="pSession">会话 —</span>
    <span class="pill" id="pMode">普通模式</span>
  </header>

  <div class="tabs" id="tabs">
    <div class="tab active" data-tab="overview">概览</div>
    <div class="tab" data-tab="session">会话</div>
    <div class="tab" data-tab="settings">设置</div>
    <div class="tab" data-tab="diag">诊断</div>
  </div>

  <div id="tab-overview" class="tabpane"></div>
  <div id="tab-session" class="tabpane" style="display:none"></div>
  <div id="tab-settings" class="tabpane" style="display:none"></div>
  <div id="tab-diag" class="tabpane" style="display:none"></div>
</div>
<div class="toast" id="toast"></div>

<script>
const BOOT = ${bootJson};
const $ = (id) => document.getElementById(id);
const TOKEN_KEY = 'mumulink.token';

function toast(msg) {
  const t = $('toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('show'), 2600);
}
function token() { return localStorage.getItem(TOKEN_KEY) || ''; }
function hdrs(extra) {
  return Object.assign({ 'Content-Type': 'application/json' }, token() ? { 'X-MuMuLink-Token': token() } : {}, extra || {});
}
const IS_LOCAL = ['127.0.0.1', 'localhost', '::1', '[::1]'].indexOf(location.hostname) >= 0;

async function api(method, path, body) {
  const r = await fetch(path, { method, headers: hdrs(), body: body === undefined ? undefined : JSON.stringify(body) });
  let data = null; try { data = await r.json(); } catch (_) { data = null; }
  if (!r.ok) {
    const e = new Error((data && (data.hint || data.error || data.message)) || ('HTTP ' + r.status));
    e.status = r.status; e.data = data; throw e;
  }
  return data;
}
function fmtBytes(n) {
  if (!n) return '0 B';
  const u = ['B','KB','MB','GB']; let i = 0; let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toFixed(v < 10 ? 1 : 0) + ' ' + u[i];
}
function ts() { return new Date().toLocaleTimeString('zh-CN', { hour12: false }); }

// ---------------- 配对门 ----------------
function renderPairing(msg) {
  $('tab-overview').style.display = '';
  $('tabs').style.display = 'none';
  ['session','settings','diag'].forEach((t) => { $('tab-' + t).style.display = 'none'; });
  $('hName').textContent = BOOT.name + ' · 需要配对';
  $('hSub').textContent = IS_LOCAL ? '正在为本机自动配对…（通常无需手动输入）' : '在下方输入代理控制台显示的 6 位配对码';
  $('tab-overview').innerHTML = \`
    <div class="card pairbox">
      <h2>配对这台电脑</h2>
      <div class="hintline">配对码显示在代理窗口标题/日志里（“配对码：xxxxxx”），也在下面的概览卡片里；电脑本机打开控制台会自动配对。</div>
      <input id="pairIn" inputmode="numeric" maxlength="6" placeholder="6 位数字" style="text-align:center;font-size:22px;letter-spacing:6px">
      <div class="btns"><button class="primary" id="pairBtn" style="flex:1">配对并进入控制台</button></div>
      <div class="hintline" id="pairMsg">\${msg || ''}</div>
    </div>\`;
  if (IS_LOCAL) {
    // 本机打开控制台：直接向本机代理要一个 token，不打扰用户找配对码
    (async () => {
      try {
        const r = await fetch('/api/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
        const j = await r.json();
        if (!r.ok) throw new Error(j.hint || j.error || '自动配对失败');
        localStorage.setItem(TOKEN_KEY, j.token);
        location.reload();
      } catch (e) {
        $('pairMsg').textContent = '本机自动配对失败：' + e.message + '（可手输配对码）';
      }
    })();
  }
  $('pairBtn').onclick = async () => {
    const code = ($('pairIn').value || '').trim();
    if (!/^\\d{6}$/.test(code)) { $('pairMsg').textContent = '请输入 6 位数字配对码'; return; }
    try {
      const r = await fetch('/api/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }) });
      const j = await r.json();
      if (!r.ok) throw new Error(j.hint || j.error || '配对失败');
      localStorage.setItem(TOKEN_KEY, j.token);
      toast('配对成功');
      location.reload();
    } catch (e) { $('pairMsg').textContent = e.message; }
  };
}

// ---------------- 概览 ----------------
async function renderOverview() {
  const st = await api('GET', '/api/status');
  const s = st.session || {};
  const dev = st.device || {};
  const q = s.quality || {};
  $('pDevice').textContent = dev.connected ? ('设备 ' + (dev.model || '')) : '设备 离线';
  $('pDevice').className = 'pill ' + (dev.connected ? 'on' : '');
  $('pSession').textContent = s.running ? '串流中' : '空闲';
  $('pSession').className = 'pill ' + (s.running ? 'on' : '');
  $('pMode').textContent = (st.mode === 'game') ? '游戏模式' : '普通模式';
  $('pMode').className = 'pill ' + (st.mode === 'game' ? 'game' : '');
  $('hSub').textContent = \`\${st.name} · v\${st.agentVersion} · 运行 \${Math.floor((st.uptimeSec||0)/60)} 分钟 · Node \${st.node}\`;

  const addrs = (st.addresses || []).map((a) => \`
    <div class="addr"><div><div class="mono">\${a.address}</div><div class="hintline">\${a.name}</div></div>
      <button class="ghost" data-copy="\${a.address}">复制</button></div>\`).join('') || '<div class="hintline">没有可用的局域网 IPv4 地址</div>';

  $('tab-overview').innerHTML = \`
    <div class="grid two">
      <div class="card">
        <h2>连接地址（手机填写）</h2>
        \${addrs}
        <div class="row"><span class="k">HTTP</span><span class="v mono">: \${st.ports.http}</span></div>
        <div class="row"><span class="k">视频 / 音频 / 控制</span><span class="v mono">\${st.ports.video} / \${st.ports.audio} / \${st.ports.control}</span></div>
        <div class="row"><span class="k">发现 UDP</span><span class="v mono">\${st.ports.udp}</span></div>
        <div class="row"><span class="k">配对码</span><span class="v mono" style="font-size:18px;letter-spacing:3px">\${st.pairingCode || '******'}</span></div>
        <div class="btns">
          <button class="primary" id="btnApk">下载手机 App</button>
          <button id="btnRestartSim">重启模拟器</button>
        </div>
      </div>
      <div class="card">
        <h2>当前状态</h2>
        <div class="row"><span class="k">设备</span><span class="v">\${dev.connected ? \`\${dev.model || ''} Android \${dev.android || '?'} · \${dev.width}×\${dev.height} @\${dev.density}\` : '未连接'}</span></div>
        <div class="row"><span class="k">会话</span><span class="v">\${s.running ? '运行中' : '已停止'}</span></div>
        <div class="row"><span class="k">画质</span><span class="v">\${q.maxSize || '?'}p · \${q.maxFps || '?'}fps · \${q.bitRate ? (q.bitRate/1000000).toFixed(1)+' Mbps' : '?'}</span></div>
        <div class="row"><span class="k">显示适配</span><span class="v">\${(s.display && s.display.mode) === 'match' ? '匹配 DPI' : '适配屏幕'}</span></div>
        <div class="row"><span class="k">音频</span><span class="v">\${s.audioEnabled ? (s.audioConnected ? '<span class="ok">已连接</span>' : '<span class="warn">等待 App</span>') : '关闭'}</span></div>
        <div class="row"><span class="k">视频 / 控制</span><span class="v">\${s.videoConnected ? '<span class="ok">已连</span>' : '<span class="bad">未连</span>'} / \${s.controlConnected ? '<span class="ok">已连</span>' : '<span class="bad">未连</span>'}</span></div>
        <div class="row"><span class="k">音频已转发</span><span class="v mono">\${fmtBytes(s.audioBytes)}</span></div>
        <div class="row"><span class="k">最近错误</span><span class="v">\${s.lastError ? '<span class="bad">' + String(s.lastError).slice(0,80) + '</span>' : '无'}</span></div>
        <div class="btns"><button id="btnRefreshOv">刷新</button><button class="ghost" id="btnSignOut">退出配对</button></div>
      </div>
    </div>\`;
  $('btnRefreshOv').onclick = () => renderOverview().catch((e) => toast(e.message));
  $('btnApk').onclick = () => { location.href = '/apk'; };
  $('btnRestartSim').onclick = async () => {
    if (!confirm('重启 MuMu 模拟器？会断开当前串流，约需 15 秒。')) return;
    try { await api('POST', '/api/device/restart', {}); toast('已请求重启模拟器'); setTimeout(() => renderOverview().catch(()=>{}), 4000); }
    catch (e) { toast(e.message); }
  };
  $('btnSignOut').onclick = () => { localStorage.removeItem(TOKEN_KEY); location.reload(); };
  document.querySelectorAll('[data-copy]').forEach((b) => {
    b.onclick = () => { navigator.clipboard && navigator.clipboard.writeText(b.dataset.copy); toast('已复制 ' + b.dataset.copy); };
  });
}

// ---------------- 会话 ----------------
async function renderSession() {
  const st = await api('GET', '/api/status');
  const s = st.session || {};
  const q = s.quality || { maxSize: 1080, maxFps: 60, bitRate: 4000000 };
  $('tab-session').innerHTML = \`
    <div class="grid two">
      <div class="card">
        <h2>串流参数</h2>
        <label class="f"><span>分辨率上限</span>
          <select id="sMaxSize">\${[720,1080,1440,1920].map((v) => \`<option value="\${v}" \${q.maxSize===v?'selected':''}>≤\${v}</option>\`).join('')}</select>
        </label>
        <div class="inline">
          <label class="f"><span>帧率</span>
            <select id="sFps">\${[30,45,60,90,120].map((v) => \`<option value="\${v}" \${q.maxFps===v?'selected':''}>\${v}</option>\`).join('')}</select>
          </label>
          <label class="f"><span>码率 Mbps</span>
            <select id="sBitrate">\${[2,4,6,8,12].map((v) => \`<option value="\${v}" \${Math.round((q.bitRate||0)/1000000)===v?'selected':''}>\${v}</option>\`).join('')}</select>
          </label>
        </div>
        <label class="f"><span>显示适配</span>
          <select id="sDisplay">
            <option value="fit" \${(s.display&&s.display.mode)!=='match'?'selected':''}>适配屏幕（保留设备原分辨率）</option>
            <option value="match" \${(s.display&&s.display.mode)==='match'?'selected':''}>匹配 DPI（改为手机分辨率/密度）</option>
          </select>
        </label>
        <label class="f" style="display:flex;align-items:center;gap:9px"><input type="checkbox" id="sAudio" style="width:auto" \${s.audioEnabled?'checked':''}> <span style="margin:0">音频同步（48kHz 立体声）</span></label>
        <div class="btns">
          <button class="primary" id="btnStart">开始 / 重启会话</button>
          <button class="danger" id="btnStop">停止会话</button>
        </div>
      </div>
      <div class="card">
        <h2>游戏模式</h2>
        <div class="hintline">一键切到 1080p / 90fps / 12Mbps / 音频，并在手机端启用低延迟触控。</div>
        <div class="btns">
          <button class="primary" id="btnGameOn">开启游戏模式</button>
          <button id="btnGameOff">退出游戏模式</button>
        </div>
        <div class="hintline" style="margin-top:14px">说明：scrcpy 服务端不支持运行中改码率，任何参数变化都会重启会话（约 1.5 秒），手机端会自动重连。</div>
      </div>
    </div>\`;
  const params = () => ({
    quality: { maxSize: +$('sMaxSize').value, maxFps: +$('sFps').value, bitRate: +$('sBitrate').value * 1000000 },
    display: { mode: $('sDisplay').value },
    audio: $('sAudio').checked,
  });
  $('btnStart').onclick = async () => {
    try {
      const p = params();
      await api('POST', '/api/session/start', { quality: p.quality, display: p.display, audio: p.audio });
      toast('会话已启动'); setTimeout(() => renderSession().catch(()=>{}), 1200);
    } catch (e) { toast(e.message); }
  };
  $('btnStop').onclick = async () => {
    try { await api('POST', '/api/session/stop', {}); toast('会话已停止'); setTimeout(() => renderSession().catch(()=>{}), 800); }
    catch (e) { toast(e.message); }
  };
  $('btnGameOn').onclick = async () => {
    try { await api('POST', '/api/mode', { mode: 'game' }); toast('游戏模式已开启'); setTimeout(() => renderSession().catch(()=>{}), 1500); }
    catch (e) { toast(e.message); }
  };
  $('btnGameOff').onclick = async () => {
    try { await api('POST', '/api/mode', { mode: 'normal' }); toast('已退出游戏模式'); setTimeout(() => renderSession().catch(()=>{}), 800); }
    catch (e) { toast(e.message); }
  };
}

// ---------------- 设置 ----------------
async function renderSettings() {
  const r = await api('GET', '/api/settings');
  const s = r.settings;
  $('tab-settings').innerHTML = \`
    <div class="card">
      <h2>基础</h2>
      <div class="inline">
        <label class="f"><span>电脑名称</span><input id="fName" value="\${escapeAttr(s.name)}"></label>
        <label class="f"><span>配对码（6 位数字）</span><input id="fCode" value="\${escapeAttr(s.pairingCode)}" maxlength="12"></label>
      </div>
      <div class="inline">
        <label class="f"><span>adb 路径</span><input id="fAdb" value="\${escapeAttr(s.adb)}"></label>
        <label class="f"><span>设备（IP:端口）</span><input id="fDevice" value="\${escapeAttr(s.device)}"></label>
      </div>
      <label class="f"><span>MuMu 管理器路径（mumu-cli.exe / MuMuManager.exe）</span><input id="fMumu" value="\${escapeAttr(s.mumuManager)}"></label>
      <div class="inline">
        <label class="f"><span>HTTP 端口</span><input id="fHttp" type="number" value="\${s.httpPort}"></label>
        <label class="f"><span>UDP 发现端口</span><input id="fUdp" type="number" value="\${s.udpPort}"></label>
      </div>
      <div class="inline">
        <label class="f"><span>视频端口</span><input id="fVideo" type="number" value="\${s.videoPort}"></label>
        <label class="f"><span>音频端口</span><input id="fAudio" type="number" value="\${s.audioPort}"></label>
      </div>
      <div class="inline">
        <label class="f"><span>控制端口</span><input id="fControl" type="number" value="\${s.controlPort}"></label>
        <label class="f"><span>adb forward 端口</span><input id="fForward" type="number" value="\${s.adbForwardPort}"></label>
      </div>
      <label class="f" style="display:flex;align-items:center;gap:9px"><input type="checkbox" id="fAudioEnabled" style="width:auto" \${s.audioEnabled?'checked':''}> <span style="margin:0">允许音频转发</span></label>
      <div class="btns">
        <button class="primary" id="btnSave">保存设置</button>
        <button class="ghost" id="btnReload">重新读取</button>
      </div>
      <div class="hintline">端口、音频开关改动后需要重启代理（概览页的操作菜单或关闭窗口再打开）。</div>
    </div>\`;
  $('btnReload').onclick = () => renderSettings().catch((e) => toast(e.message));
  $('btnSave').onclick = async () => {
    try {
      const body = {
        name: $('fName').value.trim(), pairingCode: $('fCode').value.trim(),
        adb: $('fAdb').value.trim(), device: $('fDevice').value.trim(), mumuManager: $('fMumu').value.trim(),
        httpPort: +$('fHttp').value, udpPort: +$('fUdp').value, videoPort: +$('fVideo').value,
        audioPort: +$('fAudio').value, controlPort: +$('fControl').value, adbForwardPort: +$('fForward').value,
        audioEnabled: $('fAudioEnabled').checked,
      };
      const res = await api('POST', '/api/settings', body);
      toast(res.needsRestart ? '已保存，重启代理后端口生效' : '已保存');
    } catch (e) { toast(e.message); }
  };
}
function escapeAttr(s) { return escapeHtml(s == null ? '' : s); }

// ---------------- 诊断 ----------------
async function renderDiag() {
  const [deps, diag] = await Promise.all([api('GET', '/api/deps'), api('GET', '/api/diagnostics')]);
  const items = (deps.items || []).map((it) => \`
    <div class="dep"><div class="st \${it.ok ? 'ok' : 'bad'}">\${it.ok ? '✓' : '✗'}</div>
      <div class="txt"><div>\${escapeHtml(it.label)}</div><div class="d">\${escapeHtml(it.detail || '')}</div></div></div>\`).join('');
  $('tab-diag').innerHTML = \`
    <div class="grid two">
      <div class="card">
        <h2>依赖检查</h2>
        <div class="deps">\${items}</div>
        <div class="btns"><button id="btnDeps">重新检查</button></div>
      </div>
      <div class="card">
        <h2>诊断</h2>
        <div class="row"><span class="k">最近错误</span><span class="v">\${diag.lastError ? '<span class="bad">'+escapeHtml(String(diag.lastError).slice(0,120))+'</span>' : '无'}</span></div>
        <div class="row"><span class="k">scid</span><span class="v mono">\${escapeHtml(diag.scid || '—')}</span></div>
        <div class="row"><span class="k">显示</span><span class="v mono">\${escapeHtml((diag.display||'').replace(/\\n/g,' · '))}</span></div>
        <div class="row"><span class="k">adb forward</span><span class="v mono">\${escapeHtml((diag.forwards||[]).join(' | ') || '—')}</span></div>
        <h2 style="margin-top:16px">最近日志（200 行）</h2>
        <div class="logs" id="logBox">\${escapeHtml((diag.logs||[]).join('\\n'))}</div>
        <div class="btns"><button id="btnDiag">刷新诊断</button><button class="ghost" id="btnCopyLog">复制日志</button></div>
      </div>
    </div>\`;
  $('btnDeps').onclick = () => renderDiag().catch((e) => toast(e.message));
  $('btnDiag').onclick = () => renderDiag().catch((e) => toast(e.message));
  $('btnCopyLog').onclick = () => { const t = $('logBox').textContent; navigator.clipboard && navigator.clipboard.writeText(t); toast('日志已复制'); };
  const box = $('logBox'); if (box) box.scrollTop = box.scrollHeight;
}

// ---------------- 启动 ----------------
const RENDER = { overview: renderOverview, session: renderSession, settings: renderSettings, diag: renderDiag };
async function showTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  ['overview','session','settings','diag'].forEach((n) => { $('tab-' + n).style.display = (n === name) ? '' : 'none'; });
  try { await RENDER[name](); } catch (e) {
    if (e.status === 401) { renderPairing('配对已失效，请重新输入配对码'); return; }
    toast(e.message);
  }
}
document.querySelectorAll('.tab').forEach((t) => { t.onclick = () => showTab(t.dataset.tab); });

(async () => {
  if (!token()) { renderPairing(''); return; }
  try {
    await api('GET', '/api/status');
  } catch (e) {
    if (e.status === 401) { renderPairing('配对码已变更或凭证失效'); return; }
  }
  showTab('overview');
  setInterval(() => {
    if ($('tab-overview').style.display !== 'none' && token()) renderOverview().catch(() => {});
  }, 4000);
})();
</script>
</body>
</html>`;
}

module.exports = { renderPage };
