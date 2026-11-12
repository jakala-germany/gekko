#!/bin/bash
# Builds the iOS system runner (XCUITest) into dist/gekko-ios-runner (xctestrun + products), relocatable, and the agent
# into dist/gekko-ios-agent. The same for the Apple TV simulator: dist/gekko-tvos-runner and dist/gekko-tvos-agent.
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

# The runner for the Apple TV simulator (it presses the buttons of the remote); inside build/, which is not tracked
rm -rf ../dist/gekko-tvos-runner
xcodebuild build-for-testing -project GekkoRunner.xcodeproj -scheme GekkoRunnerTV \
  -destination 'generic/platform=tvOS Simulator' -derivedDataPath build/tvos GEKKO_VERSION="$VERSION" -quiet
mkdir -p ../dist/gekko-tvos-runner
cp -R build/tvos/Build/Products/Debug-appletvsimulator ../dist/gekko-tvos-runner/
cp build/tvos/Build/Products/*.xctestrun ../dist/gekko-tvos-runner/GekkoRunner.xctestrun
(cd ../dist && zip -qr gekko-tvos-runner.zip gekko-tvos-runner)
cd ..
xcodebuild -scheme GekkoAgent -destination 'generic/platform=iOS Simulator' -derivedDataPath build-agent build -quiet
rm -rf dist/gekko-ios-agent && mkdir -p dist/gekko-ios-agent
cp -R build-agent/Build/Products/Debug-iphonesimulator/PackageFrameworks/GekkoAgent.framework dist/gekko-ios-agent/
plutil -replace GekkoVersion -string "$VERSION" dist/gekko-ios-agent/GekkoAgent.framework/Info.plist
codesign --force --sign - dist/gekko-ios-agent/GekkoAgent.framework
(cd dist && zip -qr gekko-ios-agent.zip gekko-ios-agent)

# The agent for the Apple TV simulator: dyld refuses a library that was built for another platform
xcodebuild -scheme GekkoAgent -destination 'generic/platform=tvOS Simulator' -derivedDataPath build-agent/tvos build -quiet
rm -rf dist/gekko-tvos-agent && mkdir -p dist/gekko-tvos-agent
cp -R build-agent/tvos/Build/Products/Debug-appletvsimulator/PackageFrameworks/GekkoAgent.framework dist/gekko-tvos-agent/
plutil -replace GekkoVersion -string "$VERSION" dist/gekko-tvos-agent/GekkoAgent.framework/Info.plist
codesign --force --sign - dist/gekko-tvos-agent/GekkoAgent.framework
(cd dist && zip -qr gekko-tvos-agent.zip gekko-tvos-agent)
echo "Built dist/gekko-ios-runner, dist/gekko-ios-agent, dist/gekko-tvos-runner and dist/gekko-tvos-agent"
