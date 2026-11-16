#!/usr/bin/env bash
# net_sim.sh — 网络切换/断线重连模拟（模拟器版）+ PC 代理端口体检。
#
# 模拟器不适合 `svc wifi disable`（QEMU slirp 走虚拟网卡），所以断线主要用两种方式：
#   1) airplane on|off —— 飞行模式（可能切不断 slirp，脚本会实测并告诉你结果）；
#   2) proxy kill / proxy start —— 按端口杀掉/拉起 PC 代理进程（最贴近"服务端断线"）。
#
# 用法：
#   ./net_sim.sh status [A|B]                 实例网络状态 + 宿主可达性
#   ./net_sim.sh host [A|B]                   从实例内探测 10.0.2.2 / 192.168.1.10 与代理端口
#   ./net_sim.sh airplane on|off [A|B]        切换飞行模式并实测宿主是否仍可达
#   ./net_sim.sh ports                        查看 27180/27181/27182/27183 监听（区分 0.0.0.0 与 127.0.0.1）
#   ./net_sim.sh proxy status [--port 27180]  端口占用进程详情
#   ./net_sim.sh proxy kill [--port 27180] [--force]
#   ./net_sim.sh proxy start --cmd "node pc/server.js" [--dir DIR] [--port 27180] [--log FILE]
#   ./net_sim.sh proxy restart --cmd "..."
#   ./net_sim.sh ip                           宿主局域网 IPv4（给 App 填地址用）
#
# 退出码：0 = 成功/预期状态；1 = 失败/未达预期；2 = 参数错误。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

PROXY_PORTS="27180 27181 27182 27183"
HOST_IPS="10.0.2.2 192.168.1.10"

need_device() { e2e_require_device "$1" || exit 1; }
say() { printf '%s\n' "$@"; }

# ping 宿主：打印 REACHABLE/UNREACHABLE
ping_host() {  # $1 serial $2 host
  local ser="$1" host="$2" out rc
  out="$("$ADB" -s "$ser" shell "ping -c 1 -W 2 $host 2>&1" 2>/dev/null | tr -d '\r')"
  rc=$?
  if printf '%s' "$out" | grep -q 'bytes from'; then
    echo "PING $host REACHABLE ttl=$(printf '%s' "$out" | grep -o 'ttl=[0-9]*' | head -1)"
    return 0
  fi
  echo "PING $host UNREACHABLE rc=$rc"
  return 1
}

# TCP 探测（依赖 Android 自带 nc/toybox netcat）
tcp_probe() {  # $1 serial $2 host $3 port
  local ser="$1" host="$2" port="$3" out
  out="$("$ADB" -s "$ser" shell "command -v nc >/dev/null 2>&1 && nc -w 2 $host $port < /dev/null; echo rc=\$?" 2>/dev/null | tr -d '\r')"
  if printf '%s' "$out" | grep -q 'rc=0'; then
    echo "TCP $host:$port OPEN"
    return 0
  fi
  echo "TCP $host:$port CLOSED_OR_BLOCKED ($(printf '%s' "$out" | tail -1))"
  return 1
}

do_host() {  # $1 serial alias
  local ser
  ser="$(e2e_resolve_serial "${1:-B}")"
  need_device "$ser"
  say "HOST_PROBE serial=$ser"
  for h in $HOST_IPS; do ping_host "$ser" "$h" || true; done
  for port in $PROXY_PORTS; do tcp_probe "$ser" 10.0.2.2 "$port" || true; done
}

do_status() {  # $1 serial alias
  local ser role="${1:-B}" air wifi data routes
  ser="$(e2e_resolve_serial "$role")"
  need_device "$ser"
  air="$("$ADB" -s "$ser" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')"
  wifi="$("$ADB" -s "$ser" shell settings get global wifi_on 2>/dev/null | tr -d '\r')"
  data="$("$ADB" -s "$ser" shell settings get global mobile_data 2>/dev/null | tr -d '\r')"
  routes="$("$ADB" -s "$ser" shell ip route 2>/dev/null | tr -d '\r' | head -3 | tr '\n' ';')"
  say "NET_STATUS role=$role serial=$ser airplane_mode_on=${air:-?} wifi_on=${wifi:-?} mobile_data=${data:-?}"
  say "ROUTE ${routes:-none}"
  for h in $HOST_IPS; do ping_host "$ser" "$h" || true; done
}

do_airplane() {  # $1 on|off $2 serial
  local mode="${1:-}" role="${2:-B}" ser out state
  ser="$(e2e_resolve_serial "$role")"
  need_device "$ser"
  case "$mode" in on|off) : ;; *) echo "用法: net_sim.sh airplane on|off [A|B]" >&2; exit 2 ;; esac

  if [ "$mode" = "on" ]; then
    out="$("$ADB" -s "$ser" shell "cmd connectivity airplane-mode enable" 2>&1 | tr -d '\r')"
    if printf '%s' "$out" | grep -qiE 'unknown|error|usage'; then
      "$ADB" -s "$ser" shell "settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true" >/dev/null 2>&1
    fi
  else
    out="$("$ADB" -s "$ser" shell "cmd connectivity airplane-mode disable" 2>&1 | tr -d '\r')"
    if printf '%s' "$out" | grep -qiE 'unknown|error|usage'; then
      "$ADB" -s "$ser" shell "settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false" >/dev/null 2>&1
    fi
  fi
  sleep 3
  state="$("$ADB" -s "$ser" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')"
  say "AIRPLANE mode=$mode serial=$ser airplane_mode_on=${state:-?}"
  if [ "$mode" = "on" ]; then
    if ping_host "$ser" 10.0.2.2 >/dev/null 2>&1; then
      say "NOTE 飞行模式开启后宿主仍可达：QEMU 网络未被切断，请用 proxy kill 模拟断线。"
      say "RESULT=AIRPLANE_ON_HOST_STILL_REACHABLE"
    else
      say "RESULT=AIRPLANE_ON_HOST_UNREACHABLE"
    fi
  else
    ping_host "$ser" 10.0.2.2 || true
    say "RESULT=AIRPLANE_OFF"
  fi
}

# 打印某端口监听信息；无监听时返回 1。
port_info() {  # $1 port
  local port="$1" found=0 line addr pid name
  while read -r line; do
    [ -n "$line" ] || continue
    found=1
    addr="$(printf '%s' "$line" | awk '{print $2}')"
    pid="$(printf '%s' "$line" | awk '$1=="UDP"{print $4} $1=="TCP"{print $5}')"
    name="$(tasklist /FI "PID eq $pid" /NH 2>/dev/null | tr -d '\r' | awk 'NF{print $1; exit}')"
    local scope="ALL_INTERFACES"
    case "$addr" in
      127.0.0.1:*|\[::1\]:*) scope="LOOPBACK_ONLY_WARNING" ;;
    esac
    printf 'PORT %s %-3s %-18s pid=%s proc=%s %s\n' "$port" "$(printf '%s' "$line" | awk '{print $1}')" "$addr" "$pid" "${name:-?}" "$scope"
  done <<EOF
$(netstat -ano 2>/dev/null | tr -d "\r" | awk -v p=":$port" '($1=="TCP" && $2 ~ p"$" && $4=="LISTENING") || ($1=="UDP" && $2 ~ p"$")')
EOF
  [ "$found" = 1 ]
}

do_ports() {
  local port any=0
  for port in $PROXY_PORTS; do
    if port_info "$port"; then any=1; else say "PORT $port - (无监听)"; fi
  done
  [ "$any" = 1 ] && say "RESULT=PORTS_ANY_LISTENING" || say "RESULT=PORTS_NONE"
}

proxy_pids() {  # $1 port  -> 打印 pid 列表（TCP LISTEN 或 UDP）
  netstat -ano 2>/dev/null | tr -d "\r" | awk -v p=":$1" '($1=="TCP" && $2 ~ p"$" && $4=="LISTENING") || ($1=="UDP" && $2 ~ p"$") {print $NF}' | sort -u
}

do_proxy() {  # $1 status|kill|start|restart，其余为选项
  local sub="${1:-status}"; shift || true
  local PORT=27180 FORCE=0 CMD="" DIR="$REPO_ROOT" LOG=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --port) PORT="${2:-27180}"; shift 2 ;;
      --force) FORCE=1; shift ;;
      --cmd) CMD="${2:-}"; shift 2 ;;
      --dir) DIR="${2:-$REPO_ROOT}"; shift 2 ;;
      --log) LOG="${2:-}"; shift 2 ;;
      *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
  done

  case "$sub" in
    status)
      if port_info "$PORT"; then say "RESULT=LISTENING"; else say "RESULT=NO_LISTENER"; fi
      ;;
    kill)
      local pids pid name killed=0
      pids="$(proxy_pids "$PORT" | tr '\n' ' ')"
      if [ -z "$(printf '%s' "$pids" | tr -d ' ')" ]; then
        say "PROXY kill port=$PORT NO_LISTENER（无需杀）"
        say "RESULT=NO_LISTENER"
        exit 0
      fi
      for pid in $pids; do
        name="$(tasklist /FI "PID eq $pid" /NH 2>/dev/null | tr -d '\r' | awk 'NF{print $1; exit}')"
        if [ "$FORCE" = 0 ] && [ "$name" != "node.exe" ]; then
          say "REFUSE pid=$pid proc=${name:-?} 不是 node.exe（确认后加 --force）"
          exit 1
        fi
        taskkill /F /PID "$pid" >/dev/null 2>&1
        say "KILLED pid=$pid proc=${name:-?} port=$PORT"
        killed=$((killed + 1))
      done
      sleep 1
      if [ -z "$(proxy_pids "$PORT" | tr -d ' \n')" ]; then
        say "RESULT=KILLED n=$killed"
      else
        say "RESULT=KILL_FAILED（端口仍被占用）"
        exit 1
      fi
      ;;
    start)
      [ -n "$CMD" ] || { echo "ERROR: proxy start 需要 --cmd \"启动命令\"" >&2; exit 2; }
      [ -n "$LOG" ] || LOG="$LOG_DIR/proxy-$(e2e_ts).log"
      mkdir -p "$(dirname "$LOG")"
      ( cd "$DIR" && bash -c "$CMD" > "$LOG" 2>&1 & echo $! > "$STATE_DIR/proxy.pid" )
      sleep 1
      local pid
      pid="$(cat "$STATE_DIR/proxy.pid" 2>/dev/null | tr -d '\r')"
      say "PROXY start cmd='$CMD' dir=$DIR pid=${pid:-?} log=$LOG"
      local i=0
      while [ "$i" -lt 20 ]; do
        if port_info "$PORT" >/dev/null 2>&1; then
          port_info "$PORT"
          say "RESULT=STARTED_LISTENING port=$PORT"
          exit 0
        fi
        sleep 1
        i=$((i + 1))
      done
      say "RESULT=STARTED_NOT_LISTENING port=$PORT（进程已拉起但端口未监听，查日志）"
      exit 1
      ;;
    restart)
      local force_opt=""
      [ "$FORCE" = 1 ] && force_opt="--force"
      "$0" proxy kill --port "$PORT" $force_opt >/dev/null 2>&1 || true
      if [ -n "$LOG" ]; then
        "$0" proxy start --cmd "$CMD" --dir "$DIR" --port "$PORT" --log "$LOG"
      else
        "$0" proxy start --cmd "$CMD" --dir "$DIR" --port "$PORT"
      fi
      ;;
    *) echo "用法: net_sim.sh proxy status|kill|start|restart [选项]" >&2; exit 2 ;;
  esac
}

do_ip() {
  local ips
  ips="$(ipconfig 2>/dev/null | awk -F: '/IPv4/{gsub(/^[ \t]+|[ \t]+$/, "", $2); if ($2 !~ /^127\./) print $2}')"
  if [ -z "$ips" ]; then
    say "IPV4 (未解析到，ipconfig 输出可能不是英文/中文标准格式)"
    exit 1
  fi
  for ip in $ips; do say "HOST_IPV4 $ip"; done
  say "NOTE App 填实例内可达地址：10.0.2.2（slirp）或上面的局域网 IP；代理必须监听 0.0.0.0。"
}

CMD="${1:-}"
[ -n "$CMD" ] || { sed -n '2,26p' "$0"; exit 2; }
shift || true
case "$CMD" in
  status) do_status "${1:-B}" ;;
  host) do_host "${1:-B}" ;;
  airplane) do_airplane "${1:-}" "${2:-B}" ;;
  ports) do_ports ;;
  proxy) do_proxy "$@" ;;
  ip) do_ip ;;
  -h|--help) sed -n '2,26p' "$0" ;;
  *) echo "未知子命令: $CMD" >&2; sed -n '2,26p' "$0"; exit 2 ;;
esac
