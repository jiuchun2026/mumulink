// touch-probe.js — 直连代理的 27182/27183，用**裸控制消息**发一次点击，
// 用来把「App 的触摸链路」与「代理/scrcpy 服务端」分开定位。
//
// 用法：
//   node tools/touch-probe.js <token> <xNorm> <yNorm> [pointerId] [gapMs] [actionButton]
// 例：
//   node tools/touch-probe.js 63504f... 0.5 0.5            # 默认 pointerId=-2, gap=90ms, actionButton=0
//   node tools/touch-probe.js 63504f... 0.5 0.5 44 1 1     # 模拟 App 当前参数
//
// 前提：App 必须已断开（scrcpy 服务端只接受一对 video/control 连接）。
// 需要先用 HTTP /api/session/start 起好会话。
'use strict';
const net = require('net');

const HOST = process.env.MUMULINK_HOST || '192.168.1.10';
const VIDEO_PORT = Number(process.env.MUMULINK_VIDEO_PORT || 27182);
const CONTROL_PORT = Number(process.env.MUMULINK_CONTROL_PORT || 27183);

const token = process.argv[2] || '';
const mode = process.argv[3] === 'key' ? 'key' : 'touch';
const nx = parseFloat(process.argv[3] || '0.5');
const ny = parseFloat(process.argv[4] || '0.5');
const pointerId = BigInt(process.argv[5] || '-2');
const gapMs = parseInt(process.argv[6] || '90', 10);
const actionButtonMode = parseInt(process.argv[7] || '0', 10); // 0=只在 DOWN 发按钮位, 1=App 旧行为
const keycode = parseInt(process.argv[4] || '4', 10);

if (!token) {
  console.error('用法: node tools/touch-probe.js <token> <xNorm> <yNorm> [pointerId] [gapMs] [actionButton]');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function handshake(sock) {
  return new Promise((resolve, reject) => {
    let line = '';
    const onData = (d) => {
      line += d.toString('latin1');
      const i = line.indexOf('\n');
      if (i >= 0) {
        sock.removeListener('data', onData);
        const text = line.slice(0, i);
        if (text === 'MUMULINK1 OK') resolve(text);
        else reject(new Error('握手失败: ' + text));
      }
    };
    sock.on('data', onData);
    sock.on('error', reject);
    sock.write(`MUMULINK1 ${token}\n`);
  });
}

function readExactly(sock, n) {
  return new Promise((resolve, reject) => {
    let buf = Buffer.alloc(0);
    const onData = (d) => {
      buf = Buffer.concat([buf, d]);
      if (buf.length >= n) {
        sock.removeListener('data', onData);
        // 剩余字节属于后续帧，本探针只用一次，丢掉也无妨
        resolve(buf.subarray(0, n));
      }
    };
    sock.on('data', onData);
    sock.on('error', reject);
  });
}

function connect(port) {
  return new Promise((resolve, reject) => {
    const s = net.connect({ host: HOST, port }, () => resolve(s));
    s.setNoDelay(true);
    s.on('error', reject);
  });
}

function touchMessage(action, x, y, w, h, actionButton, buttons) {
  const b = Buffer.alloc(32);
  let o = 0;
  b.writeUInt8(2, o); o += 1; // TYPE_INJECT_TOUCH_EVENT
  b.writeUInt8(action, o); o += 1;
  b.writeBigInt64BE(pointerId, o); o += 8;
  b.writeInt32BE(x, o); o += 4;
  b.writeInt32BE(y, o); o += 4;
  b.writeUInt16BE(w & 0xffff, o); o += 2;
  b.writeUInt16BE(h & 0xffff, o); o += 2;
  b.writeUInt16BE(0xffff, o); o += 2;
  b.writeInt32BE(actionButton, o); o += 4;
  b.writeInt32BE(buttons, o); o += 4;
  return b;
}

(async () => {
  const video = await connect(VIDEO_PORT);
  await handshake(video);
  const codec = await readExactly(video, 4);
  const meta = await readExactly(video, 12);
  const codecId = codec.readUInt32BE(0);
  if (codecId !== 0x68323634) throw new Error('codec id 不是 H.264: 0x' + codecId.toString(16));
  const w = meta.readInt32BE(4);
  const h = meta.readInt32BE(8);

  const control = await connect(CONTROL_PORT);
  await handshake(control);
  await sleep(150);

  const x = Math.round(nx * (w - 1));
  const y = Math.round(ny * (h - 1));
  const downActionButton = actionButtonMode === 1 ? 1 : 0;
  const down = touchMessage(0, x, y, w, h, downActionButton, 1);
  const up = touchMessage(1, x, y, w, h, 0, 0);
  control.write(down);
  await sleep(gapMs);
  control.write(up);
  await sleep(150);

  console.log(JSON.stringify({ meta: { w, h }, x, y, pointerId: pointerId.toString(), gapMs, actionButtonMode }));
  video.destroy();
  control.destroy();
  process.exit(0);
})().catch((e) => {
  console.error('探针失败: ' + e.message);
  process.exit(1);
});
