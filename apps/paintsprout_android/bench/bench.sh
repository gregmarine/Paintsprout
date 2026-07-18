#!/usr/bin/env bash
# Per-tool frame-time benchmark for Paintsprout, run against the connected
# device. Injects an identical set of stylus strokes for each drawing tool and
# records the app's RenderThread frame-time percentiles from dumpsys gfxinfo —
# the measurement that actually catches GPU-side jank (onDraw wall time is
# misleading; see the tray-phase perf notes).
#
# The app is force-restarted before each tool so every run starts from a fresh
# canvas and the tools can't affect each other through committed paint.
#
# Caveats, identical across runs (so relative comparisons hold):
#  - `input stylus swipe` injects fewer MOVE samples than a real pen and
#    reports constant pressure / no tilt, so absolute numbers are gentler
#    than real drawing.
#  - Coordinates assume the Movink in landscape (2200x1440) with the tool
#    rail on the left edge.
#
# Usage:  bench/bench.sh [output-file]      (default: bench/results/last-run.txt)
#         SERIAL=<adb-serial> to override the device.
set -euo pipefail

SERIAL="${SERIAL:-5HL21V5007384}"
PKG="com.symmetricalpalmtree.paintsprout"
ACT="$PKG/.MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/results/last-run.txt}"
mkdir -p "$(dirname "$OUT")"

ADB() { adb -s "$SERIAL" "$@"; }

# Tool-rail tap targets (x is the rail, y per tool), landscape px.
RAIL_X=67
TOOLS=(
  "pencil 73"
  "pen 153"
  "brush 532"
  "watercolor 614"
  "marker 691"
  "spray 767"
  "eraser 845"
)

stroke() {
  ADB shell input stylus swipe "$@"
  sleep 1 # let the bake land before the next stroke
}

bench_tool() {
  local name=$1 y=$2
  ADB shell am force-stop "$PKG"
  sleep 1
  ADB shell am start -n "$ACT" >/dev/null
  sleep 4 # launch + surface generation
  ADB shell input tap "$RAIL_X" "$y"
  sleep 1
  ADB shell dumpsys gfxinfo "$PKG" reset >/dev/null
  sleep 0.5
  # The fixed stroke set: three medium diagonals and one slow long stroke
  # (the slow one is the long-stroke stress case for the live preview).
  stroke 400 300 1800 1100 2500
  stroke 400 1100 1800 300 2500
  stroke 300 700 1900 800 2500
  stroke 500 400 1700 1000 5000
  sleep 2
  {
    echo "== $name =="
    ADB shell dumpsys gfxinfo "$PKG" | awk '/^Total frames rendered/,/^HISTOGRAM/' | grep -v '^HISTOGRAM'
    echo
  } >>"$OUT"
}

{
  echo "# Paintsprout frame bench"
  echo "# date: $(date '+%Y-%m-%d %H:%M')"
  echo "# commit: $(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo '?')"
  echo "# device: $(ADB shell getprop ro.product.model | tr -d '\r') (Android $(ADB shell getprop ro.build.version.release | tr -d '\r'))"
  echo
} >"$OUT"

for t in "${TOOLS[@]}"; do
  # shellcheck disable=SC2086
  bench_tool $t
done

echo "Done: $OUT"
