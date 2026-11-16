'use strict';

const dgram = require('dgram');
const os = require('os');

// IPv4 工具
function v4ToInt(ip) {
  return ip.split('.').reduce((acc, n) => ((acc << 8) + Number(n)) >>> 0, 0);
}
function intToV4(n) {
  return [ (n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255 ].join('.');
}
function broadcastOf(addr, netmask) {
  try {
    const a = v4ToInt(addr);
    const m = v4ToInt(netmask);
    return intToV4((a & m) | (~m >>> 0));
  } catch (_) {
    return null;
  }
}

const VIRTUAL_RE = /vmware|virtualbox|hyper-v|vethernet|loopback|tailscale|zerotier|wsl|docker/i;

function listIPv4() {
  const all = [];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const it of ifaces[name] || []) {
      if (it.family !== 'IPv4' && it.family !== 4) continue;
      if (it.internal) continue;
      if (it.address.startsWith('169.254.')) continue;
      all.push({ name, address: it.address, netmask: it.netmask, virtual: VIRTUAL_RE.test(name) });
    }
  }
  return all;
}

function pickPrimary(list) {
  const real = list.filter((x) => !x.virtual);
  const rank = (x) => {
    if (x.address.startsWith('192.168.')) return 0;
    if (/^10\./.test(x.address)) return 1;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(x.address)) return 2;
    return 3;
  };
  const pool = real.length ? real : list;
  return pool.slice().sort((a, b) => rank(a) - rank(b))[0] || null;
}

class Discovery {
  constructor({ config, logger, agentVersion }) {
    this.config = config;
    this.logger = logger;
    this.agentVersion = agentVersion;
    this.socket = null;
    this.timer = null;
    this.ips = [];
    this.host = null;
    this.broadcasts = [];
    this.paired = false;
    this.stopped = true;
  }

  payload() {
    const p = {
      magic: 'MUMULINK',
      v: 1,
      name: this.config.name,
      host: this.host || '127.0.0.1',
      httpPort: this.config.httpPort,
      videoPort: this.config.videoPort,
      controlPort: this.config.controlPort,
      paired: !!this.paired,
      agentVersion: this.agentVersion
    };
    if (this.ips.length) p.ips = this.ips.slice();
    return p;
  }

  start() {
    this.stopped = false;
    const list = listIPv4();
    this.ips = list.map((x) => x.address);
    const primary = pickPrimary(list);
    this.host = primary ? primary.address : '127.0.0.1';
    const bc = new Set(['255.255.255.255']);
    for (const it of list) {
      const b = broadcastOf(it.address, it.netmask);
      if (b && b !== '255.255.255.255') bc.add(b);
    }
    this.broadcasts = [...bc];
    this.logger.info(`UDP 发现：host=${this.host} ips=[${this.ips.join(', ')}] 广播地址=[${this.broadcasts.join(', ')}]`);

    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.socket = sock;
    sock.on('error', (e) => {
      this.logger.warn(`UDP 发现 socket 错误：${e.message}`);
    });
    sock.on('message', (msg, rinfo) => {
      const text = msg.toString('utf8').trim();
      if (!text.includes('MUMULINK_DISCOVER')) return;
      const data = Buffer.from(JSON.stringify(this.payload()), 'utf8');
      sock.send(data, rinfo.port, rinfo.address, (err) => {
        if (err) this.logger.warn(`回复发现探测失败（${rinfo.address}:${rinfo.port}）：${err.message}`);
        else this.logger.info(`收到发现探测 ${rinfo.address}:${rinfo.port}，已单播回复`);
      });
    });
    sock.bind(this.config.udpPort, '0.0.0.0', () => {
      try { sock.setBroadcast(true); } catch (e) { this.logger.warn(`开启 UDP 广播失败：${e.message}`); }
      this.logger.info(`UDP 发现已监听 0.0.0.0:${this.config.udpPort}`);
      this._sendAll();
      this.timer = setInterval(() => this._sendAll(), 2000);
      this.timer.unref && this.timer.unref();
    });
  }

  _sendAll() {
    if (this.stopped || !this.socket) return;
    const data = Buffer.from(JSON.stringify(this.payload()), 'utf8');
    for (const addr of this.broadcasts) {
      this.socket.send(data, this.config.udpPort, addr, (err) => {
        if (err && !this._warned) {
          this._warned = true;
          this.logger.warn(`UDP 广播到 ${addr} 失败：${err.message}（不影响局域网主动探测）`);
        }
      });
    }
  }

  setPaired(v) { this.paired = !!v; }

  stop() {
    this.stopped = true;
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
    if (this.socket) {
      try { this.socket.close(); } catch (_) { /* ignore */ }
      this.socket = null;
    }
  }
}

module.exports = { Discovery, listIPv4, pickPrimary, broadcastOf };
