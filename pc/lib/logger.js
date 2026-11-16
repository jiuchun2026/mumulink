'use strict';

const fs = require('fs');

// 环形日志 + 控制台输出（SPEC 3.6）
const MAX_LINES = 1200;

function pad(n, w = 2) {
  return String(n).padStart(w, '0');
}

function timeStr(d = new Date()) {
  return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + '.' + pad(d.getMilliseconds(), 3);
}

class Logger {
  constructor({ maxLines = MAX_LINES, echo = true, file = process.env.MUMULINK_LOG || '' } = {}) {
    this.maxLines = maxLines;
    this.echo = echo;
    this.file = file || '';
    this.seq = 0;
    this.lines = []; // {seq, ts, level, msg, text}
  }

  _add(level, msg) {
    const ts = new Date().toISOString();
    const time = timeStr(new Date(ts));
    const entry = { seq: ++this.seq, ts, time, level, msg: String(msg), text: `[${time}] ${level} ${msg}` };
    this.lines.push(entry);
    if (this.lines.length > this.maxLines) this.lines.splice(0, this.lines.length - this.maxLines);
    if (this.echo) {
      const fn = level === 'ERROR' ? console.error : level === 'WARN' ? console.warn : console.log;
      fn(entry.text);
    }
    if (this.file) {
      try { fs.appendFileSync(this.file, entry.text + '\n', 'utf8'); } catch (e) { /* 日志不可写不影响主流程 */ }
    }
    return entry;
  }

  info(msg) { return this._add('INFO', msg); }
  warn(msg) { return this._add('WARN', msg); }
  error(msg) { return this._add('ERROR', msg); }

  since(seq) {
    const n = Number.isFinite(seq) ? seq : 0;
    return this.lines.filter((e) => e.seq > n).map((e) => e.text);
  }

  tail(n = 40) {
    return this.lines.slice(-n).map((e) => e.text);
  }
}

module.exports = { Logger, timeStr };
