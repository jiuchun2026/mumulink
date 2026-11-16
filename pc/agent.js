'use strict';

const http = require('http');
const net = require('net');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

const { Logger } = require('./lib/logger');
const { loadConfig, findScrcpyJar, saveConfig, randomToken, AGENT_VERSION, DEFAULT_CONFIG_PATH } = require('./lib/config');
const { Adb, AgentError, ERR, fail } = require('./lib/adb');
const { DisplayManager } = require('./lib/display');
const { SessionManager } = require('./lib/scrcpy');
const { Discovery } = require('./lib/discovery');
const { renderPage } = require('./lib/page');

const REPO_ROOT = path.resolve(__dirname, '..');

function parseArgs(argv) {
  const out = { configPath: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--config' && argv[i + 1]) { out.configPath = argv[++i]; }
    else if (a.startsWith('--config=')) { out.configPath = a.slice('--config='.length); }
  }
  return out;
}

const logger = new Logger();
const args = parseArgs(process.argv.slice(2));
let config;
let configPath;
try {
  const loaded = loadConfig({ configPath: args.configPath || DEFAULT_CONFIG_PATH });
  config = loaded.config;
  configPath = loaded.configPath;
  if (loaded.created) logger.info(`首次运行，已生成配置文件：${configPath}`);
} catch (e) {
  console.error(`[FATAL] 配置加载失败：${e.message}`);
  process.exit(2);
}

const localJar = findScrcpyJar(config, REPO_ROOT);
const adb = new Adb({ adbPath: config.adb, device: config.device, logger });
const display = new DisplayManager({ adb, logger });

function tokenMatches(t) {
  if (typeof t !== 'string') return false;
  if (!config.token) return t === '';
  const a = Buffer.from(t);
  const b = Buffer.from(config.token);
  if (a.length !== b.length) return false;
  try { return crypto.timingSafeEqual(a, b); } catch (_) { return false; }
}

const session = new SessionManager({
  adb, display, logger, config,
  verifyToken: tokenMatches,
  localJar
});

const discovery = new Discovery({ config, logger, agentVersion: AGENT_VERSION });
discovery.setPaired(!!config.token);

let httpServer = null;
let videoServer = null;
let controlServer = null;
const preHandshakeSockets = new Set();
let shuttingDown = false;
let restarting = false;
let deviceCache = null;
let deviceCacheAt = 0;

function getDeviceStatus(force) {
  const now = Date.now();
  if (!force && deviceCache && now - deviceCacheAt < 3000) return deviceCache;
  let value;
  try {
    const dev = adb.ensureDevice();
    const d = adb.getDisplay();
    value = {
      model: dev.model,
      android: dev.android,
      width: d.width,
      height: d.height,
      density: d.density,
      connected: true
    };
  } catch (e) {
    value = {
      model: '未连接',
      android: '未知',
      width: 0,
      height: 0,
      density: 0,
      connected: false,
      error: e.message
    };
  }
  const changed = !deviceCache || deviceCache.connected !== value.connected || deviceCache.model !== value.model ||
    deviceCache.width !== value.width || deviceCache.height !== value.height;
  if (changed) {
    if (value.connected) logger.info(`设备状态：${value.model} Android ${value.android} ${value.width}x${value.height} @${value.density}dpi`);
    else logger.warn(`设备状态：未连接（${value.error}）`);
  }
  deviceCache = value;
  deviceCacheAt = Date.now();
  return value;
}

function invalidateDeviceCache() { deviceCache = null; deviceCacheAt = 0; }

// ---------- HTTP ----------

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

function sendError(res, err) {
  let status = 500;
  let code = 'INTERNAL';
  let message = '代理内部错误';
  let hint = '';
  if (err instanceof AgentError) {
    status = err.status || 500;
    code = err.code;
    message = err.message;
    hint = err.hint || '';
  } else if (err && err.code && ERR[err.code]) {
    status = ERR[err.code];
    code = err.code;
    message = err.message;
    hint = err.hint || '';
  } else if (err instanceof Error) {
    message = `代理内部错误：${err.message}`;
    logger.error(`HTTP 500：${err.stack || err.message}`);
  }
  const body = { ok: false, error: code, message };
  if (hint) body.hint = hint;
  sendJson(res, status, body);
}

function readBody(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new AgentError('BAD_REQUEST', '请求体过大（上限 1MB）', 400));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', (e) => reject(new AgentError('BAD_REQUEST', `读取请求失败：${e.message}`, 400)));
  });
}

async function readJson(req) {
  const text = await readBody(req);
  if (!text.trim()) return {};
  try {
    const obj = JSON.parse(text);
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
      throw new AgentError('BAD_REQUEST', '请求体必须是 JSON 对象', 400);
    }
    return obj;
  } catch (e) {
    if (e instanceof AgentError) throw e;
    throw new AgentError('BAD_REQUEST', `请求体不是合法 JSON：${e.message}`, 400);
  }
}

function reqToken(req) {
  const x = req.headers['x-mumulink-token'];
  if (typeof x === 'string' && x.trim()) return x.trim();
  const auth = req.headers['authorization'];
  if (typeof auth === 'string') {
    const m = auth.match(/^Bearer\s+(.+)$/i);
    if (m) return m[1].trim();
  }
  return '';
}

function requireAuth(req) {
  if (!config.token) {
    if (!config.requirePairing) return;
    fail('NOT_PAIRED', '代理尚未配对，请先打开配对页输入配对码');
  }
  if (!tokenMatches(reqToken(req))) {
    fail('BAD_TOKEN', '配对令牌无效，请重新配对');
  }
}

const pairAttempts = { count: 0, firstAt: 0 };

async function handlePair(req, res) {
  const body = await readJson(req);
  const code = String(body.code == null ? '' : body.code).trim();
  const now = Date.now();
  if (now - pairAttempts.firstAt > 60000) { pairAttempts.count = 0; pairAttempts.firstAt = now; }
  if (pairAttempts.count >= 5) {
    fail('TOO_MANY_ATTEMPTS', '配对尝试过于频繁，请 1 分钟后再试');
  }
  const expected = String(config.pairingCode);
  const ok = code.length === expected.length && crypto.timingSafeEqual(Buffer.from(code), Buffer.from(expected));
  if (!ok) {
    pairAttempts.count++;
    logger.warn(`配对失败：配对码错误（${req.socket.remoteAddress}）`);
    fail('BAD_CODE', '配对码错误');
  }
  pairAttempts.count = 0;
  let newToken = false;
  if (!config.token) {
    config.token = randomToken();
    saveConfig(configPath, config);
    newToken = true;
    discovery.setPaired(true);
    logger.info(`配对成功：已生成并保存 token（${config.token.slice(0, 8)}…）到 ${configPath}`);
  } else {
    logger.info(`配对成功：返回已有 token（${req.socket.remoteAddress}）`);
  }
  sendJson(res, 200, { ok: true, token: config.token, newToken });
}

async function handleAgentRestart(req, res) {
  sendJson(res, 202, { ok: true, message: '代理正在重启' });
  scheduleAgentRestart();
}

async function handleDeviceRestart(req, res) {
  await session.stop('重启 MuMu 实例').catch(() => {});
  const exe = findMuMuManager();
  if (!exe) {
    // 没有 MuMu 管理器时退回 adb reboot：同样能清掉被控端残留的输入指针
    try {
      await adb.exec(['-s', config.deviceSerial, 'reboot']);
      invalidateDeviceCache();
      logger.info('未找到 MuMu 管理器，已用 adb reboot 重启被控端');
      sendJson(res, 200, { ok: true, message: '已发出 adb reboot（重新连接约需 30–60 秒）' });
      return;
    } catch (e) {
      fail('INTERNAL', `未找到 MuMuManager/mumu-cli，且 adb reboot 失败：${e.message}`);
      return;
    }
  }
  logger.info(`重启 MuMu 实例 0：${exe} control -v 0 restart`);
  try {
    const p = spawn(exe, ['control', '-v', '0', 'restart'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    const collect = (c) => { out += c.toString('utf8'); if (out.length > 4000) out = out.slice(-4000); };
    p.stdout.on('data', collect);
    p.stderr.on('data', collect);
    p.on('exit', (code) => logger.info(`MuMuManager 重启命令退出（code=${code}）：${out.trim().slice(-500) || '无输出'}`));
    p.on('error', (e) => logger.error(`MuMuManager 启动失败：${e.message}`));
  } catch (e) {
    fail('INTERNAL', `启动 MuMuManager 失败：${e.message}`);
  }
  invalidateDeviceCache();
  sendJson(res, 200, { ok: true, message: '已发出重启 MuMu 实例 0 的命令' });
}

function findMuMuManager() {
  const candidates = [
    config.mumuManager,
    'C:/Program Files/Netease/MuMuPlayer/nx_main/mumu-cli.exe',
    'C:/Program Files/Netease/MuMuPlayer/nx_main/MuMuManager.exe',
    'C:/Program Files/Netease/MuMuPlayer-12.0/shell/MuMuManager.exe',
    'C:/Program Files/Netease/MuMuPlayerGlobal-12.0/shell/MuMuManager.exe'
  ].filter(Boolean);
  for (const c of candidates) {
    try { if (fs.statSync(c).isFile()) return c; } catch (_) { /* ignore */ }
  }
  return null;
}

async function route(req, res) {
  const u = new URL(req.url, 'http://localhost');
  const pathname = u.pathname.replace(/\/+$/, '') || '/';
  const method = req.method || 'GET';

  if (pathname === '/' && method === 'GET') {
    const html = renderPage(config, { agentVersion: AGENT_VERSION });
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': Buffer.byteLength(html),
      'Cache-Control': 'no-store'
    });
    res.end(html);
    return;
  }

  // 手机浏览器下载安装包（跨网段也能更新 App）
  if (pathname === '/apk' && method === 'GET') {
    // 依次向上找 dist/*.apk 或 MuMuLink*.apk（兼容「仓库里运行」与「解压后的代理包」两种布局）
    const distApks = [];
    let dir = __dirname;
    for (let i = 0; i < 5; i++) {
      const direct = (() => {
        try {
          return fs.readdirSync(dir)
            .filter((f) => /^MuMuLink.*\.apk$/i.test(f))
            .map((f) => path.join(dir, f));
        } catch (e) { return []; }
      })();
      const nested = (() => {
        try {
          return fs.readdirSync(path.join(dir, 'dist'))
            .filter((f) => f.toLowerCase().endsWith('.apk'))
            .map((f) => path.join(dir, 'dist', f));
        } catch (e) { return []; }
      })();
      distApks.push(...direct, ...nested);
      const nxt = path.dirname(dir);
      if (nxt === dir) break;
      dir = nxt;
    }
    distApks.sort((a, b) => { try { return fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs; } catch (e) { return 0; } });
    const candidates = [
      ...distApks,
      path.resolve(__dirname, '..', 'MuMuLink-1.0.1.apk'),
      path.resolve(REPO_ROOT, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    ];
    const apkPath = candidates.find((p) => { try { return fs.existsSync(p) && fs.statSync(p).size > 1e6; } catch (e) { return false; } });
    if (!apkPath) { sendJson(res, 404, { ok: false, error: 'APK_NOT_FOUND', message: '安装包未放在 dist/MuMuLink-1.0.0.apk' }); return; }
    const st = fs.statSync(apkPath);
    logger.info(`下载安装包：${apkPath}（${st.size} B）`);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': st.size,
      'Content-Disposition': `attachment; filename="${path.basename(apkPath)}"`,
      'Cache-Control': 'no-store'
    });
    fs.createReadStream(apkPath).pipe(res);
    return;
  }

  if (pathname === '/api/hello' && method === 'GET') {
    const device = getDeviceStatus();
    const s = session.getPublicInfo();
    sendJson(res, 200, {
      ok: true,
      magic: 'MUMULINK',
      v: 1,
      name: config.name,
      agentVersion: AGENT_VERSION,
      paired: !!config.token,
      device,
      session: {
        running: s.running,
        quality: s.quality,
        display: s.display,
        videoConnected: s.videoConnected,
        controlConnected: s.controlConnected,
        lastError: s.lastError
      }
    });
    return;
  }

  if (pathname === '/api/pair' && method === 'POST') {
    requireMethod(method, 'POST');
    await handlePair(req, res);
    return;
  }

  if (pathname === '/api/logs' && method === 'GET') {
    requireAuth(req);
    let since = Number(u.searchParams.get('since') || 0);
    if (!Number.isFinite(since) || since < 0) since = 0;
    const lines = logger.since(since).slice(-1000);
    sendJson(res, 200, { ok: true, seq: logger.seq, lines });
    return;
  }

  if (pathname === '/api/session/start' && method === 'POST') {
    requireAuth(req);
    const body = await readJson(req);
    const r = await session.start({ quality: body.quality, display: body.display });
    invalidateDeviceCache();
    // 刷新为应用 wm 之后的生效值，避免 App 启动后立刻 hello 命中旧缓存
    let effective = null;
    try { effective = getDeviceStatus(true); } catch (_) { /* ignore */ }
    sendJson(res, 200, {
      ok: true,
      deviceW: effective ? effective.width : r.deviceW,
      deviceH: effective ? effective.height : r.deviceH,
      videoPort: r.videoPort,
      controlPort: r.controlPort,
      quality: r.quality,
      display: r.display
    });
    return;
  }

  if (pathname === '/api/session/stop' && method === 'POST') {
    requireAuth(req);
    await readJson(req).catch(() => ({}));
    await session.stop('App 请求停止');
    invalidateDeviceCache();
    sendJson(res, 200, { ok: true });
    return;
  }

  if (pathname === '/api/session/quality' && method === 'POST') {
    requireAuth(req);
    const body = await readJson(req);
    await session.setQuality(body);
    invalidateDeviceCache();
    sendJson(res, 200, { ok: true });
    return;
  }

  if (pathname === '/api/display' && method === 'POST') {
    requireAuth(req);
    const body = await readJson(req);
    logger.info(`display API 请求：${JSON.stringify(body)}`);
    if (!body || !body.mode) fail('BAD_REQUEST', '缺少 display.mode（fit 或 match）');
    const applied = display.apply(body);
    invalidateDeviceCache();
    try { getDeviceStatus(true); } catch (_) { /* ignore */ }
    sendJson(res, 200, {
      ok: true,
      applied: { width: applied.width, height: applied.height, density: applied.density }
    });
    return;
  }

  if (pathname === '/api/display/reset' && method === 'POST') {
    requireAuth(req);
    await readJson(req).catch(() => ({}));
    const applied = display.apply({ mode: 'fit' });
    invalidateDeviceCache();
    try { getDeviceStatus(true); } catch (_) { /* ignore */ }
    sendJson(res, 200, { ok: true, applied: { width: applied.width, height: applied.height, density: applied.density } });
    return;
  }

  if (pathname === '/api/text' && method === 'POST') {
    requireAuth(req);
    const body = await readJson(req);
    const text = body.text == null ? '' : String(body.text);
    // 非 ASCII 走 SET_CLIPBOARD + PASTE：设备端 KeyCharacterMap 会丢弃 CJK，INJECT_TEXT 不可用。
    // 纯 ASCII 保留原 INJECT_TEXT 路径，作为回归基线。
    const needsClipboard = /[^\x00-\x7F]/.test(text);
    const r = needsClipboard
      ? session.sendClipboard(text, { paste: body.paste !== false })
      : session.sendText(text);
    sendJson(res, 200, {
      ok: true,
      mode: needsClipboard ? 'clipboard' : 'inject_text',
      bytes: r.bytes,
      sequence: r.sequence,
      truncated: !!r.truncated
    });
    return;
  }

  if (pathname === '/api/agent/restart' && method === 'POST') {
    requireAuth(req);
    await readJson(req).catch(() => ({}));
    await handleAgentRestart(req, res);
    return;
  }

  if (pathname === '/api/device/restart' && method === 'POST') {
    requireAuth(req);
    await readJson(req).catch(() => ({}));
    await handleDeviceRestart(req, res);
    return;
  }

  if (pathname.startsWith('/api/')) {
    sendJson(res, 404, { ok: false, error: 'NOT_FOUND', message: `接口不存在：${pathname}` });
    return;
  }
  sendJson(res, 404, { ok: false, error: 'NOT_FOUND', message: '页面不存在' });
}

function requireMethod() { /* 路由已按 method 匹配，保留占位 */ }

function scheduleAgentRestart() {
  if (restarting) return;
  restarting = true;
  setTimeout(async () => {
    logger.info('收到重启代理请求，准备 re-exec 自身…');
    try { await session.stop('重启代理'); } catch (e) { logger.warn(`停止会话失败：${e.message}`); }
    discovery.stop();
    closeServers();
    try { display.forceRestoreSync(); } catch (_) { /* ignore */ }
    let child;
    try {
      child = spawn(process.execPath, process.argv.slice(1), {
        detached: true,
        stdio: 'ignore',
        windowsHide: true,
        cwd: process.cwd(),
        env: Object.assign({}, process.env, { MUMULINK_RESTART_CHILD: '1' })
      });
      child.unref();
      logger.info(`新代理进程已启动（pid=${child.pid}），旧进程退出`);
    } catch (e) {
      logger.error(`re-exec 失败：${e.message}`);
      process.exitCode = 1;
      setTimeout(() => process.exit(1), 100);
      return;
    }
    setTimeout(() => process.exit(0), 100);
  }, 120);
}

function closeServers() {
  for (const s of preHandshakeSockets) { try { s.destroy(); } catch (_) { /* ignore */ } }
  preHandshakeSockets.clear();
  for (const srv of [videoServer, controlServer]) {
    if (srv) { try { srv.close(); } catch (_) { /* ignore */ } }
    if (srv && srv.closeAllConnections) { try { srv.closeAllConnections(); } catch (_) { /* ignore */ } }
  }
  videoServer = null;
  controlServer = null;
  if (httpServer) {
    try { httpServer.close(); } catch (_) { /* ignore */ }
    if (httpServer.closeAllConnections) { try { httpServer.closeAllConnections(); } catch (_) { /* ignore */ } }
    httpServer = null;
  }
}

async function shutdown(sig) {
  if (shuttingDown) return;
  shuttingDown = true;
  logger.info(`收到 ${sig}，开始优雅退出…`);
  const force = setTimeout(() => {
    logger.warn('优雅退出超时，强制退出');
    process.exit(1);
  }, 8000);
  force.unref && force.unref();
  try {
    await session.stop(`代理退出（${sig}）`);
  } catch (e) {
    logger.warn(`退出时停止会话失败：${e.message}`);
  }
  discovery.stop();
  closeServers();
  try { display.forceRestoreSync(); } catch (_) { /* ignore */ }
  logger.info('代理已退出');
  process.exit(0);
}

// ---------- 启动 ----------

async function main() {
  process.title = `MuMuLink Agent :${config.httpPort}`;
  if (process.platform === 'win32') {
    try { spawnSync('cmd', ['/c', 'title MuMuLink Agent :' + config.httpPort], { windowsHide: true, timeout: 1000 }); } catch (_) { /* ignore */ }
  }
  logger.info('==============================================');
  logger.info(`MuMuLink PC Agent v${AGENT_VERSION}（node ${process.version}，${process.platform}）`);
  logger.info(`配置：${configPath}（${config.name}）`);
  logger.info(`端口：HTTP ${config.httpPort} / UDP ${config.udpPort} / video ${config.videoPort} / control ${config.controlPort}`);
  logger.info(`adb：${config.adb}`);
  logger.info(`设备：${config.device}，jar：${localJar || '未找到'}`);
  logger.info(`scrcpy 设备端 jar：${config.deviceJarPath}`);
  const adbVer = adb.raw(['version'], { timeout: 8000, log: false });
  if (adbVer.ok) logger.info(`adb 版本：${adbVer.stdout.split(/\r?\n/)[0] || '未知'}`);
  else logger.warn(`adb 不可用：${(adbVer.stderr || adbVer.error && adbVer.error.message || 'unknown').trim()}`);

  httpServer = http.createServer((req, res) => {
    if (shuttingDown) {      sendJson(res, 503, { ok: false, error: 'SHUTTING_DOWN', message: '代理正在退出' });
      return;
    }
    route(req, res).catch((err) => {
      try { sendError(res, err); } catch (_) { /* ignore */ }
    });
  });
  httpServer.keepAliveTimeout = 65000;
  httpServer.headersTimeout = 66000;
  httpServer.on('clientError', (err, socket) => {
    try { socket.end('HTTP/1.1 400 Bad Request\r\n\r\n'); } catch (_) { /* ignore */ }
  });
  httpServer.on('error', (e) => {
    if (e.code === 'EADDRINUSE') {
      if (process.env.MUMULINK_RESTART_CHILD === '1') return; // bind() 负责重试
      console.error(`[FATAL] HTTP 端口 ${config.httpPort} 已被占用。请关闭占用程序（可能是另一个代理实例），或修改 config.json 的 httpPort 后重启。`);
      process.exit(1);
    }
    console.error(`[FATAL] HTTP 服务错误：${e.message}`);
    process.exit(1);
  });

  videoServer = net.createServer((socket) => {
    socket.setNoDelay(true);
    socket.on('error', (e) => logger.warn(`视频连接 socket 错误：${e.message}`));
    preHandshakeSockets.add(socket);
    socket.once('close', () => preHandshakeSockets.delete(socket));
    session.handleVideoConnection(socket).catch((e) => {
      logger.warn(`视频连接处理异常：${e.message}`);
      try { socket.destroy(); } catch (_) { /* ignore */ }
    });
  });
  videoServer.on('error', (e) => {
    if (e.code === 'EADDRINUSE') {
      if (process.env.MUMULINK_RESTART_CHILD === '1') return; // bind() 负责重试
      console.error(`[FATAL] 视频端口 ${config.videoPort} 已被占用。请修改 config.json 的 videoPort 后重启。`);
      process.exit(1);
    }
    console.error(`[FATAL] 视频服务错误：${e.message}`);
    process.exit(1);
  });

  controlServer = net.createServer((socket) => {
    socket.setNoDelay(true);
    socket.on('error', (e) => logger.warn(`控制连接 socket 错误：${e.message}`));
    preHandshakeSockets.add(socket);
    socket.once('close', () => preHandshakeSockets.delete(socket));
    session.handleControlConnection(socket).catch((e) => {
      logger.warn(`控制连接处理异常：${e.message}`);
      try { socket.destroy(); } catch (_) { /* ignore */ }
    });
  });
  controlServer.on('error', (e) => {
    if (e.code === 'EADDRINUSE') {
      if (process.env.MUMULINK_RESTART_CHILD === '1') return; // bind() 负责重试
      console.error(`[FATAL] 控制端口 ${config.controlPort} 已被占用。请修改 config.json 的 controlPort 后重启。`);
      process.exit(1);
    }
    console.error(`[FATAL] 控制服务错误：${e.message}`);
    process.exit(1);
  });

  // 重启子进程：父进程退出与端口释放存在窗口，延迟并重试 bind，避免整体消失
  const delay = (ms) => new Promise((r) => setTimeout(r, ms));
  async function bind(server, port, label) {
    const restartChild = process.env.MUMULINK_RESTART_CHILD === '1';
    if (restartChild) await delay(1200);
    for (let attempt = 0; ; attempt++) {
      try {
        await new Promise((resolve, reject) => {
          const onErr = (e) => { server.removeListener('listening', onOk); reject(e); };
          const onOk = () => { server.removeListener('error', onErr); resolve(); };
          server.once('error', onErr);
          server.once('listening', onOk);
          server.listen(port, '0.0.0.0');
        });
        return;
      } catch (e) {
        if (e.code === 'EADDRINUSE' && restartChild && attempt < 10) {
          logger.warn(`${label} 端口 ${port} 仍被占用，400ms 后重试（${attempt + 1}/10）`);
          await delay(400);
          continue;
        }
        throw e;
      }
    }
  }
  await bind(httpServer, config.httpPort, 'HTTP');
  await bind(videoServer, config.videoPort, '视频');
  await bind(controlServer, config.controlPort, '控制');

  logger.info(`HTTP 已监听 0.0.0.0:${config.httpPort}，TCP 视频 ${config.videoPort} / 控制 ${config.controlPort}`);
  discovery.start();

  setTimeout(() => {
    try { getDeviceStatus(true); } catch (_) { /* ignore */ }
  }, 200);
}

const exitHook = () => {
  try { display.forceRestoreSync(); } catch (_) { /* ignore */ }
};
process.on('exit', exitHook);
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
if (process.platform === 'win32') process.on('SIGBREAK', () => shutdown('SIGBREAK'));
process.on('uncaughtException', (e) => {
  logger.error(`未捕获异常：${e.stack || e.message}`);
  session.stop('未捕获异常').catch(() => {}).finally(() => {
    try { display.forceRestoreSync(); } catch (_) { /* ignore */ }
    process.exit(1);
  });
});
process.on('unhandledRejection', (e) => {
  logger.error(`未处理的 Promise 拒绝：${e && e.stack ? e.stack : e}`);
});

main().catch((e) => {
  console.error(`[FATAL] 启动失败：${e.stack || e.message}`);
  process.exit(1);
});
