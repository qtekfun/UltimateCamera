#!/usr/bin/env bash
# Runs the Phase 0 spike on one phone over adb and collects the JSON report (docs/TESTING.md, docs/spike-report.md).
#
#   scripts/spike/run-spike.sh <serial> [--apk FILE] [--package ID] [--tests caps,matrix,sustained,concurrent,audio,net]
#                              [--seconds N] [--sustained N] [--net-host IP] [--net-link wifi|hotspot] [--out DIR] [--pull-videos]
#                              [--try-unadvertised]   (also attempt sizes/frame rates the camera does not advertise, incl. 8K)
#
# The phone must be unlocked. Device sessions take /tmp/pixel-device.lock, shared with the other projects that use the
# same phones. Results go to ~/.cache/ultimatecamera-work/spike/<model>/ by default (disk, not tmpfs).
set -euo pipefail

serial="${1:?usage: run-spike.sh <serial> [options]}"; shift
apk="" package="com.qtekfun.ultimatecamera" tests="caps,matrix,concurrent,audio" seconds=20 sustained=300 net_host="" net_link="" out="" pull_videos=0 try_unadvertised=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) apk="$2"; shift 2 ;;
    --package) package="$2"; shift 2 ;;
    --tests) tests="$2"; shift 2 ;;
    --seconds) seconds="$2"; shift 2 ;;
    --sustained) sustained="$2"; shift 2 ;;
    --net-host) net_host="$2"; shift 2 ;;
    --net-link) net_link="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --pull-videos) pull_videos=1; shift ;;
    --try-unadvertised) try_unadvertised=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

adb_() { adb -s "$serial" "$@"; }

# UC_DEVICE_LOCK lets a second phone run at the same time (two-phone tests) while the first run holds the shared lock.
exec 9> "${UC_DEVICE_LOCK:-/tmp/pixel-device.lock}"
echo "waiting for the device lock…"
flock 9

[ "$(adb_ get-state 2>/dev/null)" = "device" ] || { echo "device $serial is not connected/authorised" >&2; exit 1; }
model="$(adb_ shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')"
out="${out:-$HOME/.cache/ultimatecamera-work/spike/$model}"
mkdir -p "$out"

if [ -n "$apk" ]; then
  echo "installing $apk"
  adb_ install -r "$apk"
fi
for p in CAMERA RECORD_AUDIO BLUETOOTH_CONNECT; do adb_ shell pm grant "$package" "android.permission.$p" 2>/dev/null || true; done

adb_ shell input keyevent KEYCODE_WAKEUP
if adb_ shell dumpsys window | grep -qE "mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true"; then
  echo "the phone is locked: unlock it and run again" >&2; exit 1
fi

# Matrix: ~8 configs x (seconds + warmup + cooldown); sustained adds its own length; generous margin.
budget=$(( 600 + 12 * (seconds + 10) ))
case ",$tests," in *,sustained,*) budget=$(( budget + sustained + 60 )) ;; esac

adb_ logcat -c
extras=(--es tests "$tests" --ei seconds "$seconds" --ei sustainedSeconds "$sustained")
[ -n "$net_host" ] && extras+=(--es netHost "$net_host")
[ -n "$net_link" ] && extras+=(--es netLink "$net_link")
[ "$try_unadvertised" = 1 ] && extras+=(--ez tryUnadvertised true)
adb_ shell am start -S -W -n "$package/com.qtekfun.ultimatecamera.SpikeAutomation" "${extras[@]}" > /dev/null
echo "running ($tests), up to ${budget}s; progress:"

log="$out/logcat-$(date +%Y%m%d-%H%M%S).txt"
# Run adb itself in the background (not the adb_ function, whose subshell would leave adb orphaned on kill), without
# the lock descriptor: a leaked logcat must never keep the shared device lock.
adb -s "$serial" logcat -v time -s UCSPIKE:I > "$log" 9>&- &
logcat_pid=$!
trap 'kill "$logcat_pid" 2> /dev/null || true' EXIT
deadline=$(( SECONDS + budget )) shown=0
while [ "$SECONDS" -lt "$deadline" ]; do
  sleep 5
  total=$(wc -l < "$log")
  if [ "$total" -gt "$shown" ]; then
    sed -n "$(( shown + 1 )),${total}p" "$log" | grep -E "PIPELINE|EXPORTED|DONE|LUT_" | sed 's/^/  /' || true
    shown=$total
  fi
  grep -q "UCSPIKE.*DONE" "$log" && break
done
kill "$logcat_pid" 2> /dev/null || true
grep -q "UCSPIKE.*DONE" "$log" || { echo "timed out after ${budget}s (see $log)" >&2; exit 1; }

exported="$(grep -o 'EXPORTED Download/[^ ]*' "$log" | tail -n1 | cut -d' ' -f2 | tr -d '\r')"
[ -n "$exported" ] || { echo "no report exported (see $log)" >&2; exit 1; }
adb_ pull "/sdcard/$exported" "$out/" > /dev/null
echo "report: $out/$(basename "$exported")"

if [ "$pull_videos" = 1 ]; then
  mkdir -p "$out/videos"
  adb_ pull "/sdcard/Android/data/$package/files/spike/." "$out/videos/" > /dev/null || echo "could not pull videos"
  if command -v ffprobe > /dev/null; then
    for f in "$out"/videos/*.mp4; do
      [ -e "$f" ] || continue
      echo "$(basename "$f"): $(ffprobe -v error -select_streams v:0 -count_packets \
        -show_entries stream=codec_name,profile,pix_fmt,color_transfer,color_primaries,width,height,r_frame_rate,nb_read_packets \
        -of compact=p=0:nk=0 "$f")"
    done | tee "$out/videos/ffprobe.txt"
  fi
fi
