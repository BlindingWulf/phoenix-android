# Development setup (desktop, no phone required)

Everything here runs on Windows or Linux without an Android device or adb. Commands run from `android-stage10a/` unless stated.

> **Verification status:** GitHub Actions ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs these steps from a fresh checkout on `ubuntu-latest` and `windows-latest` (Temurin 25), on every push and pull request. On 2026-10-27 it was green: six core acceptance harnesses plus all 342 app unit tests. CI does not package an APK.

## 1. Toolchain

| Tool | Version | Notes |
|---|---|---|
| Git | any recent | |
| JDK | **Temurin 25** (25.0.4 used) | Runs Gradle, and builds the runtime jar. AGP 9 needs JDK 17+; 25 is what the project uses. |
| Gradle | **9.1.0** via wrapper | `gradlew` / `gradlew.bat`; do not use a system Gradle |
| Android Gradle Plugin | **9.0.1** (built-in Kotlin) | `build.gradle.kts` |
| Kotlin Compose compiler plugin | **2.3.10** | |
| Android SDK | platform **android-36**, build-tools **36.0.0**, platform-tools | `compileSdk`/`targetSdk` 36, `minSdk` 29 |
| Python | 3.12+ | helper scripts (`tools/*.py`) |
| Android NDK | 27.3.13750724 | **only** to rebuild the native Java runtime / launcher |
| Go 1.26.7 + gomobile, NDK r23b (Linux) | | **only** to rebuild the NetBird AAR from source |

Point the build at your SDK with `ANDROID_HOME`, or with `sdk.dir=...` in `android-stage10a/local.properties` (gitignored).

```bash
# Linux example
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
export ANDROID_HOME=$HOME/Android/Sdk
```

**Windows clones:** `tools/netbird/LICENSES/third-party/...` has deep paths. Clone into a short directory (e.g. `C:\src`) with long paths enabled, or the checkout fails with "Filename too long":
```powershell
git -c core.longpaths=true clone https://github.com/BlindingWulf/phoenix-android.git C:\src\phoenix-android
```

## 2. Inputs that are not in Git

| File | Needed for | How to get it |
|---|---|---|
| `app/libs/netbird-android-v0.80.0-debug.aar`, `...-release.aar` | **Compiling `:app` and running the app unit tests.** `NetBirdVpnService.kt` imports the gomobile classes. | Download from this repo's GitHub release (see below), or rebuild (`tools/netbird/BUILDING.md`). Verify with `tools/netbird/SHA256SUMS`. |
| `runtime/generated/` (Java 25 runtime assets + `jniLibs`, ~160 MB) | Packaging an APK only | `tools/runtime/build_runtime.ps1` (Windows PowerShell, NDK 27.3, JDK 25). It downloads the SHA-pinned AngelAuraMC JRE 25 archives. |
| `runtime/generated/jniLibs/arm64-v8a/libplayitagent.so` | Packaging an APK only | `tools/playit/build.ps1` (Rust + NDK); hash in `tools/playit/BUILD_METADATA.txt` |
| `app/src/main/assets/phoenix-metrics.jar` | Packaging an APK only | `tools/build_telemetry.ps1` (or `javac --release 17` + `jar`, see the script) |

Unit tests and static analysis need **only the NetBird AARs**.

### Fetch the NetBird AARs (prebuilt, hash-checked)
The AARs are attached to the GitHub release `v0.5.0-beta.1` as *build inputs*. They are not an app.

```bash
cd android-stage10a
gh release download v0.5.0-beta.1 -R BlindingWulf/phoenix-android -p 'netbird-android-v0.80.0-*.aar' -D app/libs
grep '\.aar$' tools/netbird/SHA256SUMS | sha256sum -c -
```

On Windows PowerShell:
```powershell
cd android-stage10a
gh release download v0.5.0-beta.1 -R BlindingWulf/phoenix-android -p "netbird-android-v0.80.0-*.aar" -D app/libs
Get-FileHash app/libs/*.aar -Algorithm SHA256   # compare with tools/netbird/SHA256SUMS
```

## 3. Build and test

Linux / macOS:
```bash
cd android-stage10a
chmod +x gradlew
./gradlew :core:acceptance :core:provisionAcceptance :core:worldAcceptance \
          :core:networkingAcceptance :core:netbirdAcceptance :core:playitAcceptance --console=plain
./gradlew :app:testDebugUnitTest --console=plain          # 342 JVM unit tests (AGP 9 runs unit tests on the debug type only)
./gradlew :app:lintDebug --console=plain                  # Android lint (static analysis)
```

Windows:
```powershell
cd android-stage10a
.\gradlew.bat :core:acceptance :core:provisionAcceptance :core:worldAcceptance :core:networkingAcceptance :core:netbirdAcceptance :core:playitAcceptance --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

If Windows Gradle test JVMs fail with `Could not find or load main class Files\...`, your `PATH` contains a stray `"` character. Strip it for that shell only:

```powershell
$env:Path = ($env:Path -replace '"','')
```

### About the test suites
- **`:app` unit tests:** JUnit 4 on the plain JVM, with no network access. They cover:
  - the process and leftover-server classifier (`LeftoverServerTest`) and start/wait state machines (`StartWaitTest`, `ReliabilityTest`);
  - the console (`ConsoleTest`) and JVM/Phoenium flags (`JvmTuningTest`, `PhoeniumTuningTest`, `ServerMemoryTest`);
  - networking intent and status (`NetworkIntentTest`, `NetBirdStatus*Test`, `RouteSwitcherTest`, `PublicRoutesTest`, `PlayitTunnelsTest`, `ConnectSetupTest`);
  - DNS wire parsing (`DnsWireTest`, `MinecraftHowDns*Test`), Minecraft.how clients, mods and packs (`ModChecksTest`, `ModJournalTest`), world storage migration, and spawn-prep.
- **`:core` harnesses:** `main()`-style acceptance programs run as Gradle `JavaExec` tasks. They use fake HTTP transports and **loopback sockets only**.
  - `:core:byopAcceptance` needs Python with `cryptography`; it prints SKIP otherwise.
  - `:core:test` has no JUnit tests. If Gradle 9 complains that no tests were discovered, run the acceptance tasks directly. Fixing this is tracked in the brief.
- **Adding tests:** put new backend and security tests in `:core` (Android-free) when you can, or in `app/src/test` with fakes. Loopback servers (`ServerSocket(0, 50, InetAddress.getLoopbackAddress())`) are fine. Do not contact public services from tests.

## 4. Build variants

| Build type | Application ID | Signing | Purpose |
|---|---|---|---|
| `release` | `dev.phoenix.server` | Owner key from `keystore.properties` (never committed) | The shipped app (R8 minify + resource shrink, not debuggable) |
| `rcCheck` | `dev.phoenix.server.rccheck` | debug key | Release configuration under a throwaway id, for phone checks |
| `debug` | `dev.phoenix.server.stage10a` | debug key | Development (debuggable) |
| `fast` | `dev.phoenix.server.stage10a` | debug key | Debug, but not debuggable (AOT-compiled by ART) |
| `uiCheck` | `dev.phoenix.server.uicheck` | debug key | Throwaway copy for screen checks |
| `freshCheck` | `dev.phoenix.server` | debug key | Clean-install check of the production id |

- `assembleRelease` needs every input from section 2.
- Without `keystore.properties`, the release APK is unsigned.
- Only `arm64-v8a` is packaged.

## 5. What a desktop cannot verify
Desktop JVM tests and mocks **do not prove**:
- Android memory behaviour (LMK, heap headroom);
- wake locks, foreground-service survival and Doze/background limits;
- process-group kill behaviour (Android 16 kills the server JVM together with the app process);
- CPU-affinity and big-core scheduling;
- real VPN/TUN behaviour;
- the bundled JRE on 16 KB-page devices.

Hand those to the maintainer's phone testers using [`MYTHOS_START_HERE.md` § Phone-only checks](MYTHOS_START_HERE.md#phone-only-checks).

## 6. Local server experiments (optional)
You can run a real Minecraft 26.2 dedicated server on your desktop to exercise status probes, console parsing and network fixtures:
- use `online-mode=false` and `server-ip=127.0.0.1`;
- use only loopback and your own machine;
- never run load or DoS tests against public servers, Minecraft.how, NetBird or playit.gg.
