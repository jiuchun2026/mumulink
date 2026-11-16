// Dev probe for MuMuLink video stream format (send_device_meta=false send_dummy_byte=false).
// Usage: node test-agent-probe.js <port> [durationMs]
const net = require('net');

const port = parseInt(process.argv[2] || '27282', 10);
const durationMs = parseInt(process.argv[3] || '3000', 10);

class Reader {
  constructor(sock) {
    this.sock = sock;
    this.buf = Buffer.alloc(0);
    this.waiters = [];
    this.err = null;
    this.ended = false;
    sock.on('data', d => { this.buf = Buffer.concat([this.buf, d]); this._flush(); });
    sock.on('error', e => { this.err = e; this._flush(); });
    sock.on('close', () => { this.ended = true; this._flush(); });
  }
  _flush() {
    while (this.waiters.length) {
      const w = this.waiters[0];
      if (this.buf.length >= w.n) {
        const out = this.buf.subarray(0, w.n);
        this.buf = this.buf.subarray(w.n);
        this.waiters.shift();
        w.resolve(out);
      } else if (this.err || this.ended) {
        this.waiters.shift();
        w.reject(new Error('socket ended waiting ' + w.n + ' (have ' + this.buf.length + ')'));
      } else break;
    }
  }
  read(n) {
    return new Promise((resolve, reject) => { this.waiters.push({ n, resolve, reject }); this._flush(); });
  }
}

(async () => {
  const sock = net.connect(port, '127.0.0.1');
  const r = new Reader(sock);
  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  console.log('connected ' + port + ' (raw, no dummy/name)');
  const codec = (await r.read(4)).readUInt32BE(0);
  console.log('first u32 = 0x' + codec.toString(16), codec === 0x68323634 ? '(h264 OK)' : '(UNEXPECTED)');
  const meta = await r.read(12);
  const flags = meta.readUInt32BE(0), w = meta.readInt32BE(4), h = meta.readInt32BE(8);
  console.log('meta flags=0x' + flags.toString(16), 'size=' + w + 'x' + h);
  const started = Date.now();
  let media = 0, key = 0, cfg = 0, bytes = 0, sess = 0, firstPts = -1, lastPts = -1;
  while (Date.now() - started < durationMs) {
    const hdr = await r.read(12);
    const ptsf = hdr.readBigUInt64BE(0);
    const size = hdr.readUInt32BE(8);
    const payload = await r.read(size);
    const isSession = (ptsf & (1n << 63n)) !== 0n;
    const isConfig = (ptsf & (1n << 62n)) !== 0n;
    const isKey = (ptsf & (1n << 61n)) !== 0n;
    if (isSession) { sess++; console.log('session pkt size=' + size + ' hex=' + payload.toString('hex')); continue; }
    media++; bytes += size; if (isConfig) cfg++; if (isKey) key++;
    const pts = Number(ptsf & ((1n << 62n) - 1n));
    if (firstPts < 0) firstPts = pts;
    lastPts = pts;
    if (media <= 6) console.log(`pkt#${media} size=${size} pts=${pts} key=${isKey} cfg=${isConfig} head=${payload.subarray(0, 8).toString('hex')}`);
  }
  const elapsed = (Date.now() - started) / 1000;
  console.log(`SUMMARY media=${media} key=${key} cfg=${cfg} session=${sess} size=${w}x${h} fps=${(media / elapsed).toFixed(1)} kbps=${(bytes * 8 / elapsed / 1000).toFixed(0)} ptsSpan=${((lastPts - firstPts) / 1e6).toFixed(2)}s`);
  sock.destroy();
  process.exit(0);
})().catch(e => { console.error('PROBE FAIL:', e.message); process.exit(1); });
