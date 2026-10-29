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
adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk

"$GEKKO" --version
"$GEKKO" check --project examples/sample-tests

# Every test that is expected to pass, with a junit file
"$GEKKO" run --project examples/sample-tests --exclude-tags broken,ios-only \
  --junit "$OUT/junit.xml" --output "$OUT/results"

# The location of a physical device comes from the system runner; GEKKO_MOCK_LOCATION=1 takes that way on the emulator
GEKKO_MOCK_LOCATION=1 "$GEKKO" run --project examples/sample-tests --tags android-only --output "$OUT/mock-location"

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
