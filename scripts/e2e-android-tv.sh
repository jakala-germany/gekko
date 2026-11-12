#!/bin/bash
# Runs the TV tests of examples/sample-tests on the connected Android TV emulator with the freshly built CLI; used by CI,
# works locally too.
# Needs: ./gradlew :cli:linkReleaseExecutableLinuxX64 (or :macosArm64) :sample-android:assembleDebug :system-runner-android:assembleDebug
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -s)" in
  Darwin) DEFAULT_GEKKO=cli/build/bin/macosArm64/releaseExecutable/gekko.kexe ;;
  MINGW* | MSYS* | CYGWIN*) DEFAULT_GEKKO=cli/build/bin/mingwX64/releaseExecutable/gekko.exe ;;
  *) DEFAULT_GEKKO=cli/build/bin/linuxX64/releaseExecutable/gekko.kexe ;;
esac
GEKKO="${GEKKO:-$DEFAULT_GEKKO}"
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
OUT=build/e2e-android-tv
rm -rf "$OUT" && mkdir -p "$OUT"

adb wait-for-device

# The device log tells a missing agent ("could not listen") from a slow start or a crash; the CI uploads the folder
save_logcat() {
  adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
}
trap save_logcat EXIT
adb logcat -c || true

adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk

"$GEKKO" --version
"$GEKKO" check --project examples/sample-tests

# The CLI recognises the device as a TV, which is what turns the TV rules on
if ! "$GEKKO" devices | grep -q '^android .* tv$'; then
  echo "the device must be reported as a TV by gekko devices, got: $("$GEKKO" devices)" >&2
  exit 1
fi

# The tests of the remote; the one that is meant to fail runs below
"$GEKKO" run --project examples/sample-tests --tags tv --exclude-tags broken \
  --junit "$OUT/junit.xml" --output "$OUT/results" 2>&1 | tee "$OUT/run.log"

# A TV has no touch: tapping fails with exit 1 and names the remote
"$GEKKO" launch --project examples/sample-tests
status=0
refusal="$("$GEKKO" tap --project examples/sample-tests --id sample.teaser.1 2>&1)" || status=$?
if [ "$status" -ne 1 ] || ! grep -q "use pressKey" <<<"$refusal"; then
  echo "tapping on a TV must fail with exit 1 and point to pressKey, got $status: $refusal" >&2
  exit 1
fi

# A field takes text only when the remote moved the focus to it; the focus is on the first teaser after the start
status=0
refusal="$("$GEKKO" type x --project examples/sample-tests --id sample.tv.search 2>&1)" || status=$?
if [ "$status" -ne 1 ] || ! grep -q "focus is on sample.teaser.1" <<<"$refusal"; then
  echo "typing into a field without the focus must fail with exit 1 and say where the focus is, got $status: $refusal" >&2
  exit 1
fi

# The end of a row stops the focus: the search for the next row fails early and says where the focus got stuck
status=0
"$GEKKO" run --project examples/sample-tests examples/sample-tests/tests/tv-focus-trap.yaml --output "$OUT/trap" \
  > "$OUT/trap.log" 2>&1 || status=$?
if [ "$status" -ne 1 ] || ! grep -q "focus stuck on sample.teaser.3" "$OUT/trap.log"; then
  echo "the focus trap must fail with exit 1 and name the teaser it is stuck on, got $status: $(cat "$OUT/trap.log")" >&2
  exit 1
fi
echo "Android TV end to end passed"
