'use strict';
// 打印本机用于配对的主 IPv4 与 HTTP 端口：192.168.1.10:27180
const fs = require('fs');
const os = require('os');
const path = require('path');

function isVirtual(name) {
  return /vmware|virtualbox|hyper-v|vethernet|loopback|docker|wsl|tap|tun/i.test(name || '');
}
function listIPv4() {
  const out = [];
  const ifaces = os.networkInterfaces();
  for (const [name, addrs] of Object.entries(ifaces)) {
    for (const a of addrs || []) {
      if (a.family === 'IPv4' && !a.internal) out.push({ name, address: a.address });
    }
  }
  return out;
}
function pickPrimary() {
  const all = listIPv4();
  const real = all.filter((x) => !isVirtual(x.name));
  const preferred = real.find((x) => /^10\./.test(x.address)) || real.find((x) => /^192\.168\./.test(x.address)) || real[0];
  return preferred || all[0] || { address: '127.0.0.1', name: 'loopback' };
}

let port = 27180;
try {
  const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8'));
  if (cfg.httpPort) port = cfg.httpPort;
} catch (_) { /* 首次运行尚无 config，用默认端口 */ }

process.stdout.write(`${pickPrimary().address}:${port}\n`);
