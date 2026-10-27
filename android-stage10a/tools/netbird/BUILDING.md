# Embedded NetBird Android transport

Phoenix embeds the upstream NetBird Android Go client in this app. The client uses Android's `VpnService` TUN and the official management-configured browser SSO flow; it does not invoke the separate NetBird companion APK. The current artifact contains `arm64-v8a` only, so Phoenix reports the embedded transport as unsupported on other ABIs.

## Pinned inputs

- NetBird source: `v0.80.0`, commit `fca64287cf51a85552a41b022013abbfdb335452` from `https://github.com/netbirdio/netbird`.
- Local Android OAuth callback patch: [`netbird-android.patch`](netbird-android.patch).
- Go: `go1.26.7 linux/amd64`.
- `gomobile`: `golang.org/x/mobile` version `v0.0.0-20260816165457-f98cc9b3c733` (module checksum `h1:XKMObIaAElmkdO+4SQh1iCfzwciZHJi1OblnX9BED9k=`).
- Android SDK platform: API 36.
- Android NDK: r23b, package revision `23.1.7779620`.
- JDK: the tested build used Adoptium `javac 25.0.4.1`.

The checked inputs and generated debug and release artifact hashes are recorded in [`SHA256SUMS`](SHA256SUMS). `build-android-aar.sh` also writes a sorted Go package/module graph to [`go-dependency-graph.txt`](go-dependency-graph.txt) for review.

## Build

The script expects the pinned NetBird checkout at `.tools/netbird-build/netbird-src`, Go under `.tools/netbird-build/golang`, a `gomobile` executable in `.tools/netbird-build/gopath/bin`, Android SDK platform 36 under `.tools/android-sdk`, a Linux Android NDK selected through `ANDROID_NDK_HOME`, and `javac` on `PATH`. On WSL with a Windows JDK, set `JAVAC_DIR` to a directory containing a `javac` wrapper that translates WSL file paths before invoking Windows `javac`.

From the Android project directory:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r23b
export GOROOT="$PWD/.tools/netbird-build/golang"
export GOPATH="$PWD/.tools/netbird-build/gopath"
export GOBIN="$GOPATH/bin"
export GOCACHE="$PWD/.tools/netbird-build/gocache"
export PATH="$GOBIN:$GOROOT/bin:$PATH"
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260816165457-f98cc9b3c733

tools/netbird/build-android-aar.sh \
  dev.phoenix.server.stage10a app/libs/netbird-android-v0.80.0-debug.aar
tools/netbird/build-android-aar.sh \
  dev.phoenix.server app/libs/netbird-android-v0.80.0-release.aar
```

The application ID is embedded in the Go link flags because WireGuard uses an app-private Unix socket under that application's cache directory. The debug and release artifacts therefore must be built separately. The AARs are intentionally excluded from Git because each is about 26 MB; build them from the pinned source and verify them against `SHA256SUMS` before distributing an APK.

## Scope and licensing

The patch only changes the upstream `client/android` wrapper and its OAuth flow to provide the access token, selected JWT, refresh token, expiry, management-issued token endpoint and client ID to Phoenix after the official SSO flow succeeds. It adds an account-session acquisition method for an already-enrolled client and fixes the upstream PKCE expiry conversion to use the remaining token lifetime instead of `time.Time.Second()`. The patch includes a 3600-second regression test. It does not implement an OAuth endpoint, password flow, or NetBird server component.

NetBird's root license says BSD-3-Clause applies except the top-level `management/`, `signal/`, `relay/`, and `combined/` directories, which are AGPLv3. The linked Go package graph is generated for `GOOS=android GOARCH=arm64` from the exact client binding package. The dependency license report and the license texts required for redistribution are in [`LICENSES/dependencies.csv`](LICENSES/dependencies.csv) and [`LICENSES/third-party`](LICENSES/third-party) (generated with `go-licenses` for `./client/android`). NetBird's BSD-3-Clause text is included in [`LICENSES/BSD-3-Clause.txt`](LICENSES/BSD-3-Clause.txt). Review this inventory and the source package graph together before changing the build scope.
