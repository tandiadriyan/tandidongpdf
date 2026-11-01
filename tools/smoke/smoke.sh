#!/usr/bin/env bash
# Emulator smoke test: installs the debug APK, opens a sample PDF, drives the main tools
# through UI Automator, saves screenshots, and fails if the app crashes.
set -u
PKG=com.tandidong.pdf
OUT=${SMOKE_OUT:-smoke-out}
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -n1)
MISSING=0

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
shot() {
  adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true
  dump
  echo "[$1] $(python3 tools/smoke/labels.py "$OUT/ui.xml")"
}
find_el() { python3 tools/smoke/ui.py "$OUT/ui.xml" "$1"; }
has() { dump; find_el "$1" >/dev/null; }
tap() {
  for _ in 1 2 3 4 5; do
    dump
    if c=$(find_el "$1"); then
      echo "tap '$1' at $c"
      adb shell input tap $c
      sleep 2
      return 0
    fi
    sleep 1.5
  done
  echo "::warning::Element not found: $1"
  shot "missing-$(echo "$1" | tr -c 'a-zA-Z0-9' '_')"
  MISSING=$((MISSING + 1))
  return 1
}
type_text() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep 1; }
# Hides the soft keyboard if it is showing (BACK only closes the IME then).
hide_kb() {
  if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent 4; sleep 1; fi
}
# Taps a tool in the horizontally scrolling tool bar, scrolling it left until the tool is visible.
tap_tool() {
  for _ in 1 2 3 4 5; do
    dump
    if c=$(find_el "$1"); then
      echo "tap tool '$1' at $c"
      adb shell input tap $c
      sleep 2
      return 0
    fi
    if y=$(find_el "Discard and close"); then
      y=${y#* }
      adb shell input swipe $((W*70/100)) $y $((W*20/100)) $y 400
      sleep 1.5
    else
      sleep 1.5
    fi
  done
  echo "::warning::Tool not found: $1"
  shot "missing-tool-$(echo "$1" | tr -c 'a-zA-Z0-9' '_')"
  MISSING=$((MISSING + 1))
  return 1
}
# Types into the text field whose label is $1.
fill() { tap "$1" && type_text "$2"; }
crashed() { adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" >/dev/null; }
check_crash() {
  if crashed; then
    echo "::error::App crashed during: $1"
    adb logcat -d | grep -E -A 50 "FATAL EXCEPTION" | head -n 150
    shot "crash"
    exit 1
  fi
}
# Dismisses system "isn't responding" dialogs (e.g. a slow launcher right after boot).
dismiss_system() {
  for _ in 1 2 3 4; do
    dump
    if find_el "*isn't responding*" >/dev/null; then
      echo "Dismissing a system 'not responding' dialog"
      if c=$(find_el "Wait"); then adb shell input tap $c; else adb shell input keyevent 4; fi
      sleep 3
    else
      return 0
    fi
  done
}
# Returns to the document's main toolbar (closing tools, dialogs and sheets).
ensure_main() {
  for _ in 1 2 3 4 5; do
    dismiss_system
    dump
    if find_el "Comment" >/dev/null && find_el "Tools" >/dev/null; then return 0; fi
    if c=$(find_el "OK"); then adb shell input tap $c; sleep 2; continue; fi
    if c=$(find_el "Done"); then adb shell input tap $c; sleep 3; continue; fi
    if c=$(find_el "Cancel"); then adb shell input tap $c; sleep 2; continue; fi
    adb shell input keyevent 4; sleep 2
  done
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

# 1. Launch and open the sample document (retrying if the freshly booted system is sluggish)
sleep 5
dismiss_system
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 4; dismiss_system; shot 00-home; check_crash "launch"
OPENED=0
for attempt in 1 2 3; do
  adb shell am start -W -a android.intent.action.VIEW -d "file:///data/data/$PKG/files/inbox/sample.pdf" -t application/pdf -n $PKG/.MainActivity >/dev/null
  sleep 8; dismiss_system; check_crash "open document"
  if has "sample.pdf"; then OPENED=1; break; fi
  echo "Document not visible yet (attempt $attempt)"
done
shot 01-opened
[ "$OPENED" = 1 ] || { echo "::error::Document did not open"; exit 1; }

# 2. Scroll, double-tap zoom in and out
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; sleep 2
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 300; sleep 2
adb shell "input tap $((W/2)) $((H/2)); input tap $((W/2)) $((H/2))"; sleep 3; shot 02-zoomed
adb shell "input tap $((W/2)) $((H/2)); input tap $((W/2)) $((H/2))"; sleep 3
check_crash "scroll and zoom"

# 3. Highlight text
ensure_main
tap "Comment" && tap "Highlight" && {
  adb shell input swipe $((W*15/100)) $((H*25/100)) $((W*85/100)) $((H*25/100)) 600; sleep 2
  shot 03-highlight-pending; tap "Done"; sleep 4; shot 04-highlight-saved
}
check_crash "highlight"

# 4. Draw with the pen
ensure_main
tap "Comment" && tap "Draw" && {
  adb shell input swipe $((W*30/100)) $((H*55/100)) $((W*60/100)) $((H*62/100)) 500; sleep 1
  tap "Done"; sleep 4; shot 05-ink
}
check_crash "pen"

# 5. Sticky note
ensure_main
tap "Comment" && tap_tool "Note" && {
  adb shell input tap $((W*70/100)) $((H*40/100)); sleep 2
  type_text "Smoke test note"; tap "OK"; tap "Done"; sleep 4; shot 06-note
}
check_crash "note"

# 6. Search & redact email addresses
ensure_main
tap "Redact" && tap "Search & redact" && tap "Email addresses" && tap "Find & mark" && {
  sleep 4; shot 07-redact-marked
  tap "Apply*" && tap "Apply" && { sleep 6; shot 08-redacted; tap "OK"; }
}
check_crash "redact"

# 7. Fill & Sign: date and the form field
ensure_main
tap "Fill & Sign" && tap "Date" && {
  adb shell input tap $((W/2)) $((H*45/100)); sleep 1
  tap "Done"; sleep 4; shot 09-date
}
ensure_main
tap "Fill & Sign" && tap_tool "Fields list" && tap "Full name" && {
  tap "Full name"; type_text "Ada Lovelace"; tap "Set"; sleep 4; shot 10-form-filled
}
check_crash "fill & sign"

# 8. Edit existing text
ensure_main
tap "Edit" && tap "Edit text" && {
  adb shell input tap $((W*30/100)) $((H*21/100)); sleep 3
  shot 11-edit-text
  if has "Replace"; then tap "Replace"; sleep 4; fi
}
check_crash "edit text"

# 9. Organize pages: rotate the first page and apply
ensure_main
tap "Pages" && {
  sleep 4; shot 12-organize
  tap "Page 1" && tap "Rotate right" && tap "Apply" && sleep 4
  shot 13-organized
  adb shell input keyevent 4; sleep 2
}
check_crash "organize"

# 10. Tools: compress, watermark, page numbers
ensure_main
tap "Tools" && { sleep 2; shot 14-tools; tap "Compress" && tap "Compress" && { sleep 6; shot 15-compressed; tap "OK"; }; }
check_crash "compress"
ensure_main
tap "Tools" && tap "Text watermark" && tap "Add" && { sleep 5; shot 16-watermark; }
check_crash "watermark"
ensure_main
tap "Tools" && tap "Headers & page numbers" && tap "Apply" && { sleep 5; shot 17-page-numbers; }
check_crash "page numbers"

# 11. Search
ensure_main
tap "Search" && tap "Search in document" && {
  type_text "revenue"; adb shell input keyevent 66; sleep 4; shot 18-search
  hide_kb
  tap "Close search"
}
check_crash "search"

# 12. Bookmarks panel
ensure_main
tap "More options" && tap "Bookmarks" && { sleep 3; shot 19-bookmarks; adb shell input keyevent 4; sleep 2; }
check_crash "bookmarks"

# 12b. Edit images (page 3 has a picture), links, background, attachments
ensure_main
tap "More options" && tap "Go to page" && { fill "1 – 3" "3"; hide_kb; tap "OK"; sleep 3; }
tap "Edit" && tap_tool "Images" && {
  adb shell input tap $((W*35/100)) $((H*24/100)); sleep 3
  shot 20a-image-selected
  if has "Delete image"; then
    adb shell input swipe $((W*35/100)) $((H*24/100)) $((W*50/100)) $((H*30/100)) 600; sleep 5
    shot 20b-image-moved
    adb shell input tap $((W*50/100)) $((H*30/100)); sleep 3
    tap "Delete image"; sleep 4; shot 20c-image-deleted
  else
    echo "::warning::No image selected on page 3"
  fi
  tap_tool "Link" && {
    adb shell input swipe $((W*15/100)) $((H*45/100)) $((W*60/100)) $((H*48/100)) 500; sleep 2
    fill "https://… or name@example.com" "example.com"; hide_kb
    tap "Add"; sleep 4; shot 20d-link-added
  }
  tap_tool "Background" && { tap "Apply"; sleep 4; shot 20e-background; }
  tap "Done"; sleep 3
}
check_crash "edit images and links"
ensure_main
tap "More options" && tap "Attachments" && { sleep 2; shot 20f-attachments; adb shell input keyevent 4; sleep 2; }
check_crash "attachments"

# 13. Prepare form: add a text field and a checkbox, then review the fields list
ensure_main
tap "Tools" && tap "Prepare form" && {
  sleep 2
  tap_tool "Text field" && {
    adb shell input swipe $((W*15/100)) $((H*58/100)) $((W*60/100)) $((H*61/100)) 500; sleep 2
    shot 21-new-field-dialog
    tap "Add"; sleep 4
  }
  tap_tool "Checkbox" && { adb shell input tap $((W*70/100)) $((H*60/100)); sleep 2; tap "Add"; sleep 4; }
  shot 22-form-fields
  tap_tool "Edit fields" && { adb shell input tap $((W*35/100)) $((H*595/1000)); sleep 2; shot 23-field-properties; tap "Cancel"; }
  tap "Done"; sleep 3
}
check_crash "prepare form"

# 14. Digital signature: create a self-signed ID and sign
ensure_main
tap "Fill & Sign" && tap_tool "Digital ID" && {
  adb shell input swipe $((W*40/100)) $((H*68/100)) $((W*85/100)) $((H*75/100)) 500; sleep 2
  shot 24-cert-dialog
  tap "Create ID" && {
    fill "Name" "Smoke Tester"; hide_kb
    fill "Password (min. 6 characters)" "secret123"; hide_kb
    fill "Confirm password" "secret123"; hide_kb
    shot 25-create-id
    tap "Create"; sleep 8
  }
  fill "Digital ID password" "secret123"; hide_kb
  shot 26-sign-dialog
  tap "Sign" && { sleep 10; shot 27-signed; tap "OK"; sleep 3; }
  if has "Signed by*"; then echo "Digital signature verified in the app banner"; else echo "::warning::No signature banner after signing"; fi
  shot 28-signature-banner
}
check_crash "digital signature"

# 15. Leave the document (discard changes) and return home
ensure_main
adb shell input keyevent 4; sleep 2
if has "Discard"; then tap "Discard"; fi
sleep 2; shot 30-home-after; check_crash "close"

# 16. Screens that open from home
ensure_home() {
  for _ in 1 2 3 4; do
    if has "Settings"; then return 0; fi
    adb shell input keyevent 4; sleep 2
    if has "Discard"; then tap "Discard"; fi
  done
}
ensure_home
tap "Combine files" && { sleep 2; shot 31-combine; adb shell input keyevent 4; sleep 2; }
tap "Compare" && { sleep 2; shot 32-compare; adb shell input keyevent 4; sleep 2; }
tap "Settings" && { sleep 2; shot 33-settings; adb shell input keyevent 4; sleep 2; }
check_crash "tool screens"

echo "---- render log ----"
adb logcat -d -s TandidongRender:D | head -n 15
echo "rendered pages: $(adb logcat -d -s TandidongRender:D | grep -c 'page=')"
echo "---- core warnings ----"
adb logcat -d -s TandidongCore:W | tail -n 40
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
if [ "$MISSING" -ne 0 ]; then
  echo "::warning::$MISSING UI step(s) could not be completed; see screenshots."
fi
echo "Smoke test finished without crashes."
