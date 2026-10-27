# Architecture

Paths are relative to `android-stage10a/`.

## Modules
| Module | Language | Android? | Role |
|---|---|---|---|
| `:app` (`app/src/main/java/dev/phoenix/server/`) | Kotlin + Compose | yes | UI, view model, backend orchestration, Android services, integrations |
| `:core` (`core/src/main/java/org/phoenix/core/`) | Java 17 | **no** | Testable, Android-free logic: server lifecycle (`ServerController`, `ProcessRuntimeHost`), status probes, provisioning (`provision/`), world import guards (`world/`), playit client (`playit/`), NetBird services/pairing (`network/netbird/`), DNS plan |
| `runtime/` | C + Java | native | `phoenix_jvm.c` → `libphoenixjvm.so`, the installed launcher that starts HotSpot (sets `PR_SET_PDEATHSIG`); `RuntimeLayout.java` unpacks the JRE data and verifies native hashes |
| `telemetry-agent/` | Java | JVM agent | `phoenix-metrics.jar`, a `-javaagent` for JVM metrics |
| `deploy/oracle-proxy/` | Terraform, shell, Python | server | Optional self-hosted regional relay, paired to the phone |
| `tools/` | Python, PowerShell, shell | build | SDK setup, runtime/NetBird/playit reproducible builds, APK verification, secret scan |

## Main flows

### Start a server
1. **UI to backend:** `HomeScreen` → `PhoenixViewModel` → `PhoenixBackend.start()`.
2. **Provisioning (first run):**
   - `core/provision/Provisioner` downloads pinned artifacts (SHA-256 checked) after the user accepts the EULA (`EulaDecisionWriter`).
   - `CrossplayInstaller` installs Geyser, Floodgate and voice chat.
   - `SpawnPrepInstaller` installs the bundled spawn-prep mod.
3. **Configuration:** `PhoenixBackend` writes `server.properties` and the mod configs.
   - `JvmTuning` picks the heap from device RAM.
   - `PhoeniumTuning` adds Phoenium flags only in *Accelerated* mode.
   - AOT is applied only if the user enabled it.
4. **Launch:**
   - Leftover check: `LeftoverServer` and `Reliability` classify any Minecraft process from an earlier run.
   - The process is started with `ProcessBuilder(listOf(<nativeLibraryDir>/libphoenixjvm.so, nativeLibraryDir, javaHome, <JVM options>, "--", mainClass, args))`. This is an argument list, with no shell.
   - The launch runs on a retained executor thread, because Linux parent-death signals track the creating thread.
5. **Running:**
   - `ProcessRuntimeHost` pumps stdout/stderr lines into `ServerController` → `PhoenixBackend.consume()`. This drives startup progress, players, TPS, crash detection and spawn-prep progress (`SpawnPrep`, `StartWait`).
   - The server is **ONLINE** only after Minecraft readiness *and* a protocol probe (`MinecraftStatusProbe`, `BedrockStatusProbe`).
6. **Hosting:** `HostingService`, a foreground service, keeps hosting alive.

### Stop, restart and recovery
- **Stop:** the clean stop sends `stop` on stdin and waits for the world save. Force stop is a real stop. Restarts are bounded (3 in 15 minutes).
- **App recreated while the server is running:** `AdoptedProcess` reconnects to the server. Console input is off for an adopted server.
- **Android 16:** killing the app kills the server JVM as well. The app reports it on the next launch.
- **Crash classification:** a crash banner shows the cause and the report file. Chunk loads cancelled during a stop are not counted as world damage.

### Remote access
- **playit.gg:**
  - `PlayitManager` and `core/playit/*` handle claim and tunnels over HTTPS.
  - `PlayitAgent` runs the official agent via `libphoenixjvm.so --playit`, a fixed `execv` of the sibling `libplayitagent.so`.
  - Tunnels point at `127.0.0.1`.
- **NetBird:**
  - `NetBirdVpnService` embeds the NetBird Go client (`VpnService` TUN).
  - Sign-in uses NetBird's browser SSO (`NetBirdOAuthSession`).
  - Permanent TCP/UDP services are created through the NetBird API (`core/network/netbird/NetBirdServicesClient`).
  - `NetworkIntent` remembers the user's on/off choice.
- **Custom name:**
  - `MinecraftHow*` writes the CNAME/SRV records.
  - `NameFrontDoor` (TCP 25591) serves status pings and transfers 26.2 logins to the active route.
  - `RouteSwitcher` and `PublicRoutes` choose the front door.
- **Oracle relay (optional):** `ProxyPairingHost` with `core/network/netbird/ProxyPairing` (HMAC proof → ECDH → AES-GCM), and `deploy/oracle-proxy`.

### Worlds and mods
- **Worlds:** `WorldManager` handles create, import, export, backup and restore, with `core/world/WorldImportLimits` and `NbtLevelDatVerifier`. `ServerStoppedGuard` makes world mutations require a stopped server. `WorldStorageMigration` moves older layouts without loss.
- **Mods:** `ModManager`, `ModChecks` and `ModPacks` cover jar and `.mrpack` import, metadata checks and stack warnings (for example a Phoenium version older than the spawner fix).

## Where untrusted input enters
1. **Server stdout:** contains player chat. Parsers live in `PhoenixBackend.consume()`; see brief item 1.
2. **App-owned listeners:** `NameFrontDoor` and `ProxyPairingHost`.
3. **User-imported archives:** worlds, mods and mrpacks.
4. **Remote API responses:** NetBird, playit, Minecraft.how, Mojang/Geyser skin services and status probes. These are size-capped (see the security doc).
5. **Intents and deep links:** `MinecraftHowBrowserCallbackActivity`, which uses state plus PKCE.

## Data locations (on the phone)
- **`filesDir/servers/<world>`:** server files and worlds.
- **`filesDir/playit/`:** the playit agent's working files.
- **`noBackupFilesDir/netbird-*`:** NetBird state.
- **`files/backups`:** world backups.
- **SharedPreferences:** UI settings.

Backup and device-transfer policy: `res/xml/data_extraction_rules.xml` (see findings E3/P6-4).
