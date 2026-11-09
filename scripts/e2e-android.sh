#!/bin/bash
# Runs examples/sample-tests on the connected emulator with the freshly built CLI; used by CI, works locally too.
# Needs: ./gradlew :cli:linkReleaseExecutableLinuxX64 (or :macosArm64) :sample-android:assembleDebug :system-runner-android:assembleDebug
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -s)" in
  Darwin) DEFAULT_GEKKO=cli/build/bin/macosArm64/releaseExecutable/gekko.kexe ;;
  *) DEFAULT_GEKKO=cli/build/bin/linuxX64/releaseExecutable/gekko.kexe ;;
esac
GEKKO="${GEKKO:-$DEFAULT_GEKKO}"
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
OUT=build/e2e
rm -rf "$OUT" && mkdir -p "$OUT"

adb wait-for-device

# The device log tells a missing agent ("could not listen") from a slow start or a crash; the CI uploads build/e2e
save_logcat() {
  adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
}
trap save_logcat EXIT
adb logcat -c || true

adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk

"$GEKKO" --version
"$GEKKO" check --project examples/sample-tests

# Every test that is expected to pass, with a junit file
"$GEKKO" run --project examples/sample-tests --exclude-tags broken,ios-only,web-only \
  --junit "$OUT/junit.xml" --output "$OUT/results" 2>&1 | tee "$OUT/run.log"

# A drag moves the list exactly as far as the finger, a fast swipe flings it, a slow one does not
./scripts/check-touches.sh "$OUT/run.log"

# The system runner keeps time: this test forces it to hold three long presses of 900 ms and to swipe and drag. It takes
# a few seconds; while the runner stepped through UiDevice.swipe it took far more than the bound (#31)
RUNNER_TIME="$(sed -n 's/.*<testcase name="The system runner keeps its time"[^>]* time="\([0-9.]*\)".*/\1/p' "$OUT/junit.xml")"
RUNNER_TIME_LIMIT=15
if [ -z "$RUNNER_TIME" ]; then
  echo "the runner timing test did not run" >&2
  exit 1
fi
echo "The system runner kept its time: ${RUNNER_TIME}s (limit ${RUNNER_TIME_LIMIT}s)"
if ! awk -v time="$RUNNER_TIME" -v limit="$RUNNER_TIME_LIMIT" 'BEGIN { exit !(time <= limit) }'; then
  echo "the system runner is too slow: ${RUNNER_TIME}s, expected at most ${RUNNER_TIME_LIMIT}s" >&2
  exit 1
fi

# The location of a physical device comes from the system runner; GEKKO_MOCK_LOCATION=1 takes that way on the emulator
GEKKO_MOCK_LOCATION=1 "$GEKKO" run --project examples/sample-tests --tags location --output "$OUT/mock-location"

# A failing test exits with 1 and leaves its artifacts; the exit code is part of the CLI contract
status=0
"$GEKKO" run --project examples/sample-tests --tags broken --output "$OUT/failing" || status=$?
if [ "$status" -ne 1 ]; then
  echo "a failing test must exit with 1, got $status" >&2
  exit 1
fi
grep -q '"kind": "test"' "$OUT"/failing/*/failure.json

# No gekko.yaml is a usage error: exit code 2
status=0
(cd "$OUT" && "$GEKKO" check) || status=$?
if [ "$status" -ne 2 ]; then
  echo "a missing gekko.yaml must exit with 2, got $status" >&2
  exit 1
fi
echo "Android end to end passed"
