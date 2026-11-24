#!/bin/bash
# Runs examples/sample-tests on an iOS simulator with the freshly built CLI; used by CI, works locally too.
# Needs: ./gradlew :cli:linkReleaseExecutableMacosArm64, ./scripts/build-ios-runner.sh, ./scripts/build-sample-ios.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GEKKO="${GEKKO:-cli/build/bin/macosArm64/releaseExecutable/gekko.kexe}"
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
OUT=build/e2e
rm -rf "$OUT" && mkdir -p "$OUT"

# A booted iPhone simulator, or the first available iPhone
UDID="$(xcrun simctl list devices booted | grep -E 'iPhone' | sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -n 1 || true)"
if [ -z "$UDID" ]; then
  UDID="$(xcrun simctl list devices available | grep -E 'iPhone' | head -n 1 | sed -n 's/.*(\([0-9A-F-]\{36\}\)).*/\1/p')"
  [ -n "$UDID" ] || { echo "no iPhone simulator available" >&2; exit 1; }
  xcrun simctl boot "$UDID"
fi
xcrun simctl bootstatus "$UDID" -b

"$GEKKO" --version
"$GEKKO" install sample-ios/build/Sample.app --project examples/sample-tests --platform ios -s "$UDID"
"$GEKKO" run --project examples/sample-tests --platform ios -s "$UDID" --exclude-tags broken,android-only,web-only,location,tv \
  --junit "$OUT/junit.xml" --output "$OUT/results" 2>&1 | tee "$OUT/run.log"

# A drag moves the list exactly as far as the finger, a fast swipe flings it, a slow one does not
./scripts/check-touches.sh "$OUT/run.log"

# iOS has no back key: pressing it fails with exit 1 and says so. The in-app agent hands the key to the system runner,
# which refuses it as well
"$GEKKO" launch --project examples/sample-tests --platform ios -s "$UDID"
status=0
refusal="$("$GEKKO" press-key back --project examples/sample-tests --platform ios -s "$UDID" 2>&1)" || status=$?
if [ "$status" -ne 1 ] || ! grep -qi "no back key" <<<"$refusal"; then
  echo "pressing back on iOS must fail with exit 1 and say there is no back key, got $status: $refusal" >&2
  exit 1
fi

# The in-app agent and the system runner call every element by the same role (a client cannot choose which of them reads
# the screen): compare both trees of the main screen and of a sheet with one of each kind of control
DEVICE=(--project examples/sample-tests --platform ios -s "$UDID")
read_trees() {
  "$GEKKO" tree --json "${DEVICE[@]}" > "$OUT/$1-agent.json"
  "$GEKKO" tree --json --system "${DEVICE[@]}" > "$OUT/$1-runner.json"
}
"$GEKKO" assert --id sample.controls "${DEVICE[@]}"
read_trees main
python3 -I scripts/check-roles.py "$OUT/main-agent.json" "$OUT/main-runner.json" \
  sample.name=TextField sample.password=SecureTextField sample.greet=Button sample.list=ScrollView sample.time=DatePicker
"$GEKKO" tap --id sample.controls "${DEVICE[@]}"
"$GEKKO" assert --id sample.controls.done "${DEVICE[@]}"
read_trees controls
python3 -I scripts/check-roles.py "$OUT/controls-agent.json" "$OUT/controls-runner.json" \
  Controls=NavigationBar sample.controls.header=Text sample.controls.notes=TextView sample.controls.slider=Slider \
  sample.controls.stepper=Stepper sample.controls.done=Button "Search?=SearchField" \
  "sample.controls.switch?=Switch"
"$GEKKO" tap --id sample.controls.done "${DEVICE[@]}"

# A permission prompt covers the app, which stays active below it, so its agent cannot see the prompt: gekko asks the
# system runner, reads the prompt, does not tap through it to the app, and a plain tap answers it. A prompt that stays up
# (a check below failed) blocks the next run, so it is answered whatever happens.
trap '"$GEKKO" tap --text "Don’t Allow" --timeout 500 "${DEVICE[@]}" >/dev/null 2>&1 || true' EXIT
"$GEKKO" launch --fresh "${DEVICE[@]}"
"$GEKKO" tap --id sample.permission "${DEVICE[@]}"
"$GEKKO" tree --json "${DEVICE[@]}" > "$OUT/alert.json"
if ! grep -Eq '"role": ?"Alert"' "$OUT/alert.json"; then
  echo "the tree while a permission prompt is up must be the prompt (role Alert), see $OUT/alert.json" >&2
  exit 1
fi
status=0
blocked="$("$GEKKO" tap --id sample.greet --timeout 1000 "${DEVICE[@]}" 2>&1)" || status=$?
if [ "$status" -ne 1 ] || ! grep -q "system alert" <<<"$blocked"; then
  echo "a tap on the app below a prompt must fail with exit 1 and name the alert, got $status: $blocked" >&2
  exit 1
fi
"$GEKKO" tap --text "Allow While Using App" "${DEVICE[@]}"
"$GEKKO" assert --id sample.permission.result --text "Location: allowed" "${DEVICE[@]}"

status=0
"$GEKKO" run --project examples/sample-tests --platform ios -s "$UDID" --tags broken --exclude-tags tv --output "$OUT/failing" || status=$?
if [ "$status" -ne 1 ]; then
  echo "a failing test must exit with 1, got $status" >&2
  exit 1
fi
echo "iOS end to end passed"
