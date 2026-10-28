#!/bin/bash
# Runs examples/sample-tests on an iOS simulator with the freshly built CLI; used by CI, works locally too.
# Needs: ./gradlew :cli:linkReleaseExecutableMacosArm64, ./scripts/build-ios-runner.sh, ./scripts/build-sample-ios.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GEKKO="${GEKKO:-cli/build/bin/macosArm64/releaseExecutable/gekko.kexe}"
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
OUT=build/e2e
rm -rf "$OUT" && mkdir -p "$OUT"

# A booted simulator, or the first available iPhone
UDID="$(xcrun simctl list devices booted | sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -n 1)"
if [ -z "$UDID" ]; then
  UDID="$(xcrun simctl list devices available | grep -E 'iPhone' | head -n 1 | sed -n 's/.*(\([0-9A-F-]\{36\}\)).*/\1/p')"
  [ -n "$UDID" ] || { echo "no iPhone simulator available" >&2; exit 1; }
  xcrun simctl boot "$UDID"
fi
xcrun simctl bootstatus "$UDID" -b

"$GEKKO" --version
"$GEKKO" install sample-ios/build/Sample.app --project examples/sample-tests --platform ios -s "$UDID"
"$GEKKO" run --project examples/sample-tests --platform ios -s "$UDID" --exclude-tags broken \
  --junit "$OUT/junit.xml" --output "$OUT/results"

status=0
"$GEKKO" run --project examples/sample-tests --platform ios -s "$UDID" --tags broken --output "$OUT/failing" || status=$?
if [ "$status" -ne 1 ]; then
  echo "a failing test must exit with 1, got $status" >&2
  exit 1
fi
echo "iOS end to end passed"
