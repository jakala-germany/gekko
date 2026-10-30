#!/bin/bash
# Runs examples/sample-tests on an iPhone on a USB cable with the freshly built CLI. Builds the sample app and the
# system runner for your team first. Works only on a Mac with Xcode 27 and a signing team.
#
#   scripts/e2e-ios-device.sh -s <UDID> --team <TEAMID> [--bundle-prefix gekko.<teamid>]
#
# Before you start: the phone is connected by USB, unlocked and trusted, Developer Mode is on, Settings > Developer >
# "Enable UI Automation" is on and Auto-Lock is "Never" for the time of the run. Everything gekko prints and every
# artifact ends up in build/e2e-device/: send that folder (zip it) when something fails.
set -uo pipefail
cd "$(dirname "$0")/.."

UDID=""
TEAM=""
PREFIX=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s) UDID="${2:?-s needs a UDID}"; shift ;;
    --team) TEAM="${2:?--team needs a team id}"; shift ;;
    --bundle-prefix) PREFIX="${2:?--bundle-prefix needs a value}"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$UDID" ] && [ -n "$TEAM" ] || { echo "usage: $0 -s <UDID> --team <TEAMID> [--bundle-prefix P]" >&2; exit 2; }
PREFIX="${PREFIX:-gekko.$(printf '%s' "$TEAM" | tr '[:upper:]' '[:lower:]')}"

GEKKO="${GEKKO:-cli/build/bin/macosArm64/releaseExecutable/gekko.kexe}"
if [ ! -x "$GEKKO" ]; then
  ./gradlew :cli:linkReleaseExecutableMacosArm64 || exit 1
fi
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
ROOT="$(pwd)"
OUT=build/e2e-device
rm -rf "$OUT" && mkdir -p "$OUT"
export GEKKO_DEBUG=1

{
  echo "== host"; sw_vers; xcodebuild -version; xcrun devicectl --version
  echo "== gekko"; "$GEKKO" --version
  echo "== gekko devices"; "$GEKKO" devices
  echo "== devicectl list devices"; xcrun devicectl list devices
} > "$OUT/diagnostics.txt" 2>&1
cat "$OUT/diagnostics.txt"
grep -qi "$(printf '%s' "$UDID" | tr -d '-')" <(tr -d '-' < "$OUT/diagnostics.txt") || {
  echo "The device $UDID is not listed in $OUT/diagnostics.txt: connect it by USB, unlock it and trust this Mac" >&2
  exit 1
}

./scripts/build-ios-runner.sh --device --team "$TEAM" --bundle-prefix "$PREFIX" 2>&1 | tee "$OUT/build-runner.log"
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "building the runner failed, see $OUT/build-runner.log" >&2; exit 1; }
./scripts/build-sample-ios-device.sh --team "$TEAM" --bundle-id "$PREFIX.sample" 2>&1 | tee "$OUT/build-sample.log"
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "building the sample app failed, see $OUT/build-sample.log" >&2; exit 1; }

# The sample tests, pointed at the device builds
PROJECT="$OUT/project"
mkdir -p "$PROJECT"
cp -R examples/sample-tests/tests examples/sample-tests/flows "$PROJECT/"
cat > "$PROJECT/gekko.yaml" <<YAML
app:
  ios: $PREFIX.sample
ios:
  deviceApp: $ROOT/sample-ios/build-device/Sample.app
  deviceRunnerDir: $ROOT/dist/gekko-ios-device-runner
defaultTimeoutMs: 5000
YAML

failures=0
step() {
  local name="$1"; shift
  echo "== $name"
  "$@" > "$OUT/$name.log" 2>&1
  local status=$?
  cat "$OUT/$name.log"
  if [ "$status" -ne 0 ]; then
    echo "FAILED: $name (exit $status)" | tee -a "$OUT/summary.txt"
    failures=$((failures + 1))
  else
    echo "ok: $name" | tee -a "$OUT/summary.txt"
  fi
}

COMMON=(--project "$PROJECT" --platform ios -s "$UDID")
step install "$GEKKO" install "$ROOT/sample-ios/build-device/Sample.app" "${COMMON[@]}"
step launch "$GEKKO" launch --fresh "${COMMON[@]}"
step tree "$GEKKO" tree "${COMMON[@]}"
step screenshot "$GEKKO" screenshot "$OUT/screenshot.png" "${COMMON[@]}"
step run "$GEKKO" run "${COMMON[@]}" --exclude-tags broken,android-only --junit "$OUT/junit.xml" --output "$OUT/results"

# A failing test must fail with exit 1 and leave its artifacts; --uninstall cleans the phone up afterwards
"$GEKKO" run "${COMMON[@]}" --tags broken --output "$OUT/failing" --uninstall > "$OUT/failing.log" 2>&1
status=$?
cat "$OUT/failing.log"
if [ "$status" -ne 1 ]; then
  echo "FAILED: a failing test must exit with 1, got $status" | tee -a "$OUT/summary.txt"
  failures=$((failures + 1))
else
  echo "ok: failing test" | tee -a "$OUT/summary.txt"
fi

if [ "$failures" -ne 0 ]; then
  echo "iOS device end to end: $failures step(s) failed; send the folder $OUT" >&2
  exit 1
fi
echo "iOS device end to end passed"
