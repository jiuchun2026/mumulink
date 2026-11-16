// scrcpy 5.0.1 two-socket probe: video + control, verifies stream and a tap injection.
// usage: node tools/probe3.js <fwdPort> <out.h264> <durationMs>
const net = require('net');
const fs = require('fs');

const port = parseInt(process.argv[2] || '27194', 10);
const out = process.argv[3] || 'tools/probe3.h264';
const dur = parseInt(process.argv[4] || '3000', 10);
const tapX = process.argv[5] !== undefined ? parseFloat(process.argv[5]) : null;
const tapY = process.argv[6] !== undefined ? parseFloat(process.argv[6]) : null;

let meta = null;
let videoSock = null;
let ctrlSock = null;
const chunks = [];
let frames = 0, config = 0, key = 0, bytes = 0;
const t0 = Date.now();

function connectVideo() {
  videoSock = net.connect(port, '127.0.0.1');
  videoSock.setNoDelay(true);
  videoSock.on('connect', () => {
    console.log('[video] connected');
    // server accepts video then control; open control immediately after
    setTimeout(connectControl, 150);
  });
  videoSock.on('error', (e) => console.error('[video] error', e.message));

  let buf = Buffer.alloc(0), stage = 'codec';
  const need = (n) => buf.length >= n;
  const take = (n) => { const b = buf.subarray(0, n); buf = buf.subarray(n); return b; };

  videoSock.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    for (;;) {
      if (stage === 'codec') {
        if (!need(4)) return;
        console.log('[video] codec 0x' + take(4).readUInt32BE(0).toString(16));
        stage = 'meta';
        continue;
      }
      if (stage === 'meta') {
        if (!need(12)) return;
        const b = take(12);
        meta = { flags: b.readUInt32BE(0), w: b.readUInt32BE(4), h: b.readUInt32BE(8) };
        console.log('[video] meta ' + JSON.stringify(meta));
        stage = 'frames';
        continue;
      }
      if (stage === 'frames') {
        if (!need(12)) return;
        const hdr = take(12);
        const ptsFlags = hdr.readBigUInt64BE(0);
        const size = hdr.readUInt32BE(8);
        const isConfig = (ptsFlags & (1n << 62n)) !== 0n;
        const isKey = (ptsFlags & (1n << 61n)) !== 0n;
        if (!need(size)) { buf = Buffer.concat([hdr, buf]); return; }
        const payload = take(size);
        frames++; bytes += size;
        if (isConfig) config++;
        if (isKey) key++;
        if (!isConfig && frames < 4) console.log('[video] frame#' + frames + ' key=' + isKey + ' ' + size + 'B');
        chunks.push(payload);
      }
    }
  });
}

function connectControl() {
  ctrlSock = net.connect(port, '127.0.0.1');
  ctrlSock.setNoDelay(true);
  ctrlSock.on('connect', () => {
    console.log('[control] connected');
    setTimeout(() => {
      if (!meta) { console.error('[control] no meta yet, skip tap'); return; }
      if (tapX !== null && tapY !== null) tap(tapX, tapY);
      else tap(meta.w / 2, meta.h / 2);
    }, 400);
  });
  ctrlSock.on('error', (e) => console.error('[control] error', e.message));
  ctrlSock.on('data', (d) => console.log('[control] device msg ' + d.length + 'B ' + d.subarray(0, 8).toString('hex')));
}

function u8(v) { const b = Buffer.alloc(1); b.writeUInt8(v); return b; }
function i32(v) { const b = Buffer.alloc(4); b.writeInt32BE(v); return b; }
function i64(v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); return b; }
function u16(v) { const b = Buffer.alloc(2); b.writeUInt16BE(v); return b; }
function pos(x, y) {
  return Buffer.concat([i32(Math.round(x)), i32(Math.round(y)), u16(meta.w), u16(meta.h)]);
}
function touch(action, pointerId, x, y, buttons) {
  // type=2 action u8 pointerId i64 Position pressure u16 actionButton i32 buttons i32
  return Buffer.concat([u8(2), u8(action), i64(pointerId), pos(x, y), u16(0xffff), i32(buttons), i32(buttons)]);
}
function tap(x, y) {
  console.log('[control] tap at ' + Math.round(x) + ',' + Math.round(y));
  ctrlSock.write(touch(0, 0, x, y, 1));
  setTimeout(() => ctrlSock.write(touch(1, 0, x, y, 0)), 90);
}

connectVideo();

setTimeout(() => {
  const dt = (Date.now() - t0) / 1000;
  fs.writeFileSync(out, Buffer.concat(chunks));
  console.log(JSON.stringify({ meta, frames, config, key, bytes, kbps: Math.round(bytes * 8 / 1000 / dt), fps: Math.round((frames - config) / dt) }));
  try { videoSock && videoSock.destroy(); } catch (_) {}
  try { ctrlSock && ctrlSock.destroy(); } catch (_) {}
  process.exit(0);
}, dur);
