#!/usr/bin/env bash
# Installs the APK on the running emulator, walks through the main user journeys (browse, book,
# publish, rate, sign up), saves screenshots and fails if the app crashed.
APK="$1"
OUT="$2"
PKG=com.carsathi.app
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
n=0

shot() { n=$((n + 1)); adb exec-out screencap -p > "$OUT/$(printf '%02d' $n)-$1.png"; echo "shot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1; }
tap() { # tap <text> [index]   (prefix text with "=" for an exact match)
  dump
  local c
  c=$(python3 "$HERE/find_node.py" /tmp/ui.xml "$1" "${2:-0}") || { echo "not found: $1"; return 1; }
  adb shell input tap $c
  sleep "${WAIT:-3}"
}
hide_kb() { adb shell dumpsys input_method | grep -q "mInputShown=true" && { adb shell input keyevent KEYCODE_BACK; sleep 1; }; return 0; }
type_text() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep 1; hide_kb; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }
scroll() { adb shell input swipe 540 1700 540 600 400; sleep 1; }
next() { tap "=Next"; }

adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION || true
gps() { adb emu geo fix 77.2195 28.6328 > /dev/null 2>&1; sleep 1; adb emu geo fix 77.2196 28.6329 > /dev/null 2>&1; sleep 1; }  # Connaught Place, Delhi
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 10
shot welcome

# ---------------------------------------------------------------- browse as the demo member
WAIT=8 tap "Explore with the demo account"
shot search-home

# ---------------------------------------------------------------- current location + exact points
gps
tap "Leaving from"; shot city-picker-current-location
WAIT=8 tap "Use current location"
shot search-from-current-location
tap "Going to"; tap "Jaipur"
shot dropoff-point-picker
tap "Sindhi Camp Bus Stand"
shot search-with-exact-points
WAIT=6 tap "=Search rides"
shot results
tap "Filter" && { shot filters; back; }
tap "seat" || { tap "Next day"; shot results-next-day; tap "seat"; }
shot ride-details; scroll; shot ride-details-more

# ---------------------------------------------------------------- book a seat (UPI)
tap "=Book" || tap "=Request to book"
shot booking
tap "UPI ID (e.g. name@okaxis)" && type_text "kabir@okaxis"
WAIT=5 tap "Book for" || WAIT=5 tap "Send request"
shot booked
tap "See your rides"
shot your-rides-after-booking

# ---------------------------------------------------------------- rate passengers of a past ride
tap "=Past"; shot your-rides-past
tap "Rate your passengers" && {
  shot manage-past-ride
  tap "=Rate" && {
    tap "5 stars"
    tap "Share some details" && type_text "Great company on the way to Jaipur"
    shot rate
    tap "Submit rating"
    shot after-rating
  }
  back
}
tap "=Upcoming"

# ---------------------------------------------------------------- driver: accept a request
tap "You're driving" && {
  shot manage-ride
  tap "=Accept" && shot request-accepted
  back
}

# ---------------------------------------------------------------- inbox
tap "=Inbox"; shot inbox
tap "Rahul" && {
  tap "Write a message" && type_text "Thanks, see you there"
  tap "Send"
  sleep 6
  shot chat-with-reply
  back
}

# ---------------------------------------------------------------- profile
tap "=Profile"; shot profile
tap "=Account"; shot account
tap "Payments & refunds" && { shot transactions; back; }
tap "=About you"

# ---------------------------------------------------------------- publish a full ride
tap "=Publish"; shot publish-from
gps
WAIT=8 tap "Use current location"
shot publish-pickup-current-location
tap "Choose on the map" && {
  sleep 8; shot map-pin-picker
  WAIT=4 tap "Confirm this point"
  shot publish-pickup-pinned
}
next
tap "Jaipur"
shot publish-dropoff-picker
tap "Type the exact address" && type_text "MI Road"
WAIT=4 tap "Set as the exact"
shot publish-dropoff-typed
next
shot publish-stopovers; next
shot publish-date; next
shot publish-time; next
shot publish-car; next
shot publish-seats; next
shot publish-booking; next
shot publish-price; next
shot publish-comment; next
shot publish-return
WAIT=5 tap "=Publish ride"
shot published
tap "See my ride"
shot my-new-ride
sleep 22
shot my-new-ride-after-booking
back

tap "Notifications" && { shot notifications; back; }

# ---------------------------------------------------------------- sign out and sign up a new member
tap "=Profile"; tap "=Account"
scroll
tap "=Log out" && WAIT=4 tap "=Log out" -1
shot logged-out
tap "Sign up with email"
tap "=Email" && type_text "asha.verma@example.com"; next
tap "First name" && type_text "Asha"
tap "Last name" && type_text "Verma"; next
tap "DD/MM/YYYY" && tap "=OK"; next
tap "Ms / Mrs"; next
tap "=Password" && type_text "Passw0rd1"; next
next
tap "I accept the Terms"
shot signup-terms
WAIT=6 tap "Create my account"
shot new-member-home
tap "=Profile"; shot new-member-profile

echo "---- crash buffer ----"
adb logcat -d -b crash | tee "$OUT/crash.txt"
adb logcat -d | grep -E "AndroidRuntime|FATAL|carsathi" | tail -200 > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/crash.txt"; then
  echo "App crashed"
  exit 1
fi
echo "Smoke test finished without crashes"
