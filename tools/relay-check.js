// End-to-end relay check: HTTP session start -> video socket handshake -> parse stream -> control socket handshake -> tap.
// usage: node tools/relay-check.js <httpBase e.g. http://192.168.1.10:27280> <videoPort> <controlPort> <token> [tap]
const net = require('net');
const http = require('http');
const { URL } = require('url');

const base = process.argv[2] || 'http://127.0.0.1:27280';
const vport = parseInt(process.argv[3] || '27282', 10);
const cport = parseInt(process.argv[4] || '27283', 10);
const token = process.argv[5] || '';
const doTap = process.argv[6] === 'tap';
const tapX = parseFloat(process.argv[7] || '0.5');
const tapY = parseFloat(process.argv[8] || '0.5');
const host = new URL(base).hostname;
const hport = parseInt(new URL(base).port || '80', 10);

function req(method, path, body, tok) {
  return new Promise((resolve, reject) => {
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const r = http.request({ host, port: hport, path, method, timeout: 15000, headers: {
      'Content-Type': 'application/json',
      ...(tok ? { 'X-MuMuLink-Token': tok } : {}),
      ...(data ? { 'Content-Length': data.length } : {}),
    } }, (res) => {
      let buf = '';
      res.setEncoding('utf8');
      res.on('data', (d) => buf += d);
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(buf); } catch (_) { /* ignore */ }
        resolve({ status: res.statusCode, json, raw: buf });
      });
    });
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(new Error('http timeout')); });
    if (data) r.write(data);
    r.end();
  });
}

function handshake(port, tok, label) {
  return new Promise((resolve, reject) => {
    const s = net.connect(port, host);
    s.setNoDelay(true);
    let stage = 'auth';
    let acc = Buffer.alloc(0);
    const timer = setTimeout(() => reject(new Error(`${label} handshake timeout`)), 8000);
    s.on('connect', () => {
      s.write(`MUMULINK1 ${tok}\n`);
    });
    s.on('error', (e) => { clearTimeout(timer); reject(e); });
    s.on('data', (d) => {
      acc = Buffer.concat([acc, d]);
      if (stage === 'auth') {
        const nl = acc.indexOf(0x0a);
        if (nl < 0) return;
        const line = acc.subarray(0, nl).toString('utf8');
        acc = acc.subarray(nl + 1);
        if (!line.startsWith('MUMULINK1 OK')) { clearTimeout(timer); reject(new Error(`${label} auth failed: ${line}`)); return; }
        stage = 'data';
        clearTimeout(timer);
        resolve({ sock: s, rest: acc });
      }
    });
  });
}

function readStream(rest, sock, durMs) {
  return new Promise((resolve) => {
    let buf = rest;
    let meta = null, frames = 0, config = 0, key = 0, bytes = 0, first = null;
    const t0 = Date.now();
    const need = (n) => buf.length >= n;
    const take = (n) => { const b = buf.subarray(0, n); buf = buf.subarray(n); return b; };
    let stage = 'codec';
    function pump() {
      for (;;) {
        if (stage === 'codec') {
          if (!need(4)) return;
          first = take(4).readUInt32BE(0);
          stage = 'meta';
          continue;
        }
        if (stage === 'meta') {
          if (!need(12)) return;
          const b = take(12);
          meta = { flags: b.readUInt32BE(0), w: b.readUInt32BE(4), h: b.readUInt32BE(8) };
          stage = 'frames';
          continue;
        }
        if (!need(12)) return;
        const hdr = take(12);
        const pf = hdr.readBigUInt64BE(0);
        const size = hdr.readUInt32BE(8);
        if (!need(size)) { buf = Buffer.concat([hdr, buf]); return; }
        take(size);
        frames++; bytes += size;
        if ((pf & (1n << 62n)) !== 0n) config++;
        if ((pf & (1n << 61n)) !== 0n) key++;
      }
    }
    sock.on('data', (d) => { buf = Buffer.concat([buf, d]); pump(); });
    setTimeout(() => {
      const dt = (Date.now() - t0) / 1000;
      resolve({ codecId: first ? '0x' + first.toString(16) : null, meta, frames, config, key, bytes, kbps: Math.round(bytes * 8 / 1000 / dt), fps: Math.round(Math.max(0, frames - config) / dt) });
    }, durMs);
  });
}

function i32(v) { const b = Buffer.alloc(4); b.writeInt32BE(v); return b; }
function i64(v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); return b; }
function u16(v) { const b = Buffer.alloc(2); b.writeUInt16BE(v); return b; }
function u8(v) { const b = Buffer.alloc(1); b.writeUInt8(v); return b; }
function touch(meta, action, pid, x, y, buttons) {
  return Buffer.concat([u8(2), u8(action), i64(pid), i32(Math.round(x)), i32(Math.round(y)), u16(meta.w), u16(meta.h), u16(0xffff), i32(buttons), i32(buttons)]);
}

(async () => {
  const t0 = Date.now();
  const quality = { maxSize: 720, maxFps: 30, bitRate: 1500000 };
  console.log('1) POST /api/session/start', JSON.stringify(quality));
  const st = await req('POST', '/api/session/start', { quality, display: { mode: 'fit', width: 1440, height: 2560, density: 360 } }, token);
  console.log('   ->', st.status, JSON.stringify(st.json));
  if (st.status !== 200 || !st.json || !st.json.ok) { console.error('FAIL session start'); process.exit(1); }

  console.log('2) video handshake');
  const v = await handshake(vport, token, 'video');
  console.log('   OK');

  console.log('3) control handshake');
  const c = await handshake(cport, token, 'control');
  console.log('   OK');

  console.log('4) read stream 3000ms');
  const stats = await readStream(v.rest, v.sock, 3000);
  console.log('   ->', JSON.stringify(stats));

  if (doTap && stats.meta) {
    console.log('5) tap center via control');
    const m = stats.meta;
    c.sock.write(touch(m, 0, 0, m.w / 2, m.h / 2, 1));
    setTimeout(() => c.sock.write(touch(m, 1, 0, m.w / 2, m.h / 2, 0)), 90);
    await new Promise((r) => setTimeout(r, 600));
  }

  v.sock.destroy(); c.sock.destroy();
  console.log('6) POST /api/session/stop');
  const sp = await req('POST', '/api/session/stop', {}, token);
  console.log('   ->', sp.status, JSON.stringify(sp.json));

  const ok = stats.meta && stats.frames > 3 && stats.config >= 1 && stats.key >= 1;
  console.log(JSON.stringify({ RESULT: ok ? 'PASS' : 'FAIL', tookMs: Date.now() - t0, stats }));
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error('ERROR', e.message); process.exit(2); });
