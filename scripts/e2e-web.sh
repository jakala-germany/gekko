#!/bin/bash
# Runs examples/sample-tests in Chromium, Firefox and WebKit with the freshly built CLI; used by CI, works locally too.
# Needs: ./gradlew :cli:linkReleaseExecutableLinuxX64 (or :macosArm64, :mingwX64 in Git Bash on Windows) and Node.js 18+.
# GEKKO_WEB_WITH_DEPS=1 also installs the system libraries of the browsers (Linux CI).
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -s)" in
  Darwin) DEFAULT_GEKKO=cli/build/bin/macosArm64/releaseExecutable/gekko.kexe ;;
  MINGW* | MSYS* | CYGWIN*) DEFAULT_GEKKO=cli/build/bin/mingwX64/releaseExecutable/gekko.exe ;;
  *) DEFAULT_GEKKO=cli/build/bin/linuxX64/releaseExecutable/gekko.kexe ;;
esac
GEKKO="${GEKKO:-$DEFAULT_GEKKO}"
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"
OUT=build/e2e-web
rm -rf "$OUT" && mkdir -p "$OUT"
BROWSERS=(chromium firefox webkit)
DEPS=()
if [ -n "${GEKKO_WEB_WITH_DEPS:-}" ]; then DEPS=(--with-deps); fi

"$GEKKO" --version
"$GEKKO" check --project examples/sample-tests

# Playwright and the browsers
for browser in "${BROWSERS[@]}"; do
  "$GEKKO" install --project examples/sample-tests --platform web -s "$browser" ${DEPS[@]+"${DEPS[@]}"}
done

# Every test that is expected to pass, in one browser after the other, with a junit file each
for browser in "${BROWSERS[@]}"; do
  "$GEKKO" run --project examples/sample-tests --platform web -s "$browser" \
    --exclude-tags broken,broken-group,android-only,ios-only,session-shared,tv \
    --junit "$OUT/junit-$browser.xml" --output "$OUT/$browser"
done

# A shared session keeps the browser context between the tests of a run: the second test sees what the first stored
for browser in "${BROWSERS[@]}"; do
  GEKKO_WEB_SESSION=shared "$GEKKO" run --project examples/sample-tests --platform web -s "$browser" --tags session-shared \
    --junit "$OUT/junit-shared-$browser.xml" --output "$OUT/shared-$browser"
done
# ... and a fresh session does not: the same two tests must fail in the second one
status=0
"$GEKKO" run --project examples/sample-tests --platform web -s chromium --tags session-shared --output "$OUT/not-shared" || status=$?
if [ "$status" -ne 1 ]; then
  echo "without a shared session the second session test must fail with 1, got $status" >&2
  exit 1
fi

# All browsers at the same time: every engine runs the whole selection
"$GEKKO" run --project examples/sample-tests --devices chromium,firefox,webkit --tags smoke \
  --junit "$OUT/junit-parallel.xml" --output "$OUT/parallel"
for browser in "${BROWSERS[@]}"; do
  grep -q "classname=\"$browser\"" "$OUT/junit-parallel.xml"
done

# A group whose setup fails: its tests are skipped with the reason, not run and failed one by one
status=0
"$GEKKO" run --project examples/sample-tests --platform web --tags broken-group --output "$OUT/broken-group" \
  --junit "$OUT/junit-broken-group.xml" > "$OUT/broken-group.log" 2>&1 || status=$?
if [ "$status" -ne 1 ]; then
  echo "a failed group setup must exit with 1, got $status" >&2
  exit 1
fi
test "$(grep -c '^FAIL' "$OUT/broken-group.log")" -eq 1
test "$(grep -c '^SKIP' "$OUT/broken-group.log")" -eq 2
grep -q 'skipped="2"' "$OUT/junit-broken-group.xml"

# A failing test exits with 1 and leaves its artifacts; the exit code is part of the CLI contract
status=0
"$GEKKO" run --project examples/sample-tests --platform web --tags broken --exclude-tags tv --output "$OUT/failing" || status=$?
if [ "$status" -ne 1 ]; then
  echo "a failing test must exit with 1, got $status" >&2
  exit 1
fi
grep -q '"kind": "test"' "$OUT"/failing/*/failure.json
test -s "$OUT"/failing/*/screenshot.png
test -s "$OUT"/failing/*/tree.txt

# A browser that the run started is stopped again when the run is done
devices="$("$GEKKO" devices)"
if echo "$devices" | grep -q '^web '; then
  echo "a browser of the run is still running: $devices" >&2
  exit 1
fi
echo "Web end to end passed"
