'use strict';

const { spawn, spawnSync } = require('child_process');

class AgentError extends Error {
  constructor(code, message, status, hint) {
    super(message);
    this.code = code;
    this.status = status || 500;
    this.hint = hint || '';
  }
}

const ERR = {
  BAD_REQUEST: 400,
  BAD_CODE: 401,
  BAD_TOKEN: 401,
  NOT_PAIRED: 401,
  TOO_MANY_ATTEMPTS: 429,
  NOT_FOUND: 404,
  METHOD_NOT_ALLOWED: 405,
  NO_DEVICE: 503,
  PORT_IN_USE: 409,
  SCRCPY_BUSY: 409,
  CONTROL_NOT_READY: 409,
  VIDEO_NOT_READY: 409,
  NOT_RUNNING: 409,
  ADB_ERROR: 500,
  DISPLAY_APPLY_FAILED: 500,
  PUSH_FAILED: 500,
  SCRCPY_START_FAILED: 500,
  INTERNAL: 500,
  TIMEOUT: 504
};

function fail(code, message, hint) {
  throw new AgentError(code, message, ERR[code] || 500, hint);
}

// 对 adb.exe 的极薄封装（同步执行，便于测试与顺序控制）
class Adb {
  constructor({ adbPath, device, logger, timeoutMs = 20000 }) {
    this.adbPath = adbPath;
    this.device = device;
    this.logger = logger;
    this.timeoutMs = timeoutMs;
    this.serial = device;
  }

  raw(args, opts = {}) {
    const timeout = opts.timeout || this.timeoutMs;
    const res = spawnSync(this.adbPath, args, {
      encoding: 'utf8',
      timeout,
      windowsHide: true,
      maxBuffer: 64 * 1024 * 1024
    });
    const out = {
      ok: !res.error && res.status === 0,
      status: res.status,
      stdout: res.stdout || '',
      stderr: res.stderr || '',
      error: res.error || null
    };
    if (!out.ok) {
      const detail = out.error ? out.error.message : (out.stderr || out.stdout || `exit ${out.status}`);
      if (opts.log !== false) this.logger && this.logger.warn(`adb ${args.join(' ')} 失败：${detail.trim()}`);
    }
    return out;
  }

  shell(cmd, opts = {}) {
    return this.raw(['-s', this.serial, 'shell', cmd], opts);
  }

  shellText(cmd, opts = {}) {
    const r = this.shell(cmd, opts);
    if (!r.ok) fail('ADB_ERROR', `执行 adb shell 失败：${(r.stderr || r.stdout).trim() || '未知错误'}`);
    return r.stdout;
  }

  listDevices() {
    const r = this.raw(['devices'], { timeout: 8000 });
    if (!r.ok) return [];
    const out = [];
    for (const line of r.stdout.split(/\r?\n/)) {
      const m = line.match(/^(\S+)\s+(device|offline|unauthorized)\s*$/);
      if (m) out.push({ serial: m[1], state: m[2] });
    }
    return out;
  }

  // 返回 {connected, serial, model, android}
  ensureDevice() {
    const isTcp = /:\d+$/.test(this.device) && !this.device.startsWith('emulator-');
    let devices = this.listDevices();
    let found = devices.find((d) => d.serial === this.device);
    if ((!found || found.state !== 'device') && isTcp) {
      const c = this.raw(['connect', this.device], { timeout: 3000, log: false });
      this.logger && this.logger.info(`adb connect ${this.device}: ${(c.stdout || c.stderr).trim()}`);
      devices = this.listDevices();
      found = devices.find((d) => d.serial === this.device);
    }
    if (!found || found.state !== 'device') {
      const detail = devices.length ? devices.map((d) => `${d.serial}(${d.state})`).join(', ') : '无任何设备';
      fail('NO_DEVICE', `未找到 MuMu 模拟器设备 ${this.device}（当前设备：${detail}）。请先启动 MuMu 模拟器并确认 adb 已连接。`);
    }
    const info = this.shell('getprop ro.product.model; getprop ro.build.version.release', { log: false, timeout: 8000 });
    const lines = info.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    return {
      connected: true,
      serial: this.device,
      model: lines[0] || '未知',
      android: lines[1] || '未知'
    };
  }

  getDisplay() {
    const r = this.shell('wm size; wm density', { log: false });
    if (!r.ok) fail('ADB_ERROR', `读取屏幕信息失败：${(r.stderr || r.stdout).trim() || 'adb shell 执行失败'}`);
    const size = parseSize(r.stdout);
    const density = parseDensityInfo(r.stdout);
    if (!size) fail('ADB_ERROR', `无法解析 wm size 输出：${r.stdout.trim()}`);
    // wm 会同时打印 Physical 与 Override；有效值优先 Override（MuMu Android 15 的 MATCH 适配依赖此点）
    return {
      width: size.width,
      height: size.height,
      density: density.effective,
      physicalWidth: size.physicalWidth || size.width,
      physicalHeight: size.physicalHeight || size.height,
      physicalDensity: density.physical || density.effective,
      overrideSize: size.override || null,
      overrideDensity: density.override,
      raw: r.stdout.trim()
    };
  }

  md5(remotePath) {
    const r = this.shell(`md5sum '${remotePath}' 2>/dev/null`, { log: false, timeout: 10000 });
    if (!r.ok) return null;
    const m = r.stdout.trim().match(/^([0-9a-fA-F]{32})\s/);
    return m ? m[1].toLowerCase() : null;
  }

  push(localPath, remotePath) {
    this.raw(['-s', this.serial, 'shell', `mkdir -p '${remotePath.replace(/\/[^/]+$/, '')}'`], { log: false, timeout: 10000 });
    const r = this.raw(['-s', this.serial, 'push', localPath, remotePath], { timeout: 120000, log: false });
    if (!r.ok || /error/i.test(r.stderr) && !/pushed/i.test(r.stdout)) {
      fail('PUSH_FAILED', `推送 scrcpy-server 到 ${remotePath} 失败：${(r.stderr || r.stdout).trim() || '未知错误'}`);
    }
    return (r.stdout + r.stderr).trim();
  }

  forwardAdd(localPort, socketName) {
    this.forwardRemove(localPort);
    const r = this.raw(['-s', this.serial, 'forward', `tcp:${localPort}`, `localabstract:${socketName}`], { timeout: 10000, log: false });
    if (!r.ok) {
      const msg = (r.stderr || r.stdout).toLowerCase();
      if (msg.includes('cannot bind') || msg.includes('address already in use') || msg.includes('failed to bind')) {
        fail('PORT_IN_USE', `本机端口 ${localPort} 被占用，无法建立 adb forward。请关闭占用该端口的程序，或修改 config.json 中的 videoPort/controlPort 后重启代理。`);
      }
      fail('ADB_ERROR', `adb forward 失败：${(r.stderr || r.stdout).trim() || '未知错误'}`);
    }
  }

  forwardRemove(localPort) {
    this.raw(['-s', this.serial, 'forward', '--remove', `tcp:${localPort}`], { timeout: 8000, log: false });
  }

  // 按 scid 精确匹配设备端 scrcpy server 进程，避免误杀其它实例
  scrcpyPids(scidHex) {
    const pat = `com.genymobile.scrcpy.Server 5.0.1.*scid=${scidHex}`;
    const r = this.shell(`ps -A -o PID,ARGS 2>/dev/null | grep -E '${pat}' | grep -v grep | awk '{print $1}'`, { log: false, timeout: 10000 });
    if (!r.ok) return [];
    return r.stdout.split(/\s+/).map((s) => s.trim()).filter((s) => /^\d+$/.test(s));
  }

  killScid(scidHex) {
    const pids = this.scrcpyPids(scidHex);
    if (!pids.length) return 0;
    this.logger && this.logger.warn(`兜底清理设备端 scrcpy 进程：${pids.join(',')}`);
    this.shell(`kill -9 ${pids.join(' ')} 2>/dev/null`, { log: false, timeout: 10000 });
    return pids.length;
  }

  screencapRaw() {
    const res = spawnSync(this.adbPath, ['-s', this.serial, 'exec-out', 'screencap'], {
      timeout: 30000,
      windowsHide: true,
      maxBuffer: 64 * 1024 * 1024
    });
    if (res.error || res.status !== 0 || !res.stdout || res.stdout.length < 16) {
      fail('ADB_ERROR', `截屏失败：${res.error ? res.error.message : (res.stderr || '').toString().trim() || '无数据'}`);
    }
    return res.stdout;
  }
}

function parseSize(text) {
  // 输出可能是：Physical size: 1440x2560 / Override size: 900x1600 / 仅一行
  let physical = null;
  let override = null;
  for (const line of String(text).split(/\r?\n/)) {
    let m = line.match(/Physical size:\s*(\d+)x(\d+)/i);
    if (m) physical = { width: +m[1], height: +m[2] };
    m = line.match(/Override size:\s*(\d+)x(\d+)/i);
    if (m) override = { width: +m[1], height: +m[2] };
  }
  const shown = override || physical;
  if (!shown) {
    const m = String(text).match(/(\d+)x(\d+)/);
    if (!m) return null;
    physical = { width: +m[1], height: +m[2] };
    return { width: physical.width, height: physical.height, physicalWidth: physical.width, physicalHeight: physical.height, override: null };
  }
  return {
    width: shown.width,
    height: shown.height,
    physicalWidth: physical ? physical.width : shown.width,
    physicalHeight: physical ? physical.height : shown.height,
    override
  };
}

function parseDensityInfo(text) {
  const s = String(text);
  const pm = s.match(/Physical density:\s*(\d+)/i);
  const om = s.match(/Override density:\s*(\d+)/i);
  const physical = pm ? +pm[1] : 0;
  const override = om ? +om[1] : null;
  const effective = override || physical || (s.match(/(\d+)\s*$/m) ? +s.match(/(\d+)\s*$/m)[1] : 0);
  return { physical, override, effective };
}

module.exports = { Adb, AgentError, ERR, fail };
