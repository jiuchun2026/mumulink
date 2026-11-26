'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const AGENT_VERSION = '1.0.0';
const DEFAULT_CONFIG_PATH = path.join(__dirname, '..', 'config.json');

const DEFAULTS = {
  name: '客厅电脑',
  httpPort: 27180,
  udpPort: 27181,
  videoPort: 27182,
  controlPort: 27183,
  audioPort: 27184,
  audio: { enabled: true, codec: 'raw' },
  gameMode: { maxSize: 1080, maxFps: 90, bitRate: 12000000 },
  adbForwardPort: 28182,
  adb: 'D:/app/Android/Sdk/platform-tools/adb.exe',
  device: '127.0.0.1:16384',
  scrcpyJar: 'tools/scrcpy-server.jar',
  scrcpyJarMd5: '55a1ef0a811f1cb0d417084b70e0a630',
  deviceJarPath: '/sdcard/mumulink/scrcpy.bin',
  mumuManager: 'D:/app/MuMuPlayer/nx_main/mumu-cli.exe',
  pairingCode: '',
  token: '',
  requirePairing: true
};

function randomPairingCode() {
  return String(crypto.randomInt(0, 1000000)).padStart(6, '0');
}

function randomToken() {
  return crypto.randomBytes(32).toString('hex');
}

function validPort(n) {
  return Number.isInteger(n) && n > 0 && n < 65536;
}

function loadConfig(opts = {}) {
  const file = path.resolve(opts.configPath || DEFAULT_CONFIG_PATH);
  let onDisk = {};
  let created = false;
  if (fs.existsSync(file)) {
    try {
      onDisk = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (err) {
      throw new Error(`配置文件 ${file} 不是合法 JSON：${err.message}`);
    }
  } else {
    created = true;
    fs.mkdirSync(path.dirname(file), { recursive: true });
  }

  const cfg = Object.assign({}, DEFAULTS, onDisk);
  let dirty = created;
  if (!cfg.pairingCode) {
    cfg.pairingCode = randomPairingCode();
    dirty = true;
  }
  if (typeof cfg.token !== 'string') cfg.token = '';
  cfg.requirePairing = cfg.requirePairing !== false;

  for (const k of ['httpPort', 'udpPort', 'videoPort', 'controlPort', 'adbForwardPort']) {
    cfg[k] = Number(cfg[k]);
    if (!validPort(cfg[k])) throw new Error(`config.${k} 不是合法端口：${cfg[k]}`);
  }
  const ports = [cfg.httpPort, cfg.udpPort, cfg.videoPort, cfg.controlPort, cfg.adbForwardPort];
  if (new Set(ports).size !== ports.length) throw new Error('config 中 httpPort/udpPort/videoPort/controlPort/adbForwardPort 不能重复');

  if (dirty) saveConfig(file, cfg);
  return { config: cfg, configPath: file, created };
}

function saveConfig(file, cfg) {
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, file);
}

function findScrcpyJar(config, repoRoot) {
  const candidates = [
    path.resolve(repoRoot, config.scrcpyJar || ''),
    path.resolve(repoRoot, 'tools', 'scrcpy-server.jar'),
    path.resolve(repoRoot, 'tools', 'scrcpy-server-v5.0.1'),
    path.resolve(repoRoot, '..', 'artifacts', 'scrcpy-server-v5.0.1')
  ];
  for (const c of candidates) {
    try {
      const st = fs.statSync(c);
      if (st.isFile() && st.size > 100000) return c;
    } catch (_) { /* 忽略 */ }
  }
  return null;
}

module.exports = {
  AGENT_VERSION,
  DEFAULT_CONFIG_PATH,
  DEFAULTS,
  loadConfig,
  saveConfig,
  randomToken,
  randomPairingCode,
  findScrcpyJar
};
