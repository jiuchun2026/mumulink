// Probe: connect to a scrcpy-server video socket (tunnel_forward via adb forward)
// and decode the wire format, dumping H.264 elementary stream.
// Usage: node scrcpy-probe.js <port> <out.h264> [durationMs]
const net = require('net');
const fs = require('fs');

const port = parseInt(process.argv[2] || '27183', 10);
const outPath = process.argv[3] || 'probe.h264';
const durationMs = parseInt(process.argv[4] || '4000', 10);

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
      } else if (this.err) {
        this.waiters.shift();
        w.reject(this.err);
      } else if (this.ended) {
        this.waiters.shift();
        w.reject(new Error('socket closed while waiting for ' + w.n + ' bytes (have ' + this.buf.length + ')'));
      } else break;
    }
  }
  read(n) {
    return new Promise((resolve, reject) => {
      this.waiters.push({ n, resolve, reject });
      this._flush();
    });
  }
}

function u32(b, o = 0) { return b.readUInt32BE(o); }
function i32(b, o = 0) { return b.readInt32BE(o); }

(async () => {
  const sock = net.connect(port, '127.0.0.1');
  const r = new Reader(sock);
  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  console.log('connected to 127.0.0.1:' + port);

  const dummy = await r.read(1);
  console.log('dummy byte:', dummy[0]);

  const nameBuf = await r.read(64);
  const name = nameBuf.toString('utf8').replace(/\0+$/, '');
  console.log('device name:', JSON.stringify(name));

  const codecId = u32(await r.read(4));
  console.log('codec id: 0x' + codecId.toString(16), codecId === 0x68323634 ? '(h264)' :
    codecId === 0x68323635 ? '(h265)' : codecId === 0x00616176 ? '(av1)' : '(?)');
  if (codecId === 0) { console.log('server disabled stream'); process.exit(2); }

  const meta = await r.read(12);
  const flags = i32(meta, 0), width = i32(meta, 4), height = i32(meta, 8);
  console.log('session meta: flags=0x' + (flags >>> 0).toString(16), 'size=' + width + 'x' + height,
    'isSession=' + ((flags & 0x80000000) !== 0), 'clientResize=' + (flags & 1));

  const out = fs.createWriteStream(outPath);
  let packets = 0, mediaPackets = 0, configPackets = 0, keyFrames = 0, totalBytes = 0;
  let firstPts = -1, lastPts = -1;
  const started = Date.now();
  const sizes = [];
  while (Date.now() - started < durationMs) {
    let hdr;
    try { hdr = await r.read(12); } catch (e) { console.log('read end:', e.message); break; }
    const ptsAndFlags = hdr.readBigUInt64BE(0);
    const size = u32(hdr, 8);
    let payload;
    try { payload = await r.read(size); } catch (e) { console.log('payload end:', e.message); break; }
    packets++;
    const isSession = (ptsAndFlags & (1n << 63n)) !== 0n;
    const isConfig = (ptsAndFlags & (1n << 62n)) !== 0n;
    const isKey = (ptsAndFlags & (1n << 61n)) !== 0n;
    if (isSession) { console.log('  [session] size=' + size + ' data=' + payload.toString('hex')); continue; }
    mediaPackets++;
    if (isConfig) configPackets++;
    if (isKey) keyFrames++;
    const pts = Number(ptsAndFlags & ((1n << 62n) - 1n));
    if (firstPts < 0) firstPts = pts;
    lastPts = pts;
    totalBytes += size;
    if (sizes.length < 12) sizes.push(size);
    out.write(payload);
    if (packets <= 12) {
      console.log(`  pkt#${packets} size=${size} pts=${pts} config=${isConfig} key=${isKey} head=${payload.subarray(0, 12).toString('hex')}`);
    }
  }
  const elapsed = (Date.now() - started) / 1000;
  out.end();
  console.log('---- summary ----');
  console.log('packets=' + packets, 'media=' + mediaPackets, 'config=' + configPackets, 'keyframes=' + keyFrames);
  console.log('bytes=' + totalBytes + ' (' + (totalBytes * 8 / elapsed / 1000).toFixed(0) + ' kbps)');
  console.log('pts span=' + ((lastPts - firstPts) / 1e6).toFixed(3) + 's over wall ' + elapsed.toFixed(2) + 's');
  console.log('first sizes:', sizes.join(','));
  console.log('wrote ' + outPath);
  sock.destroy();
  process.exit(0);
})().catch(e => { console.error('FAIL:', e); process.exit(1); });
