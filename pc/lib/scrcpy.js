'use strict';

const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const { spawn } = require('child_process');
const { AgentError, fail } = require('./adb');

const DEFAULT_QUALITY = { maxSize: 1080, maxFps: 60, bitRate: 4000000 };
const SCRCPY_VERSION = '5.0.1';
const REMOTE_JAR = '/sdcard/mumulink/scrcpy.bin';
const EXPECTED_MD5 = '55a1ef0a811f1cb0d417084b70e0a630';
function configAudioEnabled(cfg) {
  const a = cfg && cfg.audio;
  if (a === false) return false;
  if (a && typeof a === 'object' && a.enabled === false) return false;
  return true;
}

function serverArgsTail(audioEnabled) {
  return `log_level=${process.env.MUMULINK_SCRCPY_LOG_LEVEL || 'info'} video=true audio=${audioEnabled ? 'true' : 'false'} ` +
    'audio_codec=raw control=true tunnel_forward=true ' +
    'send_device_meta=false send_dummy_byte=false send_stream_meta=true send_frame_meta=true';
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

function normalizeQuality(q, base) {
  const src = Object.assign({}, base || DEFAULT_QUALITY, q || {});
  const out = {
    maxSize: Number(src.maxSize),
    maxFps: Number(src.maxFps),
    bitRate: Number(src.bitRate)
  };
  if (!Number.isInteger(out.maxSize) || out.maxSize < 240 || out.maxSize > 4096) {
    fail('BAD_REQUEST', `画质 maxSize 必须是 240–4096 的整数（收到 ${src.maxSize}）`);
  }
  if (!Number.isInteger(out.maxFps) || out.maxFps < 10 || out.maxFps > 120) {
    fail('BAD_REQUEST', `画质 maxFps 必须是 10–120 的整数（收到 ${src.maxFps}）`);
  }
  if (!Number.isInteger(out.bitRate) || out.bitRate < 100000 || out.bitRate > 50000000) {
    fail('BAD_REQUEST', `画质 bitRate 必须是 100000–50000000 的整数（收到 ${src.bitRate}）`);
  }
  return out;
}

function normalizeDisplay(spec) {
  const s = spec && typeof spec === 'object' ? spec : { mode: 'fit' };
  if (s.mode === 'match') {
    const width = Number(s.width);
    const height = Number(s.height);
    const density = Number(s.density);
    return { mode: 'match', width, height, density };
  }
  return { mode: 'fit' };
}

function scidRandom() {
  return crypto.randomBytes(4).readUInt32BE(0) & 0x7fffffff;
}

// ControlMessage TYPE_SET_CLIPBOARD = 9（已用 dexdump 从 tools/scrcpy-server.jar 的 classes.dex 核对）
// 线上格式：[4B BE type][8B BE sequence][1B paste][4B BE len][UTF-8]
const TYPE_SET_CLIPBOARD = 9;
function encodeSetClipboard(text, sequence, paste = true) {
  const payload = Buffer.from(String(text == null ? '' : text), 'utf8');
  const seq = BigInt(sequence == null ? 1 : sequence);
  const buf = Buffer.alloc(4 + 8 + 1 + 4 + payload.length);
  buf.writeUInt8(TYPE_SET_CLIPBOARD, 0);
  buf.writeBigUInt64BE(seq, 4);
  buf.writeUInt8(paste ? 1 : 0, 12);
  buf.writeUInt32BE(payload.length, 13);
  payload.copy(buf, 17);
  return { buf, bytes: payload.length };
}

// 读取握手行，并持续收集之后的字节（避免握手与 pipe 之间丢数据）
function handshakeReader(socket, timeoutMs = 3000) {
  let buf = Buffer.alloc(0);
  let pending = Buffer.alloc(0);
  let settled = false;
  let resolvePromise;
  const promise = new Promise((r) => { resolvePromise = r; });
  const timer = setTimeout(() => settle(null, 'TIMEOUT'), timeoutMs);

  function settle(line, err) {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    socket.removeListener('data', onData);
    socket.pause();
    if (!err) {
      socket.removeListener('data', onData);
      socket.on('data', onPending);
      socket.pause();
    }
    resolvePromise({ line, err });
  }
  function onData(chunk) {
    buf = Buffer.concat([buf, chunk]);
    const i = buf.indexOf(0x0a);
    if (i >= 0) {
      const line = buf.slice(0, i).toString('utf8').replace(/\r$/, '');
      pending = buf.slice(i + 1);
      settle(line, null);
    } else if (buf.length > 2048) {
      settle(null, 'TOO_LONG');
    }
  }
  function onPending(chunk) { pending = Buffer.concat([pending, chunk]); }
  socket.on('data', onData);
  socket.once('error', () => settle(null, 'SOCKET_ERROR'));
  socket.once('close', () => settle(null, 'SOCKET_CLOSED'));

  return {
    promise,
    takePending() {
      socket.removeListener('data', onPending);
      const p = pending;
      pending = Buffer.alloc(0);
      return p;
    }
  };
}

function connectLocal(port, timeoutMs = 2500) {
  return new Promise((resolve, reject) => {
    const s = net.connect({ host: '127.0.0.1', port });
    const timer = setTimeout(() => {
      s.destroy();
      reject(new Error(`连接本地转发端口 ${port} 超时`));
    }, timeoutMs);
    s.once('connect', () => { clearTimeout(timer); resolve(s); });
    s.once('error', (e) => { clearTimeout(timer); reject(e); });
  });
}

async function connectLocalRetry(port, deadlineMs = 6000) {
  const end = Date.now() + deadlineMs;
  let lastErr;
  while (Date.now() < end) {
    try {
      return await connectLocal(port, Math.min(1200, end - Date.now()));
    } catch (e) {
      lastErr = e;
      await sleep(200);
    }
  }
  throw lastErr || new Error(`连接端口 ${port} 失败`);
}

// 被动解析视频流，仅用于日志/统计，不改动任何字节
class VideoStats {
  constructor(logger, scidHex) {
    this.logger = logger;
    this.scid = scidHex;
    this.buf = Buffer.alloc(0);
    this.state = 'codec';
    this.codec = 0;
    this.meta = null;
    this.frames = 0;
    this.keyFrames = 0;
    this.configFrames = 0;
    this.sessionPackets = 0;
    this.bytes = 0;
    this.startedAt = 0;
    this.lastLogAt = 0;
    this.finished = false;
  }

  push(chunk) {
    if (!this.startedAt) this.startedAt = Date.now();
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    this._parse();
  }

  _parse() {
    for (;;) {
      if (this.state === 'codec') {
        if (this.buf.length < 4) return;
        this.codec = this.buf.readUInt32BE(0);
        this.buf = this.buf.slice(4);
        this.logger.info(`视频流 codec=0x${this.codec.toString(16).padStart(8, '0')}`);
        this.state = 'meta';
      } else if (this.state === 'meta') {
        if (this.buf.length < 12) return;
        const flags = this.buf.readUInt32BE(0);
        const width = this.buf.readInt32BE(4);
        const height = this.buf.readInt32BE(8);
        this.buf = this.buf.slice(12);
        this.meta = { flags, width, height };
        this.logger.info(`视频流初始 session meta：${width}x${height} flags=0x${flags.toString(16)}`);
        this.state = 'frames';
      } else {
        // 中途 session meta 是裸 12 字节（flags|width|height），没有帧头，需先识别
        if (this.buf.length >= 4) {
          const first = this.buf.readUInt32BE(0);
          if (first === 0x80000000 || first === 0x80000001) {
            if (this.buf.length < 12) return;
            const w = this.buf.readInt32BE(4);
            const h = this.buf.readInt32BE(8);
            if (w > 0 && w <= 8192 && h > 0 && h <= 8192) {
              this.buf = this.buf.slice(12);
              this.sessionPackets++;
              this.meta = { flags: first, width: w, height: h };
              this.logger.info(`视频流中途 session meta 更新：${w}x${h}（第 ${this.sessionPackets} 个），后续帧继续透传`);
              continue;
            }
          }
        }
        if (this.buf.length < 12) return;
        const pts = this.buf.readBigUInt64BE(0);
        const size = this.buf.readUInt32BE(8);
        if (size > 32 * 1024 * 1024) {
          this.logger.warn(`视频流出现异常帧长度 ${size}，统计器停止解析`);
          this.finished = true;
          return;
        }
        if (this.buf.length < 12 + size) return;
        const payload = this.buf.slice(12, 12 + size);
        this.buf = this.buf.slice(12 + size);
        const isSession = (pts & (1n << 63n)) !== 0n;
        const isConfig = (pts & (1n << 62n)) !== 0n;
        const isKey = (pts & (1n << 61n)) !== 0n;
        if (isSession) {
          this.sessionPackets++;
          if (size >= 12) {
            const f = payload.readUInt32BE(0);
            const w = payload.readInt32BE(4);
            const h = payload.readInt32BE(8);
            this.meta = { flags: f, width: w, height: h };
            this.logger.info(`视频流中途 session meta 更新：${w}x${h}（第 ${this.sessionPackets} 个 session 包），后续帧继续透传`);
          } else {
            this.logger.info(`视频流收到 session 包（size=${size}）`);
          }
        } else {
          this.frames++;
          this.bytes += size;
          if (isConfig) this.configFrames++;
          if (isKey) this.keyFrames++;
          if (this.frames === 1) this.logger.info(`视频流首帧：${size} B${isKey ? ' KEY' : ''}${isConfig ? ' CONFIG' : ''}`);
          const now = Date.now();
          if (!this.lastLogAt) this.lastLogAt = now;
          if (now - this.lastLogAt >= 5000) {
            const sec = (now - this.startedAt) / 1000;
            this.logger.info(`视频统计：${this.frames} 帧 / ${sec.toFixed(1)}s（fps≈${(this.frames / sec).toFixed(1)}，码率≈${((this.bytes * 8) / sec / 1000).toFixed(0)} kbps，KEY=${this.keyFrames}，CONFIG=${this.configFrames}，sessionPacket=${this.sessionPackets}）`);
            this.lastLogAt = now;
          }
        }
      }
    }
  }

  snapshot() {
    const sec = this.startedAt ? (Date.now() - this.startedAt) / 1000 : 0;
    return {
      codec: this.codec,
      meta: this.meta,
      frames: this.frames,
      keyFrames: this.keyFrames,
      configFrames: this.configFrames,
      sessionPackets: this.sessionPackets,
      bytes: this.bytes,
      seconds: +sec.toFixed(2),
      fps: sec > 0 ? +(this.frames / sec).toFixed(2) : 0,
      kbps: sec > 0 ? Math.round((this.bytes * 8) / sec / 1000) : 0
    };
  }
}

class SessionManager {
  constructor({ adb, display, logger, config, verifyToken, localJar }) {
    this.adb = adb;
    this.display = display;
    this.logger = logger;
    this.config = config;
    this.verifyToken = verifyToken;
    this.localJar = localJar;

    this.queue = Promise.resolve();
    this.running = false;
    this.busy = false;
    this.scidHex = null;
    this.forwardPort = null;
    this.socketName = null;
    this.serverProc = null;
    this.serverExit = null;
    this.serverOutput = [];
    this.quality = Object.assign({}, DEFAULT_QUALITY);
    this.displaySpec = { mode: 'fit' };
    this.videoClient = null;
    this.audioClient = null;
    this.controlClient = null;
    this.videoUpstream = null;
    this.audioUpstream = null;
    this.controlUpstream = null;
    this.audioEnabled = true;
    this.audioBytes = 0;
    this.audioPackets = 0;
    this.videoStats = null;
    this.statsTimer = null;
    this.lastError = null;
    this.startedAt = 0;
    this._stopping = false;
  }

  _serial(fn) {
    const run = this.queue.then(fn, fn);
    this.queue = run.then(() => undefined, () => undefined);
    return run;
  }

  isRunning() { return this.running; }

  getPublicInfo() {
    return {
      running: this.running,
      quality: Object.assign({}, this.quality),
      display: Object.assign({}, this.displaySpec),
      videoConnected: !!this.videoClient,
      audioConnected: !!this.audioClient,
      audioEnabled: !!this.audioEnabled,
      controlConnected: !!this.controlClient,
      audioBytes: this.audioBytes,
      audioPackets: this.audioPackets,
      scid: this.scidHex,
      lastError: this.lastError,
      stats: this.videoStats ? this.videoStats.snapshot() : null
    };
  }

  async start({ quality, display, audio } = {}) {
    return this._serial(() => this._start(quality, display, audio));
  }

  async stop(reason = '手动停止') {
    return this._serial(() => this._stop(reason));
  }

  async setQuality(q) {
    const normalized = normalizeQuality(q, this.quality);
    if (!this.running) {
      this.quality = normalized;
      this.logger.info(`画质已更新（未运行，下次会话生效）：${normalized.maxSize}@${normalized.maxFps} ${normalized.bitRate}bps`);
      return { restarted: false, quality: normalized };
    }
    this.logger.info(`切换画质：${normalized.maxSize}@${normalized.maxFps} ${normalized.bitRate}bps，重启会话`);
    return this.start({ quality: normalized, display: this.displaySpec });
  }

  async _start(quality, display, audio) {
    if (this.busy) fail('SCRCPY_BUSY', '会话正在启动或停止中，请稍后重试');
    this.busy = true;
    try {
      if (this.running || this.serverProc) {
        this.logger.info('检测到已有会话，先停止旧会话再启动');
        await this._stop('重复启动：先停旧会话');
      }
      this.lastError = null;
      const q = normalizeQuality(quality, this.quality);
      const dspec = normalizeDisplay(display);
      this.audioEnabled = (audio === undefined || audio === null)
        ? configAudioEnabled(this.config)
        : !!audio;
      this.audioBytes = 0;
      this.audioPackets = 0;
      this.logger.info(`会话启动：quality=${q.maxSize}@${q.maxFps} ${q.bitRate}bps display=${dspec.mode}${dspec.mode === 'match' ? ` ${dspec.width}x${dspec.height}@${dspec.density}` : ''}`);

      const dev = this.adb.ensureDevice();
      this.logger.info(`设备就绪：${dev.model} Android ${dev.android}（${dev.serial}）`);

      this.display.readPhysical();
      const appliedDisplay = this.display.apply(dspec);

      const jarLocal = this._ensureJar();
      this.logger.info(`scrcpy-server 本地文件：${jarLocal}`);

      const scid = scidRandom();
      const scidHex = scid.toString(16).padStart(8, '0');
      this.scidHex = scidHex;
      this.socketName = `scrcpy_${scidHex}`;
      this.quality = q;
      this.displaySpec = dspec.mode === 'match' ? { mode: 'match', width: dspec.width, height: dspec.height, density: dspec.density } : { mode: 'fit' };

      this.forwardPort = this._setupForward();
      this.logger.info(`adb forward tcp:${this.forwardPort} → localabstract:${this.socketName}（LAN 监听端口 video=${this.config.videoPort} control=${this.config.controlPort} 与 forward 隔离）`);

      this._startServer(scidHex);
      await this._waitServerReady();

      this.running = true;
      this.startedAt = Date.now();
      this.logger.info(`scrcpy 会话已就绪（scid=${scidHex}），等待 App 连接 videoPort=${this.config.videoPort} audioPort=${this.config.audioPort}${this.audioEnabled ? '' : '（音频关闭）'} controlPort=${this.config.controlPort}`);

      const cur = this.adb.getDisplay();
      this.displayApplied = this.display.overrideApplied;
      return {
        deviceW: appliedDisplay.width,
        deviceH: appliedDisplay.height,
        videoPort: this.config.videoPort,
        audioPort: this.config.audioPort,
        audioEnabled: this.audioEnabled,
        controlPort: this.config.controlPort,
        quality: Object.assign({}, q),
        display: appliedDisplay,
        scid: scidHex
      };
    } catch (err) {
      this.lastError = err instanceof Error ? err.message : String(err);
      this.logger.error(`会话启动失败：${this.lastError}`);
      await this._cleanupAfterFailure();
      throw err;
    } finally {
      this.busy = false;
    }
  }

  // 固定 adb forward 端口与 LAN 监听端口隔离：默认 28182，占用则 28183..28192 递增
  _setupForward() {
    const base = Number(this.config.adbForwardPort) || 28182;
    let lastErr = null;
    for (let p = base; p <= base + 10; p++) {
      try { this.adb.forwardRemove(p); } catch (_) { /* ignore */ }
      try {
        this.adb.forwardAdd(p, this.socketName);
        return p;
      } catch (e) {
        lastErr = e;
        if (e.code !== 'PORT_IN_USE') throw e;
        this.logger.warn(`adb forward 端口 ${p} 被占用，尝试 ${p + 1}：${e.message}`);
      }
    }
    fail('PORT_IN_USE', `adb forward 端口 ${base}..${base + 10} 均不可用，无法连接 scrcpy server。请关闭占用端口的程序或修改 config.json 的 adbForwardPort。`);
  }

  async _cleanupAfterFailure() {
    this.running = false;
    this._destroyClients();
    try { await this._killServer(); } catch (_) { /* ignore */ }
    if (this.scidHex) { try { this.adb.killScid(this.scidHex); } catch (_) { /* ignore */ } }
    if (this.forwardPort) { try { this.adb.forwardRemove(this.forwardPort); } catch (_) { /* ignore */ } }
    this.forwardPort = null;
    try { this.display.restore('启动失败回滚'); } catch (_) { /* ignore */ }
    this.scidHex = null;
    this.socketName = null;
  }

  _ensureJar() {
    const expected = (this.config.scrcpyJarMd5 || EXPECTED_MD5).toLowerCase();
    const remote = this.config.deviceJarPath || REMOTE_JAR;
    this.remoteJar = remote;
    const local = this.localJar;
    if (!local || !fs.existsSync(local)) {
      fail('PUSH_FAILED', '未找到本地 scrcpy-server.jar（应位于 tools/scrcpy-server.jar 或 artifacts/scrcpy-server-v5.0.1）');
    }
    const localMd5 = crypto.createHash('md5').update(fs.readFileSync(local)).digest('hex');
    if (localMd5 !== expected) {
      fail('PUSH_FAILED', `本地 scrcpy-server jar 校验失败：期望 md5 ${expected}，实际 ${localMd5}（文件 ${local}）`);
    }
    const remoteMd5 = this.adb.md5(remote);
    if (remoteMd5 === expected) {
      this.logger.info(`设备端 jar 校验通过：${remote} md5=${expected}`);
      return local;
    }
    this.logger.warn(`设备端 jar ${remoteMd5 ? 'md5 不符（' + remoteMd5 + '）' : '不存在'}，重新推送 → ${remote}`);
    this.adb.push(local, remote);
    const after = this.adb.md5(remote);
    if (after !== expected) {
      fail('PUSH_FAILED', `推送后设备端 md5 校验失败：期望 ${expected}，实际 ${after || '读取失败'}；请确认 /sdcard 可写`);
    }
    this.logger.info(`jar 推送完成：${remote} md5=${expected}（${fs.statSync(local).size} 字节）`);
    return local;
  }

  _buildServerCommand(scidHex) {
    const q = this.quality;
    return `CLASSPATH=${this.remoteJar || this.config.deviceJarPath || REMOTE_JAR} app_process / com.genymobile.scrcpy.Server ${SCRCPY_VERSION} ${serverArgsTail(this.audioEnabled)} ` +
      `scid=${scidHex} max_size=${q.maxSize} max_fps=${q.maxFps} video_bit_rate=${q.bitRate} ` +
      'stay_awake=true cleanup=true power_on=false clipboard_autosync=false';
  }

  _startServer(scidHex) {
    const cmd = this._buildServerCommand(scidHex);
    this.logger.info(`启动 scrcpy server：adb -s ${this.adb.serial} shell "${cmd}"`);
    const proc = spawn(this.adb.adbPath, ['-s', this.adb.serial, 'shell', cmd], {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    this.serverProc = proc;
    this.serverOutput = [];
    this.serverExit = new Promise((resolve) => {
      proc.once('exit', (code, signal) => resolve({ code, signal }));
      proc.once('error', (e) => resolve({ code: -1, signal: null, error: e }));
    });
    const onLine = (line) => {
      if (!line.trim()) return;
      this.serverOutput.push(line);
      if (this.serverOutput.length > 80) this.serverOutput.shift();
      this.logger.info(`[scrcpy] ${line}`);
    };
    let so = '';
    let se = '';
    const feed = (which) => (chunk) => {
      const text = chunk.toString('utf8');
      if (which === 'out') so += text; else se += text;
      const split = (s) => {
        const parts = s.split(/\r?\n/);
        return [parts.slice(0, -1), parts[parts.length - 1]];
      };
      if (which === 'out') { const [lines, rest] = split(so); lines.forEach(onLine); so = rest; }
      else { const [lines, rest] = split(se); lines.forEach(onLine); se = rest; }
    };
    proc.stdout.on('data', feed('out'));
    proc.stderr.on('data', feed('se'));
    proc.once('exit', (code, signal) => {
      const last = this.serverOutput.slice(-6).join(' | ');
      if (this._stopping) {
        this.logger.info(`scrcpy server 已退出（code=${code}${signal ? ', signal=' + signal : ''}）`);
      } else if (this.running) {
        this.lastError = `scrcpy server 异常退出（code=${code}${signal ? ', signal=' + signal : ''}）${last ? '：' + last : ''}`;
        this.logger.error(this.lastError);
        this.running = false;
        this._destroyClients();
        // 异步清理，避免阻塞事件循环
        setTimeout(() => {
          this.stop('scrcpy server 异常退出').catch(() => {});
        }, 50);
      } else {
        this.logger.warn(`scrcpy server 启动阶段退出（code=${code}${signal ? ', signal=' + signal : ''}）${last ? '：' + last : ''}`);
      }
    });
    return proc;
  }

  _waitServerReady(timeoutMs = 12000) {
    if (!this.serverProc) return Promise.reject(new AgentError('SCRCPY_START_FAILED', '内部错误：server 未启动', 500));
    const proc = this.serverProc;
    const started = Date.now();
    return new Promise((resolve, reject) => {
      let done = false;
      let timer = null;
      let poll = null;
      const finish = (err) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        clearTimeout(poll);
        if (err) reject(err); else resolve();
      };
      const check = () => {
        if (done) return;
        const txt = this.serverOutput.join('\n');
        if (/(^|\n).*Device:\s*\[/.test(txt) || /INFO:\s*Device:/.test(txt)) return finish(null);
        if (/Address already in use|bind failed|Exception|Error:|ERROR:|Aborted|ClassNotFound|SIGABRT/i.test(txt)) {
          const detail = this.serverOutput.slice(-3).join(' | ');
          const busy = /Address already in use|bind failed/i.test(txt);
          return finish(new AgentError(busy ? 'SCRCPY_BUSY' : 'SCRCPY_START_FAILED',
            busy ? `设备端 abstract socket ${this.socketName} 已被占用，请稍后重试（${detail}）` : `scrcpy server 启动失败：${detail}`, busy ? 409 : 500));
        }
        if (proc.exitCode !== null || proc.killed) {
          const detail = this.serverOutput.slice(-6).join(' | ') || '无输出';
          return finish(new AgentError('SCRCPY_START_FAILED', `scrcpy server 启动后立即退出（code=${proc.exitCode}）：${detail}`, 500));
        }
        if (Date.now() - started > timeoutMs) {
          return finish(new AgentError('SCRCPY_START_FAILED', `scrcpy server 启动超时（${timeoutMs}ms），最后输出：${this.serverOutput.slice(-4).join(' | ') || '无'}`, 504));
        }
        poll = setTimeout(check, 100);
      };
      timer = setTimeout(() => finish(new AgentError('SCRCPY_START_FAILED', 'scrcpy server 启动超时', 504)), timeoutMs + 1000);
      check();
    });
  }

  async _stop(reason = '手动停止') {
    if (!this.running && !this.serverProc && !this.scidHex) {
      return { stopped: false, reason };
    }
    this._stopping = true;
    this.running = false;
    this.logger.info(`停止会话（${reason}）…`);
    this._destroyClients();
    try {
      await this._killServer();
    } catch (e) {
      this.logger.warn(`清理 server 进程时出错：${e.message}`);
    }
    if (this.scidHex) {
      try { this.adb.killScid(this.scidHex); } catch (_) { /* ignore */ }
    }
    if (this.forwardPort) { try { this.adb.forwardRemove(this.forwardPort); } catch (_) { /* ignore */ } }
    this.forwardPort = null;
    try { this.display.restore(reason); } catch (e) { this.logger.warn(`恢复显示失败：${e.message}`); }
    this.scidHex = null;
    this.socketName = null;
    this.videoStats = null;
    if (this.statsTimer) { clearInterval(this.statsTimer); this.statsTimer = null; }
    this._stopping = false;
    this.logger.info(`会话已停止（${reason}），显示参数已恢复`);
    return { stopped: true, reason };
  }

  _destroyClients() {
    for (const s of [this.videoClient, this.audioClient, this.controlClient, this.videoUpstream, this.audioUpstream, this.controlUpstream]) {
      if (s) { try { s.destroy(); } catch (_) { /* ignore */ } }
    }
    this.videoClient = null;
    this.audioClient = null;
    this.controlClient = null;
    this.videoUpstream = null;
    this.audioUpstream = null;
    this.controlUpstream = null;
    this.audioEnabled = true;
    this.audioBytes = 0;
    this.audioPackets = 0;
  }

  async _killServer() {
    const proc = this.serverProc;
    if (!proc) return;
    // 先精确杀设备端本会话 scid 的 server，让它自行结束 adb shell（避免误伤其他 adb.exe）
    if (this.scidHex) {
      try { this.adb.killScid(this.scidHex); } catch (_) { /* ignore */ }
    }
    if (this.serverExit) await Promise.race([this.serverExit, sleep(1500)]);
    if (proc.exitCode === null) {
      // 最后兜底：只终止我们自己 spawn 的 adb shell 子进程
      try { proc.kill('SIGTERM'); } catch (_) { /* ignore */ }
      if (this.serverExit) await Promise.race([this.serverExit, sleep(800)]);
    }
    if (proc.exitCode === null) {
      try { proc.kill('SIGKILL'); } catch (_) { /* ignore */ }
      if (this.serverExit) await Promise.race([this.serverExit, sleep(500)]);
    }
    this.serverProc = null;
    this.serverExit = null;
  }

  // ---------- App 连接 ----------

  _rejectHandshake(socket, code, message) {
    try { socket.write(`MUMULINK1 ERR ${code}\n`); } catch (_) { /* ignore */ }
    this.logger.warn(`握手拒绝：${code}${message ? ' ' + message : ''}`);
    setTimeout(() => { try { socket.destroy(); } catch (_) { /* ignore */ } }, 50);
  }

  _authToken(token) {
    try {
      return !!this.verifyToken(token);
    } catch (_) {
      return false;
    }
  }

  async handleVideoConnection(socket) {
    socket.setNoDelay(true);
    const reader = handshakeReader(socket);
    const { line, err } = await reader.promise;
    if (err) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_REQUEST', err === 'TIMEOUT' ? '握手超时（3s）' : '握手读取失败');
      return;
    }
    const m = line.match(/^MUMULINK1\s+(.*)$/);
    if (!m) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_REQUEST', '握手格式错误');
      return;
    }
    const token = m[1].trim();
    if (!this._authToken(token)) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_TOKEN', '配对令牌无效');
      return;
    }
    if (!this.running) {
      reader.takePending();
      this._rejectHandshake(socket, 'NO_SESSION', '代理当前没有运行中的会话');
      return;
    }
    if (this.videoClient) {
      reader.takePending();
      this._rejectHandshake(socket, 'SESSION_BUSY', '已有视频连接');
      return;
    }
    const pending = reader.takePending();
    this.videoClient = socket;
    this.logger.info(`接受视频连接：${socket.remoteAddress}:${socket.remotePort}`);
    socket.on('close', () => {
      if (this.videoClient === socket) {
        this.videoClient = null;
        this.logger.info('视频连接已断开');
        if (this.running && !this._stopping) {
          this.stop('App 视频连接断开').catch(() => {});
        }
      }
    });
    socket.on('error', (e) => this.logger.warn(`视频连接错误：${e.message}`));

    let upstream;
    try {
      upstream = await connectLocalRetry(this.forwardPort, 6000);
    } catch (e) {
      this.logger.error(`连接 scrcpy 视频上游失败：${e.message}`);
      this._rejectHandshake(socket, 'SCRCPY_START_FAILED', '连接设备视频流失败');
      if (this.videoClient === socket) this.videoClient = null;
      return;
    }
    if (this.videoClient !== socket) {
      try { upstream.destroy(); } catch (_) { /* ignore */ }
      return;
    }
    this.videoUpstream = upstream;
    this.logger.info('scrcpy 视频上游已连接');

    socket.write('MUMULINK1 OK\n');
    this.logger.info(`视频握手成功，开始推流（${socket.remoteAddress}）`);

    this.videoStats = new VideoStats(this.logger, this.scidHex);
    upstream.on('data', (chunk) => {
      try { this.videoStats.push(chunk); } catch (e) { this.logger.warn(`视频统计解析异常：${e.message}`); }
    });
    upstream.on('close', () => {
      if (this.videoUpstream === upstream) {
        this.videoUpstream = null;
        this.logger.warn('scrcpy 视频上游已关闭');
        if (this.running && !this._stopping) this.stop('视频上游关闭').catch(() => {});
      }
    });
    upstream.on('error', (e) => this.logger.warn(`视频上游错误：${e.message}`));

    // 设备 → 手机
    upstream.pipe(socket);
    // 手机 → 设备：丢弃（scrcpy 视频 socket 不使用反向数据）
    socket.on('data', () => {});
    if (pending.length) {
      try { upstream.write(pending); } catch (_) { /* ignore */ }
    }
    socket.resume();
  }

  async handleAudioConnection(socket) {
    socket.setNoDelay(true);
    const reader = handshakeReader(socket);
    const { line, err } = await reader.promise;
    if (err) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_REQUEST', err === 'TIMEOUT' ? '握手超时（3s）' : '握手读取失败');
      return;
    }
    const m = line.match(/^MUMULINK1\s+(.*)$/);
    if (!m) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_REQUEST', '握手格式错误');
      return;
    }
    if (!this._authToken(m[1].trim())) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_TOKEN', '配对令牌无效');
      return;
    }
    if (!this.running) {
      reader.takePending();
      this._rejectHandshake(socket, 'NO_SESSION', '代理当前没有运行中的会话');
      return;
    }
    if (!this.audioEnabled) {
      reader.takePending();
      this._rejectHandshake(socket, 'AUDIO_DISABLED', '本会话未启用音频，请在 App 设置里打开音频后重连');
      return;
    }
    if (!this.videoUpstream || !this.videoClient) {
      reader.takePending();
      this._rejectHandshake(socket, 'VIDEO_NOT_READY', '视频连接尚未建立');
      return;
    }
    if (this.audioClient) {
      reader.takePending();
      this._rejectHandshake(socket, 'SESSION_BUSY', '已有音频连接');
      return;
    }
    const pending = reader.takePending();
    this.audioClient = socket;
    this.logger.info(`接受音频连接：${socket.remoteAddress}:${socket.remotePort}`);
    socket.on('close', () => {
      if (this.audioClient === socket) {
        this.audioClient = null;
        this.logger.info('音频连接已断开（视频与控制不受影响）');
      }
    });
    socket.on('error', (e) => this.logger.warn(`音频连接错误：${e.message}`));

    let upstream;
    try {
      upstream = await connectLocalRetry(this.forwardPort, 6000);
    } catch (e) {
      this.logger.error(`连接 scrcpy 音频上游失败：${e.message}`);
      this._rejectHandshake(socket, 'SCRCPY_START_FAILED', '连接设备音频流失败');
      if (this.audioClient === socket) this.audioClient = null;
      return;
    }
    if (this.audioClient !== socket) {
      try { upstream.destroy(); } catch (_) { /* ignore */ }
      return;
    }
    this.audioUpstream = upstream;
    this.logger.info('scrcpy 音频上游已连接');
    socket.write('MUMULINK1 OK\n');
    this.logger.info('音频握手成功，开始转发 PCM（48kHz/立体声/16bit）');

    upstream.on('data', (chunk) => {
      this.audioBytes += chunk.length;
      this.audioPackets += 1;
    });
    upstream.on('close', () => {
      if (this.audioUpstream === upstream) {
        this.audioUpstream = null;
        this.logger.warn('scrcpy 音频上游已关闭');
      }
    });
    upstream.on('error', (e) => this.logger.warn(`音频上游错误：${e.message}`));

    upstream.pipe(socket, { end: false });
    socket.on('close', () => { try { upstream.destroy(); } catch (_) { /* ignore */ } });
    if (pending.length) {
      try { upstream.write(pending); } catch (_) { /* ignore */ }
    }
    socket.resume();
  }

  async handleControlConnection(socket) {
    socket.setNoDelay(true);
    const reader = handshakeReader(socket);
    const { line, err } = await reader.promise;
    if (err) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_REQUEST', err === 'TIMEOUT' ? '握手超时（3s）' : '握手读取失败');
      return;
    }
    const m = line.match(/^MUMULINK1\s+(.*)$/);
    if (!m) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_REQUEST', '握手格式错误');
      return;
    }
    const token = m[1].trim();
    if (!this._authToken(token)) {
      reader.takePending();
      this._rejectHandshake(socket, 'BAD_TOKEN', '配对令牌无效');
      return;
    }
    if (!this.running) {
      reader.takePending();
      this._rejectHandshake(socket, 'NO_SESSION', '代理当前没有运行中的会话');
      return;
    }
    if (!this.videoUpstream || !this.videoClient) {
      reader.takePending();
      this._rejectHandshake(socket, 'VIDEO_NOT_READY', '视频连接尚未建立');
      return;
    }
    if (this.controlClient) {
      reader.takePending();
      this._rejectHandshake(socket, 'SESSION_BUSY', '已有控制连接');
      return;
    }
    const pending = reader.takePending();
    this.controlClient = socket;
    this.logger.info(`接受控制连接：${socket.remoteAddress}:${socket.remotePort}`);
    socket.on('close', () => {
      if (this.controlClient === socket) {
        this.controlClient = null;
        this.logger.info('控制连接已断开');
        if (this.running && !this._stopping) {
          this.stop('App 控制连接断开').catch(() => {});
        }
      }
    });
    socket.on('error', (e) => this.logger.warn(`控制连接错误：${e.message}`));

    let upstream;
    try {
      upstream = await connectLocalRetry(this.forwardPort, 6000);
    } catch (e) {
      this.logger.error(`连接 scrcpy 控制上游失败：${e.message}`);
      this._rejectHandshake(socket, 'SCRCPY_START_FAILED', '连接设备控制通道失败');
      if (this.controlClient === socket) this.controlClient = null;
      return;
    }
    if (this.controlClient !== socket) {
      try { upstream.destroy(); } catch (_) { /* ignore */ }
      return;
    }
    this.controlUpstream = upstream;
    this.logger.info('scrcpy 控制上游已连接');
    socket.write('MUMULINK1 OK\n');
    this.logger.info('控制握手成功，开始双向透传');

    upstream.pipe(socket);
    socket.pipe(upstream);
    upstream.on('close', () => {
      if (this.controlUpstream === upstream) {
        this.controlUpstream = null;
        this.logger.info('scrcpy 控制上游已关闭');
      }
    });
    upstream.on('error', (e) => this.logger.warn(`控制上游错误：${e.message}`));
    if (pending.length) {
      try { upstream.write(pending); } catch (_) { /* ignore */ }
    }
    socket.resume();
  }

  // INJECT_TEXT（>300 字节截断）
  sendText(text) {
    if (!this.controlUpstream || this.controlUpstream.destroyed) {
      fail('CONTROL_NOT_READY', '控制通道未建立，无法发送文本');
    }
    const raw = Buffer.from(String(text == null ? '' : text), 'utf8');
    let payload = raw;
    if (raw.length > 300) {
      let end = 300;
      while (end > 0 && (raw[end] & 0xc0) === 0x80) end--;
      payload = raw.slice(0, end);
      this.logger.warn(`文本超过 300 字节，截断为 ${end} 字节`);
    }
    const buf = Buffer.alloc(5 + payload.length);
    buf.writeUInt8(1, 0); // TYPE_INJECT_TEXT
    buf.writeUInt32BE(payload.length, 1);
    payload.copy(buf, 5);
    this.controlUpstream.write(buf);
    this.logger.info(`已注入文本 ${payload.length} 字节：${text.length > 40 ? text.slice(0, 40) + '…' : text}`);
    return { bytes: payload.length, truncated: raw.length > 300 };
  }

  // SET_CLIPBOARD + PASTE（CJK 走这条；INJECT_TEXT 在设备端会被 KeyCharacterMap 丢弃）
  sendClipboard(text, opts = {}) {
    if (!this.controlUpstream || this.controlUpstream.destroyed) {
      fail('CONTROL_NOT_READY', '控制通道未建立，无法写入剪贴板');
    }
    const paste = opts.paste !== false;
    const sequence = (this._clipboardSeq = (this._clipboardSeq || 0) + 1);
    const { buf, bytes } = encodeSetClipboard(text, sequence, paste);
    this.controlUpstream.write(buf);
    this.logger.info(`[in] clipboard seq=${sequence} paste=${paste} len=${bytes}`);
    return { bytes, sequence, mode: 'clipboard' };
  }
}

module.exports = {
  SessionManager,
  VideoStats,
  DEFAULT_QUALITY,
  normalizeQuality,
  normalizeDisplay,
  scidRandom,
  encodeSetClipboard,
  TYPE_SET_CLIPBOARD,
  REMOTE_JAR,
  EXPECTED_MD5,
  SCRCPY_VERSION
};
