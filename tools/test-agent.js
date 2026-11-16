#!/usr/bin/env node
'use strict';
/*
 * MuMuLink PC 代理端到端自测（零依赖，只用 Node 内置模块）
 *
 * 覆盖：
 *   HTTP：hello / pair / token 鉴权 / logs / 配对页 / text / agent restart / device restart 鉴权
 *   TCP ：LAN 地址走代理、无 token 与错误 token 拒绝、视频握手、帧解析、控制握手、INJECT_TOUCH 点击
 *   会话：start/stop、quality 重启、并发第二视频连接拒绝、客户端断线清理、adb forward 隔离
 *   显示：MATCH/FIT 下 wm size/density 生效与恢复、运行中改 wm 不崩
 *   UDP ：MUMULINK_DISCOVER 单播应答
 *
 * 用法：node tools/test-agent.js [--port-base 27380]（默认 27380 起，避开联调生产端口 27180-27183）
 * 退出码：0 全过；1 有失败；2 环境/启动失败。
 */

const http = require('http');
const net = require('net');
const dgram = require('dgram');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn, spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const AGENT_JS = path.join(ROOT, 'pc', 'agent.js');
const ADB = process.env.MUMULINK_ADB || 'adb.exe';
const DEVICE = process.env.MUMULINK_DEVICE || '127.0.0.1:16384';
const PAIR_CODE = '246810';
const BAD_CODE = '000000';

const PORTS = { http: 27180, udp: 27181, video: 27182, control: 27183, adbForward: 28182 };
let PORT_BASE = 27380;
for (let i = 0; i < process.argv.length; i++) {
  if (process.argv[i] === '--port-base' && process.argv[i + 1]) PORT_BASE = Number(process.argv[i + 1]) || 27380;
}

let pass = 0;
let fail = 0;
const failures = [];
let currentSection = '准备';

function section(name) {
  currentSection = name;
  console.log(`\n=== ${name} ===`);
}

function check(name, cond, detail = '') {
  const label = `[${currentSection}] ${name}`;
  if (cond) {
    pass++;
    console.log(`PASS  ${label}${detail ? '  ' + detail : ''}`);
    return true;
  }
  fail++;
  failures.push(label + (detail ? ' :: ' + detail : ''));
  console.log(`FAIL  ${label}${detail ? '  ' + detail : ''}`);
  return false;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sleepSync = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

function adb(args, opts = {}) {
  const encoding = opts.binary ? null : 'utf8';
  const r = spawnSync(ADB, ['-s', DEVICE].concat(args), {
    encoding,
    timeout: opts.timeout || 25000,
    maxBuffer: 96 * 1024 * 1024,
    windowsHide: true
  });
  return {
    ok: !r.error && r.status === 0,
    status: r.status,
    stdout: r.stdout,
    stderr: r.stderr ? r.stderr.toString() : '',
    error: r.error
  };
}

function adbShell(cmd, opts = {}) {
  const r = adb(['shell', cmd], opts);
  if (!r.ok) throw new Error(`adb shell 失败：${cmd} :: ${(r.stderr || '').trim() || (r.stdout || '').trim()}`);
  return r.stdout;
}

function parseWm() {
  const out = adbShell('wm size; wm density');
  const out2 = String(out);
  const physSize = out2.match(/Physical size:\s*(\d+)x(\d+)/);
  const overSize = out2.match(/Override size:\s*(\d+)x(\d+)/);
  const physDpi = out2.match(/Physical density:\s*(\d+)/);
  const overDpi = out2.match(/Override density:\s*(\d+)/);
  if (!physSize) throw new Error(`无法解析 wm size：${out2}`);
  return {
    physical: { width: +physSize[1], height: +physSize[2] },
    override: overSize ? { width: +overSize[1], height: +overSize[2] } : null,
    physicalDensity: physDpi ? +physDpi[1] : null,
    overrideDensity: overDpi ? +overDpi[1] : null,
    raw: out2
  };
}

function restoreDisplay(baseline) {
  try {
    adbShell('wm size reset; wm density reset');
  } catch (_) { /* ignore */ }
  if (baseline && baseline.override) {
    try {
      adbShell(`wm size ${baseline.override.width}x${baseline.override.height}`);
      if (baseline.overrideDensity) adbShell(`wm density ${baseline.overrideDensity}`);
    } catch (_) { /* ignore */ }
  }
}

function request(method, pathname, { token, body, timeout = 15000 } = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), 'utf8');
    const headers = {};
    if (payload) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
      headers['Content-Length'] = payload.length;
    }
    if (token) headers['X-MuMuLink-Token'] = token;
    const req = http.request(
      { host: '127.0.0.1', port: PORTS.http, path: pathname, method, headers, timeout, agent: false },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try { json = JSON.parse(text); } catch (_) { /* non-json */ }
          resolve({ status: res.statusCode, headers: res.headers, text, json });
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error(`HTTP 超时：${method} ${pathname}`)));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function getLanIp() {
  const nets = os.networkInterfaces();
  const skip = /^(lo|docker|vEthernet|VMware|VirtualBox|Loopback)/i;
  const candidates = [];
  for (const [name, list] of Object.entries(nets)) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      if (/^169\.254\./.test(a.address)) continue;
      if (skip.test(name)) continue;
      candidates.push({ name, address: a.address });
    }
  }
  if (!candidates.length) return '127.0.0.1';
  const pref = candidates.find((c) => /^10\./.test(c.address)) || candidates.find((c) => /^192\.168\./.test(c.address)) || candidates[0];
  return pref.address;
}

// 从 socket 读取一行（成功后 socket 处于 paused，调用方自行处理 rest 并 resume）
function readLine(sock, timeout = 8000) {
  return new Promise((resolve, reject) => {
    let buf = Buffer.alloc(0);
    const timer = setTimeout(() => { cleanup(); reject(new Error('读取握手行超时')); }, timeout);
    const cleanup = () => {
      clearTimeout(timer);
      sock.removeListener('data', onData);
      sock.removeListener('error', onError);
      sock.removeListener('close', onClose);
    };
    const onData = (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const i = buf.indexOf(10);
      if (i >= 0) {
        cleanup();
        sock.pause();
        const line = buf.slice(0, i).toString('utf8').replace(/\r$/, '');
        resolve({ line, rest: buf.slice(i + 1) });
      }
    };
    const onError = (e) => { cleanup(); reject(e); };
    const onClose = () => { cleanup(); reject(new Error('socket 在握手完成前关闭')); };
    sock.on('data', onData);
    sock.on('error', onError);
    sock.on('close', onClose);
  });
}

async function connectTcp(host, port, timeout = 8000) {
  const sock = net.connect({ host, port });
  sock.setNoDelay(true);
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => { sock.destroy(); reject(new Error(`连接 ${host}:${port} 超时`)); }, timeout);
    sock.once('connect', () => { clearTimeout(t); sock.removeListener('error', onErr); resolve(); });
    const onErr = (e) => { clearTimeout(t); reject(e); };
    sock.once('error', onErr);
  });
  return sock;
}

async function tcpHandshake(host, port, tokenLine) {
  const sock = await connectTcp(host, port);
  sock.write(tokenLine);
  const hs = await readLine(sock);
  return { sock, line: hs.line, rest: hs.rest };
}

class VideoParser {
  constructor(initial) {
    this.buf = initial && initial.length ? Buffer.from(initial) : Buffer.alloc(0);
    this.state = 'codec';
    this.codec = null;
    this.meta = null;
    this.metas = [];
    this.frames = 0;
    this.keys = 0;
    this.configs = 0;
    this.sessions = 0;
    this.bytes = 0;
    this.startedAt = 0;
    this.lastFrameAt = 0;
  }
  feed(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    this.drain();
  }
  drain() {
    for (;;) {
      if (this.state === 'codec') {
        if (this.buf.length < 4) return;
        this.codec = this.buf.readUInt32BE(0).toString(16);
        this.startedAt = Date.now();
        this.buf = this.buf.slice(4);
        this.state = 'meta';
        continue;
      }
      if (this.state === 'meta') {
        if (this.buf.length < 12) return;
        this.meta = this.readMeta(this.buf);
        this.metas.push(this.meta);
        this.buf = this.buf.slice(12);
        this.state = 'frames';
        continue;
      }
      if (this.buf.length < 4) return;
      const first = this.buf.readUInt32BE(0);
      if (first === 0x80000000 || first === 0x80000001) {
        if (this.buf.length < 12) return;
        this.meta = this.readMeta(this.buf);
        this.metas.push(this.meta);
        this.sessions++;
        this.buf = this.buf.slice(12);
        continue;
      }
      if (this.buf.length < 12) return;
      const pts = this.buf.readBigUInt64BE(0);
      const size = this.buf.readUInt32BE(8);
      if (this.buf.length < 12 + size) return;
      this.buf = this.buf.slice(12 + size);
      this.frames++;
      this.bytes += size;
      this.lastFrameAt = Date.now();
      if (pts & (1n << 62n)) this.configs++;
      if (pts & (1n << 61n)) this.keys++;
    }
  }
  readMeta(buf) {
    return { flags: buf.readUInt32BE(0), width: buf.readInt32BE(4), height: buf.readInt32BE(8) };
  }
  snapshot() {
    const sec = this.startedAt ? Math.max(0.001, (Date.now() - this.startedAt) / 1000) : 0;
    return {
      codec: this.codec,
      meta: this.meta,
      metas: this.metas.slice(),
      frames: this.frames,
      keys: this.keys,
      configs: this.configs,
      sessions: this.sessions,
      sec,
      fps: sec ? +(this.frames / sec).toFixed(1) : 0,
      kbps: sec ? Math.round((this.bytes * 8) / sec / 1000) : 0
    };
  }
}

const openSockets = [];
function track(sock) { openSockets.push(sock); return sock; }

async function openVideo(host, token) {
  const v = await tcpHandshake(host, PORTS.video, `MUMULINK1 ${token}\n`);
  track(v.sock);
  return v;
}

async function openControl(host, token) {
  const c = await tcpHandshake(host, PORTS.control, `MUMULINK1 ${token}\n`);
  track(c.sock);
  c.sock.resume();
  return c;
}

async function startStream(host, token, ms = 3000) {
  const v = await openVideo(host, token);
  if (v.line !== 'MUMULINK1 OK') throw new Error(`视频握手失败：${v.line}`);
  const c = await openControl(host, token);
  if (c.line !== 'MUMULINK1 OK') throw new Error(`控制握手失败：${c.line}`);
  const parser = new VideoParser(v.rest);
  v.sock.on('data', (chunk) => parser.feed(chunk));
  v.sock.resume();
  c.sock.resume();
  await sleep(ms);
  return { video: v.sock, control: c.sock, parser, snap: () => parser.snapshot() };
}

function closeStream(st) {
  if (!st) return;
  for (const s of [st.video, st.control]) {
    if (s) { try { s.destroy(); } catch (_) { /* ignore */ } }
  }
}

function touchMessage(action, x, y, w, h) {
  const b = Buffer.alloc(32);
  let o = 0;
  b.writeUInt8(2, o); o += 1;                 // TYPE_INJECT_TOUCH_EVENT
  b.writeUInt8(action, o); o += 1;            // 0=DOWN 1=UP
  b.writeBigInt64BE(-2n, o); o += 8;          // POINTER_ID_GENERIC_FINGER
  b.writeInt32BE(Math.round(x), o); o += 4;
  b.writeInt32BE(Math.round(y), o); o += 4;
  b.writeUInt16BE(w & 0xffff, o); o += 2;
  b.writeUInt16BE(h & 0xffff, o); o += 2;
  b.writeUInt16BE(0xffff, o); o += 2;         // pressure = 1.0
  b.writeInt32BE(0, o); o += 4;               // actionButton
  b.writeInt32BE(action === 0 ? 1 : 0, o); o += 4; // buttons
  return b;
}

async function sendTap(control, x, y, w, h) {
  control.write(touchMessage(0, x, y, w, h));
  await sleep(90);
  control.write(touchMessage(1, x, y, w, h));
}

function screencapRaw() {
  const r = adb(['exec-out', 'screencap'], { binary: true, timeout: 30000 });
  if (!r.ok || !r.stdout || r.stdout.length < 12) throw new Error('screencap 失败');
  const buf = r.stdout;
  const width = buf.readUInt32LE(0);
  const height = buf.readUInt32LE(4);
  const pixels = width * height * 4;
  if (buf.length < 12 + pixels) throw new Error(`screencap 数据不完整：${buf.length} < ${12 + pixels}`);
  const header = buf.length - pixels; // 12 或 16
  return { width, height, header, data: buf.subarray(header) };
}

function diffScreens(a, b) {
  if (a.width !== b.width || a.height !== b.height) return 1;
  const { width: w, height: h } = a;
  const y0 = Math.min(80, h - 1);
  const y1 = Math.max(y0 + 1, h - 80);
  let samples = 0;
  let changed = 0;
  for (let y = y0; y < y1; y += 5) {
    for (let x = 0; x < w; x += 5) {
      const i = (y * w + x) * 4;
      const d = Math.abs(a.data[i] - b.data[i]) + Math.abs(a.data[i + 1] - b.data[i + 1]) + Math.abs(a.data[i + 2] - b.data[i + 2]);
      samples++;
      if (d > 30) changed++;
    }
  }
  return changed / Math.max(1, samples);
}

function dumpUi() {
  adbShell('input keyevent KEYCODE_WAKEUP');
  try { adbShell('wm dismiss-keyguard'); } catch (_) { /* ignore */ }
  // 先 force-stop，保证每次进入 Settings 根页面（否则会 resume 上次的子页面）
  try { adbShell('am force-stop com.android.settings'); } catch (_) { /* ignore */ }
  adbShell('am start -a android.settings.SETTINGS');
  sleepSync(1500);
  // Android 15 上 uiautomator dump 常以 139 退出但文件已写好，必须忽略退出码
  try { adbShell('rm -f /sdcard/mumulink-ui.xml'); } catch (_) { /* ignore */ }
  adb(['shell', 'uiautomator dump /sdcard/mumulink-ui.xml'], { timeout: 30000 });
  const xml = adbShell('cat /sdcard/mumulink-ui.xml');
  if (!/^\s*<\?xml/.test(xml)) throw new Error('uiautomator dump 未生成有效 XML：' + xml.slice(0, 120));
  return xml;
}

function decodeXml(s) {
  return s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

function parseUi(xml) {
  const nodes = [];
  const re = /<node[^>]*>/g;
  let m;
  let uiW = 0;
  let uiH = 0;
  let rotation = 0;
  const rm = xml.match(/<hierarchy[^>]*rotation="(\d)"/);
  if (rm) rotation = Number(rm[1]);
  while ((m = re.exec(xml))) {
    const tag = m[0];
    const text = decodeXml((tag.match(/text="([^"]*)"/) || [])[1] || '');
    const desc = decodeXml((tag.match(/content-desc="([^"]*)"/) || [])[1] || '');
    const bounds = (tag.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || []).slice(1).map(Number);
    if (bounds.length === 4) {
      uiW = Math.max(uiW, bounds[2]);
      uiH = Math.max(uiH, bounds[3]);
      nodes.push({
        text, desc,
        clickable: /clickable="true"/.test(tag),
        checkable: /checkable="true"/.test(tag),
        cls: (tag.match(/class="([^"]*)"/) || [])[1] || '',
        pkg: (tag.match(/package="([^"]*)"/) || [])[1] || '',
        bounds
      });
    }
  }
  return { nodes, uiW, uiH, rotation };
}

function pickSettingsTarget(ui) {
  const inScreen = (n) => n.bounds[2] > 0 && n.bounds[3] > 0 &&
    n.bounds[0] >= 0 && n.bounds[1] >= 0 && n.bounds[2] <= ui.uiW && n.bounds[3] <= ui.uiH;
  // 优先可见的开关/可点击行；保证节点完整落在当前旋转的屏幕坐标内，避免点 off-screen 节点
  const visible = ui.nodes.filter((n) => inScreen(n) && n.bounds[2] - n.bounds[0] > 8 && n.bounds[3] - n.bounds[1] > 8);
  // 优先顶部/中部的可点击行，避免底部被导航栏/分屏边界裁掉（VPN 行在最底部，实测点击无效）
  const wanted = ['在设置中搜索', '搜索设置', 'Search settings', '互联网', 'WLAN', '网络和互联网', '电池', 'Battery', '省流模式', '关于手机', 'About phone', 'VPN'];
  for (const want of wanted) {
    const n = visible.find((x) => x.text === want || x.desc === want);
    if (n) return { node: n, label: want };
  }
  const toggle = visible.find((n) => n.checkable && n.clickable);
  if (toggle) return { node: toggle, label: toggle.text || toggle.desc || '开关' };
  const clickable = visible.find((n) => n.clickable && (n.text || n.desc));
  if (clickable) return { node: clickable, label: clickable.text || clickable.desc };
  const withText = visible.find((n) => n.text);
  return withText ? { node: withText, label: withText.text } : null;
}

async function waitMetaForUi(stream, uiW, uiH, timeout = 3000) {
  const target = uiW / uiH;
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    const m = stream.snap().meta;
    if (m && m.width > 0 && m.height > 0) {
      last = m;
      if (Math.abs((m.width / m.height) - target) < 0.06) return m;
    }
    await sleep(200);
  }
  return last;
}

function findListenerPid(port) {
  const r = spawnSync('netstat', ['-ano'], { encoding: 'utf8', timeout: 15000, windowsHide: true });
  const lines = (r.stdout || '').split(/\r?\n/);
  for (const line of lines) {
    if (!new RegExp(`:${port}\\s+`).test(line)) continue;
    const m = line.match(/LISTENING\s+(\d+)\s*$/);
    if (m) return Number(m[1]);
  }
  return null;
}

function killPid(pid) {
  if (!pid) return;
  try { spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { encoding: 'utf8', timeout: 15000, windowsHide: true }); } catch (_) { /* ignore */ }
}

async function waitHello(timeout = 20000) {
  const deadline = Date.now() + timeout;
  let lastErr = null;
  while (Date.now() < deadline) {
    try {
      const r = await request('GET', '/api/hello', { timeout: 3000 });
      if (r.status === 200 && r.json && r.json.ok) return r.json;
    } catch (e) { lastErr = e; }
    await sleep(250);
  }
  throw new Error(`等待代理存活超时（${timeout}ms）${lastErr ? '：' + lastErr.message : ''}`);
}

function tcpPortFree(port) {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.once('error', () => resolve(false));
    s.listen(port, '0.0.0.0', () => s.close(() => resolve(true)));
  });
}

function udpPortFree(port) {
  return new Promise((resolve) => {
    const s = dgram.createSocket('udp4');
    s.once('error', () => resolve(false));
    s.bind(port, '0.0.0.0', () => s.close(() => resolve(true)));
  });
}

function portHasListener(port) {
  try {
    const r = spawnSync('netstat', ['-ano'], { encoding: 'utf8', timeout: 15000, windowsHide: true });
    return (r.stdout || '').split(/\r?\n/).some((line) => new RegExp(`:${port}\\s+`).test(line) && /LISTENING/i.test(line));
  } catch (_) { return false; }
}

async function pickPorts() {
  const bases = [PORT_BASE];
  for (let b = 28100; b <= 34100; b += 100) bases.push(b);
  for (const base of bases) {
    const p = { http: base, udp: base + 1, video: base + 2, control: base + 3, adbForward: base + 1002 };
    if (p.http > 64000) continue;
    // Windows 的 SO_REUSEADDR 可能让 tcpPortFree 在已监听时仍成功；先查 netstat 排除端口串扰
    if ([p.http, p.udp, p.video, p.control, p.adbForward].some(portHasListener)) continue;
    const ok = (await tcpPortFree(p.http)) && (await udpPortFree(p.udp)) &&
      (await tcpPortFree(p.video)) && (await tcpPortFree(p.control)) && (await tcpPortFree(p.adbForward));
    if (ok) { Object.assign(PORTS, p); return; }
  }
  throw new Error(`找不到可用测试端口（从 ${PORT_BASE} 起）`);
}

async function waitAgentPid(pid, timeout = 20000) {
  const deadline = Date.now() + timeout;
  let last = '';
  while (Date.now() < deadline) {
    const listener = findListenerPid(PORTS.http);
    if (listener === pid) {
      try {
        const r = await request('GET', '/api/hello', { timeout: 2000 });
        if (r.status === 200 && r.json && r.json.ok && r.json.name === 'E2E测试机') return r.json;
        last = `hello status=${r.status} name=${r.json && r.json.name}`;
      } catch (e) { last = e.message; }
    } else {
      last = `监听 PID=${listener}，期望 ${pid}`;
    }
    await sleep(250);
  }
  throw new Error(`新代理未就绪（${last}）`);
}

async function main() {
  section('0. 环境与临时配置');
  const t0 = Date.now();
  await pickPorts();
  console.log(`测试端口：HTTP ${PORTS.http} / UDP ${PORTS.udp} / video ${PORTS.video} / control ${PORTS.control} / forward ${PORTS.adbForward}`);
  const baseline = parseWm();
  check('设备可达且读取到物理分辨率', baseline.physical.width > 0 && baseline.physical.height > 0,
    `${baseline.physical.width}x${baseline.physical.height} @${baseline.physicalDensity}dpi`);

  const lanIp = getLanIp();
  check('找到 LAN IPv4（不能是 127.0.0.1）', lanIp !== '127.0.0.1', lanIp);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mumulink-e2e-'));
  const configPath = path.join(tmpDir, 'config.json');
  const cfg = {
    name: 'E2E测试机',
    httpPort: PORTS.http,
    udpPort: PORTS.udp,
    videoPort: PORTS.video,
    controlPort: PORTS.control,
    adbForwardPort: PORTS.adbForward,
    adb: ADB,
    device: DEVICE,
    scrcpyJar: path.join(ROOT, 'tools', 'scrcpy-server.jar'),
    scrcpyJarMd5: '55a1ef0a811f1cb0d417084b70e0a630',
    deviceJarPath: '/sdcard/mumulink/scrcpy.bin',
    mumuManager: 'C:/Program Files/Netease/MuMuPlayer/nx_main/MuMuManager.exe',
    pairingCode: PAIR_CODE,
    token: '',
    requirePairing: true
  };
  fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2), 'utf8');

  // 清掉可能残留的同端口 forward（只动测试端口）
  spawnSync(ADB, ['-s', DEVICE, 'forward', '--remove', `tcp:${PORTS.adbForward}`], { encoding: 'utf8' });
  restoreDisplay(baseline);

  let agent = spawn(process.execPath, [AGENT_JS, '--config', configPath], {
    cwd: ROOT,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe']
  });
  const agentLog = [];
  agent.stdout.on('data', (d) => agentLog.push(d.toString()));
  agent.stderr.on('data', (d) => agentLog.push(d.toString()));
  let agentAlive = true;
  agent.on('exit', () => { agentAlive = false; });

  let token = '';
  let stream = null;
  let restartedPid = null;

  try {
    const hello0 = await waitAgentPid(agent.pid);
    check('代理启动并响应 /api/hello', !!hello0.ok && hello0.magic === 'MUMULINK' && hello0.v === 1, `name=${hello0.name}`);
    check('初始未配对、设备已连接', hello0.paired === false && hello0.device && hello0.device.connected === true,
      `${hello0.device && hello0.device.model} Android ${hello0.device && hello0.device.android}`);

    section('1. HTTP 鉴权与配对');
    const noTok = await request('GET', '/api/logs');
    check('无 token 访问受保护接口被拒', noTok.status === 401 && noTok.json && ['NOT_PAIRED', 'BAD_TOKEN'].includes(noTok.json.error),
      `status=${noTok.status} error=${noTok.json && noTok.json.error}`);
    const wrongTok0 = await request('GET', '/api/logs', { token: 'f'.repeat(64) });
    check('未配对时错误 token 也被拒', wrongTok0.status === 401 && wrongTok0.json && ['NOT_PAIRED', 'BAD_TOKEN'].includes(wrongTok0.json.error),
      `status=${wrongTok0.status}`);

    const badPair = await request('POST', '/api/pair', { body: { code: BAD_CODE } });
    check('错误配对码返回 401 BAD_CODE', badPair.status === 401 && badPair.json && badPair.json.error === 'BAD_CODE',
      badPair.json && badPair.json.message);
    const goodPair = await request('POST', '/api/pair', { body: { code: PAIR_CODE } });
    token = goodPair.json && goodPair.json.token;
    check('正确配对码返回 32 字节 token', goodPair.status === 200 && !!token && /^[0-9a-f]{64}$/.test(token),
      token ? token.slice(0, 8) + '…' : 'no token');

    const logs = await request('GET', '/api/logs?since=0', { token });
    check('带 token 可读日志', logs.status === 200 && logs.json.ok && Array.isArray(logs.json.lines) && logs.json.lines.length > 0,
      `${logs.json && logs.json.lines && logs.json.lines.length} 行`);
    const wrongTok = await request('GET', '/api/logs', { token: 'f'.repeat(64) });
    check('配对后错误 token 被拒（BAD_TOKEN）', wrongTok.status === 401 && wrongTok.json && wrongTok.json.error === 'BAD_TOKEN',
      wrongTok.json && wrongTok.json.message);
    const page = await request('GET', '/', { token });
    check('配对页可访问且包含配对码/移动端 viewport',
      page.status === 200 && /MuMuLink/.test(page.text) && page.text.includes(PAIR_CODE) && /viewport/.test(page.text));

    section('2. TCP 鉴权（未启动会话）');
    const noTokTcp = await tcpHandshake(lanIp, PORTS.control, 'MUMULINK1 \n');
    check('无 token 的 TCP 握手返回 ERR BAD_TOKEN', noTokTcp.line === 'MUMULINK1 ERR BAD_TOKEN', noTokTcp.line);
    noTokTcp.sock.destroy();
    const badTokTcp = await tcpHandshake(lanIp, PORTS.control, `MUMULINK1 ${'a'.repeat(64)}\n`);
    check('错误 token 的 TCP 握手返回 ERR BAD_TOKEN', badTokTcp.line === 'MUMULINK1 ERR BAD_TOKEN', badTokTcp.line);
    badTokTcp.sock.destroy();

    section('3. 会话启动（BALANCED + FIT）');
    const st = await request('POST', '/api/session/start', {
      token,
      body: { quality: { maxSize: 1080, maxFps: 60, bitRate: 4000000 }, display: { mode: 'fit' } }
    });
    check('session/start 成功', st.status === 200 && st.json.ok, JSON.stringify(st.json && st.json.quality));
    check('返回 LAN video/control 端口', st.json.videoPort === PORTS.video && st.json.controlPort === PORTS.control);
    check('FIT 下设备尺寸等于物理尺寸',
      st.json.deviceW === baseline.physical.width && st.json.deviceH === baseline.physical.height,
      `${st.json.deviceW}x${st.json.deviceH}`);
    const wmAfterStart = parseWm();
    check('FIT 启动后 wm 无 Override', !wmAfterStart.override && !wmAfterStart.overrideDensity, wmAfterStart.raw.replace(/\n/g, ' | '));

    section('4. LAN 视频/控制握手与帧解析（证明走代理）');
    stream = await startStream(lanIp, token, 3000);
    const snap = stream.snap();
    check('视频握手 MUMULINK1 OK 且解析到 codec', stream.video && snap.codec === '68323634', `codec=${snap.codec}`);
    check('解析到 session meta', !!snap.meta, snap.meta ? `${snap.meta.width}x${snap.meta.height}` : 'null');
    check('3 秒内至少 1 个 KEY 帧和 3 个 P 帧', snap.keys >= 1 && snap.frames >= 3,
      `frames=${snap.frames} keys=${snap.keys} configs=${snap.configs} fps=${snap.fps} kbps=${snap.kbps}`);

    section('5. 并发第二个视频连接应被拒绝');
    const second = await tcpHandshake(lanIp, PORTS.video, `MUMULINK1 ${token}\n`);
    check('第二个视频连接返回 ERR（SESSION_BUSY）', /^MUMULINK1 ERR (SESSION_BUSY|VIDEO_NOT_READY|ALREADY_CONNECTED)$/.test(second.line), second.line);
    second.sock.destroy();
    await sleep(300);
    const stillSnap = stream.snap();
    const firstAlive = stream.video && !stream.video.destroyed && stream.video.readyState === 'open';
    check('拒绝第二连接后首个视频流仍存活', firstAlive,
      `socket=${stream.video && stream.video.readyState} frames=${snap.frames}→${stillSnap.frames}（静态屏幕可能无新帧）`);

    section('6. INJECT_TOUCH 点击 + 截图差异');
    let tapOk = false;
    let tapDetail = '';
    try {
      // 共享设备上方向可能被别的自动化随时切换，单次映射容易踩到旋转切换窗口期：最多重试 3 次
      for (let attempt = 1; attempt <= 3 && !tapOk; attempt++) {
        const xml = dumpUi();
        const ui = parseUi(xml);
        const picked = pickSettingsTarget(ui);
        if (!picked) { tapDetail = `attempt=${attempt} 设置页未找到可点目标`; continue; }
        const meta = await waitMetaForUi(stream, ui.uiW, ui.uiH, 2500);
        if (!meta) { tapDetail = `attempt=${attempt} 未取得视频 session meta`; continue; }
        try {
          const uiDir = path.join(__dirname, '..', 'pc', 'test-tmp');
          fs.mkdirSync(uiDir, { recursive: true });
          fs.writeFileSync(path.join(uiDir, 'last-ui.xml'), xml, 'utf8');
        } catch (_) { /* ignore */ }
        const [x1, y1, x2, y2] = picked.node.bounds;
        const cx = (x1 + x2) / 2;
        const cy = (y1 + y2) / 2;
        // uiautomator bounds 是「当前旋转后的整块屏幕」绝对坐标（分屏时节点只在某个 pane 内），
        // 按整屏尺寸归一化；旋转以 dump 里的 hierarchy rotation 为准。
        const cur = parseWm();
        const wmSize = cur.override || cur.physical;
        const landscape = ui.rotation === 1 || ui.rotation === 3;
        const dispW = landscape ? wmSize.height : wmSize.width;
        const dispH = landscape ? wmSize.width : wmSize.height;
        // dump 之后设备若被别人旋转，meta 与 UI 方向会不一致，直接丢弃本次
        if (Math.abs((meta.width / meta.height) - (dispW / dispH)) > 0.08) {
          tapDetail = `attempt=${attempt} 方向不一致（rot=${ui.rotation} disp=${dispW}x${dispH} meta=${meta.width}x${meta.height}）`;
          continue;
        }
        let vx = Math.round((cx / dispW) * meta.width);
        let vy = Math.round((cy / dispH) * meta.height);
        vx = Math.max(0, Math.min(meta.width - 1, vx));
        vy = Math.max(0, Math.min(meta.height - 1, vy));
        const before = screencapRaw();
        await sendTap(stream.control, vx, vy, meta.width, meta.height);
        await sleep(1300);
        const after = screencapRaw();
        const ratio = diffScreens(before, after);
        tapOk = ratio > 0.005;
        tapDetail = `attempt=${attempt} target=${picked.label} ui(${Math.round(cx)},${Math.round(cy)}) rot=${ui.rotation} disp=${dispW}x${dispH} meta=${meta.width}x${meta.height} video(${vx},${vy}) 变化像素=${(ratio * 100).toFixed(2)}%`;
        try { adbShell('input keyevent KEYCODE_BACK'); } catch (_) { /* ignore */ }
        await sleep(400);
      }
      check('INJECT_TOUCH 点击导致界面变化 >0.5%', tapOk, tapDetail);
    } catch (e) {
      check('INJECT_TOUCH 点击导致界面变化 >0.5%', false, e.message);
    }

    section('7. /api/text 注入（控制通道已连）');
    const txt = await request('POST', '/api/text', { token, body: { text: 'MuMuLink E2E' } });
    check('控制通道已连时 /api/text 返回 ok', txt.status === 200 && txt.json.ok, JSON.stringify(txt.json));

    section('8. 画质切换后重连取帧');
    const q = await request('POST', '/api/session/quality', { token, body: { maxSize: 720, maxFps: 30, bitRate: 1500000 } });
    check('session/quality 返回 ok', q.status === 200 && q.json.ok);
    await sleep(1200);
    const helloQ = await waitHello(8000);
    check('画质切换后会话仍在运行且参数已更新',
      helloQ.name === 'E2E测试机' && helloQ.session && helloQ.session.running === true &&
      helloQ.session.quality.maxSize === 720 && helloQ.session.quality.maxFps === 30,
      JSON.stringify(helloQ.session && helloQ.session.quality) + ` name=${helloQ.name} listenerPid=${findListenerPid(PORTS.http)} agentPid=${agent.pid}`);
    closeStream(stream);
    stream = null;
    await sleep(300);
    stream = await startStream(lanIp, token, 2500);
    const snapQ = stream.snap();
    check('画质切换后可重新连接并出帧', snapQ.frames >= 2 && snapQ.keys >= 1, `frames=${snapQ.frames} keys=${snapQ.keys} fps=${snapQ.fps}`);
    closeStream(stream);
    stream = null;
    const stopQ = await request('POST', '/api/session/stop', { token });
    check('session/stop 返回 ok', stopQ.status === 200 && stopQ.json.ok);

    section('9. display MATCH 生效与 reset 恢复');
    const st9 = await request('POST', '/api/session/start', { token, body: { quality: { maxSize: 1080, maxFps: 60, bitRate: 4000000 }, display: { mode: 'fit' } } });
    check('再次 session/start 成功', st9.status === 200 && st9.json.ok);
    const disp = await request('POST', '/api/display', { token, body: { mode: 'match', width: 1200, height: 2000, density: 320 } });
    check('display MATCH 返回 applied 1200x2000@320',
      disp.status === 200 && disp.json.ok && disp.json.applied && disp.json.applied.width === 1200 && disp.json.applied.density === 320,
      JSON.stringify(disp.json && disp.json.applied) + ` status=${disp.status} body=${String(disp.text || '').slice(0, 160)}`);
    const wmMatch = parseWm();
    check('设备 wm 已应用 Override 1200x2000/320',
      wmMatch.override && wmMatch.override.width === 1200 && wmMatch.override.height === 2000 && wmMatch.overrideDensity === 320,
      wmMatch.raw.replace(/\n/g, ' | '));
    const reset = await request('POST', '/api/display/reset', { token });
    check('display/reset 返回 ok', reset.status === 200 && reset.json.ok);
    const wmReset = parseWm();
    check('display/reset 后恢复物理 1440x2560/360',
      !wmReset.override && wmReset.physical.width === baseline.physical.width && wmReset.physical.height === baseline.physical.height &&
      (!wmReset.overrideDensity) && (!baseline.physicalDensity || wmReset.physicalDensity === baseline.physicalDensity),
      wmReset.raw.replace(/\n/g, ' | '));

    section('10. 运行中 wm size 改变：server 不崩、客户端可重连');
    let midOk = true;
    let midDetail = '';
    try {
      stream = await startStream(lanIp, token, 1500);
      const beforeMid = stream.snap();
      adbShell('wm size 1200x2000');
      adbShell('wm density 320');
      await sleep(2500);
      const helloMid = await waitHello(8000);
      const midSnap = stream.snap();
      const keptStream = stream.video && !stream.video.destroyed;
      midDetail = `before=${beforeMid.meta && beforeMid.meta.width + 'x' + beforeMid.meta.height} during=${midSnap.meta && midSnap.meta.width + 'x' + midSnap.meta.height} sessions=${midSnap.sessions} running=${helloMid.session.running} streamAlive=${keptStream}`;
      midOk = !!helloMid.ok && helloMid.session && helloMid.session.running === true;
      adbShell('wm size reset; wm density reset');
      await sleep(2000);
      if (keptStream) {
        const afterMid = stream.snap();
        midOk = midOk && (afterMid.frames > beforeMid.frames);
        midDetail += ` frames=${beforeMid.frames}→${afterMid.frames}`;
      } else {
        closeStream(stream);
        stream = null;
        const st10 = await request('POST', '/api/session/start', { token, body: { quality: { maxSize: 1080, maxFps: 60, bitRate: 4000000 }, display: { mode: 'fit' } } });
        stream = await startStream(lanIp, token, 2000);
        const reSnap = stream.snap();
        midOk = midOk && st10.status === 200 && reSnap.frames >= 2;
        midDetail += ` 重连后 frames=${reSnap.frames}`;
      }
    } catch (e) {
      midOk = false;
      midDetail = e.message;
      try { adbShell('wm size reset; wm density reset'); } catch (_) { /* ignore */ }
    }
    check('中途改 wm 后代理存活/会话可恢复且不崩', midOk, midDetail);
    closeStream(stream);
    stream = null;
    try { await request('POST', '/api/session/stop', { token }); } catch (_) { /* ignore */ }

    section('11. 客户端断线后清理并允许新会话');
    const st11a = await request('POST', '/api/session/start', { token, body: { quality: { maxSize: 1080, maxFps: 60, bitRate: 4000000 }, display: { mode: 'fit' } } });
    check('断线测试前启动会话', st11a.status === 200 && st11a.json.ok, JSON.stringify(st11a.json && st11a.json.error));
    stream = await startStream(lanIp, token, 1200);
    check('断线前视频已出帧', stream.snap().frames >= 1);
    closeStream(stream);
    stream = null;
    await sleep(3500);
    const helloDisc = await waitHello(8000);
    check('客户端断开后会话自动停止', helloDisc.session && helloDisc.session.running === false, `running=${helloDisc.session && helloDisc.session.running}`);
    const fw = spawnSync(ADB, ['-s', DEVICE, 'forward', '--list'], { encoding: 'utf8', timeout: 10000 });
    check('断开后 adb forward 已移除', !new RegExp(`tcp:${PORTS.adbForward}\\b`).test(fw.stdout || ''), (fw.stdout || '').trim() || '(空)');
    const st11 = await request('POST', '/api/session/start', { token, body: { quality: { maxSize: 1080, maxFps: 60, bitRate: 4000000 }, display: { mode: 'fit' } } });
    check('断开后可重新启动会话', st11.status === 200 && st11.json.ok, JSON.stringify(st11.json && st11.json.error));
    stream = await startStream(lanIp, token, 2000);
    check('断开后可启动新会话并出帧', stream.snap().frames >= 2, `frames=${stream.snap().frames}`);
    closeStream(stream);
    stream = null;
    await request('POST', '/api/session/stop', { token });

    section('12. UDP 发现');
    const disco = await new Promise((resolve, reject) => {
      const s = dgram.createSocket('udp4');
      const timer = setTimeout(() => { s.close(); reject(new Error('UDP 发现超时')); }, 5000);
      s.on('message', (msg) => {
        clearTimeout(timer);
        let j = null;
        try { j = JSON.parse(msg.toString('utf8')); } catch (_) { /* ignore */ }
        s.close();
        resolve(j);
      });
      s.bind(0, '127.0.0.1', () => s.send('MUMULINK_DISCOVER', PORTS.udp, '127.0.0.1'));
    }).catch((e) => ({ error: e.message }));
    check('UDP MUMULINK_DISCOVER 收到 JSON 应答',
      disco && !disco.error && disco.magic === 'MUMULINK' && disco.v === 1 && disco.httpPort === PORTS.http && disco.videoPort === PORTS.video,
      disco && disco.error ? disco.error : JSON.stringify(disco && { name: disco.name, host: disco.host, httpPort: disco.httpPort }));
    check('UDP 应答 host 为 LAN IP 且不是 127.0.0.1', disco && disco.host === lanIp, disco && disco.host);

    section('13. 设备重启接口鉴权（不真正执行）');
    const devRestart = await request('POST', '/api/device/restart', { body: {} });
    check('device/restart 无 token 返回 401（路由存在且受保护）', devRestart.status === 401, `status=${devRestart.status}`);

    section('14. 重启代理（detached re-exec）');
    const badRestart = await request('POST', '/api/agent/restart', { body: {} });
    check('agent/restart 无 token 被拒', badRestart.status === 401);
    const restart = await request('POST', '/api/agent/restart', { token, body: {} });
    check('agent/restart 返回 202', restart.status === 202 && restart.json.ok, `status=${restart.status}`);
    await sleep(2500);
    const helloR = await waitHello(20000);
    const newPid = findListenerPid(PORTS.http);
    restartedPid = newPid;
    check('重启后代理恢复响应且 token 仍有效', !!helloR.ok && helloR.paired === true,
      `新 PID=${newPid} 旧 PID=${agent.pid}`);
    check('重启后确实是新进程在监听', !!newPid && newPid !== agent.pid, `pid ${agent.pid} → ${newPid}`);
    const logsR = await request('GET', '/api/logs', { token });
    check('重启后 token 鉴权仍通过', logsR.status === 200 && logsR.json.ok);

    section('15. 收尾：显示恢复与基线校验');
    const wmFinal = parseWm();
    check('测试结束 wm 恢复物理尺寸且无 Override',
      !wmFinal.override && wmFinal.physical.width === baseline.physical.width && wmFinal.physical.height === baseline.physical.height,
      wmFinal.raw.replace(/\n/g, ' | '));
    check('测试结束 wm density 无 Override', !wmFinal.overrideDensity, String(wmFinal.physicalDensity));
    const fwFinal = spawnSync(ADB, ['-s', DEVICE, 'forward', '--list'], { encoding: 'utf8', timeout: 10000 });
    check('测试结束无残留测试 forward', !new RegExp(`tcp:${PORTS.adbForward}\\b`).test(fwFinal.stdout || ''), (fwFinal.stdout || '').trim() || '(空)');
  } catch (e) {
    fail++;
    failures.push(`${currentSection} 异常：${e.message}`);
    console.log(`FAIL  [${currentSection}] 未捕获异常：${e.message}`);
  } finally {
    for (const s of openSockets) { try { s.destroy(); } catch (_) { /* ignore */ } }
    try { await request('POST', '/api/session/stop', { token, timeout: 5000 }); } catch (_) { /* ignore */ }
    restoreDisplay(baseline);
    // 只杀自己 spawn 的进程（含 restart 后的新 PID），绝不按端口盲杀其他代理
    if (agent && agent.pid) killPid(agent.pid);
    if (restartedPid && restartedPid !== agent.pid) killPid(restartedPid);
    if (agent && agentAlive) {
      try { agent.kill('SIGTERM'); } catch (_) { /* ignore */ }
      await sleep(500);
      if (agentAlive) { try { agent.kill('SIGKILL'); } catch (_) { /* ignore */ } }
    }
    try {
      const outDir = path.join(__dirname, '..', 'pc', 'test-tmp');
      fs.mkdirSync(outDir, { recursive: true });
      fs.writeFileSync(path.join(outDir, 'last-agent.log'), agentLog.join(''), 'utf8');
      fs.writeFileSync(path.join(outDir, 'last-agent-config.json'), JSON.stringify(PORTS, null, 2) + `\n// agentPid=${agent && agent.pid} token=${token ? token.slice(0, 8) + '…' : '(none)'}\n`, 'utf8');
    } catch (_) { /* ignore */ }
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) { /* ignore */ }
    const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
    console.log('\n================ 汇总 ================');
    console.log(`PASS ${pass}  FAIL ${fail}  用时 ${elapsed}s`);
    if (failures.length) {
      console.log('失败项：');
      for (const f of failures) console.log(' - ' + f);
      console.log('\n代理日志尾部：');
      console.log(agentLog.join('').split(/\r?\n/).slice(-40).join('\n'));
    }
    console.log('RESULT:', fail === 0 ? 'PASS' : 'FAIL');
    process.exitCode = fail === 0 ? 0 : 1;
  }
}

main().catch((e) => {
  console.error('FATAL', e && e.stack ? e.stack : e);
  process.exit(2);
});
