#!/usr/bin/env bash
# ADB-side capture for a full dock/undock cycle.
#
# Use this when you want the log streaming to a file on a computer rather than
# filling /sdcard - the one case where ADB beats doing it on the device. For
# everything routine, use tools/dex-probe.sh in a Termux root shell instead.
#
#   ./capture-probe-log.sh [output-file]
#
# Walks you through both halves of the diff, then leaves the log tailing.

set -euo pipefail

TAG=DexRRProbe
ACTION=com.tiimoo.dexrefresh.ACTION_SNAPSHOT
OUT=${1:-dexprobe-$(date +%Y%m%d-%H%M%S).log}

command -v adb >/dev/null || { echo "adb not found in PATH" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device connected" >&2; exit 1; }

snapshot() {
    adb shell "su -c 'am broadcast -a $ACTION --es label $1'" >/dev/null
    echo "  -> snapshot '$1' requested"
    sleep 3
}

echo "Clearing the log buffer."
adb logcat -c || true

echo "Starting capture to $OUT"
adb logcat -v time -s "$TAG":V | tee "$OUT" &
TEE_PID=$!
# Keep the tail alive until the user stops it, but do not leave it orphaned.
trap 'kill "$TEE_PID" 2>/dev/null || true' EXIT
sleep 2

echo
echo "STEP 1: make sure DeX is NOT running and the phone is idle."
read -r -p "Press Enter when ready... " _
snapshot dex-off

echo
echo "STEP 2: start DeX now and let it settle."
read -r -p "Press Enter once DeX is up... " _
snapshot dex-on

echo
echo "Both snapshots captured. The log is still tailing - dock/undock now if you"
echo "want the transitions too. Ctrl-C when done; output is in $OUT"
wait "$TEE_PID"
