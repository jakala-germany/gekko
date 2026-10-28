#!/bin/bash
# Builds the iOS system runner (XCUITest) into dist/gekko-ios-runner (xctestrun + products), relocatable.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# The release version (gradle.properties) is what Hello answers; it goes into the Info.plist of both products
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$ROOT/gradle.properties" | head -n 1)"
VERSION="${VERSION:-unknown}"
cd "$ROOT/system-runner-ios"
xcodegen generate -q
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
