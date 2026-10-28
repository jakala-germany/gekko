#!/bin/bash
# Installs the gekko CLI and its system runners from a release of jakala-germany/gekko.
#
#   scripts/install.sh [VERSION|latest]        install (default: the latest release)
#   scripts/install.sh --uninstall             remove the CLI and the runners again
#
# Remote (pin the version in the URL and in the argument):
#   curl -fsSL https://raw.githubusercontent.com/jakala-germany/gekko/1.0.0/scripts/install.sh | bash -s -- 1.0.0
#
# The CLI goes to $GEKKO_BIN_DIR (default ~/.local/bin), the runners to $GEKKO_HOME/runners (default ~/.gekko/runners);
# gekko finds them there without any setting. Every download is checked against the SHA256SUMS of the release. With
# GEKKO_VERIFY_ATTESTATION=1 and the GitHub CLI installed the build provenance is verified as well.
# GEKKO_RELEASE_BASE_URL replaces https://github.com/jakala-germany/gekko/releases (for tests).
set -euo pipefail

BASE_URL="${GEKKO_RELEASE_BASE_URL:-https://github.com/jakala-germany/gekko/releases}"
BIN_DIR="${GEKKO_BIN_DIR:-$HOME/.local/bin}"
GEKKO_DIR="${GEKKO_HOME:-$HOME/.gekko}"
RUNNERS_DIR="$GEKKO_DIR/runners"

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed -e '/^set -euo/d' -e 's/^# \{0,1\}//'
}

die() {
  echo "install.sh: $*" >&2
  exit 1
}

version="latest"
uninstall=false
for argument in "$@"; do
  case "$argument" in
    --uninstall) uninstall=true ;;
    -h | --help) usage; exit 0 ;;
    -*) die "unknown option $argument (see --help)" ;;
    *) version="$argument" ;;
  esac
done

if [ "$uninstall" = true ]; then
  rm -f "$BIN_DIR/gekko"
  rm -rf "$RUNNERS_DIR"
  echo "Removed $BIN_DIR/gekko and $RUNNERS_DIR (the token file in $GEKKO_DIR is kept)"
  exit 0
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) asset="gekko-macos-arm64"; ios=true ;;
  Linux-x86_64) asset="gekko-linux-x64"; ios=false ;;
  *) die "unsupported platform $(uname -s)-$(uname -m): gekko runs on macOS arm64 and Linux x64" ;;
esac

if [ "$version" = "latest" ]; then
  latest="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$BASE_URL/latest")" || die "could not look up the latest release"
  version="${latest##*/}"
fi
version="${version#v}"
case "$version" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) die "'$version' is not a release version like 1.0.0" ;;
esac
DOWNLOAD_URL="$BASE_URL/download/$version"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
assets=("$asset" "gekko-system-runner.apk")
if [ "$ios" = true ]; then
  assets+=("gekko-ios-runner.zip" "gekko-ios-agent.zip")
fi

echo "Installing gekko $version"
curl -fsSL -o "$work/SHA256SUMS" "$DOWNLOAD_URL/SHA256SUMS" || die "no release $version found at $DOWNLOAD_URL"
for name in "${assets[@]}"; do
  curl -fsSL -o "$work/$name" "$DOWNLOAD_URL/$name" || die "downloading $name failed"
done

# Only the lines of the files that were downloaded; a file that is not listed fails the check
: > "$work/CHECKSUMS"
for name in "${assets[@]}"; do
  line="$(awk -v name="$name" '$2 == name { print }' "$work/SHA256SUMS")"
  [ -n "$line" ] || die "SHA256SUMS of the release has no entry for $name"
  echo "$line" >> "$work/CHECKSUMS"
done
if command -v shasum >/dev/null 2>&1; then
  (cd "$work" && shasum -a 256 -c CHECKSUMS >/dev/null) || die "a download does not match SHA256SUMS; nothing was installed"
else
  (cd "$work" && sha256sum -c CHECKSUMS >/dev/null) || die "a download does not match SHA256SUMS; nothing was installed"
fi

if [ "${GEKKO_VERIFY_ATTESTATION:-0}" = "1" ]; then
  command -v gh >/dev/null 2>&1 || die "GEKKO_VERIFY_ATTESTATION=1 needs the GitHub CLI (gh)"
  for name in "${assets[@]}"; do
    gh attestation verify "$work/$name" --repo jakala-germany/gekko >/dev/null || die "provenance of $name could not be verified"
  done
  echo "Build provenance verified"
fi

mkdir -p "$BIN_DIR" "$RUNNERS_DIR"
install -m 0755 "$work/$asset" "$BIN_DIR/gekko"
rm -rf "${RUNNERS_DIR:?}"/*
cp "$work/gekko-system-runner.apk" "$RUNNERS_DIR/"
if [ "$ios" = true ]; then
  unzip -q -o "$work/gekko-ios-runner.zip" -d "$RUNNERS_DIR"
  unzip -q -o "$work/gekko-ios-agent.zip" -d "$RUNNERS_DIR"
fi

echo "Installed gekko $version to $BIN_DIR/gekko and the runners to $RUNNERS_DIR"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "Add $BIN_DIR to your PATH, e.g.: export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
if [ "$ios" = true ]; then
  echo "For iOS simulators set ios.agentFramework: installed in gekko.yaml (or GEKKO_IOS_AGENT)"
fi
