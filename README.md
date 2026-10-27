# Phoenix: Minecraft server hosting on Android (beta)

[![CI](https://github.com/BlindingWulf/phoenix-android/actions/workflows/ci.yml/badge.svg)](https://github.com/BlindingWulf/phoenix-android/actions/workflows/ci.yml)

Phoenix hosts a Minecraft Java Edition 26.2 server on an ARM64 Android phone, with Bedrock crossplay. It is the companion app to the **Phoenium** Fabric optimisation mod.

**Status: `0.5.0-beta.1`, a source pre-release for review.**
- This beta exists so external reviewers (in particular the *Mythos* backend/security review) can build, test and improve the hosting backend.
- No APK is published. The maintainer builds and signs installable APKs separately.

| Start here | |
|---|---|
| Reviewers / Mythos | [`docs/MYTHOS_START_HERE.md`](docs/MYTHOS_START_HERE.md), then [`docs/MYTHOS_BACKEND_SECURITY_BRIEF.md`](docs/MYTHOS_BACKEND_SECURITY_BRIEF.md) |
| Building and testing on a desktop | [`docs/DEVELOPMENT_SETUP.md`](docs/DEVELOPMENT_SETUP.md) |
| How it works | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Network exposure and threat model | [`docs/SECURITY_AND_HOSTING.md`](docs/SECURITY_AND_HOSTING.md) |
| Changes and known issues | [`CHANGELOG.md`](CHANGELOG.md) |
| Bundled/downloaded components and licences | [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) |

## What Phoenix does
- **Runtime and engines:**
  - Ships a Java 25 runtime for Android (the AngelAuraMC OpenJDK port, pinned by SHA-256) and runs the server as a separate child process.
  - Supports the Vanilla and Fabric engines.
- **Server lifecycle:**
  - Start, stop, restart, startup progress and crash classification.
  - Recovery after the app is closed, and reconnecting to a server left running.
  - Foreground-service hosting.
- **Server management:**
  - World management that preserves existing worlds.
  - Spawn pre-generation (bundled `phoenix-spawnprep` mod).
  - Minecraft console.
  - Mod import and checks.
- **Crossplay and voice:** Geyser + Floodgate Bedrock crossplay and Simple Voice Chat, all downloaded from pinned versions after the user accepts the Minecraft EULA.
- **Reaching the server from outside:**
  - playit.gg tunnels (official agent).
  - NetBird (embedded client; each user's own NetBird account).
  - Optional Minecraft.how hostname (DNS CNAME/SRV) so friends can type a name.
- **Memory:** Android memory management (heap sizing from device RAM).
- **Phoenium integration:** Settings › Performance › *Phoenium mode*.
  - **Standard** (default) passes no Phoenium flags.
  - **Accelerated** (opt-in, experimental) passes exactly `-Dphoenium.engine.tiles=true -Dphoenium.engine.workers=2 -Dphoenium.engine.schedule=dag`, and only when a Phoenium jar with the engine is installed.
  - Java AOT "Faster starts" is **off by default** (a known HotSpot C1 crash when enabled).

## Phoenium compatibility
- Phoenix 0.5.0-beta.1 targets **Phoenium 0.2.0-beta.1** (Minecraft 26.2, Fabric Loader 0.19.5).
- That Phoenium beta contains the fix for the custom-spawner crash ("failed to load chunk 1,-1" incident).
- Earlier Phoenium builds (`0.2.0-stage2`, RC1) can stop the server when a player respawns next to terrain that is still loading.
- Phoenium release: <https://github.com/BlindingWulf/phoenium/releases/tag/v0.2.0-beta.1> (pre-release, public source commit `b371a11`).
  - `phoenium-0.2.0-beta.1.jar`, SHA-256 `09530B8BA5FC090E584D4419CE133AD647CE6D6FDB708D82B0B2ADE76765037B`.
  - It requires the companion C2ME settlement jar from the same release.
- On Android, Phoenium is "hardware validation required". A release Phoenix build runs Phoenium in default mode, which was stable for 45 minutes on one phone; no performance claim is made.

## Requirements (users)
- An ARM64 Android 10+ phone (minSdk 29, targetSdk 36), tested on Android 15/16 Motorola devices with 4 KB pages. Phones with 16 KB pages are not yet supported by the bundled Java runtime.
- Enough free memory for a 1.5–2 GB heap, plus storage for worlds.

## Repository layout
```
android-stage10a/   Gradle project (the app)  — :app (Kotlin/Compose), :core (plain Java, Android-free)
  runtime/          native JVM launcher (C) + Java runtime layout code
  telemetry-agent/  -javaagent metrics source (phoenix-metrics.jar)
  deploy/oracle-proxy/  optional self-hosted relay (Terraform)
  tools/netbird/    reproducible NetBird AAR build + licences
  tools/playit/     playit agent build metadata + licence
tools/runtime/      reproducible Java 25 Android runtime packaging
docs/               reviewer and developer documentation
```
The paths match the maintainer's private development repository, so patches from pull requests apply unchanged.

## Contributing
- Work on branches and open pull requests against `main`.
- Every security or network change needs a threat scenario, a regression test and a before/after latency or functional comparison (see the brief).
- No pull request automatically replaces the maintainer's validated beta. Changes are reviewed, then re-validated on real phones.

## Licence
- Phoenix's own code is licensed under **GPL-3.0** (see [`LICENSE`](LICENSE)).
- Third-party components keep their own licences (see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)).
- Minecraft is a trademark of Mojang Studios. Phoenix is not affiliated with Mojang or Microsoft, and the Minecraft server is not bundled: it is downloaded from Mojang after the user accepts the EULA.
