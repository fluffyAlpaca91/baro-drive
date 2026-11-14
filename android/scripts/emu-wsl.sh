#!/usr/bin/env bash
# Pleos Connect v2.1.0 emulator (AVD "pleos210", API 34 Automotive) headless in WSL, for agents.
#
#   android/scripts/emu-wsl.sh start                 # boot (or reuse), wait, print the serial
#   android/scripts/emu-wsl.sh install app/build/outputs/apk/debug/app-debug.apk -g
#   android/scripts/emu-wsl.sh launch
#   android/scripts/emu-wsl.sh texts com.barodrive.app   # "x y text" of what is on screen
#   android/scripts/emu-wsl.sh tap-text '시연'          # the demo panel handle (bottom-right tab)
#   android/scripts/emu-wsl.sh shot                  # PNG under ~/work/qa-shots, prints the path
#   android/scripts/emu-wsl.sh signal gear D         # car signals through the Virtual Console
#   android/scripts/emu-wsl.sh stop
#
# Agents share one emulator: hold /tmp/baro-emu.lock with `flock -o` (without -o the emulator that
# `start` launches inherits the lock and keeps it until it exits). The app runs as Android user 10.
# Run without arguments for the full command list. Details, numbers and caveats: README-emu.md.
# stdout carries only results (serial, paths, XML, values); progress and errors go to stderr.
set -euo pipefail

EMU_AVD="${EMU_AVD:-pleos210}"
EMU_PORT="${EMU_PORT:-5580}"                 # console port; adb serial is emulator-$EMU_PORT
EMU_GPU="${EMU_GPU:-host}"                   # host = Mesa llvmpipe in WSL (see README-emu.md)
EMU_RAM_MB="${EMU_RAM_MB:-4096}"             # guest RAM (Pleos guide: 4 GB)
EMU_RENDER_THREADS="${EMU_RENDER_THREADS:-4}" # llvmpipe threads (LP_NUM_THREADS); default = all CPUs
EMU_NICE="${EMU_NICE:-10}"                   # CPU priority below the agents' Gradle builds
EMU_BOOT_TIMEOUT="${EMU_BOOT_TIMEOUT:-420}"  # seconds
EMU_APP="${EMU_APP:-com.barodrive.app/.MainActivity}"
EMU_SHOTS="${EMU_SHOTS:-$HOME/work/qa-shots}"
EMU_STOP_DRIVINGVIEW="${EMU_STOP_DRIVINGVIEW:-1}" # force-stop the Pleos driving view before tap/swipe (see below)
EMU_STATE_DIR="${EMU_STATE_DIR:-$HOME/.cache/baro-emu}"
EMU_EXTRA_LIBS="${EMU_EXTRA_LIBS:-$HOME/opt/emu-libs/root/usr/lib/x86_64-linux-gnu}"
VC_PORT="${VC_PORT:-8088}"                   # host side of `adb forward` to the Virtual Console
env_file="${BARO_ENV_FILE:-$HOME/opt/baro-env.sh}"

SERIAL="emulator-$EMU_PORT"
VC="http://127.0.0.1:$VC_PORT"
LOG="$EMU_STATE_DIR/emulator.log"
APP_PKG="${EMU_APP%%/*}"

log() { echo "emu-wsl: $*" >&2; }
die() { log "$*"; exit 1; }

if [[ -f $env_file ]]; then
  # shellcheck source=/dev/null
  source "$env_file"
fi
[[ -n ${ANDROID_HOME:-} ]] || die "ANDROID_HOME not set ($env_file missing?)"
command -v adb >/dev/null || die "adb not on PATH (source $env_file)"
mkdir -p "$EMU_STATE_DIR"

# 9>&-: an adb server that adb starts on demand must not inherit the start/stop lock (fd 9), or every later
# start/stop waits for it until the server exits.
adb_() { adb -s "$SERIAL" "$@" 9>&-; }

# PIDs of the qemu process running our AVD. Checks /proc/<pid>/exe so that a shell whose command
# line merely mentions the AVD name is never mistaken for the emulator.
emu_pids() {
  local p
  for p in $(pgrep -f -- "-avd $EMU_AVD" 2>/dev/null || true); do
    case "$(readlink "/proc/$p/exe" 2>/dev/null || true)" in
      */qemu-system-*) echo "$p" ;;
    esac
  done
}

booted() { [[ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; }

# The Pleos driving view (left cluster, ai.umos.drivingview) cannot finish starting with CPU rendering: it
# ANRs ("failed to complete startup") and the home screen restarts it about every 20 s. While it is up, taps
# on our app are swallowed, and when it holds the focus uiautomator dumps only its empty window. Stopping it
# makes taps land and gives the focus back to our app; it comes back by itself.
#   quiet_drivingview        before input: whenever it is running
#   quiet_drivingview focus  before a dump: only when it has the focus (wait-text polls often; stopping it every
#                            few seconds was followed by Pleos home screen crashes "Listener for taskId=… already exists")
quiet_drivingview() {
  [[ $EMU_STOP_DRIVINGVIEW == 1 ]] || return 0
  if [[ ${1:-} == focus ]]; then
    adb_ shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | grep -q 'ai.umos.drivingview' || return 0
  else
    [[ -n "$(adb_ shell pidof ai.umos.drivingview 2>/dev/null | tr -d '\r')" ]] || return 0
  fi
  adb_ shell am force-stop ai.umos.drivingview >/dev/null 2>&1 || true
  sleep 1
}

need_device() {
  [[ -n "$(emu_pids)" ]] || die "emulator is not running (run: $0 start)"
  adb_ get-state >/dev/null 2>&1 || die "$SERIAL is not online (run: $0 start)"
}

preflight() {
  if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    die "/dev/kvm is not usable by $(id -un). One-time fix (then restart WSL: wsl --shutdown):
  sudo usermod -aG kvm $(id -un)
or, until the next WSL restart:
  sudo chmod 666 /dev/kvm"
  fi
  [[ -x $ANDROID_HOME/emulator/emulator ]] || die "emulator package missing (android sdk install emulator)"
  [[ -f $HOME/.android/avd/$EMU_AVD.ini ]] || die "AVD $EMU_AVD missing (see README-emu.md, one-time setup)"
  # The emulator needs libpulse.so.0. Ubuntu WSL lacks it unless `sudo apt install libpulse0`;
  # README-emu.md describes the user-space copy under ~/opt/emu-libs used instead.
  if ! ldconfig -p 2>/dev/null | grep -q 'libpulse\.so\.0 '; then
    [[ -f $EMU_EXTRA_LIBS/libpulse.so.0 ]] || die "libpulse.so.0 missing: sudo apt install libpulse0"
    export LD_LIBRARY_PATH="$EMU_EXTRA_LIBS:$EMU_EXTRA_LIBS/pulseaudio${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  fi
  [[ -f $HOME/.android/adbkey.pub ]] || { adb start-server >/dev/null 2>&1 || true; [[ -f $HOME/.android/adbkey ]] && adb pubkey "$HOME/.android/adbkey" >"$HOME/.android/adbkey.pub"; }
}

# eth0 (10.0.2.15) carries the Pleos vehicle bus (DDS): the VHAL and the Virtual Console need it.
# On this image eth0 is the emulated LTE modem's data link; if Wi-Fi wins the race at boot, Android
# tears the LTE data call down ("no live network request") and eth0 stays DOWN: no car signals,
# Virtual Console writes fail with emulator_inactive. Fix: Wi-Fi off + airplane mode on/off.
eth0_up() { adb_ shell ip -4 addr show eth0 2>/dev/null | head -n 1 | grep -q 'UP,LOWER_UP'; }

ensure_vehicle_bus() {
  local i
  for i in $(seq 1 15); do eth0_up && return 0; sleep 2; done
  log "eth0 (vehicle bus) is down; turning Wi-Fi off and cycling airplane mode so LTE data brings it up"
  adb_ shell svc wifi disable >/dev/null 2>&1 || true
  adb_ shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
  sleep 3
  adb_ shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  for i in $(seq 1 45); do eth0_up && { log "eth0 is up"; return 0; }; sleep 2; done
  log "WARNING: eth0 still down; car signals and the Virtual Console will not work (try: $0 fix-net)"
  return 1
}

cmd_start() {
  preflight
  exec 9>"$EMU_STATE_DIR/lock"
  flock -w $((EMU_BOOT_TIMEOUT + 120)) 9 || die "timed out waiting for another start/stop"
  local t0 pid launcher=""
  t0=$(date +%s)
  if [[ -z "$(emu_pids)" ]]; then
    log "booting $EMU_AVD (gpu=$EMU_GPU, ram=${EMU_RAM_MB}MB, render threads=$EMU_RENDER_THREADS, nice=$EMU_NICE, cold boot); log: $LOG"
    : >"$LOG"
    # 9>&-: the emulator must not inherit the lock, or it would hold it until it exits.
    LP_NUM_THREADS="$EMU_RENDER_THREADS" setsid nohup nice -n "$EMU_NICE" \
      "$ANDROID_HOME/emulator/emulator" -avd "$EMU_AVD" -port "$EMU_PORT" \
      -no-window -no-snapshot -no-audio -no-boot-anim -gpu "$EMU_GPU" -memory "$EMU_RAM_MB" \
      >"$LOG" 2>&1 </dev/null 9>&- &
    launcher=$!
    disown || true
  else
    log "already running (pid $(emu_pids | tr '\n' ' '))"
  fi
  while ! booted; do
    pid="$(emu_pids)"
    # Right after launch the process is still the `emulator` launcher (not yet qemu).
    if [[ -z $pid ]] && ! { [[ -n $launcher ]] && kill -0 "$launcher" 2>/dev/null; }; then
      grep -v libunwind "$LOG" | tail -n 15 >&2 || true
      die "emulator exited during boot (log: $LOG)"
    fi
    (( $(date +%s) - t0 < EMU_BOOT_TIMEOUT )) || die "boot did not complete within ${EMU_BOOT_TIMEOUT}s"
    sleep 3
  done
  log "boot completed after $(( $(date +%s) - t0 ))s"
  adb_ forward "tcp:$VC_PORT" tcp:8088 >/dev/null
  ensure_vehicle_bus || true
  echo "$SERIAL"
}

cmd_stop() {
  exec 9>"$EMU_STATE_DIR/lock"
  flock -w 120 9 || die "timed out waiting for another start/stop"
  [[ -n "$(emu_pids)" ]] || { log "not running"; return 0; }
  adb forward --remove "tcp:$VC_PORT" >/dev/null 2>&1 9>&- || true
  adb_ emu kill >/dev/null 2>&1 || true
  local i
  for i in $(seq 1 30); do [[ -z "$(emu_pids)" ]] && { log "stopped"; return 0; }; sleep 1; done
  log "still running after 30s; sending SIGTERM"
  kill $(emu_pids) 2>/dev/null || true
  for i in $(seq 1 10); do [[ -z "$(emu_pids)" ]] && { log "stopped"; return 0; }; sleep 1; done
  kill -9 $(emu_pids) 2>/dev/null || true
  log "killed"
}

cmd_status() {
  local pids
  pids="$(emu_pids)"
  if [[ -z $pids ]]; then echo "running=no"; return 0; fi
  echo "running=yes pid=$(echo "$pids" | tr '\n' ' ')serial=$SERIAL"
  echo "booted=$(booted && echo yes || echo no)"
  echo "vehicle_bus_eth0=$(eth0_up && echo up || echo down)"
  local p rss
  for p in $pids; do
    rss=$(awk '/^Rss:/{print int($2/1024)}' "/proc/$p/smaps_rollup" 2>/dev/null || echo "?")
    echo "host_rss_mb=$rss cpu_pct=$(ps -o pcpu= -p "$p" | tr -d ' ')(avg since start)"
  done
  adb_ shell dumpsys car_service --services CarDrivingStateService 2>/dev/null | grep -m1 'Current Driving State' | sed 's/^ *//' || true
  adb_ shell dumpsys car_service --services CarUxRestrictionsManagerService 2>/dev/null | grep -m1 'Display id: 0 UXR' | sed 's/^ *//' || true
}

cmd_install() {
  [[ $# -ge 1 ]] || die "usage: install <apk> [adb install flags, e.g. -g]"
  local apk="$1"; shift
  [[ -f $apk ]] || die "no such file: $apk"
  need_device
  adb_ install -r -t "$@" "$apk"
}

cmd_launch() {
  need_device
  local comp="${1:-$EMU_APP}" out
  out="$(adb_ shell am start -W -n "$comp" 2>&1 | tr -d '\r')"
  grep -E 'Status|LaunchState|TotalTime|Error' <<<"$out" >&2 || true
  if grep -q 'Error' <<<"$out"; then die "launch failed: $comp"; fi
  # "Status: timeout" only means the first frame took longer than am's wait (the CPU-rendered
  # emulator is slow); the activity is still starting. Use wait-text before tapping.
  grep -q 'Status: ok' <<<"$out" || log "launch still in progress (slow first frame); use: $0 wait-text <text>"
}

cmd_shot() {
  need_device
  local out="${1:-$EMU_SHOTS/shot-$(date +%Y%m%d-%H%M%S).png}"
  mkdir -p "$(dirname "$out")"
  adb_ exec-out screencap -p >"$out.tmp"
  [[ "$(head -c 4 "$out.tmp" | tail -c 3)" == PNG ]] || { rm -f "$out.tmp"; die "screencap failed"; }
  mv "$out.tmp" "$out"
  echo "$out"
}

cmd_tap() {
  [[ $# -eq 2 ]] || die "usage: tap <x> <y>   (screen pixels, 2560x1440)"
  need_device
  quiet_drivingview
  adb_ shell input tap "$1" "$2"
}

cmd_swipe() {
  [[ $# -ge 4 ]] || die "usage: swipe <x1> <y1> <x2> <y2> [ms]"
  need_device
  quiet_drivingview
  adb_ shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"
}

cmd_key() {
  [[ $# -eq 1 ]] || die "usage: key <BACK|HOME|ENTER|...|keycode number>"
  need_device
  local k="$1"
  [[ $k =~ ^[0-9]+$ || $k == KEYCODE_* ]] || k="KEYCODE_${k^^}"
  adb_ shell input keyevent "$k"
}

# Hold keys like a physical keyboard (demo keyboard driving): a virtual USB keyboard made with the shell
# `uinput` command (JSON on stdin). `adb emu event send EV_KEY:...` does not reach the guest on this emulator
# build, `input keyevent` only taps and `input keycombination` needs two keys.
#   keys tap:d hold:up:3000 wait:500 down:left wait:700 up:left
# EMU_KEYS_NAME=qwerty2 names the device like the emulator's own keyboard, so Android gives it that keyboard's
# key layout (qwerty2.idc -> qwerty.kl, the layout PC keys get; it has no PgUp/PgDn lines) instead of Generic.kl.
declare -A UINPUT_KEY=([up]=103 [down]=108 [left]=105 [right]=106 [pageup]=104 [pagedown]=109 [w]=17 [s]=31 [p]=25 [r]=19
  [n]=49 [d]=32 [q]=16 [e]=18 [c]=46 [a]=30 [x]=45 [enter]=28 [space]=57 [esc]=1 [back]=158)
cmd_keys() {
  [[ $# -ge 1 ]] || die "usage: keys <tap:KEY|down:KEY|up:KEY|hold:KEY:MS|wait:MS>...   KEY: ${!UINPUT_KEY[*]}"
  need_device
  local codes json a op k ms
  codes="$(printf '%s,' "${UINPUT_KEY[@]}")"
  json='{"id":1,"command":"register","name":"'"${EMU_KEYS_NAME:-QA keyboard (emu-wsl)}"'","vid":6353,"pid":43981,"bus":"usb",'
  json+='"configuration":[{"type":100,"data":[1]},{"type":101,"data":['"${codes%,}"']}]}'$'\n'
  json+='{"id":1,"command":"delay","duration":600}'$'\n' # let the input reader add the device
  for a in "$@"; do
    IFS=: read -r op k ms <<<"$a"
    if [[ $op == wait ]]; then
      json+='{"id":1,"command":"delay","duration":'"${k:?wait:MS}"'}'$'\n'
      continue
    fi
    [[ -n ${UINPUT_KEY[$k]:-} ]] || die "keys: unknown key '$k' (known: ${!UINPUT_KEY[*]})"
    k=${UINPUT_KEY[$k]}
    case $op in
      down) json+='{"id":1,"command":"inject","events":[1,'"$k"',1,0,0,0]}'$'\n' ;;
      up) json+='{"id":1,"command":"inject","events":[1,'"$k"',0,0,0,0]}'$'\n' ;;
      tap | hold)
        [[ $op == tap ]] && ms=30
        json+='{"id":1,"command":"inject","events":[1,'"$k"',1,0,0,0]}'$'\n'
        json+='{"id":1,"command":"delay","duration":'"${ms:?hold:KEY:MS}"'}'$'\n'
        json+='{"id":1,"command":"inject","events":[1,'"$k"',0,0,0,0]}'$'\n' ;;
      *) die "keys: unknown step '$a'" ;;
    esac
  done
  json+='{"id":1,"command":"delay","duration":200}'
  printf '%s\n' "$json" | adb_ shell uinput - >/dev/null
}

cmd_text() {
  [[ $# -ge 1 ]] || die "usage: text <string>   (ASCII only: 'input text' cannot type Korean)"
  need_device
  local s="$*"
  LC_ALL=C grep -q '^[[:print:]]*$' <<<"$s" || die "text: ASCII only"
  # Escape for the device shell, then spaces as %s (input text's own convention).
  s="$(printf '%s' "$s" | sed -e 's|[^A-Za-z0-9._@,+=:/-]|\\&|g' -e 's|\\ |%s|g')"
  adb_ shell input text "$s"
}

cmd_dump() {
  need_device
  quiet_drivingview focus
  local f=/data/local/tmp/emu-wsl-ui.xml i out
  for i in 1 2 3; do
    out="$(adb_ shell uiautomator dump "$f" 2>&1 || true)"
    if [[ $out == *"dumped to"* ]]; then
      adb_ exec-out cat "$f"
      echo
      return 0
    fi
    sleep 1
  done
  die "uiautomator dump failed: $out"
}

# "cx cy text" per node with text or content-desc; optional package filter.
cmd_texts() {
  local pkg="${1:-}"
  cmd_dump | python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
pkg = sys.argv[1]
data = sys.stdin.read().strip()
if not data:
    sys.exit(1)
root = ET.fromstring(data)
for n in root.iter("node"):
    t = n.get("text") or n.get("content-desc")
    if not t or (pkg and n.get("package") != pkg):
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    print((x1 + x2) // 2, (y1 + y2) // 2, t.replace("\n", " "))
' "$pkg"
}

# Center "x y" of the node with that text (exact match first, then substring), or nothing.
find_text() {
  cmd_texts 2>/dev/null | python3 -I -c '
import sys
want = sys.argv[1]
rows = [l.rstrip("\n").split(" ", 2) for l in sys.stdin if l.strip()]
exact = [r for r in rows if len(r) == 3 and r[2] == want]
part = [r for r in rows if len(r) == 3 and want in r[2]]
r = (exact or part or [None])[0]
print(f"{r[0]} {r[1]}" if r else "")
' "$1" || true
}

# Poll the screen (one uiautomator dump takes ~4 s here) until the text shows up.
cmd_wait_text() {
  [[ $# -ge 1 ]] || die "usage: wait-text <text> [timeout seconds, default ${EMU_WAIT_S:-30}]"
  need_device
  local want="$1" limit="${2:-${EMU_WAIT_S:-30}}" t0 hit
  t0=$(date +%s)
  while :; do
    hit="$(find_text "$want")"
    [[ -n $hit ]] && { echo "$hit"; return 0; }
    (( $(date +%s) - t0 < limit )) || die "text '$want' did not appear within ${limit}s (see: $0 texts)"
    sleep 1
  done
}

cmd_tap_text() {
  [[ $# -ge 1 ]] || die "usage: tap-text <text> [timeout seconds]   (exact match first, then substring)"
  local hit
  hit="$(cmd_wait_text "$@")"
  log "tap $hit ('$1')"
  # shellcheck disable=SC2086
  cmd_tap $hit
}

cmd_logcat() {
  need_device
  if [[ ${1:-} == app ]]; then
    shift
    local pid
    pid="$(adb_ shell pidof "$APP_PKG" | tr -d '\r')"
    [[ -n $pid ]] || die "$APP_PKG is not running"
    adb_ logcat -v time --pid="$pid" "$@"
  else
    adb_ logcat -v time "$@"
  fi
}

# ---- Virtual Console (in-guest vehicle emulator, port 8088; see README-emu.md) ----

vc_state() { curl -sS -m 10 "$VC/vehicle/state"; }

# The vehicle emulator only accepts writes while a "browser" session is alive: POST a heartbeat
# (expires after 3 s) and wait until it reports active. Values stay after the session expires.
vc_activate() {
  adb_ forward "tcp:$VC_PORT" tcp:8088 >/dev/null
  local i
  for i in $(seq 1 30); do
    curl -sS -m 5 -X POST "$VC/browser/session?client=emu-wsl-$$" -o /dev/null 2>/dev/null || true
    if vc_state 2>/dev/null | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
sys.exit(0 if d["controls"]["emulator_active"] and d["gear"]["bridge_ready"] else 1)' 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  eth0_up || die "Virtual Console inactive: vehicle bus eth0 is down (run: $0 fix-net)"
  die "Virtual Console did not become active (see: $0 signal state availability)"
}

vc_put() {
  local path="$1" code body
  body="$(mktemp)"
  code="$(curl -sS -m 10 -X PUT "$VC$path" -o "$body" -w '%{http_code}')" || { rm -f "$body"; die "PUT $path failed"; }
  if [[ $code != 200 ]]; then
    log "PUT $path -> HTTP $code $(head -c 300 "$body")"; rm -f "$body"; return 1
  fi
  rm -f "$body"
  log "PUT $path -> 200"
}

# speed/accelerator/brake/steering are written together; keep the ones not given.
vc_controls() {
  local field="$1" value="$2" q
  q="$(vc_state | python3 -I -c '
import json, sys
c = json.load(sys.stdin)["controls"]
v = {"speed": c["speed_kph"], "accelerator": c["accelerator_pct"], "brake": c["brake_pct"], "steering": c["steering_deg"]}
v[sys.argv[1]] = float(sys.argv[2])
print("&".join(f"{k}={v[k]:g}" for k in v))
' "$field" "$value")"
  vc_put "/vehicle/controls?$q"
}

bool() {
  case "${1,,}" in
    on|true|1|yes|open|occupied|lock|locked) echo true ;;
    off|false|0|no|closed|close|empty|unlock|unlocked) echo false ;;
    *) die "expected on/off, got '$1'" ;;
  esac
}

cmd_signal() {
  local name="${1:-}" v="${2:-}"
  case "$name" in
    ""|help) cat >&2 <<'EOF'
usage: emu-wsl.sh signal <name> <value>
  gear P|R|N|D                 speed <km/h 0..240>      brake <% 0..100>    accel <% 0..100>
  steering <deg -470..500>     (arrives in apps with the opposite sign: 15 -> PERF_STEERING_ANGLE -15)
  turn off|left|right|flash-left|flash-right            hazard on|off
  seat <occupied|empty> [driver|passenger|rear_left|rear_right]
  door <front_left|front_right|rear_left|rear_right> <open|closed>   lock on|off
  odometer <km>                telltale <SeatBelt|ParkingBrake|...> <Off|On|Blink>
  raw <endpoint> [key=value ...]      e.g. raw /vehicle/hvac fan_speed=3
  list                         every card: endpoint, field, values, CarProperty
  state [key]                  current values (JSON), e.g. state controls
EOF
      return 0 ;;
  esac
  need_device
  case "$name" in
    list)
      adb_ forward "tcp:$VC_PORT" tcp:8088 >/dev/null
      local py
      py="$(cat <<'PY'
import json, sys
for f in json.load(sys.stdin)["features"]:
    c = f.get("control", {})
    if c.get("kind") == "range":
        vals = "%s..%s" % (c.get("min"), c.get("max"))
    else:
        vals = "|".join(json.dumps(o["value"]).strip('"') for o in c.get("options", [])) or c.get("kind", "")
    if c.get("field"):
        field = "%s=<%s>" % (c["field"], vals)
    else:  # one field per area, e.g. door_fl=<false|true>
        field = "&".join("%s=<%s>" % (k, vals) for k in c.get("field_by_area", {}).values())
    params = "".join("%s=%s&" % kv for kv in c.get("params", {}).items())
    areas = ",".join(str(a["value"]) for a in f.get("areas", []))
    area = " %s=<%s>" % (c["area_field"], areas) if c.get("area_field") else ""
    prop = f.get("car_property", {}).get("name", "")
    print("%-28s PUT %s?%s%s%s  [%s]" % (f["id"], f.get("write_endpoint", ""), params, field, area, prop))
PY
)"
      curl -sS -m 10 "$VC/vehicle/catalog" | python3 -I -c "$py"
      return 0 ;;
    state)
      adb_ forward "tcp:$VC_PORT" tcp:8088 >/dev/null
      vc_state | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
k = sys.argv[1]
print(json.dumps(d[k] if k else d, ensure_ascii=False, indent=1))
' "$v"
      return 0 ;;
  esac
  [[ -n $v ]] || die "usage: signal $name <value>  (see: $0 signal help)"
  vc_activate
  case "$name" in
    gear)
      case "${v^^}" in
        P|PARK) v=Park ;; R|REVERSE) v=Reverse ;; N|NEUTRAL) v=Neutral ;; D|DRIVE) v=Drive ;;
        *) die "gear: P|R|N|D" ;;
      esac
      vc_put "/vehicle/gear?gear=$v" ;;
    speed) vc_controls speed "$v" ;;
    brake) vc_controls brake "$v" ;;
    accel|accelerator) vc_controls accelerator "$v" ;;
    steering|steer) vc_controls steering "$v" ;;
    turn|blinker)
      case "${v,,}" in
        off) v=Off ;; left) v=BlinkLeft ;; right) v=BlinkRight ;;
        flash-left) v=FlashLeft ;; flash-right) v=FlashRight ;;
        *) die "turn: off|left|right|flash-left|flash-right" ;;
      esac
      vc_put "/vehicle/turn-signal?mode=$v" ;;
    hazard) vc_put "/vehicle/lights?feature=hazard&value=$(bool "$v")" ;;
    seat) vc_put "/vehicle/seat?kind=occupied&value=$(bool "$v")&area=${3:-driver}" ;;
    door)
      local f
      case "$v" in
        front_left) f=door_fl ;; front_right) f=door_fr ;; rear_left) f=door_rl ;; rear_right) f=door_rr ;;
        *) die "door: front_left|front_right|rear_left|rear_right <open|closed>" ;;
      esac
      vc_put "/vehicle/body?$f=$(bool "${3:?door $v <open|closed>}")" ;;
    lock) vc_put "/vehicle/body?doors_locked=$(bool "$v")" ;;
    odometer) vc_put "/vehicle/odometer?traveled_distance_km=$v" ;;
    telltale) vc_put "/vehicle/telltales/simulator?color=Red&state=${3:?telltale $v <Off|On|Blink>}&type=$v" ;;
    raw)
      shift 2 || true
      local q="" kv
      for kv in "$@"; do q+="${q:+&}$kv"; done
      vc_put "$v${q:+?$q}" ;;
    *) die "unknown signal '$name' (see: $0 signal help)" ;;
  esac
}

# Driving regulation (UX restrictions) on/off. `cmd hmg_car_service` from the Pleos ADB guide is
# not on the v2.1.0 emulator image, so this drives the car instead: D at 30 km/h makes AAOS report
# MOVING (requires DO), P at 0 km/h makes it PARKED.
cmd_regulation() {
  local mode="${1:-}"
  [[ $mode == on || $mode == off ]] || die "usage: regulation on|off"
  need_device
  local out
  out="$(adb_ shell cmd hmg_car_service --service drivingstate driving_regulation "$mode" 2>&1 || true)"
  if [[ $out != *"Can't find service"* ]]; then
    log "hmg_car_service: ${out:-ok}"
  else
    log "hmg_car_service is not on this image; driving the car through the Virtual Console"
    vc_activate
    if [[ $mode == on ]]; then
      vc_put "/vehicle/gear?gear=Drive"
      vc_put "/vehicle/controls?speed=30&accelerator=20&brake=0&steering=0"
    else
      vc_put "/vehicle/controls?speed=0&accelerator=0&brake=100&steering=0"
      vc_put "/vehicle/gear?gear=Park"
    fi
    sleep 2
  fi
  # 0 PARKED, 1 IDLING, 2 MOVING; UxR is a CarUxRestrictions bit mask (80 = 0x50 NO_VIDEO|NO_SETUP)
  adb_ shell dumpsys car_service --services CarDrivingStateService 2>/dev/null | grep -m1 'Current Driving State' | sed 's/^ *//' || true
  adb_ shell dumpsys car_service --services CarUxRestrictionsManagerService 2>/dev/null | grep -m1 'Display id: 0 UXR' | sed 's/^ *//' || true
}

cmd_vc() {
  need_device
  adb_ forward "tcp:$VC_PORT" tcp:8088 >/dev/null
  echo "$VC/"
  log "open it in a Windows browser (WSL forwards localhost); car writes need it open or '$0 signal ...'"
}

usage() {
  cat >&2 <<EOF
usage: $0 <command> [args]
  start                      boot headless (or reuse a running one), wait for boot, print the serial
  stop                       shut the emulator down
  status                     running?, boot, vehicle bus, host RAM/CPU, driving state, UX restrictions
  install <apk> [flags]      adb install -r -t [flags] <apk>   (-g grants runtime permissions)
  launch [pkg/.Activity]     am start -W (default $EMU_APP)
  shot [file.png]            screenshot (default $EMU_SHOTS/shot-<time>.png), prints the path
  tap <x> <y> | swipe <x1> <y1> <x2> <y2> [ms] | key <BACK|HOME|...> | text <ascii>
                             (tap/swipe first stop the Pleos driving view, which swallows taps)
  keys <step>...             hold keys like a hardware keyboard (virtual USB keyboard via uinput):
                             tap:KEY  down:KEY  up:KEY  hold:KEY:MS  wait:MS   e.g. keys tap:d hold:up:3000
  dump                       uiautomator XML to stdout
  texts [package]            "x y text" for every node with text (tap targets)
  wait-text <text> [sec]     wait until the text is on screen (default 30 s), print "x y"
  tap-text <text> [sec]      wait for the text, then tap the center of its node
  signal <name> <value>      car signals via the Virtual Console (signal help)
  regulation on|off          driving regulation (UX restrictions) on/off
  logcat [app] [args]        adb logcat -v time [args]; 'app' = only $APP_PKG
  vc                         forward the Virtual Console and print its URL
  fix-net                    bring the vehicle bus (eth0) up if car signals stop working
  serial | adb <args>        print the serial | adb -s $SERIAL <args>
env: EMU_GPU=$EMU_GPU EMU_RAM_MB=$EMU_RAM_MB EMU_RENDER_THREADS=$EMU_RENDER_THREADS EMU_NICE=$EMU_NICE
     EMU_PORT=$EMU_PORT EMU_AVD=$EMU_AVD EMU_APP=$EMU_APP EMU_STOP_DRIVINGVIEW=$EMU_STOP_DRIVINGVIEW
share: flock -o /tmp/baro-emu.lock <command>   (-o: the emulator must not inherit the lock)
app data is Android user 10: $0 adb shell pm clear --user 10 $APP_PKG
EOF
  exit 2
}

cmd="${1:-}"
[[ -n $cmd ]] || usage
shift
case "$cmd" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  install) cmd_install "$@" ;;
  launch) cmd_launch "$@" ;;
  shot) cmd_shot "$@" ;;
  tap) cmd_tap "$@" ;;
  swipe) cmd_swipe "$@" ;;
  key) cmd_key "$@" ;;
  text) cmd_text "$@" ;;
  keys) cmd_keys "$@" ;;
  dump) cmd_dump ;;
  texts) cmd_texts "$@" ;;
  wait-text) cmd_wait_text "$@" ;;
  tap-text) cmd_tap_text "$@" ;;
  signal) cmd_signal "$@" ;;
  regulation) cmd_regulation "$@" ;;
  logcat) cmd_logcat "$@" ;;
  vc) cmd_vc ;;
  fix-net) need_device; ensure_vehicle_bus ;;
  serial) echo "$SERIAL" ;;
  adb) adb_ "$@" ;;
  -h|--help|help) usage ;;
  *) log "unknown command: $cmd"; usage ;;
esac
