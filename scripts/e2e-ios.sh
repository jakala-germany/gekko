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
  Controls=NavigationBar Search=SearchField Inputs=Text sample.controls.notes=TextView sample.controls.switch=Switch \
  sample.controls.slider=Slider sample.controls.stepper=Stepper sample.controls.done=Button
"$GEKKO" tap --id sample.controls.done "${DEVICE[@]}"

status=0
"$GEKKO" run --project examples/sample-tests --platform ios -s "$UDID" --tags broken --exclude-tags tv --output "$OUT/failing" || status=$?
if [ "$status" -ne 1 ]; then
  echo "a failing test must exit with 1, got $status" >&2
  exit 1
fi
echo "iOS end to end passed"
