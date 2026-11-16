'use strict';

const { fail } = require('./adb');

// 管理 guest 的 wm size / wm density 覆盖（SPEC 3.5）
class DisplayManager {
  constructor({ adb, logger }) {
    this.adb = adb;
    this.logger = logger;
    this.physical = null;   // 本次会话前读取到的真实（reset 后）显示参数
    this.overrideApplied = false;
    this.applied = null;    // {mode,width,height,density}
  }

  readPhysical() {
    const d = this.adb.getDisplay();
    this.physical = {
      width: d.physicalWidth,
      height: d.physicalHeight,
      density: d.physicalDensity
    };
    // 若当前存在 override，说明上次会话残留，先清掉再读一次
    if (d.overrideSize || d.overrideDensity) {
      this.logger.warn(`检测到残留显示覆盖（${d.raw.replace(/\s+/g, ' | ')}），先重置`);
      this._resetRaw();
      const again = this.adb.getDisplay();
      this.physical = { width: again.physicalWidth, height: again.physicalHeight, density: again.physicalDensity };
    }
    this.logger.info(`设备物理显示：${this.physical.width}x${this.physical.height} @ ${this.physical.density}dpi`);
    return this.physical;
  }

  _resetRaw() {
    const r = this.adb.shell('wm size reset; wm density reset', { log: false, timeout: 15000 });
    if (!r.ok) this.logger.warn(`重置显示参数失败：${(r.stderr || r.stdout).trim()}`);
    return r.ok;
  }

  // spec = {mode:'fit'} | {mode:'match',width,height,density}
  apply(spec) {
    if (!spec || (spec.mode !== 'fit' && spec.mode !== 'match')) {
      fail('BAD_REQUEST', 'display.mode 必须是 "match" 或 "fit"');
    }
    if (!this.physical) this.readPhysical();

    if (spec.mode === 'fit') {
      this._resetRaw();
      this.overrideApplied = false;
      this.applied = { mode: 'fit' };
      const d = this.adb.getDisplay();
      const ok = d.physicalWidth === this.physical.width && d.physicalHeight === this.physical.height;
      this.logger.info(`显示模式 FIT：wm size=${d.width}x${d.height} density=${d.density}${ok ? '' : '（与原始值不一致，请检查）'}`);
      return this.current(d);
    }

    const width = Number(spec.width);
    const height = Number(spec.height);
    const density = Number(spec.density);
    if (!Number.isInteger(width) || !Number.isInteger(height) || width < 320 || height < 320 || width > 4096 || height > 4096) {
      fail('BAD_REQUEST', `display.width/height 必须是 320–4096 的整数（收到 ${spec.width}x${spec.height}）`);
    }
    if (!Number.isInteger(density) || density < 72 || density > 640) {
      fail('BAD_REQUEST', `display.density 必须是 72–640 的整数（收到 ${spec.density}）`);
    }

    const r = this.adb.shell(`wm size ${width}x${height}; wm density ${density}`, { log: false, timeout: 15000 });
    if (!r.ok) fail('ADB_ERROR', `设置显示参数失败：${(r.stderr || r.stdout).trim() || 'adb shell 返回错误'}`);
    const d = this.adb.getDisplay();
    // getDisplay 的有效值优先 Override（Android 15 输出两行，不能只读 Physical）
    if (d.width !== width || d.height !== height || d.density !== density) {
      this._resetRaw();
      this.overrideApplied = false;
      fail('DISPLAY_APPLY_FAILED',
        `设置显示参数后校验失败（期望 ${width}x${height}@${density}，实际 ${d.width}x${d.height}@${d.density}）`,
        '目标分辨率/密度设置失败（MuMu 拒绝 override），已回滚到原始显示参数；请换一组尺寸/DPI 重试，或改用 FIT 模式。');
    }
    this.overrideApplied = true;
    this.applied = { mode: 'match', width, height, density };
    this.logger.info(`显示模式 MATCH：wm size=${width}x${height} density=${density}（Override 生效）`);
    return this.current(d);
  }

  current(d) {
    const cur = d || this.adb.getDisplay();
    return {
      mode: this.overrideApplied ? 'match' : 'fit',
      width: cur.width,
      height: cur.height,
      density: cur.density,
      physicalWidth: this.physical ? this.physical.width : cur.physicalWidth,
      physicalHeight: this.physical ? this.physical.height : cur.physicalHeight,
      physicalDensity: this.physical ? this.physical.density : cur.physicalDensity,
      override: !!this.overrideApplied
    };
  }

  restore(reason = '会话结束') {
    if (!this.overrideApplied) return false;
    this.logger.info(`恢复显示参数（${reason}）→ ${this.physical.width}x${this.physical.height} @ ${this.physical.density}dpi`);
    this._resetRaw();
    this.overrideApplied = false;
    this.applied = null;
    const d = this.adb.getDisplay();
    if (this.physical && (d.physicalWidth !== this.physical.width || d.physicalHeight !== this.physical.height)) {
      this.logger.warn(`恢复后物理尺寸与记录不一致（记录 ${this.physical.width}x${this.physical.height}，实际 ${d.physicalWidth}x${d.physicalHeight}）`);
    }
    return true;
  }

  // 进程退出时的同步兜底（不依赖事件循环）
  forceRestoreSync() {
    if (!this.overrideApplied) return;
    try {
      this.adb.shell('wm size reset; wm density reset', { log: false, timeout: 5000 });
      this.logger.warn('已通过同步兜底恢复 wm size/density');
    } catch (_) { /* 尽力而为 */ }
    this.overrideApplied = false;
  }
}

module.exports = { DisplayManager };
