# Subsystem notes from the review

Notes on each subsystem, written by the reviewers. Use them as orientation; the code is the authority.

## A. Server management and command execution

## Server management and process execution (security notes)

Paths are relative to `android-stage10a/`.

### How processes are started
- **Minecraft server.** `PhoenixBackend.processBuilder()` (`app/src/main/java/dev/phoenix/server/PhoenixBackend.kt:462-504`) builds a `ProcessBuilder(List)`. Nothing is shell-interpreted. The argv contains:
  - `libphoenixjvm.so <nativeDir> <javaHome>`
  - heap flags `-Xms128m -Xmx<int>m`
  - the metrics `-javaagent`
  - optional fixed flag sets from `JvmTuning`, `AotCache` and `PhoeniumTuning`
  - `-DGeyser.MaxConnectionsPerAddress=<10..100>`
  - `-- org.phoenix.runtime.JarMain <server jar> nogui [--universe <card dir>]`

  World names, motd, seed and mod names never reach argv.
- **Native launcher** (`runtime/phoenix_jvm.c`, `runtime/parent_lifecycle.h`):
  - requires argv[1] to be its own install directory
  - requires `libjvm.so` to resolve inside that directory
  - sets `PR_SET_PDEATHSIG=SIGTERM`, so the server gets SIGTERM when the thread that started it, or the app, dies
  - prints `PHOENIX_RUNTIME_START ... pid=` to stderr
- **Playit agent.** `PlayitAgent.launch()` (`PlayitAgent.kt:127-150`) uses a fixed argv and a cleared environment. The key is in a 0600 file under `noBackupFilesDir/playit`, deleted once the agent reports RUNNING.
- **No shell anywhere.** There is no `Runtime.exec(String)`, no `sh -c` and no `/system/bin` in the main sources.

### Command path
- **Console tab.** `Console.toServerLine` → `ServerController.command`, which:
  - accepts commands only while ONLINE
  - rejects blank input, more than 512 chars, `\n`, `\r` and `\0`
  - routes an exact `stop` to the graceful stop

  `ProcessRuntimeHost.command` then writes the line to stdin (`core/src/main/java/org/phoenix/core/ServerController.java:233-240`, `ProcessRuntimeHost.java:38-41`).
- **Player and whitelist actions.** Only allowlisted verbs, with names matching `\.?[A-Za-z0-9_]{1,32}` (whitelist also allows spaces) (`PhoenixBackend.kt:2792-2827`).
- **No outside command source.** No network, Minecraft.How or other-app input reaches the console. `HostingService` is not exported, and its PendingIntents are immutable and explicit (`app/src/main/AndroidManifest.xml:28`).

### Lifecycle
- **Graceful stop:** `stop`, then SIGTERM after 60 s, then ERROR after another 30 s. Phoenix never sends SIGKILL on its own; only the user's Force stop does (`ServerController.java:176-207`, `PhoenixBackend.kt:247-261`).
- **Crash restart:** after 5 s, at most 3 times in 15 min. It is blocked when WARN/ERROR chunk-damage lines are present (`PhoenixBackend.kt:867-896`, `Reliability.kt:7-63`).
- **Reconnect after app death:** `PhoenixBackend.reconnect()` checks pid, start ticks, launcher and jar, then hands an `AdoptedProcess` to the controller (`PhoenixBackend.kt:929-961`). `AdoptedProcess` re-checks identity before every signal and accepts only `stop`, sent as SIGTERM (`AdoptedProcess.kt:141-177,230-240`).
- **Leftover cleanup:** before each launch, `endLeftovers` sends SIGTERM to this app's orphan `libphoenixjvm.so` processes and SIGKILL to orphan `libplayitagent.so` processes (`PhoenixBackend.kt:533-551`).

### Known weaknesses (see the review findings)
- **Stdout is trusted.** Server stdout is parsed with substring matches, and player chat is part of it (`PhoenixBackend.kt:1130-1168`, `LeftoverServer.kt:54-58`). Only spawn-prep lines are guarded (`SpawnPrep.kt:66-74`). Any new log-driven logic must anchor on the log4j prefix and server thread name, and reject chat-shaped message bodies (`<name>`, `[Not Secure]`, `* `).
- **Management API secret.** The API listens on 127.0.0.1 without TLS, behind the bearer secret in server.properties (`PhoenixBackend.kt:982-1006`, `Management.kt`). Loopback is shared with every other app on the phone.
- **No mod sandbox.** The server JVM and all mods run with Phoenix's own UID and can read and write all Phoenix private data. Treat mods as fully trusted code.

## B. Networking and remote access

## Phoenix networking: listeners and outbound clients

Paths are relative to `android-stage10a/`.

### Listeners on the phone
| Listener | Bind | Exposure | Authentication | Source |
|---|---|---|---|---|
| Minecraft Java TCP 25565 | all interfaces (`server-ip` never set) | LAN, NetBird tun, Playit (via 127.0.0.1), NetBird services, regional proxy | `online-mode=true` (default written by Phoenix) | `app/.../PhoenixBackend.kt:431` |
| Geyser (Bedrock) UDP 19132 | `0.0.0.0` | same as Java | Xbox auth through Geyser, `auth-type: floodgate` | `app/.../CrossplayInstaller.kt:67-87` |
| Simple Voice Chat UDP 24454 (or the Java port if `port=-1`) | the mod's own default | same | SVC per-player secret | `app/.../PhoenixBackend.kt:2294-2305` |
| Minecraft management API (WebSocket JSON-RPC, no TLS) | `127.0.0.1:25592-25599` | phone only (other apps on the device can connect) | `Authorization: Bearer <management-server-secret>` | `app/.../PhoenixBackend.kt:978-1004`, `app/.../Management.kt:19-46` |
| NameFrontDoor TCP 25591 | all interfaces | public through the NetBird EU name service, LAN, NetBird | none. Status pings are spliced to `127.0.0.1:serverPort`; 26.2 logins are sent a Transfer to Playit | `app/.../NameFrontDoor.kt:140-221`, `PhoenixBackend.kt:75, 2004-2040` |
| ProxyPairingHost TCP, random port 40000-48999 | all interfaces | public through a temporary NetBird TCP service, only while a pairing is pending (≤48 h) | mutual HMAC-SHA256 proof of a 128-bit code, then ECDH + AES-GCM; closes after 30 failures | `app/.../ProxyPairingHost.kt`, `core/.../netbird/ProxyPairing.java` |
| Playit agent IPC | filesystem `LocalSocket` at `filesDir/playit/playitd.sock` | app-private, not a network listener | filesystem permissions | `app/.../PlayitAgent.kt:38-43, 187-210` |

RCON and query are written as disabled (`enable-rcon=false`, `enable-query=false`). The in-app Settings can change only an allowlist of keys (`PhoenixBackend.kt:3160-3175`), so online-mode, RCON, query and server-ip cannot be changed from the UI. There is no HTTP server, NanoHTTPD, JavascriptInterface or debug listener in the shipped modules (`settings.gradle.kts` includes `:app` and `:core` only; `archive/` is not built).

Settings Phoenix changes automatically:
- `enforce-secure-profile=false` (Fabric, for Bedrock chat).
- `accepts-transfers=true` (Playit route with a custom name).
- The management API keys (every start).
- `spawn-protection=0` (once).

Keep `online-mode=true`. The normal game ports must stay publicly reachable.

### Outbound clients
- **NetBird REST**: only `https://api.netbird.io`, no redirects, 1 MiB cap. OAuth refresh goes only to `login.netbird.io` or `netbird.eu.auth0.com`. Tokens are kept in a Keystore AES-GCM file in `noBackupFilesDir/netbird-baseline`. Files: `app/.../UrlConnectionNetBirdTransport.kt`, `NetBirdOAuthSession.kt`, `NetBirdStore.kt`.
- **NetBird VPN**: embedded NetBird v0.80 client (`libs/netbird-android-v0.80.0-*.aar`, patch `tools/netbird/netbird-android.patch`). Addresses, routes and DNS come from NetBird management, with no per-app restriction. File: `app/.../NetBirdVpnService.kt:600-639`.
- **Playit**: `https://api.playit.gg` (POST only, no redirects, 64 KiB cap); claim page `https://playit.gg/claim/<code>`. Tunnels target `127.0.0.1`. The agent key is handed over in a 0600 file that is deleted once the agent runs. Files: `core/.../playit/*`, `app/.../PlayitManager.kt`, `PlayitAgent.kt`.
- **Minecraft.How**: fixed origin `https://minecraft.how`, at most 3 same-origin redirects, 2 MiB cap, XSRF cookie. The WebView sign-in is hardened:
  - main-frame host allowlist; Google is blocked in the WebView;
  - SSL errors cancel; no file or content access; no mixed content;
  - no JS bridge; isolated profile.

  The optional browser handoff uses state + PKCE S256 + a one-shot journal through the exported `MinecraftHowBrowserCallbackActivity` (`<applicationId>.minecraft-how://connected`). Files: `MinecraftHowClient.kt`, `MinecraftHowSignInActivity.kt:660-782`, `MinecraftHowSignInPolicy.kt`, `MinecraftHowBrowserConnection.kt`.
- **DNS checks**: Android `DnsResolver` (no cache) plus a direct non-recursive UDP/53 query to the zone's NS, with a bounded parser. Used only for diagnostics. Files: `AuthoritativeDns.kt`, `AndroidDnsRecordLookup.kt`.
- **Downloads**:
  - Geyser and Floodgate: HTTPS from `download.geysermc.org` or `cdn.modrinth.com`, pinned size and SHA-256.
  - Skins: Mojang session server, `api.geysermc.org`, `textures.minecraft.net`, with size caps.
  - Status probes: Java status frame ≤128 KiB; Bedrock pong is validated.
- **TLS**: platform defaults only; no custom TrustManager or HostnameVerifier. `res/xml/network_security_config.xml` denies cleartext except to `localhost` and `127.0.0.1`.

### Oracle regional proxy (`deploy/oracle-proxy`)
- **Terraform**: one Always Free VM. The security list opens only the Java TCP port, the Bedrock and voice UDP ports, WireGuard UDP 51820 and ICMP 3/4. The pairing code is regex-validated and passed in `user_data`.
- **`bootstrap.sh`**:
  - iptables chain with per-source hashlimits; the SSH accept rule is removed;
  - NetBird reverse-proxy image pinned by digest, run with `--network host`;
  - HTTPS and health endpoints on `127.0.0.1:8443` and `127.0.0.1:8080`.
- **`phoenix_pair.py`**: dials the phone through the pairing service and runs the VM side of `ProxyPairing`. It writes `NB_PROXY_TOKEN` only to `/etc/phoenix-proxy/proxy.env` (0600) and deletes the pairing code after success.

### Known weaknesses (see findings)
- **NET-1**: the management secret is sent to whatever owns the loopback port, with no check that it is Minecraft. Fix: rotate the secret each start and confirm the bind from the server log.
- **NET-2 / NET-3**: DoS on the pairing listener and the front door (wildcard bind, per-read timeouts only, pairing lockout after 30 failures).
- **NET-4**: an unvalidated player name is logged by the front door.
- **NET-5**: Minecraft sees players as 127.0.0.1 behind Playit and the front door, so bans must be by account, not by IP.

## C. Denial-of-service and resource exhaustion

## Application-layer DoS and resource limits (Phoenix, phoenix/polish-integration @ 00ecc65)

Paths are relative to `android-stage10a/`.

### Server output ingestion
- `core/.../ProcessRuntimeHost.java:23-36`: one daemon reader thread per server process. It reads 8 KiB blocks, caps each line at 8192 chars and calls the listener synchronously. **Only `IOException` is caught.**
- `app/.../PhoenixBackend.kt:1130-1168` `consume()` parses every line on that thread. The console keeps 400 entries, each cut to 4096 chars (`:1187-1196`); stack traces keep at most 12,000 chars (`Console.kt:27-71`); the crash-analysis window `recentLines` keeps 600 (`:1151`).
- Rule: any parser of server output must (a) never throw (use `toXOrNull`) and (b) match only the server's own log4j prefix, not player chat. `SpawnPrepLog` (`SpawnPrep.kt:64-74`) follows this; the tick/pid/seed/list/`@@PHOENIX_` parsers and `ChunkErrors` (`Reliability.kt:46-61`) do not yet.
- After an app restart a surviving server is adopted. Its output is the last 256 KiB of `logs/latest.log` and then whatever is appended (`AdoptedProcess.kt:67-103`).

### Disk logs
| File | Limit | Behaviour |
|---|---|---|
| `evidence/server.log` | 8 MiB | truncated to empty when exceeded (`PhoenixBackend.kt:1169-1180`) |
| `evidence/samples.jsonl` | 16 MiB | one rotation to `samples.1.jsonl` (`:1277`) |
| `playit/agent.log` | 512 KiB | rotated only at agent start (`PlayitAgent.kt:230`) |
| `logs/`, `crash-reports/` (Minecraft) | none from Phoenix | vanilla log4j rotation only |

### Restart limits
- Server: auto-restart after 5 s, at most 3 per 15 min (`Reliability.kt:7-17`, `PhoenixBackend.kt:867-897`). Never after chunk errors are found.
- Playit agent: at most 5 restarts per 10 min, backoff 2/5/15/30/60 s, then CRASHED (`PlayitAgent.kt:150-167, 250-253`).
- `HostingService` is `START_NOT_STICKY` (`HostingService.kt:40`). `healthCheck` reports and never restarts (`PhoenixBackend.kt:736-747`).

### App-owned listeners
| Listener | Bind | Concurrency | Timeouts |
|---|---|---|---|
| `NameFrontDoor` port 25591 (`PhoenixBackend.kt:75`) | 0.0.0.0, backlog 32 | at most 16 workers, extra connections closed (`NameFrontDoor.kt:147-173`) | 10 s per read during handshake, 15 s/60 s idle per read in splice; **no total deadline** |
| `ProxyPairingHost` | 0.0.0.0, backlog 1 | 1 connection | 30 s per read; **no total deadline**; 8 KiB lines; closes after 30 auth failures; 48 h window (`NetBirdNetworkManager.kt:108-110`) |

### Outbound network limits
- NetBird API: 10 s/10 s, 1 MiB, host pinned, no redirects (`UrlConnectionNetBirdTransport.kt`).
- Playit: 10 s/10 s, 64 KiB (`core/.../UrlConnectionPlayitTransport.java`).
- OAuth refresh: 64 KiB (`NetBirdOAuthSession.kt:74-97`). Skins: 8 s, 64 KiB/512 KiB, `textures.minecraft.net` only (`PlayerSkins.kt`).
- Provisioner and Crossplay: pinned size and SHA-256 on official hosts.
- `MinecraftStatusProbe`: frames of 3..131072 bytes, per-read timeout only. `BedrockStatusProbe`: one 2048-byte datagram.
- Management API: 127.0.0.1, 5 s timeouts, WebSocket frames at most 1 MiB (`Management.kt`).

### Archive imports
- World ZIP (`core/.../world/WorldImportLimits.java`): at most 2 GiB archive, 20,000 entries, 1 GiB total, 512 MiB per entry, ratio 200, 4096-char paths. Central directory and local headers are cross-checked, actual bytes counted, CRC verified.
- Mod pack ZIP (`ModPacks.kt:84-127`): at most 300 jars, 256 MiB per jar, 1 GiB in total, counted on actual bytes. `modrinth.index.json` is not bounded.
- Chunk import (`WorldManager.kt:130-150`): **no limits**. Checks free space against declared sizes only.
- Mod metadata (`ModChecks.kt`, `ModManager.kt:293-298`): checks the declared size, then reads without a limit. Nested jars are buffered up to 64 MiB each.

### server.properties written by Phoenix
`online-mode=true`, `enable-rcon=false`, `enable-query=false`, `max-players=20` (owner range 1..100), `sync-chunk-writes=false`, `enforce-secure-profile=false` (Bedrock chat), `accepts-transfers=true` (front door), `spawn-protection=0`, management API on `127.0.0.1` without TLS behind Minecraft's secret (`PhoenixBackend.kt:430-432, 695-721, 980-995`).

Left at vanilla defaults: `white-list=false`, `rate-limit=0`, `network-compression-threshold=256`, `server-ip` blank (all interfaces).

`-DGeyser.MaxConnectionsPerAddress` = max-players clamped to 10..100 (`:485-490`). Behind Playit/NetBird every player shares the relay's address, so per-address limits act as global limits.

## D. World and file integrity

## World and file integrity (Phoenix, phoenix/polish-integration 00ecc65)

**Storage layout**
- Server dir: `filesDir/servers/baseline`. Worlds are subfolders of it (`PhoenixBackend.kt:114`).
- Backups: `filesDir/backups/<world>-<epochMs>.zip`. Automatic backups are listed in `automatic.txt` (`WorldManager.kt`).
- Temporary imports go to `cacheDir`. All of this is app-private internal storage, and no storage permission is requested.
- Exports (world, backup, log) go only to SAF URIs the user picks.
- The microSD world location (`ExternalStorage.kt`, `AndroidVolumeProvider.kt`, `WorldStorageMigration.kt`) is present in the code but never switched on: the pref `world-card-uuid` is never written.

**World ZIP import and restore** (`core/.../world/WorldImporter.java`, `ZipCentralDirectory.java`, `NbtLevelDatVerifier.java`)
- The importer parses the central directory itself and rejects:
  - ZIP64, multi-disk archives, encryption, and methods other than stored/deflate;
  - trailing bytes, and local headers that differ from the central directory;
  - Unix symlinks and special files;
  - absolute, backslash, `:`, NUL, `.`/`..`, trailing-dot or space, and Windows-reserved names;
  - case or NFC collisions.
- Default limits (`WorldImportLimits.defaults()`): 2 GiB archive, 20,000 entries, 1 GiB total, 512 MiB per file, ratio 200, level.dat at most 64 MiB / 32 MiB expanded.
- Extraction goes to a same-directory staging folder with `CREATE_NEW` and checks per-entry size and CRC. level.dat must be gzip NBT with `Data.DataVersion == 4903`.
- Commit renames the new world into place. Any existing world is kept at `.phoenix-world-backup-*/old-world`; the backend does not clean this up or show it.
- `restoreBackup` and `importWorld` both use the default limits. Backups of worlds over these limits cannot be restored in the app.

**Server-stopped coverage** (`PhoenixBackend.kt`)
- create/load world, chunk import and mod-pack switch stop the server first (`stopForChange`/`stopped()`).
- Restore stops the server if the world is active, and its guard checks `host.isAlive`.
- Import goes only into a new folder.
- Delete refuses the active world.
- Backup and export of the running world use `save-off` + `save-all flush` (`withSavingPaused`).
- All of these are serialized by the `busy` flag on one executor. Mod toggle and remove are not.

**Mods** (`ModManager.kt`, `ModPacks.kt`, `ModChecks.kt`)
- File names are reduced to their base name and must match `[A-Za-z0-9._+-]{1,120}.jar`.
- Pack import caps actual bytes (256 MiB per jar, 1 GiB per pack, 300 jars).
- Removed or replaced jars are held in `.phoenix-mods/held` until the next good start, which allows rollback after a failed start.
- Mods run in the server JVM with the app UID.

**Downloads**
- `Provisioner` + `DEPENDENCY_MANIFEST.json` handle server.jar and fabric-server-launch.jar: HTTPS only, Mojang/Fabric hosts, redirects re-checked, size + SHA-256 (+ SHA-1), transactional commit. Both jars are re-hashed before every start.
- `CrossplayInstaller` + `CROSSPLAY_MANIFEST.json` handle Geyser, Floodgate, Fabric API and voicechat: HTTPS, host allow-list, no redirects, size + SHA-256, atomic move, re-checked every Fabric start.
- Spawnprep jar and Java runtime come from APK assets with SHA-256 checks.
- **Not pinned:** Fabric launcher first-run bootstrap (`.fabric/server/26.2-server.jar`, `libraries/`), see `PROVISIONING_REPORT.md:18`.

**Atomic writes**
- Atomic: `writeProperties` (AtomicFile), EULA (temp+move), spawnprep config, Geyser default config, mod journal, world-move journal (fsync).
- Not atomic: `allowUnsignedChat` (server.properties), voicechat-server.properties, `automatic.txt`, `pack-copies.txt`.
- Backup ZIPs are renamed from `.partial` without fsync.

## E. Sensitive data

## Sensitive data handling (Phoenix Android)

Paths are relative to `android-stage10a/`.

### Where secrets live
| Secret | Location | Protection |
|---|---|---|
| Playit agent key and pending claim code | `noBackupFilesDir/playit/secrets.enc` | AES-256-GCM, AndroidKeyStore alias `phoenix.playit.v2` (`app/.../PlayitManager.kt:426-462`) |
| Playit key handed to the native agent | `noBackupFilesDir/playit/agent.secret` | plaintext 0600, passed as `--secret-path`, deleted once the agent is RUNNING or stopped (`PlayitAgent.kt:102,132-185`) |
| NetBird OAuth access/refresh/ID tokens, allocation journal, BYOP proxy token during pairing | `noBackupFilesDir/netbird-<ns>/state.enc` | AES-256-GCM, AndroidKeyStore alias `phoenix.netbird.<ns>.v1` (`NetBirdStore.kt`, `NetBirdOAuthSession.kt:16-29`) |
| NetBird device identity (WireGuard private key) | `noBackupFilesDir/netbird-client/config.json`, `state.json` | written by the embedded NetBird Go client; plaintext, app-private (`NetBirdVpnService.kt:519-526`) |
| Minecraft.how session cookies | WebView profile `app_webview_minecraft-how-auth/` | Chromium cookie store, app-private, excluded from backup (`MinecraftHowSession.kt`) |
| Minecraft.how PKCE state/verifier (short-lived) | SharedPreferences `minecraft_how_handoff` | plaintext, app-private |
| Minecraft management API secret | `files/servers/baseline/server.properties` | plaintext, app-private; API bound to 127.0.0.1 only (`PhoenixBackend.kt:977-1004`) |
| Oracle pairing secret K | in memory, and the encrypted NetBird store until the pairing ends | wiped after use (`ProxyPairingHost.kt:66`); on the VM the token goes only to `/etc/phoenix-proxy/proxy.env` (0600) |

RCON and query are disabled on new servers (`PhoenixBackend.kt:431`). NetBird setup keys are not used; enrollment is browser SSO. Token refresh is only sent to `login.netbird.io` and `netbird.eu.auth0.com`.

### Trust boundary
The Minecraft server JVM and every installed mod run as Phoenix's own Linux UID (`PhoenixBackend.kt:462-503`). They can read every app-private plaintext file listed above: the NetBird device key, the Minecraft.how cookies, the management secret and a transient `agent.secret`. Keystore encryption protects the playit key and the NetBird account tokens at rest, but it does not protect against code running inside the app's UID. Only install mods you trust.

### Logging
- There is no logcat logging in `app/src/main` or `core/src/main`. API exceptions carry only code and status (`NetBirdApiException`, `PlayitApiException.safeMessage`). Secret holders override `toString()` with a redacted value.
- `files/evidence/server.log` (8 MiB cap) records every server line verbatim, including player names and IPs. The console masks IPs by default, and Copy console respects the mask. **Export full log copies the raw file, with IPs** (`PhoenixBackend.kt:3088-3096`).
- The playit agent's output goes to `files/playit/agent.log`. Only connection milestones and warnings reach the console.
- The telemetry export (`samples.jsonl`) holds device and performance metrics and the world seed. It has no player identifiers.

### Backup and transfer
`allowBackup="false"`, `fullBackupContent="false"`, and `res/xml/data_extraction_rules.xml` excludes sharedprefs and `app_webview_minecraft-how-auth/`. On Android 12+, device-to-device transfer still copies `files/`: worlds, server.properties, usercache, logs, evidence and the playit agent.log. Everything under `no_backup/` is never transferred, and Keystore keys never leave the device.

### Child processes and the APK
- Playit agent: args contain socket and secret-file paths only, and the environment is cleared.
- NetBird runs in-process.
- Server JVM: args contain heap, agent and paths only, with no secrets.
- BuildConfig holds only public Minecraft.how client and path values and the Oracle stack URL.
- Release signing values come from an untracked `keystore.properties`. Add it to `.gitignore` before publishing.

### Clipboard
The pairing code is copied with `IS_SENSITIVE` (`ConnectSetup.kt:204-210`). Other copies (addresses, seed, console with IPs masked) use plain clips.

