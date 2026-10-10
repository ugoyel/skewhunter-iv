#!/usr/bin/env bash
# Installs the APK on the running emulator, walks through the main screens, saves screenshots
# and fails if the app crashed.
APK="$1"
OUT="$2"
PKG=com.carsathi.app
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
n=0

shot() { n=$((n + 1)); adb exec-out screencap -p > "$OUT/$(printf '%02d' $n)-$1.png"; echo "shot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1; }
tap() { # tap <text> [index]
  dump
  local c
  c=$(python3 "$HERE/find_node.py" /tmp/ui.xml "$1" "${2:-0}") || { echo "not found: $1"; return 1; }
  adb shell input tap $c
  sleep "${WAIT:-3}"
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }
scroll() { adb shell input swipe 540 1700 540 600 400; sleep 1; }

adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 10
shot welcome

WAIT=8 tap "Explore with the demo account"
shot search-home
scroll; shot search-home-more
adb shell input swipe 540 600 540 1700 400; sleep 1

tap "Jaipur" || tap "Pune"
sleep 3
shot results
tap "Filter" && { shot filters; back; }
tap "seat" || { tap "Next day"; shot results-next-day; tap "seat"; }
true && { shot ride-details; scroll; shot ride-details-more; scroll; shot ride-details-end; }
tap "=Book" || tap "=Request to book"
shot booking
back; back; back

tap "Your rides"; shot your-rides
tap "Past"; shot your-rides-past; tap "Upcoming"
tap "You're driving" && { shot manage-ride; scroll; shot manage-ride-more; back; }

tap "Inbox"; shot inbox
tap "Rahul" && { shot chat; back; }

tap "Profile"; shot profile
scroll; shot profile-more
tap "Account"; shot account
tap "Payments & refunds" && { shot transactions; back; }
tap "About you"

tap "Vehicles" 1 || true
tap "Swift" && { shot add-vehicle; back; }

tap "Search"
tap "Publish"; shot publish-from
tap "Delhi" && { shot publish-pickup; }
back; back; back

tap "Notifications" && { shot notifications; back; }

echo "---- crash buffer ----"
adb logcat -d -b crash | tee "$OUT/crash.txt"
adb logcat -d | grep -E "AndroidRuntime|FATAL|carsathi" | tail -200 > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/crash.txt"; then
  echo "App crashed"
  exit 1
fi
echo "Smoke test finished without crashes"
