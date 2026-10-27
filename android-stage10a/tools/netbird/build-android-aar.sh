#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
app_root="$(cd -- "$script_dir/../.." && pwd)"
tool_root="$app_root/.tools/netbird-build"
netbird_src="${NETBIRD_SRC:-$tool_root/netbird-src}"
app_id="${1:?usage: build-android-aar.sh <applicationId> <output.aar>}"
aar_out="${2:?usage: build-android-aar.sh <applicationId> <output.aar>}"

readonly netbird_version="v0.80.0"
readonly netbird_commit="fca64287cf51a85552a41b022013abbfdb335452"
readonly patch_file="$script_dir/netbird-android.patch"

if [[ ! "$app_id" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]]; then
  echo "Invalid Android applicationId: $app_id" >&2
  exit 2
fi
if [[ "$aar_out" != /* ]]; then aar_out="$app_root/$aar_out"; fi
mkdir -p "$(dirname -- "$aar_out")"

if [[ "$(git -C "$netbird_src" rev-parse HEAD)" != "$netbird_commit" ]]; then
  echo "NetBird source must be checked out at $netbird_commit" >&2
  exit 2
fi
if ! git -C "$netbird_src" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
  git -C "$netbird_src" apply --check "$patch_file"
  git -C "$netbird_src" apply "$patch_file"
fi

export GOROOT="${GOROOT:-$tool_root/golang}"
export GOPATH="${GOPATH:-$tool_root/gopath}"
export GOBIN="${GOBIN:-$GOPATH/bin}"
export GOCACHE="${GOCACHE:-$tool_root/gocache}"
export ANDROID_HOME="${ANDROID_HOME:-$app_root/.tools/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
export NDK_HOME="$ANDROID_NDK_HOME"
if [[ -z "$ANDROID_NDK_HOME" || ! -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
  echo "Set ANDROID_NDK_HOME to an installed Linux Android NDK (r23b / 23.1.7779620 used for the pinned artifacts)." >&2
  exit 2
fi
export LD_LIBRARY_PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="${JAVAC_DIR:+$JAVAC_DIR:}$GOBIN:$GOROOT/bin:$PATH"

if [[ ! -f "$ANDROID_HOME/platforms/android-36/android.jar" ]]; then
  echo "Android SDK platform 36 is required at $ANDROID_HOME" >&2
  exit 2
fi
if ! command -v gomobile >/dev/null; then
  echo "Install the pinned gomobile tool in GOBIN before building." >&2
  exit 2
fi
echo "Go: $(go version)"
echo "javac: $(command -v javac)"
javac -version
echo "NetBird: $netbird_version ($netbird_commit)"
echo "ABI: arm64-v8a"
echo "Application ID: $app_id"
echo "Output: $aar_out"

(
  cd "$netbird_src"
  GOOS=android GOARCH=arm64 CGO_ENABLED=0 go list -deps -tags=android \
    -f '{{.ImportPath}} {{if .Module}}{{.Module.Path}}@{{.Module.Version}}{{else}}stdlib{{end}}' \
    ./client/android | LC_ALL=C sort -u > "$script_dir/go-dependency-graph.txt"
)

ldflags="-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 -checklinkname=0 -X golang.zx2c4.com/wireguard/ipc.socketDirectory=/data/data/$app_id/cache/wireguard -X github.com/netbirdio/netbird/version.version=$netbird_version"
cd "$netbird_src"
CGO_ENABLED=0 gomobile bind -v -target=android/arm64 -androidapi 29 \
  -o "$aar_out" -javapkg=io.netbird.gomobile -ldflags="$ldflags" \
  "$netbird_src/client/android"
