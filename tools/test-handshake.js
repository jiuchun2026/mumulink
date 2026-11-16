// TCP handshake gate test for MuMuLink agent.
// usage: node tools/test-handshake.js <host> <port> <token|"-"> <label>
const net = require('net');
const host = process.argv[2] || '127.0.0.1';
const port = parseInt(process.argv[3], 10);
const token = process.argv[4] === '-' ? null : process.argv[4];
const label = process.argv[5] || 'conn';

const s = net.connect(port, host);
s.setEncoding('utf8');
let got = '';
s.on('connect', () => {
  console.log(`[${label}] connected`);
  if (token !== null) s.write(`MUMULINK1 ${token}\n`);
});
s.on('data', (d) => { got += d; console.log(`[${label}] recv ${JSON.stringify(d.slice(0, 120))}`); });
s.on('error', (e) => console.log(`[${label}] error ${e.code || e.message}`));
s.on('close', () => console.log(`[${label}] closed`));
setTimeout(() => { console.log(`[${label}] timeout-got=${got.length}B`); s.destroy(); process.exit(0); }, 4000);
