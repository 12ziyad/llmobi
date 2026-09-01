#!/usr/bin/env bash
# Drive the app over adb, but never blindly.
#
# Every tap is preceded by a focus check. If anything other than LLMobi is in
# front - because the phone's owner picked it up, or a system dialog appeared -
# the script stops rather than firing a tap into somebody else's app. Learned
# this the hard way after a stray tap opened the Contacts app.
set -u
export MSYS_NO_PATHCONV=1

D="${1:?usage: drive.sh <serial>}"
PKG="app.llmobi"

focus() {
  timeout 25 adb -s "$D" shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'
}

ours() {
  focus | grep -q "$PKG"
}

require_focus() {
  if ! ours; then
    echo "ABORT: $PKG is not in front."
    echo "  focus: $(focus | sed 's/.*mCurrentFocus=//')"
    exit 2
  fi
}

tap() {  # tap X Y "label"
  require_focus
  timeout 30 adb -s "$D" shell input tap "$1" "$2" >/dev/null 2>&1
  sleep "${4:-2}"
  echo "  tapped ${3:-$1,$2}"
}

shot() {
  timeout 90 adb -s "$D" exec-out screencap -p > "shots/$1" 2>/dev/null
  echo "  shot -> shots/$1"
}

open_app() {
  timeout 40 adb -s "$D" shell "am start -S -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep 4
  require_focus
  echo "  app in front"
}

case "${2:-}" in
  open)   open_app ;;
  tap)    tap "$3" "$4" "${5:-}" "${6:-2}" ;;
  shot)   shot "$3" ;;
  check)  ours && echo "ok" || { echo "not focused"; exit 2; } ;;
  *)      echo "commands: open | tap X Y [label] [sleep] | shot FILE | check"; exit 1 ;;
esac
