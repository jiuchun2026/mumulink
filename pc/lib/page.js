'use strict';

// 极简配对页：显示配对码、IP、端口、设备状态，可配对/重启
function renderPage(config, opts = {}) {
  const boot = {
    name: config.name,
    httpPort: config.httpPort,
    videoPort: config.videoPort,
    controlPort: config.controlPort,
    udpPort: config.udpPort,
    pairingCode: config.pairingCode,
    agentVersion: opts.agentVersion || '1.0.0'
  };
  const bootJson = JSON.stringify(boot).replace(/</g, '\\u003c');
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MuMuLink 配对 - ${escapeHtml(config.name)}</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 18px; background: #0f1115; color: #e8eaf0; font: 15px/1.55 -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
  .wrap { max-width: 560px; margin: 0 auto; }
  h1 { font-size: 20px; margin: 4px 0 2px; }
  .sub { color: #8b93a7; font-size: 13px; margin-bottom: 16px; }
  .card { background: #171b22; border: 1px solid #242a35; border-radius: 14px; padding: 16px; margin-bottom: 14px; }
  .row { display: flex; justify-content: space-between; gap: 12px; padding: 5px 0; }
  .row span:first-child { color: #8b93a7; flex: 0 0 auto; }
  .row span:last-child { text-align: right; word-break: break-all; }
  .code { font: 700 34px/1.2 ui-monospace, Consolas, monospace; letter-spacing: 6px; text-align: center; color: #7ee0a8; margin: 8px 0 4px; }
  .hint { color: #8b93a7; font-size: 12px; text-align: center; }
  .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; background: #e25757; }
  .dot.on { background: #58c98a; }
  button { appearance: none; border: 1px solid #2f3644; background: #232a36; color: #e8eaf0; border-radius: 10px; padding: 11px 14px; font-size: 14px; width: 100%; margin-top: 9px; }
  button:active { background: #303848; }
  button.primary { background: #2d6cdf; border-color: #2d6cdf; }
  button.danger { background: #5a2226; border-color: #7a2d33; }
  input { width: 100%; padding: 12px; font-size: 20px; letter-spacing: 4px; text-align: center; background: #0f1115; border: 1px solid #2f3644; color: #e8eaf0; border-radius: 10px; margin: 8px 0; }
  .msg { min-height: 20px; font-size: 13px; color: #e9b45f; margin-top: 8px; }
  .logs { font: 11.5px/1.5 ui-monospace, Consolas, monospace; color: #9aa3b5; background: #0b0d11; border-radius: 10px; padding: 10px; max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
  .ok { color: #58c98a; } .bad { color: #e25757; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; }
  .grid button { margin-top: 0; }
  .foot { text-align: center; color: #5c6474; font-size: 12px; margin: 18px 0 8px; }
  a { color: #6ea8ff; }
</style>
</head>
<body>
<div class="wrap">
  <h1 id="name">MuMuLink 代理</h1>
  <div class="sub">手机与电脑在同一 Wi-Fi 后，在 App 中输入配对码完成配对</div>

  <div class="card">
    <div class="row"><span>配对码</span><span class="hint">6 位数字</span></div>
    <div class="code" id="pairCode">------</div>
    <div class="hint">在手机 App「添加电脑」里输入此配对码</div>
  </div>

  <div class="card" id="pairCard" style="display:none">
    <div>本机尚未配对，请输入上方配对码：</div>
    <input id="codeInput" inputmode="numeric" maxlength="6" placeholder="000000" autocomplete="one-time-code">
    <button class="primary" id="pairBtn">配对</button>
    <div class="msg" id="pairMsg"></div>
  </div>

  <div class="card">
    <div class="row"><span>电脑</span><span id="host">-</span></div>
    <div class="row"><span>HTTP 端口</span><span id="httpPort">-</span></div>
    <div class="row"><span>视频 / 控制端口</span><span id="ports">-</span></div>
    <div class="row"><span>设备</span><span id="device">-</span></div>
    <div class="row"><span>会话</span><span id="session">-</span></div>
    <div class="row"><span>配对状态</span><span id="paired">-</span></div>
  </div>

  <div class="card" id="sessionCard" style="display:none">
    <div class="grid">
      <button id="restartScrcpy">重启 scrcpy</button>
      <button id="stopSession">停止会话</button>
      <button id="resetDisplay">恢复显示</button>
      <button id="refresh">刷新状态</button>
    </div>
    <button id="rebootDevice" style="margin-top:9px">重启模拟器（触控失灵时用）</button>
    <button class="primary" id="restartAgent" style="margin-top:9px">重启代理</button>
    <div class="msg" id="msg"></div>
  </div>

  <div class="card">
    <div class="row"><span>最近日志</span><span class="hint"><a href="#" id="refreshLogs">刷新</a></span></div>
    <div class="logs" id="logs">（需要配对后查看）</div>
  </div>

  <div class="foot">MuMuLink PC Agent v${escapeHtml(boot.agentVersion)} · 局域网直连，无需云服务</div>
</div>
<script>
const BOOT = ${bootJson};
const TOKEN_KEY = 'mumulink.token';
const $ = (id) => document.getElementById(id);
let token = localStorage.getItem(TOKEN_KEY) || '';
let lastHello = null;

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function setText(id, text) { const el = $(id); if (el) el.textContent = text; }
function api(path, opts) {
  opts = opts || {};
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  if (token) headers['Authorization'] = 'Bearer ' + token;
  return fetch(path, Object.assign({}, opts, { headers })).then(async (r) => {
    let body = null;
    try { body = await r.json(); } catch (e) { body = null; }
    if (!r.ok) {
      const err = new Error((body && body.message) || ('HTTP ' + r.status));
      err.code = body && body.error; err.status = r.status; err.body = body;
      throw err;
    }
    return body;
  });
}
function showMsg(text, ok) {
  const el = $('msg'); if (!el) return;
  el.textContent = text || '';
  el.style.color = ok ? '#58c98a' : '#e9b45f';
}

async function refreshHello() {
  try {
    const h = await api('/api/hello');
    lastHello = h;
    setText('name', h.name || BOOT.name);
    setText('host', location.hostname);
    setText('httpPort', BOOT.httpPort);
    setText('ports', BOOT.videoPort + ' / ' + BOOT.controlPort);
    const d = h.device || {};
    setText('device', d.connected ? (d.model + ' · ' + d.width + 'x' + d.height + ' @' + d.density + 'dpi') : '未连接');
    $('device').className = d.connected ? 'ok' : 'bad';
    const s = h.session || {};
    setText('session', s.running ? ('运行中 · ' + (s.quality ? s.quality.maxSize + '@' + s.quality.maxFps + ' / ' + Math.round(s.quality.bitRate/1000) + 'kbps' : '')) : '未运行');
    $('session').className = s.running ? 'ok' : '';
    setText('paired', h.paired ? '已配对' : '未配对');
    $('paired').className = h.paired ? 'ok' : 'bad';
    $('pairCard').style.display = (h.paired && token) ? 'none' : 'block';
    $('sessionCard').style.display = token ? 'block' : 'none';
    setText('pairCode', BOOT.pairingCode || '------');
  } catch (e) {
    setText('device', '代理不可达：' + e.message);
    $('device').className = 'bad';
  }
}

async function loadLogs() {
  if (!token) return;
  try {
    const r = await api('/api/logs?since=0');
    const lines = (r.lines || []).slice(-80);
    $('logs').textContent = lines.length ? lines.join('\\n') : '（暂无日志）';
    $('logs').scrollTop = $('logs').scrollHeight;
  } catch (e) {
    $('logs').textContent = '读取日志失败：' + e.message;
  }
}

async function doPair() {
  const code = ($('codeInput').value || '').trim();
  setText('pairMsg', '正在配对…');
  try {
    const r = await api('/api/pair', { method: 'POST', body: JSON.stringify({ code }) });
    token = r.token;
    localStorage.setItem(TOKEN_KEY, token);
    setText('pairMsg', '配对成功');
    await refreshHello();
    await loadLogs();
  } catch (e) {
    setText('pairMsg', '配对失败：' + e.message);
  }
}

$('pairBtn').onclick = doPair;
$('codeInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') doPair(); });
$('refresh').onclick = () => { refreshHello(); loadLogs(); };
$('refreshLogs').onclick = (e) => { e.preventDefault(); loadLogs(); };
$('restartScrcpy').onclick = async () => {
  if (!lastHello || !lastHello.session) return;
  const q = lastHello.session.quality || { maxSize: 1080, maxFps: 60, bitRate: 4000000 };
  const disp = lastHello.session.display || { mode: 'fit' };
  showMsg('正在重启 scrcpy…');
  try {
    await api('/api/session/start', { method: 'POST', body: JSON.stringify({ quality: q, display: disp }) });
    showMsg('scrcpy 已重启，请在 App 中重连', true);
  } catch (e) { showMsg('重启失败：' + e.message); }
  refreshHello();
};
$('rebootDevice').onclick = async () => {
  if (!confirm('重启被控端模拟器？正在运行的应用会被关闭。')) return;
  showMsg('正在重启模拟器…');
  try {
    const r = await api('/api/device/restart', { method: 'POST', body: '{}' });
    showMsg((r && r.message) || '已发出重启命令，约 30–60 秒后可重连', true);
  } catch (e) { showMsg('重启失败：' + e.message); }
};
$('stopSession').onclick = async () => {
  showMsg('正在停止…');
  try { await api('/api/session/stop', { method: 'POST', body: '{}' }); showMsg('会话已停止', true); }
  catch (e) { showMsg('停止失败：' + e.message); }
  refreshHello();
};
$('resetDisplay').onclick = async () => {
  showMsg('正在恢复显示…');
  try { await api('/api/display/reset', { method: 'POST', body: '{}' }); showMsg('显示已恢复', true); }
  catch (e) { showMsg('恢复失败：' + e.message); }
  refreshHello();
};
$('restartAgent').onclick = async () => {
  if (!confirm('确定重启代理？正在运行的会话会中断。')) return;
  try {
    await api('/api/agent/restart', { method: 'POST', body: '{}' });
    showMsg('代理正在重启，稍后自动刷新…');
    setTimeout(() => { refreshHello(); loadLogs(); }, 3500);
  } catch (e) { showMsg('重启失败：' + e.message); }
};

refreshHello();
setInterval(refreshHello, 3000);
setInterval(() => { if (token) loadLogs(); }, 8000);
</script>
</body>
</html>`;
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

module.exports = { renderPage };
