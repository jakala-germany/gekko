#!/bin/bash
# Builds sample-ios/build/Sample.app for the iOS simulator with the gekko agent embedded (no Xcode project needed).
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="$(sed -n 's/^VERSION_NAME=//p' gradle.properties | head -n 1)"
VERSION="${VERSION:-unknown}"
OUT=sample-ios/build
APP=$OUT/Sample.app
rm -rf "$OUT" && mkdir -p "$APP/Frameworks"

xcodebuild -scheme GekkoAgent -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$OUT/dd" build -quiet
cp -R "$OUT/dd/Build/Products/Debug-iphonesimulator/PackageFrameworks/GekkoAgent.framework" "$APP/Frameworks/"
plutil -replace GekkoVersion -string "$VERSION" "$APP/Frameworks/GekkoAgent.framework/Info.plist"

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun swiftc -parse-as-library -sdk "$SDK" -target arm64-apple-ios15.0-simulator \
  sample-ios/Sources/SampleApp.swift -o "$APP/Sample" \
  -F "$APP/Frameworks" -framework GekkoAgent -Xlinker -rpath -Xlinker @executable_path/Frameworks

cat > "$APP/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.jakala.gekko.sample</string>
<key>CFBundleExecutable</key><string>Sample</string>
<key>CFBundleName</key><string>Sample</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>MinimumOSVersion</key><string>15.0</string>
<key>UIDeviceFamily</key><array><integer>1</integer></array>
<key>UILaunchScreen</key><dict/>
<key>UISupportedInterfaceOrientations</key><array><string>UIInterfaceOrientationPortrait</string></array>
</dict></plist>
PLIST
codesign --force --sign - "$APP/Frameworks/GekkoAgent.framework"
codesign --force --sign - "$APP"
echo "Built $APP"
