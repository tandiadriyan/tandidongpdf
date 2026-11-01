#!/usr/bin/env bash
# Emulator smoke test: installs the debug APK, opens a sample PDF, drives the main tools
# through UI Automator, saves screenshots, and fails if the app crashes.
set -u
PKG=com.tandidong.pdf
OUT=${SMOKE_OUT:-smoke-out}
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -n1)
FAILED=0

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
tap() {
  for _ in 1 2 3 4 5 6; do
    dump
    if c=$(python3 tools/smoke/ui.py "$OUT/ui.xml" "$1"); then
      echo "tap '$1' at $c"
      adb shell input tap $c
      sleep 2
      return 0
    fi
    sleep 2
  done
  echo "::warning::Element not found: $1"
  shot "missing-$(echo "$1" | tr -c 'a-zA-Z0-9' '_')"
  FAILED=1
  return 1
}
has() { dump; python3 tools/smoke/ui.py "$OUT/ui.xml" "$1" >/dev/null; }
crashed() { adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" >/dev/null; }
check_crash() {
  if crashed; then
    echo "::error::App crashed during: $1"
    adb logcat -d -b crash | tail -n 80
    adb logcat -d | grep -E -A 40 "FATAL EXCEPTION" | head -n 120
    shot "crash"
    exit 1
  fi
}

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APK"
adb push tools/smoke/sample.pdf /data/local/tmp/sample.pdf >/dev/null
adb shell chmod 644 /data/local/tmp/sample.pdf
adb shell run-as $PKG mkdir -p files/inbox
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/sample.pdf > files/inbox/sample.pdf'"
adb logcat -c

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n1)
W=${SIZE%x*}; H=${SIZE#*x}
echo "Screen ${W}x${H}"

# 1. Launch and open the sample document
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 4; shot 00-home; check_crash "launch"
adb shell am start -W -a android.intent.action.VIEW -d "file:///data/data/$PKG/files/inbox/sample.pdf" -t application/pdf -n $PKG/.MainActivity >/dev/null
sleep 8; shot 01-opened; check_crash "open document"
has "sample.pdf" || { echo "::error::Document did not open"; FAILED=1; }

# 2. Zoom and scroll
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; sleep 2
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 300; sleep 2
shot 02-scrolled; check_crash "scroll"

# 3. Highlight text with the Comment tools
tap "Comment" && tap "Highlight" && {
  adb shell input swipe $((W*15/100)) $((H*30/100)) $((W*85/100)) $((H*30/100)) 600; sleep 2
  shot 03-highlight-pending
  tap "Done"; sleep 4; shot 04-highlight-saved
}
check_crash "highlight"

# 4. Draw with the pen
tap "Comment" && tap "Draw" && {
  adb shell input swipe $((W*30/100)) $((H*55/100)) $((W*60/100)) $((H*62/100)) 500; sleep 1
  tap "Done"; sleep 4; shot 05-ink
}
check_crash "pen"

# 5. Search & redact email addresses
tap "Redact" && tap "Search & redact" && tap "Email addresses" && tap "Find & mark" && {
  sleep 4; shot 06-redact-marked
  tap "Apply*" && tap "Apply" && { sleep 6; shot 07-redacted; tap "OK"; }
}
check_crash "redact"

# 6. Fill & Sign: add a date
tap "Fill & Sign" && tap "Date" && {
  adb shell input tap $((W/2)) $((H*45/100)); sleep 1
  tap "Done"; sleep 4; shot 08-date
}
check_crash "fill & sign"

# 7. Organize pages
tap "Pages" && { sleep 4; shot 09-organize; adb shell input keyevent 4; sleep 2; }
check_crash "organize"

# 8. More tools sheet and compression
tap "More" && { sleep 2; shot 10-more-tools; tap "Compress" && tap "Compress" && { sleep 6; shot 11-compressed; tap "OK"; }; }
check_crash "compress"

# 9. Search
tap "Search" && {
  adb shell input text revenue; adb shell input keyevent 66; sleep 4; shot 12-search
  adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
}
check_crash "search"

# 10. Leave the document (discard changes) and return home
adb shell input keyevent 4; sleep 2
if has "Discard"; then tap "Discard"; fi
sleep 2; shot 13-home-after; check_crash "close"

# 11. Tools that open their own screens
tap "Images to PDF" && { sleep 3; shot 14-images; adb shell input keyevent 4; sleep 2; adb shell input keyevent 4; sleep 2; }
tap "Settings" && { sleep 2; shot 15-settings; adb shell input keyevent 4; sleep 2; }
check_crash "tool screens"

adb logcat -d -s TandidongCore:W AndroidRuntime:E > "$OUT/logcat.txt" 2>/dev/null || true
if [ "$FAILED" -ne 0 ]; then
  echo "::warning::Some UI steps could not be completed; see screenshots."
fi
echo "Smoke test finished without crashes."
