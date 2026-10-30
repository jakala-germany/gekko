#!/bin/bash
# Builds sample-ios/build-device/Sample.app for a physical iPhone: a debug build signed for your team that links the
# GekkoAgent package (a device build cannot get the agent injected like a simulator build). Needs `brew install xcodegen`.
#
#   scripts/build-sample-ios-device.sh --team TEAMID [--bundle-id gekko.teamid.sample]
#
# The bundle id must be one your team can register (default: gekko.<teamid in lower case>.sample).
set -euo pipefail
cd "$(dirname "$0")/.."

TEAM=""
BUNDLE_ID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --team) TEAM="${2:?--team needs a team id}"; shift ;;
    --bundle-id) BUNDLE_ID="${2:?--bundle-id needs a value}"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$TEAM" ] || { echo "needs --team TEAMID (your Apple developer team)" >&2; exit 2; }
BUNDLE_ID="${BUNDLE_ID:-gekko.$(printf '%s' "$TEAM" | tr '[:upper:]' '[:lower:]').sample}"

OUT=sample-ios/build-device
rm -rf "$OUT" && mkdir -p "$OUT"
(cd sample-ios && xcodegen generate -q)
xcodebuild -project sample-ios/Sample.xcodeproj -scheme Sample -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath "$OUT/dd" -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID" build -quiet
cp -R "$OUT/dd/Build/Products/Debug-iphoneos/Sample.app" "$OUT/Sample.app"
[ -d "$OUT/Sample.app/Frameworks/GekkoAgent.framework" ] || {
  echo "GekkoAgent.framework is not embedded in $OUT/Sample.app" >&2
  exit 1
}
echo "Built $OUT/Sample.app ($BUNDLE_ID)"
