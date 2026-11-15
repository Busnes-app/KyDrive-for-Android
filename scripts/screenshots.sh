#!/usr/bin/env bash
# Captures every screen in light and dark, on a phone and a tablet, into docs/screenshots/.
# Needs ANDROID_HOME and the API 36 google_apis x86_64 image. Creates its own AVDs
# (kydrive-phone, kydrive-tablet) on free console ports, so it leaves other emulators
# alone. Run from the repo root.
set -euo pipefail
sdk=${ANDROID_HOME:?set ANDROID_HOME}
adb=$sdk/platform-tools/adb
image="system-images;android-36;google_apis;x86_64"
out=docs/screenshots
mkdir -p "$out"

./gradlew -q :app:assembleDebug -PallowScreenshots=true
apk=app/build/outputs/apk/debug/app-debug.apk

# Taps the centre of the first node whose text or content-desc is exactly $1.
tap() {
  "$adb" -s "$serial" shell uiautomator dump /sdcard/ui.xml > /dev/null
  bounds=$("$adb" -s "$serial" exec-out cat /sdcard/ui.xml |
    grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" |
    head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$bounds" ] || { echo "no node named '$1' on screen" >&2; exit 1; }
  read -r x1 y1 x2 y2 <<< "$bounds"
  "$adb" -s "$serial" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
  sleep 1
}

shot() {
  "$adb" -s "$serial" exec-out screencap -p > "$out/$form-$mode-$1.png"
}

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
    echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" -s create avd -n "$avd" -k "$image" -d "$device"
  fi
  "$sdk/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim \
    -no-snapshot -gpu swiftshader_indirect > "/tmp/$avd.log" 2>&1 &
  emu=$!
  trap 'kill $emu 2>/dev/null || true' EXIT
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
  "$adb" -s "$serial" install -r "$apk" > /dev/null

  for mode in light dark; do
    if [ $mode = dark ]; then night=yes; else night=no; fi
    "$adb" -s "$serial" shell cmd uimode night $night
    "$adb" -s "$serial" shell am force-stop org.kysecurity.drive
    "$adb" -s "$serial" shell am start -W -n org.kysecurity.drive/.MainActivity > /dev/null
    sleep 2
    shot files
    tap Shared
    shot shared
    tap Settings
    shot settings
  done

  "$adb" -s "$serial" emu kill > /dev/null || true
  wait $emu || true
  trap - EXIT
done
ls -1 "$out"
