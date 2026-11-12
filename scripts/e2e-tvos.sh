#!/bin/bash
# Runs the TV tests of examples/sample-tests on an Apple TV simulator with the freshly built CLI; used by CI, works locally
# too.
# Needs: ./gradlew :cli:linkReleaseExecutableMacosArm64, ./scripts/build-ios-runner.sh, ./scripts/build-sample-ios.sh and
# the tvOS simulator runtime (xcodebuild -downloadPlatform tvOS)
set -euo pipefail
cd "$(dirname "$0")/.."

GEKKO="${GEKKO:-cli/build/bin/macosArm64/releaseExecutable/gekko.kexe}"
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
OUT=build/e2e-tvos
rm -rf "$OUT" && mkdir -p "$OUT"

# A booted Apple TV simulator, or the first available one
UDID="$(xcrun simctl list devices booted | grep -E 'Apple TV' | sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -n 1)"
if [ -z "$UDID" ]; then
  UDID="$(xcrun simctl list devices available | grep -E 'Apple TV' | head -n 1 | sed -n 's/.*(\([0-9A-F-]\{36\}\)).*/\1/p')"
  [ -n "$UDID" ] || { echo "no Apple TV simulator available (xcodebuild -downloadPlatform tvOS installs the runtime)" >&2; exit 1; }
  xcrun simctl boot "$UDID"
fi
xcrun simctl bootstatus "$UDID" -b

"$GEKKO" --version

# The CLI recognises the simulator as a TV, which is what turns the TV rules on
if ! "$GEKKO" devices | grep -q "^ios $UDID tv$"; then
  echo "the simulator must be reported as a TV by gekko devices, got: $("$GEKKO" devices)" >&2
  exit 1
fi

"$GEKKO" install sample-ios/build/SampleTV.app --project examples/sample-tests --platform ios -s "$UDID"

# The tests of the remote; the one that is meant to fail runs below
"$GEKKO" run --project examples/sample-tests --platform ios -s "$UDID" --tags tv --exclude-tags broken \
  --junit "$OUT/junit.xml" --output "$OUT/results" 2>&1 | tee "$OUT/run.log"

# A TV has no touch: tapping fails with exit 1 and names the remote
"$GEKKO" launch --project examples/sample-tests --platform ios -s "$UDID"
status=0
refusal="$("$GEKKO" tap --project examples/sample-tests --platform ios -s "$UDID" --id sample.teaser.1 2>&1)" || status=$?
if [ "$status" -ne 1 ] || ! grep -q "use pressKey" <<<"$refusal"; then
  echo "tapping on a TV must fail with exit 1 and point to pressKey, got $status: $refusal" >&2
  exit 1
fi

# A field takes text only when the remote moved the focus to it; the focus is on the first teaser after the start
status=0
refusal="$("$GEKKO" type x --project examples/sample-tests --platform ios -s "$UDID" --id sample.tv.search 2>&1)" || status=$?
if [ "$status" -ne 1 ] || ! grep -q "focus is on sample.teaser.1" <<<"$refusal"; then
  echo "typing into a field without the focus must fail with exit 1 and say where the focus is, got $status: $refusal" >&2
  exit 1
fi

# The end of a row stops the focus: the search for the next row fails early and says where the focus got stuck
status=0
"$GEKKO" run --project examples/sample-tests examples/sample-tests/tests/tv-focus-trap.yaml --platform ios -s "$UDID" \
  --output "$OUT/trap" > "$OUT/trap.log" 2>&1 || status=$?
if [ "$status" -ne 1 ] || ! grep -q "focus stuck on sample.teaser.3" "$OUT/trap.log"; then
  echo "the focus trap must fail with exit 1 and name the teaser it is stuck on, got $status: $(cat "$OUT/trap.log")" >&2
  exit 1
fi
echo "tvOS end to end passed"
