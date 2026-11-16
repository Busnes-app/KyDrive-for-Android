#!/usr/bin/env bash
# Captures every screen in light and dark, on a phone and a tablet, into docs/screenshots/.
# Needs ANDROID_HOME and the API 36 google_apis x86_64 image. Creates its own AVDs
# (kydrive-phone, kydrive-tablet) on free console ports, so it leaves other emulators
# alone. Run from the repo root. With KYDRIVE_SERVER_BIN set, it also links each emulator to
# a throwaway server (scripts/screenshot-server.sh) and captures the linked screens.
set -euo pipefail
sdk=${ANDROID_HOME:?set ANDROID_HOME}
adb=$sdk/platform-tools/adb
# avdmanager and the emulator disagree on the default AVD directory on some hosts.
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
mkdir -p "$ANDROID_AVD_HOME"
image="system-images;android-36;google_apis;x86_64"
out=docs/screenshots
mkdir -p "$out"

server=
ca=()
if [ -n "${KYDRIVE_SERVER_BIN:-}" ]; then
  server=$(mktemp -d)
  trap '[ -z "$server" ] || scripts/screenshot-server.sh stop "$server"' EXIT
  scripts/screenshot-server.sh start "$server"
  ca=("-PscreenshotCa=$server/ca.pem")
fi
./gradlew -q :app:assembleDebug -PallowScreenshots=true "${ca[@]}"
apk=app/build/outputs/apk/debug/app-debug.apk
key() { "$adb" -s "$serial" shell input keyevent "$1"; }

# Taps the centre of the first node whose text or content-desc is exactly $1.
tap() {
  "$adb" -s "$serial" shell uiautomator dump /sdcard/ui.xml > /dev/null
  bounds=$("$adb" -s "$serial" exec-out cat /sdcard/ui.xml |
    grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" |
    head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$bounds" ] || { echo "no node named '$1' on screen" >&2; exit 1; }
  read -r x1 y1 x2 y2 <<< "$bounds"
  "$adb" -s "$serial" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# Saves screen $1 once text containing $2, unique to that screen, is in the view tree and
# the display has drawn it: a capture that differs from the previous screen's and is
# unchanged a second later. A cold activity is in the tree before its first frame.
shot() {
  file=$out/$form-$mode-$1.png
  found=
  for _ in $(seq 30); do
    "$adb" -s "$serial" shell uiautomator dump /sdcard/ui.xml > /dev/null
    "$adb" -s "$serial" exec-out cat /sdcard/ui.xml | grep -qF "text=\"$2" && { found=1; break; }
    sleep 1
  done
  [ -n "$found" ] || { echo "screen $1 never showed '$2' ($form, $mode)" >&2; exit 1; }
  rm -f "$file"
  for _ in $(seq 30); do
    "$adb" -s "$serial" exec-out screencap -p > "$file.new"
    if ! cmp -s "$file.new" "$prev" && cmp -s "$file.new" "$file"; then
      rm "$file.new"
      prev=$file
      return
    fi
    mv "$file.new" "$file"
    sleep 1
  done
  rm -f "$file"
  echo "screen $1 never settled ($form, $mode)" >&2
  exit 1
}
prev=

for form in phone tablet; do
  case $form in
    phone) device=pixel_8 ;;
    tablet) device=pixel_tablet ;;
  esac
  # adb only discovers emulators on even console ports 5554-5584.
  port=
  for p in $(seq 5584 -2 5554); do
    if ! ss -Hltn "sport = :$p or sport = :$((p + 1))" | grep -q .; then port=$p; break; fi
  done
  [ -n "$port" ] || { echo "no free emulator port" >&2; exit 1; }
  avd=kydrive-$form
  serial=emulator-$port
  if ! "$sdk/emulator/emulator" -list-avds | grep -qx "$avd"; then
    echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n "$avd" -k "$image" -d "$device"
  fi
  "$sdk/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim \
    -no-snapshot -gpu swiftshader_indirect > "/tmp/$avd.log" 2>&1 &
  emu=$!
  trap 'kill $emu 2>/dev/null || true; [ -z "$server" ] || scripts/screenshot-server.sh stop "$server"' EXIT
  booted() { [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
  fail() { echo "$1; last emulator output:" >&2; tail -40 "/tmp/$avd.log" >&2; exit 1; }
  timeout 300 "$adb" -s "$serial" wait-for-device || fail "adb never saw $serial"
  for _ in $(seq 150); do
    booted && break
    kill -0 $emu || fail "emulator exited"
    sleep 2
  done
  booted || fail "boot timed out"
  "$adb" -s "$serial" shell settings put global window_animation_scale 0
  "$adb" -s "$serial" shell settings put global transition_animation_scale 0
  "$adb" -s "$serial" shell settings put global animator_duration_scale 0
  # A frozen status bar, so captures differ only when the app's screen does.
  "$adb" -s "$serial" shell settings put global sysui_demo_allowed 1
  for cmd in "enter" "clock -e hhmm 1200" "notifications -e visible false" \
    "battery -e level 100 -e plugged false" "network -e wifi show -e level 4 -e mobile hide"; do
    # shellcheck disable=SC2086 # each entry is a command and its extras
    "$adb" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command $cmd > /dev/null
  done
  "$adb" -s "$serial" install -r "$apk" > /dev/null

  for mode in light dark; do
    if [ $mode = dark ]; then night=yes; else night=no; fi
    "$adb" -s "$serial" shell cmd uimode night $night
    "$adb" -s "$serial" shell pm clear org.kysecurity.drive > /dev/null
    "$adb" -s "$serial" shell am start -W -n org.kysecurity.drive/.MainActivity > /dev/null
    shot link "On a computer, open KyDrive"
    if [ -z "$server" ]; then
      tap Settings
      shot settings "Appearance"
      continue
    fi
    # Linked: a fresh one-time code per mode, handed in as if scanned.
    link=$(scripts/screenshot-server.sh link "$server")
    "$adb" -s "$serial" shell am force-stop org.kysecurity.drive
    "$adb" -s "$serial" shell am start -W -n org.kysecurity.drive/.MainActivity --es screenshot_link "'$link'" > /dev/null
    shot confirm "This links the phone to"
    tap Link
    # Linking offers the lock once; the flow turns it on later, from Settings.
    shot offer "Anyone holding this phone"
    tap "Not now"
    shot files "Budget 2026.xlsx"
    tap "Actions for Notes.txt"
    shot actions "Save to this phone"
    key BACK
    tap Add
    shot add "Upload files"
    key BACK
    tap Projects
    shot folder "Roadmap.md"
    tap "More options"
    tap Trash
    shot trash "Old draft.docx"
    key BACK
    key BACK
    tap Shared
    shot shared "Finance"
    tap Team
    shot workspace "Shared plan.pdf"
    tap Settings
    shot settings "Unlink this phone"
    # App lock: choose a PIN, see its settings, then a restart asks for it.
    tap "Lock KyDrive with a PIN"
    shot pin "8 to 12 digits"
    tap "New PIN"
    "$adb" -s "$serial" shell input text 73915062
    tap "New PIN again"
    "$adb" -s "$serial" shell input text 73915062
    tap OK
    shot lock "Lock after leaving the app"
    "$adb" -s "$serial" shell am force-stop org.kysecurity.drive
    "$adb" -s "$serial" shell am start -W -n org.kysecurity.drive/.MainActivity > /dev/null
    shot unlock "KyDrive is locked"
    tap PIN
    "$adb" -s "$serial" shell input text 73915062
    # Enter, not the button: on a landscape tablet the keyboard covers it.
    key ENTER
    shot unlocked "Budget 2026.xlsx"
  done

  "$adb" -s "$serial" emu kill > /dev/null || true
  wait $emu || true
  trap '[ -z "$server" ] || scripts/screenshot-server.sh stop "$server"' EXIT
done
ls -1 "$out"
