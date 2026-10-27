# Third-party components

This repository contains Phoenix's own source code (GPL-3.0, see `LICENSE`) plus the third-party items listed below. Large binaries are **not** committed: they are rebuilt from pinned sources or downloaded at runtime.

## Committed in this repository
| Item | Path | Licence |
|---|---|---|
| Gradle wrapper | `android-stage10a/gradle/wrapper/*`, `gradlew`, `gradlew.bat` | Apache-2.0 |
| NetBird gomobile Java bindings (source jar) | `android-stage10a/app/libs/netbird-android-v0.80.0-release-sources.jar` | BSD-3-Clause (NetBird client) |
| NetBird client licence inventory | `android-stage10a/tools/netbird/LICENSES/` | various; see `dependencies.csv` |
| Playit agent licence | `android-stage10a/tools/playit/LICENSE-PLAYIT-AGENT.txt` | BSD-style (Developed Methods LLC) |
| Phoenix spawn-prep mod (binary, project-owned) | `android-stage10a/app/src/main/assets/spawnprep/phoenix-spawnprep.jar` | Phoenix project; source to be published with Phoenium |

## Release build inputs (attached to the GitHub release, not committed)
| Item | Licence | Reproduce with |
|---|---|---|
| `netbird-android-v0.80.0-{debug,release}.aar` | NetBird client BSD-3-Clause plus the Go dependency closure (see `tools/netbird/LICENSES/`) | `tools/netbird/build-android-aar.sh`; hashes in `tools/netbird/SHA256SUMS` |

## Built or downloaded by build scripts (not in this repo)
| Item | Licence | Source |
|---|---|---|
| Java 25 runtime for Android (AngelAuraMC OpenJDK port) | GPL-2.0 with Classpath Exception, plus bundled native-library notices | Pinned archive and source commit in `tools/runtime/` (build workflow and patch copies under `tools/runtime/evidence/`) |
| Android NDK libc++ | Apache-2.0 with LLVM exception (NDK NOTICE) | Android NDK 27.3.13750724 |
| playit agent (`libplayitagent.so`) | BSD-style | `tools/playit/build.ps1`, revision in `BUILD_METADATA.txt` |
| AndroidX, Jetpack Compose, Kotlin stdlib, desugar_jdk_libs | Apache-2.0 | Maven (Gradle) |

## Downloaded by the app at runtime (after the user accepts the Minecraft EULA)
- **Minecraft server:** Mojang EULA. Not redistributed.
- **Fabric Loader and Fabric API:** Apache-2.0.
- **Geyser and Floodgate:** MIT.
- **Simple Voice Chat:** see the mod's licence.
- All of these come from pinned versions with SHA-256 checks (`DEPENDENCY_MANIFEST.json`, `CROSSPLAY_MANIFEST.json`).

## Before any APK distribution
- An APK must ship the complete notice set (OpenJDK, NetBird Go closure, NDK, AndroidX), shown in the app.
- It must also meet the OpenJDK source-offer obligations.
- This is not done yet, which is one reason this beta is source-only.
