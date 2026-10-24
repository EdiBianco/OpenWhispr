#!/usr/bin/env bash
# Install a local JDK 17 into .jdk/ so `make check` works on a machine with no
# system JDK. Uses the Adoptium API, which redirects to the latest 17 GA build
# for this OS/arch. With a JDK already available, this does nothing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK_VERSION="${JDK_VERSION:-17}"
DEST="$ROOT/.jdk"

os="$(uname -s)"
arch="$(uname -m)"
case "$os" in
  Darwin) platform="mac" ;;
  Linux)  platform="linux" ;;
  *) echo "unsupported OS: $os" >&2; exit 1 ;;
esac
case "$arch" in
  x86_64|amd64) api_arch="x64" ;;
  arm64|aarch64) api_arch="aarch64" ;;
  *) echo "unsupported architecture: $arch" >&2; exit 1 ;;
esac

existing="$(find "$DEST" -type f -name java -path '*/bin/java' -print -quit 2>/dev/null || true)"
if [ -n "$existing" ]; then
  echo "JDK already installed: ${existing%/bin/java}"
  exit 0
fi

url="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/${platform}/${api_arch}/jdk/hotspot/normal/eclipse"
mkdir -p "$DEST"
tmp="$(mktemp -t openwhispr-jdk.XXXXXX.tar.gz)"
trap 'rm -f "$tmp"' EXIT

echo "Downloading JDK ${JDK_VERSION} for ${platform}/${api_arch}..."
curl -sSL -o "$tmp" "$url"
tar xzf "$tmp" -C "$DEST"

home="$(find "$DEST" -type f -name java -path '*/bin/java' -print -quit 2>/dev/null)"
if [ -z "$home" ]; then
  echo "JDK download did not produce a java binary" >&2
  exit 1
fi
echo "Installed: ${home%/bin/java}"
