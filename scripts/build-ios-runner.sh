#!/bin/bash
# Builds the iOS system runner (XCUITest) and the agent for simulators into dist/ (relocatable), or with --device the
# runner for a physical iPhone, signed for your team, into dist/gekko-ios-device-runner.
#
#   scripts/build-ios-runner.sh                                   simulator runner + agent (what a release ships)
#   scripts/build-ios-runner.sh --device --team TEAMID [--bundle-prefix gekko.teamid] [--install]
#
# A device runner is signed, so it cannot be part of a release: build it once per team. The bundle prefix must be one
# your team can register as App IDs (default: gekko.<teamid in lower case>). --install copies it to
# $GEKKO_HOME/runners (default ~/.gekko/runners), where gekko finds it without any setting.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# The release version (gradle.properties) is what Hello answers; it goes into the Info.plist of both products
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$ROOT/gradle.properties" | head -n 1)"
VERSION="${VERSION:-unknown}"

DEVICE=0
TEAM=""
PREFIX=""
INSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE=1 ;;
    --team) TEAM="${2:?--team needs a team id}"; shift ;;
    --bundle-prefix) PREFIX="${2:?--bundle-prefix needs a value}"; shift ;;
    --install) INSTALL=1 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

cd "$ROOT/system-runner-ios"
xcodegen generate -q

if [ "$DEVICE" -eq 1 ]; then
  [ -n "$TEAM" ] || { echo "--device needs --team TEAMID (your Apple developer team)" >&2; exit 2; }
  if [ -z "$PREFIX" ]; then
    PREFIX="gekko.$(printf '%s' "$TEAM" | tr '[:upper:]' '[:lower:]')"
  fi
  rm -rf build-device ../dist/gekko-ios-device-runner
  xcodebuild build-for-testing -project GekkoRunner.xcodeproj -scheme GekkoRunner \
    -destination 'generic/platform=iOS' -derivedDataPath build-device -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic GEKKO_BUNDLE_PREFIX="$PREFIX" GEKKO_VERSION="$VERSION" -quiet
  mkdir -p ../dist/gekko-ios-device-runner
  cp -R build-device/Build/Products/Debug-iphoneos ../dist/gekko-ios-device-runner/
  XCTESTRUN="$(ls build-device/Build/Products/*.xctestrun | head -n 1)"
  cp "$XCTESTRUN" ../dist/gekko-ios-device-runner/GekkoRunner.xctestrun
  cd ..
  if [ "$INSTALL" -eq 1 ]; then
    TARGET="${GEKKO_HOME:-$HOME/.gekko}/runners"
    mkdir -p "$TARGET"
    rm -rf "$TARGET/gekko-ios-device-runner"
    cp -R dist/gekko-ios-device-runner "$TARGET/"
    echo "Installed the device runner to $TARGET/gekko-ios-device-runner"
  fi
  echo "Built dist/gekko-ios-device-runner (bundle prefix $PREFIX, team $TEAM)"
  exit 0
fi

rm -rf build ../dist/gekko-ios-runner
xcodebuild build-for-testing -project GekkoRunner.xcodeproj -scheme GekkoRunner \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build GEKKO_VERSION="$VERSION" -quiet
mkdir -p ../dist/gekko-ios-runner
cp -R build/Build/Products/Debug-iphonesimulator ../dist/gekko-ios-runner/
cp build/Build/Products/*.xctestrun ../dist/gekko-ios-runner/GekkoRunner.xctestrun
(cd ../dist && zip -qr gekko-ios-runner.zip gekko-ios-runner)
cd ..
xcodebuild -scheme GekkoAgent -destination 'generic/platform=iOS Simulator' -derivedDataPath build-agent build -quiet
rm -rf dist/gekko-ios-agent && mkdir -p dist/gekko-ios-agent
cp -R build-agent/Build/Products/Debug-iphonesimulator/PackageFrameworks/GekkoAgent.framework dist/gekko-ios-agent/
plutil -replace GekkoVersion -string "$VERSION" dist/gekko-ios-agent/GekkoAgent.framework/Info.plist
codesign --force --sign - dist/gekko-ios-agent/GekkoAgent.framework
(cd dist && zip -qr gekko-ios-agent.zip gekko-ios-agent)
echo "Built dist/gekko-ios-runner and dist/gekko-ios-agent"
