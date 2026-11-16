#!/usr/bin/env bash
# ensure_devices.sh — 确保 A（主实例）和 B（测试手机实例1）的 adb 在线，必要时自动拉起实例。
#
# 用法：
#   ./ensure_devices.sh [--timeout 120] [--boot-timeout 240] [--no-launch]
#                       [--skip-b] [--tune] [--quiet]
#
# 行为：
#   1) 读取 MuMuManager 的 adb_host_ip/adb_port 作为权威端口（避免实例重建后端口变化）；
#   2) 端口不对/离线时先 `adb disconnect` 再 `adb connect`；
#   3) 实例未启动且允许 launch 时执行 `MuMuManager control -v <idx> launch`；
#   4) 轮询 adb state=device 且 sys.boot_completed=1；
#   5) --tune 时关闭动画并设置常亮（新实例/重启后需要）。
#
# 输出（stdout）：
#   每个角色一行：ROLE=A SERIAL=... PORT=... NAME=... STATE=device BOOT=1
#   机器可读文件：evidence/_state/devices.env（SERIAL_A=... SERIAL_B=...）
#   最后一行：RESULT=PASS|FAIL
#
# 退出码：0 = 需要的实例都就绪；1 = 至少一台失败；2 = 参数错误。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,24p' "$0"; exit 0; }

TIMEOUT=120        # adb 连接等待
BOOT_TIMEOUT=240   # 开机完成等待
LAUNCH=1
SKIP_B=0
TUNE=0
QUIET=0
CREATE=1   # 实例不存在时自动 create（仅 B / index 1）
while [ "$#" -gt 0 ]; do
  case "$1" in
    --timeout) TIMEOUT="${2:-120}"; shift 2 ;;
    --boot-timeout) BOOT_TIMEOUT="${2:-240}"; shift 2 ;;
    --no-launch) LAUNCH=0; shift ;;
    --no-create) CREATE=0; shift ;;
    --skip-b) SKIP_B=1; shift ;;
    --tune) TUNE=1; shift ;;
    --quiet) QUIET=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

e2e_mkdirs
ENV_FILE="$STATE_DIR/devices.env"
: > "$ENV_FILE"

say() { [ "$QUIET" = 1 ] || echo "$@"; }

# port_for_index <idx>：从 MuMuManager 读权威端口，失败回退到环境变量。
port_for_index() {
  local idx="$1" fallback="$2" addr
  addr="$("$PY" "$E2E_DIR/mumu.py" port "$idx" 2>/dev/null | tr -d '\r')"
  if [ -z "$addr" ]; then addr="$fallback"; fi
  printf '%s\n' "$addr"
}

# connect_serial <serial>：处理 offline/未连接两种情况。
connect_serial() {
  local ser="$1" state
  state="$("$ADB" -s "$ser" get-state 2>/dev/null | tr -d '\r')"
  if [ "$state" = "offline" ]; then
    "$ADB" disconnect "$ser" >/dev/null 2>&1
    state=""
  fi
  if [ "$state" != "device" ]; then
    say "  adb connect $ser"
    "$ADB" connect "$ser" 2>&1 | tr -d '\r' | while read -r line; do say "    $line"; done
  fi
}

# wait_device <serial> <seconds>：轮询 adb device 状态。
wait_device() {
  local ser="$1" limit="$2" end now
  end=$(( $(date +%s) + limit ))
  now=$(date +%s)
  while [ "$now" -lt "$end" ]; do
    if e2e_is_device "$ser"; then return 0; fi
    sleep 2
    now=$(date +%s)
  done
  e2e_is_device "$ser"
}

# wait_boot <serial> <seconds>
wait_boot() {
  local ser="$1" limit="$2" end now boot
  end=$(( $(date +%s) + limit ))
  while :; do
    boot="$("$ADB" -s "$ser" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [ "$boot" = "1" ]; then return 0; fi
    now=$(date +%s)
    [ "$now" -ge "$end" ] && return 1
    sleep 3
  done
}

# tune_device <serial>：关动画 + 常亮（幂等）。
tune_device() {
  local ser="$1"
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$ser" shell settings put global "$k" 0 >/dev/null 2>&1
  done
  "$ADB" -s "$ser" shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1
  "$ADB" -s "$ser" shell svc power stayon true >/dev/null 2>&1
  "$ADB" -s "$ser" shell settings put global stay_on_while_plugged_in 3 >/dev/null 2>&1
  # 唤醒并解除锁屏（新实例/重启后可能是锁屏态）
  "$ADB" -s "$ser" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  "$ADB" -s "$ser" shell wm dismiss-keyguard >/dev/null 2>&1
  "$ADB" -s "$ser" shell input keyevent 82 >/dev/null 2>&1
}

# ensure_role <A|B> <idx> <configured_serial> <required 1|0>
ensure_role() {
  local role="$1" idx="$2" fallback="$3" required="$4"
  local ser addr state name boot ok=0

  if [ "$required" = 1 ]; then
    # 实例不存在（not in MuMuManager info）时按需重建；只允许重建 index 1（B）。
    if ! "$PY" "$E2E_DIR/mumu.py" info "$idx" >/dev/null 2>&1; then
      if [ "$role" = "B" ] && [ "$CREATE" = 1 ]; then
        say "  实例 $idx 不存在 -> MuMuManager create -n $idx"
        "$MUMU_MGR" create -n "$idx" 2>&1 | tr -d '\r' | while read -r line; do say "    $line"; done
        local i=0
        while [ "$i" -lt 45 ]; do
          if "$PY" "$E2E_DIR/mumu.py" info "$idx" >/dev/null 2>&1; then break; fi
          sleep 2
          i=$((i + 1))
        done
      fi
      if ! "$PY" "$E2E_DIR/mumu.py" info "$idx" >/dev/null 2>&1; then
        say "ROLE=$role FAIL 实例 $idx 不存在（create 失败或被禁用）"
        echo "SERIAL_${role}=" >> "$ENV_FILE"
        return 1
      fi
    fi
    ser="$(port_for_index "$idx" "$fallback")"
  else
    ser=""
  fi

  # B 可选且 skip 时直接短路。
  if [ "$required" = 0 ]; then
    printf 'ROLE=%s SKIPPED\n' "$role"
    echo "SERIAL_${role}=" >> "$ENV_FILE"
    return 0
  fi

  addr="$ser"
  say "== $role（实例 $idx，期望 $addr）=="

  # 1) 先按权威端口判断，不在线则 connect。
  connect_serial "$addr"
  if ! wait_device "$addr" 8; then
    # 2) 端口读错/实例未启动时，尝试 launch 后重连。
    local started
    started="$("$PY" "$E2E_DIR/mumu.py" started "$idx" 2>/dev/null | tr -d '\r')"
    if [ "$LAUNCH" = 1 ] && [ "$started" != "1" ]; then
      say "  实例 $idx 未启动 -> MuMuManager control -v $idx launch"
      "$MUMU_MGR" control -v "$idx" launch >/dev/null 2>&1
      sleep 3
    fi
    connect_serial "$addr"
  fi

  state="$("$ADB" -s "$addr" get-state 2>/dev/null | tr -d '\r')"
  if [ "$state" != "device" ]; then
    # 3) 最后再给足超时并重试 connect（覆盖实例正在重启的情况）。
    local end now
    end=$(( $(date +%s) + TIMEOUT )); now=$(date +%s)
    while [ "$now" -lt "$end" ]; do
      connect_serial "$addr" >/dev/null 2>&1 || true
      if wait_device "$addr" 5; then state="device"; break; fi
      now=$(date +%s)
    done
  fi

  if [ "$state" != "device" ]; then
    say "  ROLE=$role FAIL serial=$addr state=${state:-missing}"
    echo "SERIAL_${role}=$addr" >> "$ENV_FILE"
    return 1
  fi

  name="$("$PY" "$E2E_DIR/mumu.py" name "$idx" 2>/dev/null | tr -d '\r')"
  if wait_boot "$addr" "$BOOT_TIMEOUT"; then boot=1; else boot=0; fi
  [ "$TUNE" = 1 ] && tune_device "$addr"
  [ "$boot" = "1" ] && ok=1

  say "  ROLE=$role SERIAL=$addr NAME=$name STATE=device BOOT=$boot"
  printf 'ROLE=%s SERIAL=%s PORT=%s NAME=%s STATE=device BOOT=%s\n' \
    "$role" "$addr" "${addr##*:}" "$name" "$boot"
  echo "SERIAL_${role}=$addr" >> "$ENV_FILE"
  [ "$ok" = 1 ] && return 0 || return 1
}

say "===== ensure_devices $(date '+%F %T') ====="
say "MUMU_MGR=$MUMU_MGR"
say "ADB=$ADB"

FAIL=0
ensure_role A 0 "$SERIAL_MAIN" 1 || FAIL=1
if [ "$SKIP_B" = 1 ]; then
  say "== B 已跳过（--skip-b）=="
else
  ensure_role B 1 "$SERIAL_TEST" 1 || FAIL=1
fi

echo "ENV_FILE=$ENV_FILE"
if [ "$FAIL" = 0 ]; then
  echo "RESULT=PASS"
  exit 0
else
  echo "RESULT=FAIL"
  echo "  hint: MuMuManager info -v all；实例卡死时 control -v 1 restart 后重试。" >&2
  exit 1
fi
