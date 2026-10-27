# Review findings at the beta source (private commit 00ecc65)

Generated 2026-10-27 by a nine-agent, read-only static review of the Phoenix source.
- Every finding is **unverified until reproduced**: confirm it against the code (and with a test) before fixing.
- Line numbers refer to this beta snapshot.
- "Repro" steps that need a server must be run only against your own local server.
- References to internal maintainer reports (P0_INCIDENT_REPORT.md, w3/RESULT.md and similar) point to documents that are not published; the summary here is what they established.

## A. Server management and command execution

Security dimension A (server management, process execution, command handling) for Phoenix @ 00ecc65, read-only review of android-stage10a. Process launch is well built. There is no shell anywhere. Every child is started through a ProcessBuilder argv list made of fixed paths, integers and booleans. Owner console input is validated against newline/CR/NUL injection. Player and whitelist names go through a regex allowlist. No exported Android component can start or stop the server or send commands. The native launcher pins libjvm to the APK's native library directory, and PR_SET_PDEATHSIG ends the server when its parent thread exits. No critical or high issues were found. The main weakness is that Phoenix trusts the server's stdout for control decisions. It matches substrings on every line, and player chat is logged to that same stream. As a result: (1) a player chatting "Saved the game" can release the live-backup flush latch early, which can produce silently inconsistent live backups and exports (medium); (2) a player can spoof the recorded server pid, the STOPPING classification used on reconnect, the player list, the seed and the JVM metrics, and can hide their own chat lines from the console with "@@PHOENIX_" (low). Phoenix already guards against this only for the spawn-prep lines (SpawnPrep.kt:66-74). Other findings: the management API bearer secret is sent to whatever socket holds the configured 127.0.0.1 port, so another app on the phone could capture it (medium, depends on unverified Minecraft bind behaviour). The server JVM and all mods run under Phoenix's own UID with no sandbox, and the restart path skips launch-input verification (medium, architectural, must be documented). Remaining issues are liveness and state gaps: an adopted STOPPING server has no timeout and the UI offers no Stop or Force stop; Force stop is queued behind a possibly blocking stdin write; the leftover-process cleanup sleeps under the controller lock and does not refuse to launch when an old JVM is still alive. Info items: automatic server.properties policy changes, debug-only JVM experiment flags, and remote values written unescaped into the voice chat config.

### A1 (medium): Player chat containing 'Saved the game' can release the live-backup flush latch early, giving inconsistent backups and exports

- **Threat:** Any player who can chat on a public, playit.gg or NetBird server. Live backups and exports are the owner's recovery path after world damage, so a corrupted backup is found only when it is restored.
- **Evidence:** PhoenixBackend.kt:1147 counts down saveFlushed for any line where line.contains("Saved the game"). withSavingPaused (PhoenixBackend.kt:3098-3111) arms the latch, sends save-off and 'save-all flush', then runs the copy once latch.await returns. It is used by the live backup (PhoenixBackend.kt:3073-3076) and the live export (PhoenixBackend.kt:3083-3084). Vanilla logs chat to stdout as '[hh:mm:ss] [Server thread/INFO]: <Name> text' or '[Not Secure] <Name> text', which contains the substring. SpawnPrepLog already guards against exactly this (SpawnPrep.kt:66-74) but the save check does not.
- **Repro:** Join as a non-op and send the chat message 'Saved the game' about once per second (under the spam-kick threshold). When the owner taps Backup or Export on the running world, if a chat line is logged after the latch is armed but before the server finishes 'save-all flush', the zip starts while region files and level.dat are still being written. Run against a real server; not verified on a device.
- **Minimal fix:** Accept only the server's own line: anchored regex ^\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\]: Saved the game$ (or SpawnPrepLog's prefix guard, with the message required to equal 'Saved the game'). Also require 'Saving the game' to be seen after the save-all command was sent. Move all stdout classifiers into one pure ServerLogLine parser that rejects message bodies starting with '<', '[Not Secure] ' or '* '.
- **Tests:** JVM unit test on the extracted classifier: '[12:00:00] [Server thread/INFO]: <Mallory> Saved the game' -> false; '[12:00:00] [Server thread/INFO]: [Not Secure] <Mallory> Saved the game' -> false; '[12:00:00] [Server thread/INFO]: Saved the game' -> true. Also a latch test with a fake controller: chat lines delivered between save-off and the flush reply must not release the latch.
- **Confidence:** medium

### A2 (medium): Management API bearer secret is sent to whatever local socket holds the configured 127.0.0.1 port

- **Threat:** A malicious app on the same phone with INTERNET permission. On Android, 127.0.0.1 ports at or above 1024 are shared by all apps. With the secret, the app could later call the real management API (operators, allowlist, stop, settings) whenever Minecraft binds that port.
- **Evidence:** enableManagementApi checks portFree with a short bind and close (PhoenixBackend.kt:986-987,996). If no port is free it returns without changing server.properties, so an earlier enabled=true/port stays (PhoenixBackend.kt:985-990). managementLoaded is set from properties alone (managementUsable, PhoenixBackend.kt:997-998, set at 650). management() takes the port and secret from properties (PhoenixBackend.kt:999-1006). thermalCheck connects and sends the secret (PhoenixBackend.kt:1023-1028). ManagementClient sends 'Authorization: Bearer <secret>' before checking anything about the peer (Management.kt:22-31).
- **Repro:** A local app holds 127.0.0.1:25592..25599 while Phoenix starts a server that had management enabled on 25592. Minecraft cannot bind 25592. When the phone gets hot, thermalCheck connects to the attacker's listener and sends the secret. The attacker releases the port; on the next start Minecraft binds 25592 with the same secret. Depends on Minecraft 26.2 continuing to start after a management bind failure and keeping the secret (unverified).
- **Minimal fix:** Set managementLoaded only after confirming the listener belongs to the server JVM: match the socket inode in /proc/<serverPid>/net/tcp to /proc/<serverPid>/fd (same UID, so readable), or require Minecraft's own management-listener log line from a prefixed server thread. When the management port changes or binding fails, remove management-server-secret so Minecraft generates a new one. Never send the secret to a peer that is not verified.
- **Tests:** Pure decision function managementReady(props, serverListeningPorts) tested with the port listed or not. ManagementClient test with a local ServerSocket fake: when readiness is false, no bytes containing the secret may be written.
- **Confidence:** low

### A7 (medium): Server JVM and every mod run as Phoenix's UID with full access to Phoenix's private data, and launch inputs are not re-verified on restart

- **Threat:** A malicious mod or mod-pack jar, or a remote code execution bug in any installed mod, runs with Phoenix's identity. It can read and modify everything under the app's filesDir, noBackupFilesDir and shared_prefs, read the Playit agent.secret during the window before the agent reports RUNNING, use the app's Keystore-backed secrets from the same UID/SELinux domain, change Phoenix's state (pid prefs, server.properties, routes), and persist after the owner removes the mod.
- **Evidence:** Children inherit the app UID; the server runs in filesDir/servers/baseline (PhoenixBackend.kt:114,500-503). Mod install checks only fabric.mod.json shape and id (ModManager.kt:92-104). The Playit secret is plaintext 0600 until RUNNING (PlayitAgent.kt:102,171-180). The CDS archive and AOT cache in cacheDir are loaded by later starts (JvmTuning.kt:23-30; AotCache flags JvmTuning.kt:86-90), and the AOT key uses only public metadata (JvmTuning.kt:93-95). The automatic restart path (ServerController.java:231) and the user Restart (PhoenixBackend.kt:608-613) skip the artifact hash check (PhoenixBackend.kt:573) and ensureRuntime (PhoenixBackend.kt:596).
- **Repro:** Install a Fabric mod whose initializer reads ../../shared_prefs/phoenix-stage10a.xml and lists ../../../no_backup, or writes a crafted file to cacheDir. Removing the mod does not remove what it wrote.
- **Minimal fix:** Document plainly for the public beta that mods are fully trusted code with Phoenix's own permissions (there is no per-child UID sandbox for exec'd binaries on Android). Re-run the artifact and runtime hash checks on every launch including restarts. Keep CDS/AOT archives keyed by a SHA-256 of the archive recorded in app-only prefs, and delete them when a mod is removed. Write the Playit secret just before exec and delete it as soon as the agent has read it.
- **Tests:** Unit test that the restart path calls the same verifyLaunchInputs() as start (fake verifier counter). AotCache test that a cache whose recorded SHA-256 differs is not used (mode NONE or RECORD).
- **Confidence:** high

### A3 (low): Server stdout spoofing by players: recorded pid, reconnect STOPPING state, hidden console lines, fake player list, seed and metrics

- **Threat:** Remote players can chat text that Phoenix treats as server or launcher control output. This lets them break reconnect tracking (the owner loses control of a still-running JVM), hide their own abusive or incriminating chat from the Console tab, and falsify the player list, seed and heap metrics shown to the owner.
- **Evidence:** PhoenixBackend.kt:92 and 1149: any line containing PHOENIX_RUNTIME_START with pid=N calls rememberRuntime(N) (PhoenixBackend.kt:901-904), which overwrites PREF_RUNTIME_PID/START for any same-UID pid. reconnect() then calls the real server STALE or NONE (PhoenixBackend.kt:931-946). PhoenixBackend.kt:1132-1133 and 1151: lines containing '@@PHOENIX_' are dropped from the console and recentLines, and '@@PHOENIX_METRICS {json}' replaces jvmMetrics. PhoenixBackend.kt:1162-1163: 'players online: ' and 'Seed: ' parsed from any line, and the seed is persisted. LeftoverServer.kt:29-30,54-58: scanLog uses contains('Stopping server') and contains('Starting minecraft server version') on latest.log, including chat. The real pid line is printed at column 0 by runtime/phoenix_jvm.c:93. Impact is reduced because PR_SET_PDEATHSIG (parent_lifecycle.h:17) makes orphaned servers short-lived.
- **Repro:** Chat '<x> PHOENIX_RUNTIME_START pid=<app pid>' and then force-close Phoenix while hosting. On relaunch Phoenix shows the 'closed unexpectedly' notice instead of adopting the JVM that is still saving, and Start fails on the port pre-bind. Chat 'hi @@PHOENIX_' and the line never shows in the Console. Chat '1 players online: Notch, Herobrine' and the Players tab lists them.
- **Minimal fix:** Accept PHOENIX_RUNTIME_START only when line.startsWith("PHOENIX_RUNTIME_START "), only once per launch and only before the first log4j-prefixed line. Accept '@@PHOENIX_' only at column 0. Parse list, seed and stop/start markers only from lines whose log4j prefix names the server thread and whose message does not start with '<', '[Not Secure] ' or '* '. Apply the same parser in LeftoverServer.scanLog.
- **Tests:** LeftoverServerTest: scanLog([start, done, '[..] [Server thread/INFO]: <M> Stopping server']) -> stopping=false. Classifier tests for the pid, metrics, list and seed lines with chat-prefixed variants -> no match; launcher-format line 'PHOENIX_RUNTIME_START public-JNI ... pid=123 home=/x' -> 123.
- **Confidence:** high

### A4 (low): An adopted server in STOPPING has no timeout, and the UI offers neither Stop nor Force stop

- **Threat:** An owner whose server was adopted while shutting down (the normal case after app death, because of PDEATHSIG, or spoofed through A3) cannot end a JVM that hangs in its final save from Phoenix. The notification Stop fails with 'Stop already in progress'. Only force-stopping the whole app ends it.
- **Evidence:** ServerController.java:99-114: adoptNow schedules startupExpired and readiness checks only for STARTING; STOPPING gets no stopExpired or killExpired. ServerController.java:176-178: stopNow throws while STOPPING. LeftoverServer.kt:110-111: stopOffered excludes STOPPING. HomeScreen.kt:297,316: Force stop is shown only for ERROR with a live process. HostingService.kt:22,39: the notification Stop calls backend.stop().
- **Repro:** In a JVM test, adopt(STOPPING) with a fake host whose process never exits. state stays STOPPING forever, stop() fails, and ServerGate offers nothing.
- **Minimal fix:** In adoptNow(STOPPING), arm the same timers as a graceful stop: stopTimeout, then terminate, then killExpired to ERROR. Show Force stop whenever processAlive && phase==STOPPING after a grace period.
- **Tests:** ServerControllerTest: adopt(STOPPING) with short timeouts and a never-exiting fake -> reaches ERROR with terminate() called. ServerGate test: (STOPPING, alive) -> force stop offered after grace.
- **Confidence:** high

### A5 (low): Force stop and state reads can be blocked behind a stdin write or the 20 s leftover wait on the controller's single queue/monitor

- **Threat:** If the server stops draining stdin (JVM frozen or SIGSTOPped, or a mod replacing the console reader) while Phoenix keeps polling, or if a leftover JVM is being waited for, the owner's Force stop cannot run. The main-thread notification monitor can block on the controller lock and cause an ANR.
- **Evidence:** ProcessRuntimeHost.java:38-41: a synchronized blocking write and flush to the child stdin. ServerController.java:233-239: called inside synchronized(this) on the single queue thread. ServerController.java:241-246 and 251-256: forceStop is submitted to that same queue, despite the comment at ProcessRuntimeHost.java:10. PhoenixBackend.kt:547: endLeftovers sleeps up to 20 s inside processBuilder, which runs from host.start inside synchronized startNow (ServerController.java:72-81). HostingService.kt:14-18 calls backend.notificationLine() on the main looper, which calls controller.snapshot(), which is synchronized (PhoenixBackend.kt:2788; ServerController.java:65-67).
- **Repro:** JVM test: a fake RuntimeHost whose command() blocks on a latch; submit command('list'), then forceStop(); forceStop's future does not complete and host.forceStop() is never called. Calling snapshot() from another thread blocks while the write is stuck.
- **Minimal fix:** Make forceStop call host.forceStop() directly (it is lock-free: volatile process) before submitting the state change. Write stdin from a dedicated writer thread with a bounded queue and timeout so the controller never holds its monitor across I/O. Move endLeftovers or reclaimMemory out of host.start into PhoenixBackend.start before controller.start().
- **Tests:** ServerControllerTest with a blocking fake host: forceStop completes within 1 s, and snapshot() returns within 100 ms while a command write is blocked.
- **Confidence:** medium

### A6 (low): Leftover cleanup can still lead to two JVMs on one world: it runs after the port pre-check, proceeds after 20 s, and matches by basename only

- **Threat:** After a crash or app death, an orphan server still saving can coexist with a new launch on the same world. Integrity then rests entirely on Minecraft's session.lock, which is unverified on card storage (FUSE/exFAT). A spurious failed start also triggers mod rollback.
- **Evidence:** PhoenixBackend.kt:575: the port pre-bind happens before processBuilder. PhoenixBackend.kt:466 and 515: reclaimMemory/endLeftovers run inside processBuilder. PhoenixBackend.kt:546-548: SIGTERM, wait up to 20 s, then continue regardless. Reliability.kt:141-142: a process is matched by argv[0] basename and ppid != app only, with no start-time or server-jar check (unlike AdoptedProcess.kt:141-145). PhoenixBackend.kt:639-644: a failed start with changed mods triggers rollback.
- **Repro:** Orphan JVM saving a large world for more than 20 s (its listener already closed, so the port check passes). Press Start: Phoenix sends SIGTERM, waits 20 s and launches a second JVM in the same server directory.
- **Minimal fix:** Run leftover cleanup before the port check. After the wait, refuse to start (clear error) if any leftover server pid is still alive. Also require the server-jar argument for SIGTERM targets. Re-read /proc/<pid>/stat start ticks immediately before sendSignal to narrow PID reuse.
- **Tests:** Extract endLeftovers into a pure function taking a /proc reader, a signal sink and a clock. Assert that a still-alive server after the timeout returns 'refuse start'. Assert that a matching basename without the server jar is not signalled with 15. Assert that ppid==app is never signalled.
- **Confidence:** medium

### A8 (low): Console 'minecraft:stop' or an in-game operator /stop is treated as a crash and auto-restarted

- **Threat:** An operator (or a datapack function or command block running 'stop') ends the server, and Phoenix restarts it within 5 s, up to 3 times in 15 min, against the stopper's intent. The owner typing 'minecraft:stop' gets the same result and the automatic pre-start backup is skipped.
- **Evidence:** ServerController.java:237: only an exact 'stop' is treated as a graceful stop. ServerController.java:211,227: any other exit is 'unexpected'. PhoenixBackend.kt:250-252 then calls afterUnexpectedExit. PhoenixBackend.kt:886-896 restarts with afterCrash=true, which skips backUpBeforeStart (PhoenixBackend.kt:581).
- **Repro:** Type '/minecraft:stop' in the Console while ONLINE; Phoenix logs 'stopped unexpectedly ... Starting it again in 5 seconds'.
- **Minimal fix:** Treat 'minecraft:stop' like 'stop'. Recognise the server's own 'Stopping server' line from the server thread (via the prefix-anchored parser from A1/A3) as an intentional stop that suppresses auto-restart, unless a crash line precedes it.
- **Tests:** ServerControllerTest: command('minecraft:stop') moves the state to STOPPING. Pure policy test: the lines [Done, '[..] [Server thread/INFO]: Stopping server'] followed by exit 0 give no restart.
- **Confidence:** high

### A9 (info): Phoenix changes security-relevant server.properties without a per-change prompt (enforce-secure-profile, accepts-transfers, management API, spawn-protection)

- **Threat:** An owner who sets enforce-secure-profile=true has it reverted to false on every Fabric start. Transfers are enabled automatically when Playit and a custom name are set. These are deliberate crossplay or front-door choices, logged to the console, but not opt-in.
- **Evidence:** PhoenixBackend.kt:704-711 (rewrites enforce-secure-profile=false on each start, a non-atomic write outside propertiesLock); 716-721 (accepts-transfers=true); 982-995 (management API on); 695-703 (spawn-protection 0, once).
- **Minimal fix:** Record a 'Phoenix-managed' marker per key, as is already done for the management API and spawn-protection, and never override a value the owner changed. Use writeProperties (atomic, locked) for enforce-secure-profile.
- **Tests:** Pure policy test: an owner-set enforce-secure-profile=true with the marker present is left unchanged.
- **Confidence:** high

### A10 (info): Debug-variant JVM experiment file accepts arbitrary -XX:/-D flags, including ones HotSpot runs through a shell

- **Threat:** In debuggable variants (debug, and freshCheck/uiCheck, which use initWith(debug)), anything able to write filesDir/jvm-experiment.txt (adb run-as, or code already running in the app UID such as a mod) can add -XX:OnError=/-XX:OnOutOfMemoryError= (HotSpot runs these via sh -c) to the next start. Not reachable in release, where BuildConfig.DEBUG is false.
- **Evidence:** JvmTuning.kt:52-55 (filters only for -XX:/-D with no whitespace); PhoenixBackend.kt:484-485 (guarded by BuildConfig.DEBUG); app/build.gradle.kts:84-113 (fast is non-debuggable; freshCheck and uiCheck inherit debuggable).
- **Minimal fix:** Allowlist experiment flag names (reject OnError, OnOutOfMemoryError, ErrorFile, JavaAgent and similar), and publish only the release variant.
- **Tests:** JvmTuningTest: experimentFlags('-XX:OnError=/x') returns an empty list.
- **Confidence:** high

### A11 (info): Values from remote APIs are written unescaped into voicechat-server.properties

- **Threat:** A voice endpoint from the Playit API, the NetBird cluster name, or the regional pairing IP containing a newline would add arbitrary keys to the Simple Voice Chat config that the server reads at start. This requires a malicious or compromised control plane or pairing peer.
- **Evidence:** PhoenixBackend.kt:2350-2352 (raw 'voice_host=$wanted' line, file.writeText); PhoenixBackend.kt:2359-2364 (wanted comes from playit.savedEndpoints().voice.text, netBird.regionalProxy().ip, netBird.endpoints().cluster()).
- **Minimal fix:** Validate wanted against ^[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?$ (or an IP literal) before writing, and reject anything else.
- **Tests:** Pure function voiceHostLine(wanted) rejects strings containing \n, \r, '=' or spaces.
- **Confidence:** medium

**Open questions:**

- Does Minecraft 26.2 keep starting when the management server cannot bind its port, and does it keep or regenerate management-server-secret across starts? This decides whether A2 can be exploited.
- Is Minecraft's session.lock (FileChannel.tryLock) enforced on the microSD/FUSE path used for --universe on target devices? This decides the real impact of A6.
- With Phoenium's regionised threading, is player chat logged off the main server thread, concurrently with 'save-all flush'? That would widen the A1 race window from about one tick to the whole flush.
- Where do the NetBird Go SDK's own config/WireGuard private key and the Minecraft.How session cookies live on disk, and are they plaintext? NetBirdStore and PlayitManager use Keystore-wrapped files (NetBirdStore.kt:23-25, PlayitManager.kt:428), but the SDK's own files and the WebView cookie store were not traced. This affects the blast radius of A7.
- Is netBird.regionalProxy().ip (set through ProxyPairingHost from the owner's VM) validated as an IP literal before use? Not traced; relevant to A11 and to the network-dimension review.
- Confirm on device that /proc is mounted with hidepid on supported Android versions, so endLeftovers and scanForServer only see Phoenix's own UID (the code comments assume this: Reliability.kt:135, LeftoverServer.kt:80).
- Not verified on a device: whether chat lines in 26.2 dedicated-server stdout carry the '[Not Secure] ' prefix when enforce-secure-profile=false. Either way the substring checks in A1/A3 match.

## B. Networking and remote access

I mapped every listener and outbound client in Phoenix's networking layer (branch phoenix/polish-integration @ 00ecc65, android-stage10a) by reading the source only. The app listens on six things:
- Minecraft Java TCP 25565 on all interfaces.
- Geyser UDP 19132 on 0.0.0.0.
- Simple Voice Chat UDP 24454 (the mod's own default bind).
- Minecraft's management API (WebSocket JSON-RPC) on 127.0.0.1:25592-25599 only, without TLS, protected by a bearer secret.
- The NameFrontDoor TCP 25591 on all interfaces (the custom-name front door).
- A short-lived ProxyPairingHost TCP listener on a random port 40000-48999 on all interfaces, open only while a pairing is pending.

There is no HTTP server, no NanoHTTPD, no WebView JavascriptInterface and no debug server in the main source set. RCON and query are written as disabled. The Playit agent's IPC is a filesystem Unix socket inside the app's private directory.

Every outbound HTTPS client is pinned to a fixed host, turns off automatic redirects (or follows only same-origin ones), and caps response size and JSON nesting depth. No code replaces the TrustManager or HostnameVerifier. The WebView cancels on any SSL error and is hardened (no file or content access, no mixed content, no popups, no JS bridge). Cleartext traffic is allowed only to localhost and 127.0.0.1.

The pairing protocol is sound: mutual HMAC proof of a 128-bit secret, fresh ECDH keys on both sides, and AES-GCM bound to the transcript. The Oracle VM opens only the game ports and WireGuard, and has no SSH.

There are no critical or high findings. The most notable weakness is local. Phoenix sends the persistent management-API bearer secret to whatever process holds the configured loopback port, without confirming it is Minecraft. A co-installed app that grabs the port could capture the secret and later control the server. The remaining findings are low-severity denial of service on the always-reachable front door and pairing listeners, log spoofing through player names, and informational design notes. Normal game ports (Java, Bedrock, voice) are meant to be reachable and should not be restricted.

### NET-1 (low): Management API bearer secret is sent to whatever owns the loopback port, with no check that it is Minecraft

- **Threat:** A malicious co-installed app with only the INTERNET permission squats 127.0.0.1:25592-25599. It captures the persistent management-server-secret when Phoenix's thermal check connects. Later it uses that secret against the real management API (stop server, add operators, change allowlist or bans).
- **Evidence:** PhoenixBackend.kt:985-990: the port is picked once. If no port in 25592-25599 is free, the function returns and leaves the old port in server.properties. PhoenixBackend.kt:650 and 956: managementLoaded = managementUsable(), which only reads server.properties and never confirms Minecraft bound the port. PhoenixBackend.kt:1000-1004 and 1023-1033: management() is called every 30 s while the phone is hot. Management.kt:21-28 connects to 127.0.0.1:port and sends 'Authorization: Bearer $secret' before authenticating the peer (cleartext, no TLS: tls-enabled=false at PhoenixBackend.kt:988-989). Minecraft generates the secret once and Phoenix never rotates it. Not verified: whether 26.2 keeps running when the management port is already taken; if it aborts startup, the attack fails.
- **Repro:** Do the configured start once (the port is saved in server.properties). Stop the server. A test app binds all of 127.0.0.1:25592-25599. Start the server and make the device hot, or lower ThermalPolicy thresholds in a debug build. The test app receives the HTTP Upgrade request containing the Authorization header. Release the ports and restart; the same secret is now valid on the real API.
- **Minimal fix:** Before each start, write a fresh random management-server-secret (32+ bytes from SecureRandom) into server.properties. Only set managementLoaded after the server log reports the management server listening on that exact port in this run. Optionally enable management TLS with a self-signed cert Phoenix generates and pins.
- **Tests:** Unit-test that enableManagementApi rotates the secret on every start. Instrumentation test: with the port pre-bound by another socket, management() returns null and no bytes are written to that socket.
- **Performance impact:** Negligible.
- **Confidence:** plausible

### NET-2 (low): Anyone who reaches the pairing listener can hold or close the pairing window (DoS)

- **Threat:** An internet attacker who learns the temporary NetBird pairing service host and port (public TCP on the shared cluster), or any LAN or NetBird peer, can do either of two things during the 48 h window. They can hold the single connection slot by trickling bytes, since each read has its own 30 s timeout and there is no overall deadline. Or they can send 30 bad handshakes, which permanently cancels the pairing. The proxy token is not exposed; the effect is only that the owner's Oracle setup fails.
- **Evidence:** ProxyPairingHost.kt:35-38 binds InetSocketAddress(pending.localPort) on all interfaces with backlog 1. ProxyPairingHost.kt:43-47 handles one connection at a time with soTimeout 30_000 per read. ProxyPairing.java:347-356 reads byte-by-byte up to 8192. ProxyPairingHost.kt:52-56 counts failures; NetBirdNetworkManager.kt:110 and 329-341 cancel the pairing after 30 failures. NetBirdNetworkManager.kt:274-276 publishes the pairing port as a public NetBird TCP service.
- **Repro:** While a pairing is open, connect to <cluster>:<pairing port> 30 times and each time send a valid-looking hello and a wrong mac. The UI then shows 'Pairing closed after repeated wrong codes'. Alternatively, keep one connection open and send one byte every 25 s.
- **Minimal fix:** Bind the listener to the NetBird tun address only (the TUN peerIp), not the wildcard. Add an overall per-connection deadline (for example 20 s) and serve connections concurrently, or close idle ones fast. Replace the permanent close with a time-based backoff, or require the failures to come within a short interval.
- **Tests:** Unit test: ProxyPairing.serve with a trickling InputStream is aborted after the overall deadline. Host test: 30 failures produce backoff, not cancellation.
- **Performance impact:** None.
- **Confidence:** confirmed

### NET-3 (low): Front door accepts on every interface and can be tied up by 16 slow connections (slowloris)

- **Threat:** A remote attacker reaches 25591 through the public NetBird EU name service, or directly over LAN or NetBird. With 16 idle or trickling connections the attacker exhausts the worker pool, so server-list pings and custom-name joins (Transfer to Playit) are refused. Joining directly through Playit's address is unaffected.
- **Evidence:** NameFrontDoor.kt:147-149: ThreadPoolExecutor with max 16 and a SynchronousQueue; excess connections are closed (lines 171-172). NameFrontDoor.kt:158 binds InetSocketAddress(port), i.e. all interfaces. Lines 177, 200 and 208-219 use only per-read idle timeouts (10 s, 15 s, 60 s) and splice for as long as bytes keep arriving. PhoenixBackend.kt:75 (FRONT_DOOR_PORT=25591) and 2040 (NetBird service to this port).
- **Repro:** Open 16 TCP connections to <eu-cluster>:<name port> and send one byte of a VarInt every 9 s on each. A real client's server-list ping now gets connection reset.
- **Minimal fix:** Add an absolute deadline for the handshake and login phases (for example 5 s). Cap concurrent connections per source address. Bind to the NetBird interface address if LAN access to the front door is not needed (players on LAN use 25565 directly).
- **Tests:** NameFrontDoorTest: 16 stalled sockets plus 1 real status ping; the ping must succeed once deadlines expire.
- **Performance impact:** None.
- **Confidence:** confirmed

### NET-4 (low): Front door logs and counts an unvalidated, client-supplied player name

- **Threat:** An unauthenticated remote client can put arbitrary up-to-16-character UTF-8 text, including control characters or line breaks, into the owner's Phoenix console as '<name> joined through your custom name…'. It can also inflate the transfer counter. Nothing is authenticated at this stage, because real authentication happens later on the Playit-routed server.
- **Evidence:** NameFrontDoor.kt:81-87: readString only checks length. NameFrontDoor.kt:103-107 (parseHello) and 192-195 (transfers++ and log with hello.name).
- **Repro:** Send a 26.2 handshake (intent 2) and a LoginHello with the name 'Paired\nOK'. The console shows a spoofed line.
- **Minimal fix:** Accept only names matching [A-Za-z0-9_]{1,16} (Java), or a Floodgate prefix plus that; otherwise drop the connection. Log 'a player' instead of the raw name if validation fails.
- **Tests:** Unit-test parseHello rejecting control characters and newlines.
- **Performance impact:** None.
- **Confidence:** confirmed

### NET-5 (info): Public routes hide player IPs from Minecraft (IP bans and per-IP limits do not work)

- **Threat:** Playit tunnels target 127.0.0.1, and front-door splices come from 127.0.0.1. Minecraft, Geyser and Simple Voice Chat therefore see loopback, or presumably the NetBird proxy's overlay address, rather than the player. /ban-ip, Geyser IP logging and any per-IP throttling cannot tell abusive players apart. This is not a vulnerability, but owners should be told that bans must be by account.
- **Evidence:** PlayitManager.kt:279, 286, 394 (LOCAL_IP="127.0.0.1"); NameFrontDoor.kt:204-212 (upstream connect to 127.0.0.1); CrossplayInstaller.kt:81 (log-player-ip-addresses: false)
- **Minimal fix:** Document it. Optionally enable PROXY protocol support (Playit supports it; Geyser and Fabric need matching config) if per-IP moderation is wanted.
- **Tests:** n/a
- **Confidence:** confirmed

### NET-6 (info): accepts-transfers=true and enforce-secure-profile=false are safe only while online-mode=true

- **Threat:** If an owner hand-edits online-mode=false (Phoenix itself never does), any client could join or be transferred in under any name. Phoenix's own settings cannot change online-mode, but imported or hand-edited server.properties are kept as they are.
- **Evidence:** PhoenixBackend.kt:431 (online-mode=true, only when the file is absent), 704-710 (enforce-secure-profile=false), 716-720 (accepts-transfers=true), 3160-3175 (online-mode is not in the settings allowlist)
- **Minimal fix:** Warn in the UI or console when online-mode=false is detected while a public route (Playit, NetBird, regional) is active.
- **Tests:** n/a
- **Confidence:** confirmed

### NET-7 (info): ProxyPairing docs say the token is revoked on failure, but the app passes a no-op revoke

- **Threat:** If an authenticated VM receives the encrypted token but reports ok:false (or the connection drops), the proxy token stays valid and stays in the pairing record until cancel or replace. The token is only ever delivered after the peer has proven K, so the exposure is limited to holders of K.
- **Evidence:** ProxyPairing.java:129-141, 160-163, 212-213 (revoke runs when not confirmed); NetBirdNetworkManager.kt:301-308 (Issued(..., DEFAULT_BASE_URI) {}: no-op revoke, by design so the VM can retry)
- **Minimal fix:** Correct the javadoc, or rotate the token after a delivery the VM did not confirm.
- **Tests:** n/a
- **Confidence:** confirmed

### NET-8 (info): Pairing secret persists in OCI user_data and Terraform state and is reachable through IMDS from the host-network proxy container

- **Threat:** Anyone who can read the stack's Terraform state or instance metadata during the 48 h window holds K and could impersonate the VM to obtain the NetBird proxy token. This is mitigated by the variable being marked sensitive, by fresh ECDH keys making recorded exchanges useless, and by the code being deleted from disk after pairing. It is accepted by design.
- **Evidence:** deploy/oracle-proxy/main.tf:31-36, 200-202; deploy/oracle-proxy/variables.tf:234-237; deploy/oracle-proxy/bootstrap.sh:71 (--network host); deploy/oracle-proxy/phoenix_pair.py:326; ProxyPairing.java:47-50
- **Minimal fix:** None required. Optionally have the phone close the pairing as soon as it succeeds (already done) and document that the Terraform state should be deleted or kept private.
- **Tests:** n/a
- **Confidence:** confirmed

### NET-9 (info): Callback uses a custom URI scheme that other apps can also register (mitigated)

- **Threat:** Another app can declare the same <applicationId>.minecraft-how scheme and receive the authorization code. This is mitigated by PKCE S256 (the verifier never leaves the app), a 32-byte state, the 10-minute expiry and a one-shot exchange. The flow is dormant in default builds because the contract is blank.
- **Evidence:** AndroidManifest.xml:54-63; MinecraftHowBrowserConnection.kt:67-76, 105-153, 160-168; app/build.gradle.kts:14-24
- **Minimal fix:** If Minecraft.How later supports it, prefer a verified https App Link or Auth Tab redirect.
- **Tests:** n/a
- **Confidence:** confirmed

### NET-10 (info): Embedded NetBird VPN accepts management-pushed routes and DNS for the whole device; peers can reach every 0.0.0.0 listener

- **Threat:** Routes and DNS servers pushed by the owner's NetBird account apply to the whole device; there is no per-app restriction. Any peer that the owner's NetBird access policies allow (the NetBird default policy is all-to-all) can reach the phone's wildcard listeners: 25565, 19132, the voice port, 25591 and the pairing port. The management API is not reachable this way because it is loopback-only.
- **Evidence:** NetBirdVpnService.kt:600-610 (no addAllowedApplication or addDisallowedApplication; routes and DNS from management); NameFrontDoor.kt:158; ProxyPairingHost.kt:37
- **Minimal fix:** Document that a dedicated NetBird account or restrictive access policies are recommended. Consider binding Phoenix's own listeners (front door, pairing) to the tun address.
- **Tests:** n/a
- **Confidence:** confirmed

### NET-11 (info): Device-to-device transfer may copy server.properties (with the management secret) on Android 12+

- **Threat:** On Android 12+, allowBackup=false does not stop device-to-device migration, which follows data_extraction_rules. Those rules exclude only shared preferences and the WebView profile, so filesDir/servers/baseline (server.properties with the management secret, worlds, logs) may move to a new device. Keystore-encrypted NetBird and Playit secrets are in noBackupFilesDir and are not transferred. This Android platform behaviour was not checked against current documentation.
- **Evidence:** AndroidManifest.xml:19-21; app/src/main/res/xml/data_extraction_rules.xml (device-transfer excludes sharedpref and app_webview_minecraft-how-auth only); PhoenixBackend.kt:114 (server dir in filesDir)
- **Minimal fix:** Add <exclude domain="file" path="servers/"/> (or include-only rules) to device-transfer, or rotate the management secret each start (NET-1 fix).
- **Tests:** n/a
- **Confidence:** plausible

**Open questions:**

- Does Minecraft 26.2 keep running if the management server port is already bound by another process, or does startup fail? This determines whether NET-1 can actually be exploited.
- Does the NetBird reverse proxy (L4 TCP/UDP services, BYOP) forward the client's real source address, for example with PROXY protocol, or does the phone see the proxy's overlay IP? I could not verify this from the Phoenix source.
- Is the embedded NetBird v0.80 client's SSH server and inbound firewall disabled by default in the bundled AAR? Its config is created by the Go library (config.json in noBackupFilesDir/netbird-client), and I could not verify this without the AAR or Go sources.
- Which NetBird access policy does Phoenix rely on for the owner's account? NetBird's default all-to-all policy would expose the phone's wildcard listeners to every peer in that account.
- Does Playit's agent support PROXY protocol for these tunnel types, and does Phoenix enable it? There is no evidence in the source that it does.
- Release builds: are the phoenix.minecraftHow.* and phoenix.oracle.stackZipUrl Gradle properties set anywhere (for example <gradle-user-home>/gradle.properties)? The repo's gradle.properties does not set them, so the browser handoff is dormant.
- Does the NetBird reverse-proxy container open any listener other than 127.0.0.1:8443, :8080 and WireGuard 51820 under --network host? The OCI security list blocks other ports regardless, but the host iptables chain does not end in an explicit DROP of its own.
- Android 12+ device-to-device transfer behaviour with allowBackup=false and these data_extraction_rules (NET-11) is unverified on device.

## C. Denial-of-service and resource exhaustion

Application-layer DoS review of Phoenix (branch phoenix/polish-integration @ 00ecc65, worktree clean). Most buffers and HTTP clients are bounded. The console keeps 400 entries, each line is cut at 8192 chars, every HTTP client has timeouts and byte caps, world-ZIP import has full limits, and Playit and server auto-restart are rate-limited. Nine findings below are real and backed by source (F1-F9), and four are info-only (F10-F13).

1. HIGH (F1): any player who can chat can crash the Phoenix app. The server's stdout is parsed with unanchored `contains()` plus `toInt()/toDouble()/toLong()`. A chat line such as "Average time per tick: 1.2.3ms" or "PHOENIX_RUNTIME_START pid=99999999999" throws NumberFormatException on the `phoenix-runtime-output` thread. That thread only catches IOException and there is no uncaught-exception handler, so Android kills the app. After a reconnect the adopted process replays the last 256 KiB of latest.log through the same code. So the app crashes again about a second after every launch while that server runs.
2. MEDIUM (F2): slow-sender (slowloris) holding of the two app-owned listeners. The name front door (port 25591, all interfaces, 16 workers) and the pairing listener (one slot, all interfaces) only time out individual reads, with no overall deadline. One pairing connection can block the whole 48 h pairing window. 16 connections can block the front door with very little traffic.
3. MEDIUM (F3): ZIP-bomb or storage exhaustion in chunk import. WorldManager.importChunks has no limits, and its free-space check uses the archive's own declared sizes, which can lie.
4. MEDIUM (F4): out-of-memory from untrusted mod and mrpack archives. The 512 KiB size check trusts the declared size, nested jars are buffered up to 64 MiB each with no count cap, and modrinth.index.json is read without a limit. launchAction catches only Exception, so an OutOfMemoryError kills the app.
5. LOW (F5-F9): reconnect() reads the whole latest.log into memory; front-door logins flood the console and wipe evidence/server.log; spoofed chat lines turn off auto-restart and fake dashboard values; per-address limits become global behind the relays; agent.log only rotates when the agent starts.

Recommended fixes are cheap and sit at connection or ingestion boundaries: wrap the per-line parser in a catch and anchor it to the server's own log prefix, add a total deadline per connection, reuse the WorldImporter limits, and use bounded reads. None of them touch Minecraft's gameplay packet path.

### F1 (high): Player chat crashes the Phoenix app (NumberFormatException on the server-output reader thread), and the crash repeats on every launch after a reconnect

- **Threat:** Any player who can join (white-list is off by default; online-mode Java or Floodgate Bedrock) and send a chat message. No operator rights are needed.
- **Evidence:** PhoenixBackend.kt:1149 `RUNTIME_PID.find(line)?.let { it.groupValues[1].toInt() }` (regex `PHOENIX_RUNTIME_START.*pid=(\d+)`, line 92); :1158 `AVERAGE_TICK` `([0-9.]+)` then `.toDouble()`; :1159-1161 `TICK_PERCENTILES` `(\d+)` then `.toInt()`; :1164-1166 `TICK_PROFILE` `(\d+)` then `.toLong()`. All are guarded only by an unanchored `line.contains(...)`, and Minecraft logs player chat to stdout. consume() is called unguarded from onLine (PhoenixBackend.kt:249). ProcessRuntimeHost.java:23-36 catches only IOException. There is no UncaughtExceptionHandler in app or core, so Android's default handler kills the process. Replay: after a restart, reconnectAtLaunch (PhoenixBackend.kt:268-273, 929-955) adopts the server, and AdoptedProcess.LogTail (AdoptedProcess.kt:67-103) replays the last 256 KiB of latest.log through the same consume().
- **Repro:** With a server online, a player sends the chat message `Average time per tick: 1.2.3ms` (or `PHOENIX_RUNTIME_START pid=99999999999`, or `Percentiles: P50: 1ms P95: 1ms P99: 1ms. Sample: 99999999999`). The server logs `[..] [Server thread/INFO]: [Not Secure] <Name> Average time per tick: 1.2.3ms`. consume() throws NumberFormatException on phoenix-runtime-output and the app process dies. The server keeps running as an orphan. On every later app launch, the reconnect replays the poisoned line about 1 s after startup and the app dies again. This continues until more than 256 KiB of newer log has been written, or the owner force-stops the app, which kills the server without a final save.
- **Minimal fix:** (1) In ProcessRuntimeHost's reader, wrap `listener.onLine(...)` in try/catch(RuntimeException) and keep reading, so a parser bug can never stop draining or kill the process. (2) In consume(), use toIntOrNull/toDoubleOrNull/toLongOrNull. (3) Anchor every status parser (runtime pid, tick query, percentiles, profiler, `players online:`, `Seed: [`, `@@PHOENIX_`) to the server's own logger prefix, as SpawnPrepLog.logPrefix does (SpawnPrep.kt:66-74). For example, accept `@@PHOENIX_` and `PHOENIX_RUNTIME_START` only at the start of a line, since the agent prints them without a log4j prefix, and accept tick and seed replies only when the text after `]: ` starts with the expected literal.
- **Tests:** Unit test: feed consume() (or a pure LineParser you extract) these lines: `[12:00:00] [Server thread/INFO]: <Steve> Average time per tick: 1.2.3ms`, `... PHOENIX_RUNTIME_START pid=99999999999`, `... Sample: 99999999999`. Assert no exception, mspt and processId unchanged. Integration test: a ProcessRuntimeHost with a listener that throws on the first line still delivers later lines and onExit. Adoption test: an AdoptedProcess tail that contains the poisoned line does not throw.
- **Performance impact:** Negligible. A try/catch per line costs nothing measurable, the anchored checks replace existing contains() calls, and none of this is in Minecraft's packet path.

### F2 (medium): Slowloris on the custom-name front door: 16 trickling connections hold every worker indefinitely

- **Threat:** Anyone who can reach port 25591: the NetBird TCP service for the custom name (all connections arrive from the relay, so per-IP limits cannot work) or any LAN peer, since it binds 0.0.0.0.
- **Evidence:** NameFrontDoor.kt:147 has 16 workers with a SynchronousQueue, and :170-172 rejects any extra connection. :177 sets soTimeout=10 s per read only. :181 readFrame reads up to 1024 bytes, and :39-48 / :60-65 read one byte at a time. :185 and :189 read up to 256 and 32 KiB. :197 `client.soTimeout = 5_000; while (input.read() >= 0) Unit` drains with no end time. :200-220 splice uses per-read idle timeouts of 15 s or 60 s and starts one extra thread per connection.
- **Repro:** Open 16 TCP connections to the front door. On each, send a valid 26.2 login handshake and Hello, read LoginFinished, send LoginAcknowledged, then send 1 byte every 4 s. Alternatively, send a handshake's bytes one every 9 s. All 16 workers stay busy. Every real server-list ping or join through the custom name is accepted and immediately closed (RejectedExecutionException), for as long as the attacker keeps up about 4 bytes/s in total.
- **Minimal fix:** Give each connection a total deadline before the splice. For example, record `val deadline = now + 10_000` at accept time and check it in a bounded read helper. Or close the socket from a ScheduledExecutor after 10 s unless it has reached splice(). Replace the drain loop at :197 with `client.shutdownOutput()` plus at most 5 s of total wait, or just close. Read the handshake with a single bounded read into a buffer instead of a byte-at-a-time VarInt. Optionally cap concurrent splices separately from handshakes, so long Via/legacy splices cannot starve handshakes.
- **Tests:** Unit test using a local ServerSocket on port 0 (NameFrontDoor.start(0)): open 16 sockets that send 1 byte every 4 s. Assert each is closed within about 11 s and that a 17th client then completes checkTransfer().
- **Performance impact:** One timestamp check per read or one scheduled task per connection, only during the handshake. Spliced game traffic is not affected.

### F3 (medium): Pairing listener: one slow connection blocks Oracle-proxy pairing for the whole 48 h window; 30 wrong MACs cancel it

- **Threat:** Anyone who can reach the pairing port: the temporary NetBird shared-cluster TCP service `phoenix-<key>-pair.<cluster>`, or any LAN peer, because the socket binds all interfaces.
- **Evidence:** ProxyPairingHost.kt:33-36 bind(InetSocketAddress(localPort), backlog=1), serving one connection at a time. :41-44 sets accept soTimeout 30 s and per-connection soTimeout=30 s, with no total deadline. The expiresAt check at :38 runs only between connections. ProxyPairing.java:347-356 readLine reads unbuffered, one byte per read, up to 8 KiB. NetBirdNetworkManager.kt:108-110 sets PAIRING_WINDOW_MS=48 h and MAX_PAIRING_FAILURES=30. :325-338 makes each failure a store write, and failure 30 calls cancelRegionalPairing.
- **Repro:** While a pairing is open, connect and send 1 byte every 29 s. The VM's connection waits in a backlog of 1 or is refused for up to about 66 h per 8 KiB line, which is longer than the window. Or send 30 well-formed hellos with a wrong MAC: the pairing is cancelled and its token revoked, and the owner must generate a new code and redeploy.
- **Minimal fix:** Add a total per-connection deadline: about 20 s for hello, MAC and confirmation (the protocol is 3 short lines), enforced with a scheduled socket close. Wrap the stream in BufferedInputStream. Optionally bind the listener to the NetBird interface address rather than 0.0.0.0. Optionally rate-limit authentication failures in time (for example at most 1 every 2 s) rather than only counting them, so cancellation takes longer than a burst.
- **Tests:** Use ProxyPairing.serve with a piped stream that trickles 1 byte every 29 s and assert the host closes it within the deadline. Then run a real ProxyPairing.pair() client against ProxyPairingHost and assert pairing succeeds after the slow client is dropped.
- **Performance impact:** None on gameplay; this listener only exists while a pairing is open.

### F4 (medium): Chunk import copies ZIP entries with no size, ratio or count limit (ZIP bomb fills phone storage)

- **Threat:** A malicious or malformed region-file ZIP that the owner imports (Worlds > import chunks).
- **Evidence:** PhoenixBackend.kt:3133-3149 copies the user's file (up to 8 GiB) into cacheDir and then calls worldManager.importChunks. WorldManager.kt:130-150: `total = picked.sumOf { byName.getValue(name).size.coerceAtLeast(0) }` uses only central-directory declared sizes, and unknown (-1) counts as 0. The check is `total < freeBytes - 256 MiB`, then `input.copyTo(it, 65536)` copies with no byte counter, no per-entry or total cap, no ratio check, no CRC check and no entry-count limit. None of WorldImportLimits / ZipCentralDirectory (used by WorldImporter) is applied here.
- **Repro:** Build a ZIP with `region/r.0.0.mca` that is deflated (about 1000:1) and whose central directory declares uncompressed size 0 or a small value. Import it as Overworld chunks. Whether this works depends on the runtime ZipFile not enforcing the declared size; see open_questions. If it does not enforce it, the copy writes until storage is full, so the world save, backups and app prefs can fail. Even with honest sizes, nothing limits the number of entries or the ratio.
- **Minimal fix:** Reuse WorldImportLimits and ZipCentralDirectory.read(archive, maxEntries). Count the bytes actually written per entry and in total (abort when over the declared size, maxEntryUncompressedBytes or the free-space budget), check the compression ratio and verify the CRC, as WorldImporter.java:126-150 already does. Region files are at most about 1 GiB each in practice.
- **Tests:** Unit tests: (a) a ZIP whose declared size is smaller than the inflated size is rejected after declared+1 bytes and leaves no .part files; (b) a ratio over 200 is rejected; (c) more than maxEntries is rejected; (d) valid region ZIPs still import.
- **Performance impact:** A counter and CRC32 per 64 KiB block; import is an offline operation.

### F5 (medium): Untrusted mod and mrpack archives can exhaust the app heap; OutOfMemoryError escapes launchAction

- **Threat:** A malicious or malformed mod jar or .mrpack added by the owner (Mods > Add mod / Add pack / pack editor).
- **Evidence:** ModChecks.kt:36-37 checks the declared `entry.size > MAX_META_BYTES` and then calls `readBytes()` without a limit. :49-60 collectNested buffers every `META-INF/jars/*.jar` of a nested jar into memory, up to 64 MiB each, with no limit on how many or in total (MAX_NESTED only counts ids). ZipInputStream skips each entry by inflating all of it. ModManager.kt:293-298 does the same declared-size-then-readBytes for fabric.mod.json. ModPacks.kt:119-120 reads modrinth.index.json with `readBytes()` and no check at all. PhoenixBackend.kt:297-301 launchAction catches Exception, not Error, so an OOM on the `work` thread kills the app. ModPacks.list (ModPacks.kt:39-46, 169-173) re-reads metadata for every pack jar when the list is shown; its metaCache lives in memory only.
- **Repro:** Build a mod jar whose fabric.mod.json declares a nested jar. Inside that nested jar, put 50 entries `META-INF/jars/aN.jar`, each 64 MiB of zeros deflated to about 64 KiB. Adding it buffers about 3.2 GB, which OOMs the app. Or build a .mrpack whose modrinth.index.json inflates to 1 GiB.
- **Minimal fix:** Read with a counting wrapper that stops at a limit and ignores the declared size (the readBounded helper already exists at ModChecks.kt:69-77; use it for fabric.mod.json and modrinth.index.json too). In collectNested, buffer only jars listed in the parent's `jars` array, cap the bytes buffered per nested level (for example 64 MiB total), and stop reading the ZipInputStream after the needed entries or a total inflated-bytes budget. Optionally catch OutOfMemoryError around archive inspection only and report it as an invalid archive.
- **Tests:** Unit tests with generated jars: a lying fabric.mod.json size, 50 large nested jars, and an oversized modrinth.index.json. Assert each is rejected with a bounded allocation, for example by running under -Xmx64m in a JVM unit test.
- **Performance impact:** None for normal mods; it only limits bytes read.

### F6 (low): reconnect() reads all of logs/latest.log into memory

- **Threat:** latest.log grows without limit within a server session (vanilla log4j rotates only by day or startup), and players can influence it with chat, connections or error spam. The app reads it in full when it reconnects after being killed.
- **Evidence:** PhoenixBackend.kt:937 `LeftoverServer.scanLog(runCatching { File(server,"logs/latest.log").readLines() }...)`; LeftoverServer.kt:54-58 needs only the last start, Done and stop lines.
- **Repro:** A long-running server whose latest.log has reached hundreds of MB (a chat-spam bot over days, or a mod error loop). Android kills Phoenix and the owner reopens it. readLines() then allocates several times the file size on the sampler thread, which can cause OOM or long GC pauses. It runs inside runCatching, but other threads can fail meanwhile.
- **Minimal fix:** Read only the tail (for example the last 1 MiB using RandomAccessFile, like AdoptedProcess.TAIL_BYTES) and scan it. If the start line is not in the tail, treat it as not starting.
- **Tests:** Unit test LeftoverServer.scanLog fed by a tail reader over a 200 MB synthetic log; assert memory stays bounded and the result matches a full scan.
- **Performance impact:** Faster reconnect; no runtime cost.

### F7 (low): Unauthenticated front-door logins flood the console and wipe evidence/server.log

- **Threat:** Anyone who can reach port 25591. The login path needs no account, because the front door answers with Transfer and never checks identity.
- **Evidence:** NameFrontDoor.kt:185-195: every completed login prints `${hello.name} joined through your custom name...` through log(), and the name is any 16 UTF-8 chars, including control characters (readString :81-87). log goes to append() (PhoenixBackend.kt:1198), then show() (400-entry buffer, :1187-1196) and logLine(). When evidence/server.log passes 8 MiB it is truncated to empty (PhoenixBackend.kt:1172, 1178).
- **Repro:** Script about 100 front-door logins per second (16 at a time). The 400 console entries are replaced within seconds, the per-line file writes add flash I/O, and within minutes server.log reaches 8 MiB and is cleared, losing earlier evidence. Names containing newlines also split server.log lines.
- **Minimal fix:** Count transfers and log at most one line per N seconds (for example "12 joins handed to Playit in the last minute"). Strip control characters and limit names to `[A-Za-z0-9_.]{1,16}`. Rotate server.log to server.1.log instead of truncating it.
- **Tests:** Unit test that 1,000 transfers in 10 s produce at most a few console lines, and that a name containing \n is sanitized.
- **Performance impact:** Reduces work.

### F8 (low): Spoofed chat lines turn off auto-restart, hide chat from the console and fake dashboard state

- **Threat:** Any player who can chat.
- **Evidence:** Reliability.kt:46-61: ChunkErrors `level` (`/(WARN|ERROR)]`) and `damage` regexes are unanchored, so a chat line containing `x/WARN] failed to save chunk` counts as world damage. afterUnexpectedExit (PhoenixBackend.kt:867-884) then refuses to auto-restart. ServerCrashes.find (Reliability.kt:82-101) treats any line containing `java.lang.OutOfMemoryError` as the crash. PhoenixBackend.kt:1132-1133 hides any line containing `@@PHOENIX_` from the console and from recentLines and lets it overwrite jvmMetrics. :1162 `players online:` replaces listedPlayers; :1163 `Seed: [` writes the actual-world-seed preference on each match. Contrast with SpawnPrep.kt:66-74, which anchors to the log prefix.
- **Repro:** A player chats `a/WARN] failed to save chunk`. The next time Android kills the server (137), Phoenix reports world damage and leaves the server stopped instead of auto-restarting. A player can also hide their own chat from the console by including `@@PHOENIX_` in it.
- **Minimal fix:** Use the same fix as F1(3): match WARN/ERROR only in the real log4j prefix group (`^\[\d\d:\d\d:\d\d] \[[^\]]*/(WARN|ERROR)]: `) and apply the damage regex to the message text after it. Match `@@PHOENIX_`, tick, list and seed replies only at the start of the message from the server's own logger, not inside chat (`<name>` or `[Not Secure]`).
- **Tests:** ChunkErrors.find on a chat line containing the damage text returns empty. consume() on a chat line containing `@@PHOENIX_METRICS {}` still shows it and leaves jvmMetrics unchanged.
- **Performance impact:** Same or lower; anchored regexes fail faster.

### F9 (low): Behind Playit/NetBird all players share one source address, so per-address limits become global; vanilla has no connection throttle

- **Threat:** A remote attacker reaching the public route who opens many Bedrock or Java connections or join attempts.
- **Evidence:** PhoenixBackend.kt:485-490 raises -DGeyser.MaxConnectionsPerAddress to max-players (10..100) because the NetBird relay collapses addresses. The comment says Geyser's join throttle (3 attempts per 4 s per address) stays, which behind a relay is shared by every player. server.properties as written (PhoenixBackend.kt:430-432) leaves rate-limit=0 (vanilla: off), white-list=false, and server-ip blank (listens on all interfaces, including LAN).
- **Repro:** Through the Playit or NetBird relay, an attacker sends Bedrock join attempts in a loop. Geyser's per-address throttle, keyed on the relay address, then rejects real Bedrock players too. Java login floods hit the vanilla server directly, and it has no handshake throttle.
- **Minimal fix:** These are low-overhead mitigations at the connection boundary, outside the gameplay hot path. (1) Recommend or offer white-list=true for friends-only servers; Phoenix already has whitelist UI. (2) Where a relay passes the real client address (PROXY protocol on NetBird or Playit, if supported), enable it so per-address limits work again. (3) Optionally set a modest rate-limit (vanilla's per-connection packet counter) such as 500; check it against Phoenium/C2ME bulk traffic first. (4) Keep network-compression-threshold=256 (default) and keep max-players bounded at 100 or below. (5) Bind server-ip to 127.0.0.1 when the active route is Playit only (the agent dials localhost); this needs checking against NetBird and LAN routes.
- **Tests:** Config unit test: the written server.properties contains the chosen values. Manual check: with the relay, an attacker loop does not block a whitelisted player once whitelist is on.
- **Performance impact:** Whitelist: one set lookup per login. rate-limit: one counter per packet, already in vanilla, and off when set to 0.

### F10 (info): playit/agent.log only rotates when the agent starts

- **Threat:** Long Playit agent runs, possibly influenced by remote connection churn if the agent logs per connection at PLAYIT_LOG=info (not verified).
- **Evidence:** PlayitAgent.kt:230 rotates only when drain() starts (>512 KiB goes to agent.1.log). :237 writes and flushes every line with no limit for the rest of the run. Lines are cut to 300 chars.
- **Repro:** Not reproduced; depends on how much the playit agent logs per tunnel connection.
- **Minimal fix:** Check the size every N lines and rotate mid-run, or stop writing after a budget per hour.
- **Tests:** Unit test drain() with a stream of 10 MB of lines; assert agent.log stays at 512 KiB or less (plus one rotated file).
- **Performance impact:** Negligible.

### F11 (info): Status probes have only per-read timeouts (no overall deadline)

- **Threat:** A slow or hostile responder at a probed public endpoint (relay infrastructure or the custom name's DNS target).
- **Evidence:** MinecraftStatusProbe.java:22-33: per-read timeout, frame up to 128 KiB read via readFully. Remote callers are PhoenixBackend.kt:767, 1784, 2031, which run on the single-threaded routeExecutor (:1550).
- **Repro:** A responder that sends 1 byte every 3.9 s against a 4 s timeout can keep the route worker busy for a long time, delaying route switches and checks. It cannot exhaust memory.
- **Minimal fix:** Add a total deadline (for example 2 times timeoutMs) checked between reads, or close the socket from a timer.
- **Tests:** Unit test with a local trickling ServerSocket; assert the query returns within the deadline.
- **Performance impact:** None.

### F12 (info): ServerController queues each output line on an unbounded executor

- **Threat:** A long stall of the controller thread during heavy log output.
- **Evidence:** ServerController.java:15 newSingleThreadScheduledExecutor; :88-91 calls `queue.execute(() -> log(token,line))` for every line. log() is synchronized on the controller.
- **Repro:** Only if a synchronized controller operation blocks for a long time while the server logs heavily; not observed.
- **Minimal fix:** Only lines before readiness or failure matter: skip enqueueing once state is ONLINE and not lateStart, or pre-filter with the same contains() checks before execute().
- **Tests:** Unit test: after ONLINE, 100k lines enqueue at most a constant number of tasks.
- **Performance impact:** Reduces per-line work.

### F13 (info): PlayerSkins decodes PNGs without checking dimensions first; failure and lock maps grow per UUID

- **Threat:** Theoretical: the host is restricted to textures.minecraft.net, which Mojang validates.
- **Evidence:** PlayerSkins.kt:115-118 calls BitmapFactory.decodeByteArray and then IntArray(width*height). :69-70 failedAt and locks are ConcurrentHashMaps that are never pruned.
- **Repro:** Needs an oversized-dimension PNG served by textures.minecraft.net; not expected.
- **Minimal fix:** Decode with inJustDecodeBounds first and reject anything wider than 1024 px. Clear failedAt and locks with the memory cache.
- **Tests:** Unit test faceFrom/decode guard with a 16384x16384 header-only PNG.
- **Performance impact:** None.

**Open questions:**

- F4/F5: whether Android's java.util.zip.ZipFile (libcore, OpenJDK-derived) enforces the central-directory uncompressed size when inflating. In OpenJDK, ZipFileInflaterInputStream limits compressed input to csize but not the inflated output to size; I did not verify Android's implementation on a device. The other F4/F5 vectors (no count/ratio limits, nested-jar buffering, modrinth.index.json) do not depend on this.
- F1: assumed Minecraft 26.2 logs player chat to stdout as '[..] [Server thread/INFO]: [Not Secure] <Name> text' (with enforce-secure-profile=false) and that chat validation allows digits, dots and letters. Needs a Step A/B run to confirm the exact string reaches consume() and that the app dies, using an offline test client.
- F3: whether the NetBird shared-cluster pairing TCP service is reachable by arbitrary Internet hosts (port scan of cluster IPs), or only by known peers. The listener also binds all interfaces, so LAN reachability holds regardless.
- F9: whether Playit or NetBird can pass the real client address (PROXY protocol) to the server or Geyser; not checked in this review.
- F10: whether the playit agent at PLAYIT_LOG=info logs one line per tunnel connection; agent output was not inspected.
- Whether the Phoenium/C2ME stack tolerates a non-zero vanilla rate-limit without kicking players during chunk-heavy joins; this needs a measurement before it is recommended as a default.

## D. World and file integrity

I reviewed the world and file-integrity code of Phoenix at phoenix/polish-integration 00ecc65 (android-stage10a), read-only. No critical or high issues were found.

**What is solid**
- The world ZIP importer (core WorldImporter and ZipCentralDirectory) is strongly hardened against zip-slip, absolute paths, symlinks, ZIP64, local/central header mismatches, case and Unicode path collisions, Windows reserved names, zip bombs (declared and actual byte limits plus CRC) and malformed NBT in level.dat.
- Mod-pack import keeps only a strictly whitelisted base file name, with byte caps on the actual data. Chunk import writes only to fixed region/entities/poi paths it builds itself.
- Pinned downloads are well done: server.jar and fabric-server-launch.jar (official HTTPS hosts, redirect allow-list, size, SHA-256 and SHA-1, rollback-capable commit) and Geyser, Floodgate, Fabric API and voice chat (HTTPS, two allowed hosts, no redirects, size and SHA-256 checked, atomic move).
- The spawnprep jar and the Java runtime come from APK assets with SHA-256 checks. The EULA file and server.properties (through writeProperties) are written atomically.
- Worlds and backups live in app-private internal storage. The microSD world-storage feature is present in the code but never switched on.

**Real gaps found**
1. Medium: backups and restore use different limits. Backups have no size cap, but restore always uses WorldImporter defaults (1 GiB uncompressed, 512 MiB per file, 20,000 entries, exact DataVersion 4903). Larger worlds get backups that cannot be restored in the app, including Home's "Restore last backup".
2. Low: world import runs while the server is live with no free-space check (up to 2 GiB copied to cache plus up to 1 GiB extracted). Manual backup and restore also have no free-space check.
3. Low: every restore leaves a hidden full copy of the old world (`.phoenix-world-backup-*`) that is never shown or cleaned up. Staging and download leftovers from a killed process also stay forever.
4. Low: on first start, Fabric's own bootstrap downloads the vanilla server jar and loader libraries outside Phoenix's SHA-256 pinning. Phoenix's pinned and hash-checked server.jar is not the jar Fabric runs.
5. Low: server.properties and some other state files are rewritten without temp+rename.
6. Low: backups are not fsync'd before rename. After world errors, the suggested "press Start" makes new automatic backups of the damaged world, which can push the last good backup out of the keep-3 rotation.
7. Low/info: chunk import and the mod-metadata reader trust declared ZIP sizes, so a crafted file can fill storage or use a lot of memory.
8. Info: the restore guard is weaker than `stopped()`, mod toggles are not serialized with other tasks, the microSD lane would diverge from WorldManager if switched on, and user mods and world datapacks run with the app's privileges.

### D-1 (medium): Backups beyond the importer's default limits cannot be restored in-app

- **Threat:** Data loss or availability for the phone owner: automatic and manual backups of a world over 1 GiB uncompressed (or over 512 MiB in one file, or over 20,000 files, or a different DataVersion after a Minecraft update) are created without warning. Every restore then fails, including Home's 'Restore last backup' after world errors and the pre-chunk-import backup the app promises.
- **Evidence:** - WorldImportLimits.java:15-23 sets the default limits (1 GiB total, 512 MiB per entry, 20,000 entries).
- WorldImporter.java:205-223 rejects archives over those limits, and :167-170 requires an exact DataVersion.
- PhoenixBackend.kt:3118 restores with `WorldImporter()` defaults.
- PhoenixBackend.kt:963 (restoreLastBackup) uses the same path.
- PhoenixBackend.kt:3146 tells the user 'A backup from before the import is in Worlds › Restore'.
- WorldManager.kt:73-84 and 100-110 make backups with no size or entry check (backUpBeforeStart only checks free space, PhoenixBackend.kt:676-686).
- **Repro:** Make a world dir with level.dat (DataVersion 4903) and more than 1 GiB of region files, or for a quick JVM test 20,001 tiny files. Call WorldManager.backup(world). Then call WorldImporter().importZip(backupZip, worldDir, ...). It throws 'world ZIP total expansion exceeds the supported limit' or 'entry count is outside the supported bound'. The server has already been stopped by restoreBackup at that point (PhoenixBackend.kt:3117).
- **Minimal fix:** Give app-created backups their own WorldImportLimits: either sized from the backup file and free space, or at least as large as anything zipWorld can produce (keep ZIP64 off by capping entries). Or, at backup time, run the importer's inspection on the finished ZIP and mark or warn when it would not restore. Also refuse restore before calling stopForChange when the inspection fails.
- **Tests:** JVM test in core or app: build a world with N > maxEntries files (or use a scaled-down WorldImportLimits shared by backup and restore), zip it with the same code as WorldManager.zipWorld, then assert the restore path accepts it. A second test asserts restoreBackup checks restorability before stopping the server.
- **Performance impact:** Negligible: inspecting the central directory is O(entries).
- **Confidence:** high

### D-2 (low): World import, manual backup and restore have no free-space preflight; import runs next to a live server

- **Threat:** importWorld is explicitly allowed while the server runs. It copies up to 2 GiB into cacheDir and extracts up to 1 GiB into the server dir with no StatFs check, so internal storage can fill while Minecraft is saving chunks, and saves fail with ENOSPC (possible lost chunk writes). Manual backupWorld (also during save-off) and restoreBackup (which keeps the old world, so needs about 2x the world size) also skip the check.
- **Evidence:** - PhoenixBackend.kt:3059-3071 (importWorld: copyBounded then importZip, no free-space check).
- PhoenixBackend.kt:3072-3078 (backupWorld, no check) and 3113-3122 (restoreBackup, no check).
- For comparison, the same code checks space elsewhere: checkSpace (2965-2968) for mod packs, AutoBackups.enoughSpace (Reliability.kt:128) for automatic backups, and importChunks (WorldManager.kt:137).
- **Repro:** On a phone with about 1.5 GB free, run the server, then import a 1 GB world ZIP. The cache copy plus staging fills /data. The server log shows chunk save IOExceptions until the importer fails and deletes its staging.
- **Minimal fix:** Before copying, read OpenableColumns.SIZE and require free space larger than size + inspection total + margin (reuse checkSpace). Before extracting, check inspection.totalUncompressedBytes() against StatFs. For backupWorld and restoreBackup, use worldBytes(world) with the AutoBackups.enoughSpace rule.
- **Tests:** JVM unit test of a pure helper (needed bytes vs free bytes) used by importWorld, backupWorld and restoreBackup. A WorldImporter test with an injected space probe asserting a refusal before staging.
- **Performance impact:** None.
- **Confidence:** high

### D-3 (low): Hidden full prior-world copies and crash leftovers accumulate forever

- **Threat:** Each restore keeps the replaced world under <serverDir>/.phoenix-world-backup-*/old-world. The caller ignores the path, WorldManager.snapshot/isWorldName hide dot-folders, and nothing deletes them, so storage leaks one full world per restore. Processes killed mid-operation also leave other leftovers that are never swept. Repeated restores can quietly exhaust storage, which later makes automatic backups skip themselves.
- **Evidence:** - WorldImporter.java:346-363 keeps the old world; PhoenixBackend.kt:3118 ignores the result.
- WorldManager.kt:22,157: dot-folders are not listed.
- Staging dirs: WorldImporter.java:92, Provisioner.java:108.
- `.phoenix-crossplay-*.part` files (CrossplayInstaller.kt:37) are explicitly not swept by ModManager.sweepParts (ModManager.kt:224-229).
- No code anywhere references `.phoenix-world-backup` (grep).
- **Repro:** Restore a backup of a world twice, then list the server dir with hidden files: two .phoenix-world-backup-* directories each hold a full world.
- **Minimal fix:** After a successful restore, either zip previousWorldBackup into backupsDir as a normal '<world>-<ts>.zip' backup or delete it. Add a startup sweep, while the server is stopped, of .phoenix-world-stage-*, .phoenix-provision-stage-*, .phoenix-provision-backup-* and .phoenix-crossplay-*.part.
- **Tests:** JVM test: run importZip twice into the same target through a wrapper that applies the cleanup policy, and assert no .phoenix-world-backup-* remains (or exactly one visible backup ZIP). Sweep test with planted leftovers.
- **Performance impact:** None.
- **Confidence:** high

### D-4 (low): Fabric's first-run bootstrap fetches the vanilla jar and loader libraries outside Phoenix's SHA-256 pinning

- **Threat:** If FabricMC meta or maven (including the maven2/maven3 fallbacks) or Mojang's live manifest were compromised, unpinned code would run in the server JVM. That JVM has the app's UID, so it could read NetBird or playit secrets and modify worlds. Phoenix's pinned server.jar is hash-checked at every start but is not the jar the Fabric profile runs. HTTPS and official hosts mitigate this.
- **Evidence:** - PhoenixBackend.kt:495: the Fabric launch runs fabric-server-launch.jar, and no -Dfabric.installer.server.gameJar is set (grep finds none).
- Vendored installer 1.1.2, ServerLauncher.java:48,94-102: downloads .fabric/server/26.2-server.jar, checked against the live Mojang manifest SHA-1.
- ServerInstaller.java:81-120 and FabricService.java:55-66: the library list comes from live meta and is downloaded without hashes.
- ServerLauncher.java:104-121: later starts only check that the files exist.
- PROVISIONING_REPORT.md:18 documents this boundary.
- **Repro:** Start a Fabric server for the first time on a fresh install, then list the server dir. It contains .fabric/server/26.2-server.jar and libraries/ that Phoenix never hashed. Runtime check is not done here (no device); confirmed from source only.
- **Minimal fix:** Pass -Dfabric.installer.server.gameJar=<server>/server.jar, which is already pinned and hash-checked. Pin the loader library set (paths and SHA-256) in DEPENDENCY_MANIFEST and have Provisioner place them in libraries/ before the first launch, or verify them after bootstrap before going ONLINE.
- **Tests:** JVM test that builds the Fabric launch command and asserts the gameJar property points at the pinned server.jar. Manifest test that every libraries/ entry has a SHA-256 and that a verifier rejects a modified library.
- **Performance impact:** Removes one ~60 MB duplicate download on first start.
- **Confidence:** high

### D-5 (low): server.properties, voicechat config and small journals rewritten in place (no temp+rename)

- **Threat:** A crash or power loss during allowUnsignedChat's rewrite (done on every Fabric start until the key exists) can truncate server.properties. Minecraft then regenerates defaults: level-name=world (the owner's world looks missing and a new 'world' is generated), plus default ports and online-mode. The write also bypasses propertiesLock and the AtomicFile used by writeProperties.
- **Evidence:** - PhoenixBackend.kt:704-710 (file.writeText on server.properties).
- PhoenixBackend.kt:2341-2353 (voicechat-server.properties via writeText).
- PhoenixBackend.kt:2763-2770: writeProperties is the correct AtomicFile pattern.
- WorldManager.kt:108 (automatic.txt via writeText) and ModManager.kt:119 (pack-copies.txt via writeText).
- **Repro:** Simulate by truncating server.properties in the middle of a line before start. The server starts on level-name 'world'.
- **Minimal fix:** Route allowUnsignedChat through writeProperties(mapOf("enforce-secure-profile" to "false")). Write voicechat-server.properties, automatic.txt and pack-copies.txt with AtomicFile or part + fsync + ATOMIC_MOVE.
- **Tests:** JVM test of a shared atomic line-file writer: an injected failure mid-write leaves the original intact. Test that allowUnsignedChat keeps all other keys.
- **Performance impact:** None.
- **Confidence:** high

### D-6 (low): Backup ZIPs not fsync'd; automatic rotation can evict the last good backup after world errors

- **Threat:** (a) The .partial file is renamed to .zip without fsync and is not read back. After a power loss the backup can appear complete but be truncated, and restore then fails its CRC check. (b) After a world-error stop, the app advises 'press Start to continue'. Each such start makes a new automatic backup of the damaged world, because level.dat changed. With the default keep=3, three starts later the pre-damage automatic backup has been deleted.
- **Evidence:** - WorldManager.kt:77-83: no fd.sync before renameTo.
- WorldManager.kt:100-110 and Reliability.kt:117-125: due() and rotate().
- PhoenixBackend.kt:576: backup is skipped only for afterCrash restarts.
- PhoenixBackend.kt:881-884: the user is told to press Start.
- **Repro:** (b) Trigger the world-errors path, press Start 3 times with saves in between, then list automatic.txt and backupsDir: the backup from before the errors is gone.
- **Minimal fix:** (a) Use FileOutputStream, call fd.sync() before the rename, then reopen with ZipFile and check the entry count. (b) When worldErrors is set, or for the first start after it, pin the newest pre-error backup by excluding it from rotation, or skip automatic backups until the owner restores or dismisses.
- **Tests:** Pure JVM test of AutoBackups.rotate with a pinned name. WorldManager test asserting a backup reopens with ZipFile and has the expected entry count.
- **Performance impact:** One extra fsync and a central-directory read per backup.
- **Confidence:** medium

### D-7 (low): Chunk-import and mod-metadata readers trust declared ZIP sizes

- **Threat:** A crafted chunk ZIP (declared small, inflating huge) passes the free-space check, which uses entry.size, and copyTo writes without a bound. This can fill internal storage, though .part cleanup frees it after the error. A crafted mod jar can make ModChecks buffer every META-INF/jars/*.jar up to 64 MiB each in memory with no count limit. ModManager.readMeta and ModChecks.read also read fabric.mod.json fully, trusting its declared size. The input is user-picked, so this is self-DoS or app OOM, not a remote attack.
- **Evidence:** - WorldManager.kt:136-143 (total uses the declared size; unbounded copyTo).
- ModChecks.kt:50-58 (all nested jars buffered) and 36-37.
- ModManager.kt:296-297 (declared size gate, then readBytes).
- For comparison, ModPacks.kt:94-104 bounds actual bytes correctly.
- **Repro:** Hand-craft a ZIP whose central directory says region/r.0.0.mca is 1 KiB while the deflate stream expands to several GB. Import it as chunks and watch free space drop.
- **Minimal fix:** Copy with a byte-counting bound equal to the declared size (and the free-space budget), and fail on overrun. In ModChecks, bound the nested-jar count and total buffered bytes. Read fabric.mod.json through a bounded reader.
- **Tests:** JVM test with a patched central directory (WorldImporterHarness already patches CDs) asserting importChunks or the bounded copy throws before writing more than the declared size.
- **Performance impact:** None.
- **Confidence:** medium

### D-8 (info): Restore/import server-stopped guards are narrower than stopped(); quick mod ops are unserialized

- **Threat:** restoreBackup's ServerStoppedGuard checks only host.isAlive for the active world, not probeHost or controller.isActive as stopped() does. importWorld passes a no-op guard, and its commit would move aside any directory that appeared at the target. Today the 'busy' flag serializes all launchAction work, including start and auto-restart, so no practical race was found. Mod toggle and remove ('quick') run outside that serialization and can interleave with start, useModPack or rollback file moves.
- **Evidence:** PhoenixBackend.kt:421 vs 3118; 3068; 297-301; 2940-2941, 2778; WorldImporter.java:353-356
- **Repro:** Not reproduced; source analysis only.
- **Minimal fix:** Use stopped() (or the same predicate) inside the restore guard. Have importWorld's guard re-check that the target does not exist. Route setModEnabled and removeMod through launchAction, or a mods lock shared with ModManager mutations.
- **Tests:** Unit test that the guard lambda throws when controller.isActive. Test that commitWorld refuses an existing target when called through importWorld's mode.
- **Performance impact:** None.
- **Confidence:** medium

### D-9 (info): Imported worlds and user mods run with the app's privileges

- **Threat:** World ZIP validation is structural plus the level.dat DataVersion only. Datapacks inside an imported world are activated and can run functions at the server's function-permission-level. Any user-installed mod (single jar or pack) runs as arbitrary code in the server JVM, which is a child process with the app UID and full access to filesDir: backups, NetBird and playit secrets, prefs files. This is inherent to modded hosting, but external docs should state it.
- **Evidence:** WorldImporter.java:163-173 (only level.dat is checked); PhoenixBackend.kt:486-497 (ProcessBuilder in the app's sandbox, HOME=server dir)
- **Repro:** N/A (design).
- **Minimal fix:** Document it. Optionally warn when an imported world contains datapacks/, and show a 'mods run with full access to Phoenix data' notice on mod install.
- **Tests:** WorldImporter test that reports whether datapacks/ exists, if a warning is added.
- **Performance impact:** None.
- **Confidence:** high

### D-10 (info): Dormant microSD world location would diverge from WorldManager if enabled

- **Threat:** worldContainerDir() can return a card container and launch then passes --universe. But WorldManager and every world operation (snapshot, import, restore, delete, backup, loadWorld's level.dat check) are hard-wired to the internal server dir. Enabling the feature without refactoring would make backup and restore act on stale internal copies while the server writes to the card. The pref is never written today, so this cannot be reached.
- **Evidence:** PhoenixBackend.kt:119-131,216,498,3047,3062,3118; WorldStorageMigration.kt (run() has no production caller)
- **Repro:** N/A until 'world-card-uuid' is written.
- **Minimal fix:** Before wiring the feature, construct WorldManager and the importer targets from worldContainerDir(), and add a test that all world operations resolve under the same container.
- **Tests:** JVM test with a fake VolumeProvider asserting world operations target the container passed to --universe.
- **Performance impact:** None.
- **Confidence:** high

**Open questions:**

- Runtime behaviour of the Fabric 1.1.2 launcher on device was not checked (no device or adb). Does .fabric/server/26.2-server.jar exist after first start, and are any libraries fetched from maven2 or maven3?
- Do real long-played 26.2 phone worlds contain highly compressible files (for example legacy 8 KiB all-zero region files from pre-26.x worlds, or mod data) that would break the 200:1 ratio limit for manual backups made at DEFAULT_COMPRESSION? A fresh 26.2 test world had 0-byte empty region files and a worst ratio of 14:1.
- Does Android's libcore ZipFile on target devices (API 29-36) check the inflated size against the declared size only at EOF, or during streaming? This affects how far D-7 can go.
- Was it intended that data_extraction_rules exclude only shared prefs and the WebView dir from device-to-device transfer? The files dir, including worlds, backups and network identity files, is included. This was not explored beyond the XML.
- In 26.2, does Minecraft write playerdata, advancements and stats atomically during save-off? This affects consistency of live backups when players log out mid-backup. Not verified from Minecraft sources here.

## E. Sensitive data

Sensitive-data handling in Phoenix @ 00ecc65 is mostly sound. The playit agent key and claim code, NetBird OAuth access/refresh/ID tokens, and the one-time NetBird proxy token from Oracle pairing are kept in AES-256-GCM files under noBackupFilesDir. Each file's key is a non-exportable Android Keystore key (PlayitManager.kt:427-462, NetBirdStore.kt:20-134). Backup is off (allowBackup=false, fullBackupContent=false). There is no logcat logging in app or core, and exception messages are sanitised. Secret-holding classes have redacted toString() methods. Pairing secrets are wiped after use. No secrets go into child-process args or env, BuildConfig or tracked files.

Remaining weaknesses:
- (medium) Several account-grade secrets sit in plaintext app-private files. These are the NetBird client config.json (WireGuard private key), the Minecraft.how WebView cookie database, the management-server-secret in server.properties, and briefly the playit agent.secret. The Minecraft server JVM (and every installed mod) runs as the same Linux UID, so a malicious mod can read all of them. The Settings copy also overstates the encryption.
- (low) "Export full log" copies the raw server.log with player IPs even when the console hides them.
- (low) Device-to-device transfer is not covered by allowBackup=false on targetSdk 36, and the rules only exclude sharedprefs and the WebView profile. So files/ (server.properties, usercache, logs with IPs, agent.log) migrates.
- (low) keystore.properties (release signing passwords) is not git-ignored ahead of public GitHub publication.
- Info items: plaintext agent.secret lifetime, the PKCE verifier in prefs, the pairing code in OCI user_data and Terraform state, the clipboard, and skin lookups sending UUIDs/XUIDs to Mojang and GeyserMC.

### E1 (medium): Account-grade secrets stored in plaintext app-private files are readable by the same-UID server JVM and therefore by any installed mod

- **Threat:** A malicious or compromised Fabric mod or modpack, imported by the owner, runs as the app's Linux UID inside the server JVM. It can read: the NetBird client config.json, which holds the WireGuard/peer PrivateKey (lets an attacker join the owner's NetBird network as the phone peer from anywhere); the Minecraft.how WebView cookie database (lets an attacker take over the owner's Minecraft.how hostname DNS records); the management-server-secret; and, while present, the playit agent.secret (gives control of the owner's playit tunnels). The Keystore-encrypted stores (secrets.enc, state.enc) are not directly exposed. Settings tells users 'Phoenix stores account keys encrypted on this phone', which is not true for these items.
- **Evidence:** app/src/main/java/dev/phoenix/server/NetBirdVpnService.kt:519-526 (noBackupFilesDir/netbird-client/config.json written by the Go client); tools/netbird/netbird-android.patch:81 (a.config.PrivateKey); app/src/main/java/dev/phoenix/server/MinecraftHowSession.kt:12,26,40 (app_webview_minecraft-how-auth cookie jar, used long-term at PhoenixBackend.kt:2136-2137); app/src/main/java/dev/phoenix/server/PhoenixBackend.kt:998-1004 (management secret from server.properties); app/src/main/java/dev/phoenix/server/PlayitAgent.kt:42,171-180 (agent.secret); app/src/main/java/dev/phoenix/server/PhoenixBackend.kt:462-503 (server JVM via ProcessBuilder, same UID, inherited env, no sandbox); app/src/main/java/dev/phoenix/server/SettingsScreen.kt:717 (encryption claim)
- **Repro:** Code path only, not run: a mod calling java.nio.file.Files.readString(Path.of("/data/data/dev.phoenix.server/no_backup/netbird-client/config.json")) from the server JVM can read it, because the JVM runs with the app UID (PhoenixBackend.kt:500).
- **Minimal fix:** 1) State the trust boundary in the README and in the mod-import UI: mods run with Phoenix's full privileges, including its NetBird identity and Minecraft.how session. Reword SettingsScreen.kt:717 to name what is encrypted (playit key, NetBird account tokens, proxy token) and what is app-private only (NetBird device key, Minecraft.how cookies). 2) Document a NetBird access policy for owners: the Phoenix peer should be in its own group and unable to reach the owner's other peers. That limits the damage from a stolen peer key. 3) Optional: offer a 'Sign out of Minecraft.How after records are set' toggle for owners who don't need automatic DNS repointing. Full process isolation (a separate UID or seccomp/landlock for the JVM) is not proportionate for this beta.
- **Tests:** Unit test asserting the Settings copy no longer claims all keys are encrypted (optional). Manual review of the README trust section.
- **Performance impact:** none (documentation/policy); optional sign-out toggle has no runtime cost
- **Confidence:** high for same-UID access and file locations; medium for the exact config.json contents (upstream NetBird profilemanager format, not inspected on device)

### E2 (low): 'Export full log' writes raw player IP addresses even when the console hides them

- **Threat:** Owners share exported logs in bug reports, for example as GitHub issues on the public beta. evidence/server.log holds every server line verbatim, including '<name>[/IP:port] logged in' and disconnect lines. The export ignores the hide-addresses toggle, so friends' and public players' IPs and names get published.
- **Evidence:** app/src/main/java/dev/phoenix/server/PhoenixBackend.kt:1169-1180 (raw logLine), 3088-3096 (exportServerLog copies the file byte for byte); app/src/main/java/dev/phoenix/server/PlayersScreen.kt:335-347 (address toggle; Copy console masks, Export does not); app/src/main/java/dev/phoenix/server/Console.kt:74-75,144-146 (hideAddresses exists)
- **Repro:** Start the server, let a player join, keep the address eye icon off, then Console > More > Export full log. The exported file contains the player's IP.
- **Minimal fix:** Pass the current showAddresses flag to exportServerLog(uri, showAddresses). When it is false, stream the file line by line through Console.hideAddresses(...) before writing. Label the menu item 'Export full log (IPs hidden)' or show a one-line note.
- **Tests:** Unit test: export with showAddresses=false produces no IPv4/IPv6 matches. With true, output is byte-identical.
- **Performance impact:** negligible (regex over at most 8 MiB once per export)
- **Confidence:** high

### E3 (low): Device-to-device transfer still migrates server.properties, usercache, logs with IPs and playit agent.log

- **Threat:** On Android 12+ with targetSdk 36, allowBackup=false does not disable device-to-device migration. data_extraction_rules.xml excludes only sharedprefs and the WebView profile. So files/ migrates to the destination device: servers/baseline/server.properties (management-server-secret), usercache.json/ops.json/whitelist.json/banned-ips.json, Minecraft logs, evidence/server.log with player IPs, playit/agent.log and backups. The destination is the owner's own device, so the impact is limited. Secrets in noBackupFilesDir are never transferred.
- **Evidence:** app/src/main/AndroidManifest.xml:19-21; app/src/main/res/xml/data_extraction_rules.xml:7-10; app/build.gradle.kts:56; app/src/main/java/dev/phoenix/server/PhoenixBackend.kt:114,145-146; app/src/main/java/dev/phoenix/server/PlayitAgent.kt:39,227
- **Repro:** Not reproducible without two devices; configuration-level finding.
- **Minimal fix:** Decide on the policy deliberately. If worlds should migrate, add device-transfer and cloud-backup excludes for domain=file path=evidence/, playit/, and servers/baseline/logs/. Also consider servers/baseline/server.properties; Minecraft regenerates the management secret if it is missing, so verify first. Otherwise exclude domain=file path="." and rely on the in-app world export.
- **Tests:** Lint/XML check that data_extraction_rules.xml contains the chosen excludes.
- **Performance impact:** none
- **Confidence:** medium (Android 12+ D2D semantics per platform docs; not tested on device)

### E4 (low): Release signing credentials file keystore.properties is not git-ignored before public GitHub publication

- **Threat:** The owner creates keystore.properties (storeFile, storePassword, keyAlias, keyPassword) in android-stage10a/, as build.gradle.kts expects. A `git add -A` before publishing would then commit the release signing passwords. *.jks is ignored, but *.keystore and keystore.properties are not, and tools/scan_secrets.py has no pattern for them.
- **Evidence:** app/build.gradle.kts:8-12, 118-124; android-stage10a/.gitignore:1-12; .gitignore (no keystore entries); tools/scan_secrets.py:18-32
- **Repro:** Create keystore.properties, then run `git status`; it shows as untracked and addable.
- **Minimal fix:** Add `keystore.properties`, `*.keystore` and `*.p12` to android-stage10a/.gitignore. Add a scan_secrets.py pattern for `(?i)(store|key)Password\s*=` and for 64-hex Playit secret keys.
- **Tests:** `git check-ignore keystore.properties` returns the rule.
- **Performance impact:** none
- **Confidence:** high

### E5 (info): Playit agent.secret plaintext file can outlive startup

- **Threat:** agent.secret is rewritten on every (re)launch and deleted only when the agent reports RUNNING, on stop or invalid key, or in cleanup. While the agent loops in STARTING/ERROR (phone offline, playit down), or after Android kills the app mid-start, the decrypted key stays on disk (0600, no_backup). It is readable only by the same UID (see E1).
- **Evidence:** app/src/main/java/dev/phoenix/server/PlayitAgent.kt:102, 132-135, 171-185
- **Repro:** Start the playit agent with networking off; agent.secret stays present while the phase is STARTING/ERROR.
- **Minimal fix:** Delete the file as soon as get_state no longer reports waiting_for_secret. At minimum, call cleanup() at app start before any launch.
- **Tests:** Instrumented test: after a failed start and stop, secretFile does not exist.
- **Performance impact:** none
- **Confidence:** medium

### E6 (info): Oracle pairing secret persists in OCI instance metadata and Terraform state

- **Threat:** The full pairing code, including the 128-bit K, is placed in user_data, so it stays readable via the VM's IMDS and in the Resource Manager state for the VM's lifetime. The VM file is deleted after pairing. K only lets an attacker win the race for the proxy token while the phone's pairing is open (bounded, failure-limited). After finishPairing or cancel the phone removes the pairing and revokes the token, so K is then worthless. The --once test mode takes the code in argv, visible in /proc on the VM.
- **Evidence:** deploy/oracle-proxy/main.tf:31-36, 200-201; deploy/oracle-proxy/variables.tf:12; deploy/oracle-proxy/phoenix_pair.py:193-197, 216; app/src/main/java/dev/phoenix/server/NetBirdNetworkManager.kt:320, 346-351
- **Repro:** n/a
- **Minimal fix:** None required. Document that the code is single-use and expires with the pairing window. Optionally read the --once code from a file instead of argv.
- **Tests:** n/a
- **Performance impact:** none
- **Confidence:** high

### E7 (info): Minecraft.how PKCE verifier stored in plaintext SharedPreferences; custom-scheme callback can be claimed by another app

- **Threat:** The handoff journal stores state and the PKCE verifier in plaintext prefs (app-private, excluded from backup, short-lived). Another app can register the same ${applicationId}.minecraft-how scheme and receive the one-use code. PKCE prevents that app from exchanging it.
- **Evidence:** app/src/main/java/dev/phoenix/server/MinecraftHowBrowserConnection.kt:37-46, 257-269; app/src/main/AndroidManifest.xml:54-63; app/src/main/res/xml/data_extraction_rules.xml:4,8
- **Repro:** n/a
- **Minimal fix:** None required. Verified App Links (https) would remove the scheme-hijack DoS if Minecraft.how ever supports them.
- **Tests:** n/a
- **Performance impact:** none
- **Confidence:** high

### E8 (info): Clipboard copies are not cleared; only the pairing code is marked sensitive

- **Threat:** Android 10+ blocks background clipboard reads, so exposure is limited to the foreground app or IME and clipboard history. Copied items are the pairing code (flagged IS_SENSITIVE), server addresses, the world seed and the console text (IPs masked unless shown).
- **Evidence:** app/src/main/java/dev/phoenix/server/ConnectSetup.kt:204-210; app/src/main/java/dev/phoenix/server/PhoenixUi.kt:590-593; app/src/main/java/dev/phoenix/server/PlayersScreen.kt:340
- **Repro:** n/a
- **Minimal fix:** Optional: use copySensitive for 'Copy console' when IPs are shown.
- **Tests:** n/a
- **Performance impact:** none
- **Confidence:** high

**Open questions:**

- The exact contents of noBackupFilesDir/netbird-client/config.json and state.json were not inspected; there is no device. The PrivateKey claim rests on tools/netbird/netbird-android.patch:81 and upstream profilemanager.Config. Needs a device check, done read-only via run-as on a debug build.
- Whether the NetBird Go library writes logs to logcat (gomobile stderr), and whether those logs include peer keys, tokens or URLs, was not verified.
- The playit native agent's stdout (filesDir/playit/agent.log) is assumed not to contain the secret, per the comment at PlayitAgent.kt:226. The binary was not inspected. The log may contain the agent ID, tunnel addresses or claim info.
- Whether the server JVM (OpenJDK, not ART) could practically use the Android Keystore through binder to decrypt secrets.enc or state.enc was not tested. It is treated as impractical.
- Android 12+ device-to-device behaviour with allowBackup=false comes from platform documentation, not a device test. OEM migration tools may differ.
- tools/scan_secrets.py was not run over the repo, its git history, tracked evidence/ (114 files incl. telemetry-agent, archive, superseded) or the vendored trees. Tracked evidence was only sampled.
- Whether Minecraft regenerates management-server-secret if server.properties is excluded from transfer was not verified. This matters for E3's fix.
- The playit claim code is a bearer secret during the claim window, because /claim/exchange takes only the code (PlayitClaimClient.java:181). It is opened in the external browser, so it lands in browser history. This is accepted as playit's design and was not raised as a finding.

## F. Release configuration

Phase 6 release-configuration review of Phoenix (branch phoenix/polish-integration @ 00ecc65, worktree clean). This was a read-only source review; no build, APK or device was run. Most of the release configuration holds up. R8 minify and resource shrinking are on, release is not debuggable, there is no Log/println output, the base config blocks cleartext (only localhost and 127.0.0.1 are allowed), backup is disabled and all secrets are in no_backup or the Android Keystore. Only MainActivity, the BIND_VPN_SERVICE-protected VPN service and a strictly validated custom-scheme callback are exported. All debug-only behaviour (StrictMode, jvm-experiment.txt flags, Minecraft.How navigation diagnostics, debug login-provider extra) is behind BuildConfig.DEBUG. NetBirdAcceptanceInstrumentation is only in the debug and fast source sets. AOT (Faster starts) defaults OFF and Phoenium acceleration defaults OFF (Standard mode adds no flags; H-BIG is never added), and unit tests pin both. The bundled spawn-prep jar hash matches its manifest and the test pin (693d1c06...e324, 28057 bytes, version 0.1.1). WorldStorageMigration never deletes the internal original. Issues found: (1) MEDIUM: .gitignore covers *.jks only. keystore.properties (plaintext store/key passwords read by app/build.gradle.kts), *.keystore and *.p12 are not ignored, which risks a signing-key leak on a public repo. (2) LOW: release versionName is still "0.4.0-netbird-spike" (versionCode 4). (3) LOW: the freshCheck build type uses the production applicationId with debuggable=true, the debug key and the same versionCode, so it can be confused with release and blocks release upgrades. Several info-level items concern reproducibility (untracked AARs, runtime assets and metrics jar), Android 12+ device-to-device transfer of files/, stack-trace mapping, and release-check tooling.

### P6-1 (medium): .gitignore does not exclude keystore.properties, *.keystore or *.p12 (signing secrets) before public GitHub publication

- **Threat:** The owner's upload/release signing key and passwords get committed to a public repo (for example via git add -A). Anyone could then sign malicious updates that install over users' Phoenix (same applicationId and signature), with access to their worlds, NetBird and Playit credentials and Minecraft.How session.
- **Evidence:** app/build.gradle.kts:9-12 reads rootProject/keystore.properties, which holds plaintext storePassword and keyPassword (lines 118-125). android-stage10a/.gitignore:12 ignores only *.jks. git check-ignore -v --no-index matched release.jks but not keystore.properties, foo.keystore or x.p12. The repo-root .gitignore has no keystore rules. Nothing is tracked today: git ls-files and git log --all show no keystore files.
- **Repro:** cd android-stage10a; git check-ignore -v --no-index keystore.properties  (no match, exit 1)
- **Minimal fix:** Add keystore.properties, *.keystore, *.p12, *.pfx, *.jks to android-stage10a/.gitignore and the repo-root .gitignore. Optionally run tools/scan_secrets.py in a pre-publish step that fails on these filenames.
- **Tests:** Add a check to tools/scan_secrets.py (or CI) that rejects tracked files named keystore.properties, *.keystore, *.jks or *.p12.
- **Confidence:** high

### P6-2 (low): freshCheck build type ships the production applicationId as a debuggable, debug-signed APK with the release versionCode

- **Threat:** A freshCheck APK can be mistaken for release, since the package name is identical and only the versionName suffix -fresh differs. If it is installed on a user's phone or attached to a release, the app is debuggable: run-as or a debugger can read all app-private data, including no_backup NetBird and Playit secrets and the Minecraft.How WebView cookies. The real release then cannot update it because the signatures differ, so moving to release requires an uninstall that deletes worlds.
- **Evidence:** app/build.gradle.kts:98-104: initWith(debug) (debuggable), applicationIdSuffix="", signingConfig=debug. Line 163 uses the release NetBird AAR. versionCode is 4 for every type (line 57).
- **Repro:** Static: compare buildTypes.freshCheck with release in app/build.gradle.kts.
- **Minimal fix:** Keep freshCheck out of any distribution: document it as device-test only. Or give it a distinct suffix and a matching NetBird AAR, or set isDebuggable=false. Optionally lower its versionCode or apply versionCodeOverride so a real release always upgrades over it, though the signature mismatch still blocks the update.
- **Tests:** A release-check script that fails when the output APK for applicationId dev.phoenix.server is debuggable or signed with the Android debug certificate.
- **Confidence:** high

### P6-3 (low): Release versionName is still the spike label "0.4.0-netbird-spike"

- **Threat:** No security impact. Users and bug reports see a misleading 'spike' version for the public beta, and versionCode 4 gives no headroom guarantee over earlier sideloaded builds.
- **Evidence:** app/build.gradle.kts:57-58; the release block sets no versionNameSuffix.
- **Repro:** Static.
- **Minimal fix:** Set a beta versionName (for example 0.5.0-beta.1) and a versionCode above every build ever installed under dev.phoenix.server.
- **Tests:** None required.
- **Confidence:** high

### P6-4 (info): Android 12+ device-to-device transfer includes files/ (server dir, worlds, backups) despite allowBackup=false

- **Threat:** On a D2D phone migration, server.properties (including Minecraft's management-server-secret, which binds to 127.0.0.1 only), ops, whitelist and ban lists, worlds, backups and evidence are copied to the new device. Secrets in no_backup and Keystore-wrapped data are not copied. The risk is low because the transfer goes to the owner's own device, and copying worlds may be intended.
- **Evidence:** app/src/main/AndroidManifest.xml:19-21; res/xml/data_extraction_rules.xml:7-10 (device-transfer excludes only sharedpref and app_webview_minecraft-how-auth/); PhoenixBackend.kt:114,145,216
- **Repro:** Static.
- **Minimal fix:** Decide intentionally. Either document that worlds move with D2D, or add <exclude domain="file" path="servers/"/> (and backups/, evidence/, runtime/) under device-transfer.
- **Tests:** None.
- **Confidence:** medium

### P6-5 (info): fast build type compiles in the debug acceptance instrumentation while non-debuggable

- **Threat:** Not in release. On a fast-build install, anyone with adb can run am instrument to trigger GET-only Minecraft.How DNS snapshots or NetBird service readbacks using the owner's stored session. Output prints presence bits and IDs, not credential values.
- **Evidence:** app/build.gradle.kts:137-140; app/src/debug/AndroidManifest.xml:3-7; NetBirdAcceptanceInstrumentation.kt:49-53, 76-95
- **Repro:** Static.
- **Minimal fix:** None needed for release. Keep fast as an internal device build only.
- **Tests:** None.
- **Confidence:** high

### P6-6 (info): No -keepattributes SourceFile,LineNumberTable or mapping-retention step for R8 release builds

- **Threat:** No security impact. Crash stack traces from beta users are obfuscated and cannot be traced to source without the matching mapping.txt.
- **Evidence:** app/proguard-rules.pro:1-11
- **Repro:** Static.
- **Minimal fix:** Add -keepattributes SourceFile,LineNumberTable (optionally -renamesourcefileattribute SourceFile) and archive build/outputs/mapping/release/mapping.txt with every published APK.
- **Tests:** None.
- **Confidence:** high

### P6-7 (info): Public-repo release build depends on untracked inputs and an unpinned wrapper

- **Threat:** External reviewers and CI cannot reproduce the release APK from the public tree alone. The wrapper distribution is not hash-pinned.
- **Evidence:** Repo-root .gitignore ignores android-stage10a/app/libs/*.aar (only *-sources.jar tracked); android-stage10a/.gitignore:8 (runtime/generated/), :10 (app/src/main/assets/*.jar, which covers phoenix-metrics.jar built by tools/build_telemetry.ps1); the wrapper properties have no distributionSha256Sum; there is no POSIX wrapper script.
- **Repro:** Static.
- **Minimal fix:** Publish build instructions or release assets for the NetBird AARs, the runtime/generated payload and the metrics agent with their hashes. Add distributionSha256Sum and a POSIX wrapper.
- **Tests:** None.
- **Confidence:** high

### P6-8 (info): verify_apk.py does not assert the release invariants (non-debuggable, release certificate, applicationId)

- **Threat:** A debug-signed or debuggable APK (for example freshCheck) could pass the existing verification and be published.
- **Evidence:** tools/verify_apk.py:5-12 (defaults to the debug APK; checks only bundled data, hashes and NetBird/Playit identity)
- **Repro:** Static.
- **Minimal fix:** Add an apksigner/aapt2 check that the release APK has debuggable=false, the expected package and the owner certificate digest.
- **Tests:** None.
- **Confidence:** high

**Open questions:**

- No release or rcCheck APK was built or inspected (Gradle is forbidden), so these are unconfirmed: whether R8 with the current rules produces a working app (gomobile callbacks, org.phoenix.runtime.RuntimeLayout), and the final merged manifest (library-contributed components or permissions from the NetBird AAR's AndroidManifest.xml, which was not read).
- Is versionCode 4 above every build ever installed under dev.phoenix.server (for example freshCheck builds on Phone A/B)? This cannot be determined from source.
- Is D2D transfer of files/ (worlds, server.properties, backups) intended, given that allowBackup=false and the comments imply backup/transfer is off?
- evidence/device/google-browser-setup-guide.png is tracked even though evidence/device/ is in .gitignore. Should it be reviewed before publication? This is outside this dimension.
- Where will the owner keep the upload keystore? storeFile in keystore.properties resolves relative to app/, not the project root (app/build.gradle.kts:120), so a keystore placed next to keystore.properties needs a ../ path or an absolute path.
- ProxyPairingHost and the management API listeners were not reviewed here, beyond confirming they bind or connect to 127.0.0.1. Other apps on the device can still reach loopback ports; whether the management-server-secret alone is sufficient protection belongs to another dimension.

## G. Build reproducibility

Fresh-clone build reproducibility for android-stage10a (published as repo root), checked read-only at 00ecc65. Gradle and the emulator were not run; a PreToolUse X1 guard hook also blocks Gradle-like commands right now.

The toolchain is Gradle 9.1.0 (wrapper), AGP 9.0.1 with AGP's built-in Kotlin, and the Kotlin Compose plugin 2.3.10. SDK levels are compileSdk 36, targetSdk 36, minSdk 29, with Java 17 source/target, core-library desugaring 2.1.5 and Compose BOM 2026.04.01. No JDK is pinned in the build (no org.gradle.java.home, no daemon-JVM file). Evidence shows the owner used Temurin JDK 25.0.4, which is also required to build the runtime JAR.

A fresh clone cannot compile :app. NetBirdVpnService.kt imports io.netbird.gomobile.android.*, and those classes exist only in two gitignored 26 MB NetBird AARs. The AARs are built on Linux with Go 1.26.7, pinned gomobile and NDK r23b from patched NetBird v0.80.0. Note the ignore rule for them lives in the repo-root .gitignore, which will not be published.

Missing inputs that do not block compile or unit tests:
- runtime/generated (JRE 25 natives and data, libphoenixjvm.so, phoenix-runtime.jar and libplayitagent.so) and the gitignored app/src/main/assets/phoenix-metrics.jar. Without them assemble still succeeds, but the APK fails verify_apk.py and fails at server start.
- The runtime generator itself (tools/runtime/build_runtime.ps1 and package_runtime.py) lives OUTSIDE android-stage10a, at repo-root tools/runtime. A published android-stage10a repo cannot regenerate the runtime.

Tests:
- :core has no JUnit. Its 7 test-source harnesses run as JavaExec tasks and look offline: fake transports, loopback sockets, and optional python with cryptography for byopAcceptance.
- :app has 342 JUnit 4 tests. The local results dated 2026-10-20 show 342 passed with 0 failures.

Also found: no POSIX gradlew script, no wrapper SHA-256, and keystore.properties is not gitignored. Several build scripts are host-specific: runtime and Playit builds are Windows-only, NetBird builds are Linux-only, and setup_sdk.py fetches Windows cmdline-tools only.

Minimal fix (leaves the shipped APK unchanged):
1. Add an opt-in -Pphoenix.netbirdStub=true. It compiles the already-tracked gomobile binding sources jar (41/41 classes match the AAR) against android.jar into a compileOnly/testImplementation stub, and refuses any package/assemble task.
2. Add gradlew and distributionSha256Sum.
3. Gitignore the AARs and keystore.properties.
4. Either move tools/runtime into the published tree or publish pinned prebuilt release assets verified against SHA256SUMS.
5. Make the core harnesses part of check, or set failOnNoDiscoveredTests=false.

### BUILD-1 (high): Fresh clone cannot compile :app or run any app unit test: NetBird binding classes come only from gitignored AARs

- **Threat:** An external reviewer or contributor without the 52 MB of private build outputs cannot compile or test the app. Nobody can independently verify that the published source matches a shipped APK.
- **Evidence:** app/build.gradle.kts:157-166 (files("libs/netbird-android-v0.80.0-*.aar")); app/src/main/java/dev/phoenix/server/NetBirdVpnService.kt:23-33 (io.netbird.gomobile.android imports); app/src/test/java/dev/phoenix/server/NetBirdStatusPollTest.kt:10; repo-root .gitignore:6; tools/netbird/BUILDING.md:38
- **Minimal fix:** Add an opt-in Gradle property phoenix.netbirdStub. When it is true, the build:
1. Extracts the tracked app/libs/netbird-android-v0.80.0-debug-sources.jar.
2. Compiles it with a JavaCompile task against androidComponents.sdkComponents.bootClasspath.
3. Jars the result, and adds it as compileOnly(files(stub)) plus testImplementation(files(stub)) in place of the six files(aar) lines.
4. Fails every package*/assemble*/bundle* task.

The default (property absent) configuration is untouched, so the shipped APK is byte-identical. As an alternative or addition, publish both AARs as pinned GitHub release assets and add a fetch script that verifies them against tools/netbird/SHA256SUMS:9-10.
- **Tests:** In CI on Ubuntu and Windows without app/libs/*.aar, run: ./gradlew -Pphoenix.netbirdStub=true :app:testDebugUnitTest (expect 342 pass). Also check that ./gradlew -Pphoenix.netbirdStub=true :app:assembleDebug fails with a clear message. A CI job should compare the stub's javap API against the AAR's classes.jar whenever the AAR is rebuilt.
- **Confidence:** high (compile failure from imports is certain; whether Gradle fails earlier on the missing file is not verified)

### BUILD-2 (medium): keystore.properties (release signing passwords) is read by the build but not gitignored

- **Threat:** When android-stage10a becomes the public repo root, an owner running 'git add -A' with keystore.properties present would publish storePassword and keyPassword. Only *.jks is ignored.
- **Evidence:** app/build.gradle.kts:9-12,104-110 (rootProject.file("keystore.properties")); android-stage10a/.gitignore (lists *.jks, not keystore.properties); git check-ignore keystore.properties returned no rule
- **Minimal fix:** Add 'keystore.properties' (and '*.keystore') to android-stage10a/.gitignore before publishing. Optionally have tools/scan_secrets.py fail if it is tracked.
- **Tests:** git check-ignore -v keystore.properties must print a rule in the published repo.
- **Confidence:** high

### BUILD-3 (medium): Runtime generator lives outside the published directory; runtime/generated cannot be rebuilt from the public repo

- **Threat:** The APK ships about 100 MB of GPLv2+CPE JRE natives and data plus a native launcher. Without tools/runtime (package_runtime.py, build_runtime.ps1, the port diff and the build workflow evidence), the public repo can neither reproduce them nor carry the corresponding-source and build-script materials the licence docs require.
- **Evidence:** repo-root tools/runtime/build_runtime.ps1:7-9 (resolves ../.. and android-stage10a/runtime); README.md:28 ('..\tools\runtime\build_runtime.ps1'); LICENSING_AND_DISTRIBUTION.md:15; repo-root tools/runtime/evidence/jdk25u_android.diff
- **Minimal fix:** Move repo-root tools/runtime into android-stage10a/tools/runtime:
- build_runtime.ps1: stage root = Join-Path $PSScriptRoot '../..', runtime = <stage>/runtime.
- package_runtime.py:92-93: change the defaults to runtime/generated and tools/runtime/.cache.
- Remove the owner-specific -Ndk default at build_runtime.ps1:2.

Or publish runtime/generated as a pinned release zip with its SHA-256 recorded in the repo.
- **Tests:** From a fresh clone, run tools/runtime/build_runtime.ps1 -Ndk <ndk27.3>, then run assembleDebug and python tools/verify_apk.py.
- **Confidence:** high

### BUILD-4 (low): AAR ignore rule exists only in the unpublished repo-root .gitignore

- **Threat:** In the published repo, the 26 MB NetBird AARs are not ignored. They can be committed by accident, which redistributes BSD-3 and Go-dependency binaries without the notice review the docs say is still incomplete, and bloats history permanently.
- **Evidence:** repo-root .gitignore:6 'android-stage10a/app/libs/*.aar'; android-stage10a/.gitignore:1-12 (no aar rule); tools/netbird/SHA256SUMS:2
- **Minimal fix:** Add 'app/libs/*.aar' to android-stage10a/.gitignore. Also add 'tools/runtime/.cache/' if tools/runtime is moved in.
- **Tests:** git check-ignore -v app/libs/netbird-android-v0.80.0-debug.aar inside the published repo.
- **Confidence:** high

### BUILD-5 (low): No POSIX gradlew script and no wrapper distribution checksum

- **Threat:** Linux and macOS developers or CI cannot run the wrapper. Without distributionSha256Sum, a tampered or MITM'd Gradle distribution would not be detected. There is also no dependency verification metadata, and gradlePluginPortal is used for plugins.
- **Evidence:** git ls-files shows gradlew.bat but no gradlew; gradle/wrapper/gradle-wrapper.properties:1-7 (no distributionSha256Sum); no gradle/verification-metadata.xml
- **Minimal fix:** Regenerate the wrapper with 'gradle wrapper --gradle-version 9.1.0 --distribution-type bin --gradle-distribution-sha256-sum <official sha>'. Commit gradlew with the executable bit (git update-index --chmod=+x gradlew). Add gradle/actions/wrapper-validation in CI.
- **Tests:** On Linux, ./gradlew --version prints Gradle 9.1.0; wrapper-validation passes.
- **Confidence:** high

### BUILD-6 (low): ':core:test' probably fails under Gradle 9 (test sources but no discoverable tests), breaking 'build'/'check'

- **Threat:** A developer running the conventional './gradlew build' or 'check' gets a failure unrelated to code quality. Meanwhile the real core checks (the JavaExec acceptance tasks) are not part of check at all.
- **Evidence:** core/build.gradle.kts:1-23 (java-library, no test dependencies, harnesses run by JavaExec); core/src/test/java/org/phoenix/core/*.java contain only main() harnesses (no org.junit imports)
- **Minimal fix:** In core/build.gradle.kts, set tasks.test { failOnNoDiscoveredTests = false }. Also make tasks.check dependOn acceptance, provisionAcceptance, worldAcceptance, networkingAcceptance, netbirdAcceptance, byopAcceptance and playitAcceptance.
- **Tests:** ./gradlew :core:check runs all 7 harnesses offline.
- **Confidence:** medium: not run. This rests on Gradle 9's documented behaviour of failing when test sources exist but no tests are discovered

### BUILD-7 (low): Host-specific, partly unpinned producer scripts

- **Threat:** No single OS can rebuild every input:
- The runtime and Playit builds need Windows: hardcoded windows-x86_64 clang, cargo.exe and .cmd linker paths.
- NetBird needs Linux: bash, the linux-x86_64 NDK r23b and the Linux Go tarball.
- setup_sdk.py only fetches the newest Windows cmdline-tools and installs no SDK packages.
Independent rebuilds are therefore hard and toolchain drift goes unnoticed.
- **Evidence:** repo-root tools/runtime/build_runtime.ps1:2,12; tools/playit/build.ps1:17-27; tools/netbird/build-android-aar.sh:39-42; tools/setup_sdk.py:13-16; README.md:23
- **Minimal fix:** Document the per-OS matrix. Let build_runtime.ps1 choose the linux-x86_64 prebuilt when present, as package_runtime.py:76 already does. Pin the cmdline-tools revision and install 'platforms;android-36', 'build-tools;36.0.0', 'platform-tools' and 'ndk;27.3.13750724' via sdkmanager in setup_sdk.py, for both host OSes.
- **Tests:** A fresh Linux container can run setup_sdk.py and then the unit tests.
- **Confidence:** high

### BUILD-8 (info): phoenix-metrics.jar is a manual, unpinned, gitignored input loaded as -javaagent into the server JVM

- **Threat:** The jar is not reproducible (no --date, javac from PATH) and has no recorded hash. The app copies it from assets without the hash check applied to phoenix-runtime.jar. On-device tampering is mitigated because APK assets are installer-protected, so this is a provenance and reproducibility gap, not an exploitable weakness.
- **Evidence:** android-stage10a/.gitignore:10; tools/build_telemetry.ps1:5-8; app/src/main/java/dev/phoenix/server/PhoenixBackend.kt:455-460,473
- **Minimal fix:** Register a Gradle task in :app that compiles telemetry-agent/src with --release 17 into a jar with a fixed timestamp and a Premain-Class manifest. Add it via variant.sources.assets?.addGeneratedSourceDirectory, as oracleStackZip already is. Alternatively, record its SHA-256 in host-manifest.json and check it like runtimeJarSha256. Note that this changes APK bytes unless the jar is bit-identical; do it as a separate, deliberate change.
- **Tests:** Two clean builds produce the same phoenix-metrics.jar SHA-256.
- **Confidence:** high

### BUILD-9 (info): Stale build and licensing documentation

- **Threat:** Reviewers will be misled:
- LICENSING_AND_DISTRIBUTION.md:11 says Playit is excluded from the APK and from core sources. In fact libplayitagent.so is packaged and required by verify_apk, and core/playit is compiled into the app.
- README.md:17 and RELEASE_READINESS.md:7 say 85 app unit tests; there are 342.
- LICENSING_AND_DISTRIBUTION.md:3 says assembly is not passing.
- **Evidence:** LICENSING_AND_DISTRIBUTION.md:3,11; app/build.gradle.kts:35-38,84; tools/verify_apk.py:42; core/build.gradle.kts (no exclusion); README.md:17; app/build/test-results/testDebugUnitTest (342 tests)
- **Minimal fix:** Update these documents before publishing.
- **Tests:** n/a
- **Confidence:** high

### BUILD-10 (info): No project LICENSE; tracked phoenix-spawnprep.jar has no source or licence in the published tree

- **Threat:** A public repo with no LICENSE is all-rights-reserved by default. The bundled mod binary can be neither rebuilt nor audited from the repo.
- **Evidence:** git ls-files (no LICENSE* in android-stage10a); app/src/main/assets/spawnprep/phoenix-spawnprep.jar; app/src/main/assets/SPAWNPREP_MANIFEST.json ('contract' points to a separate mod worktree)
- **Minimal fix:** Add a LICENSE. Either publish the spawnprep mod source (with its commit and SHA-256) or document where it lives. Keep the jar tracked, because SpawnPrepInstallerTest needs it.
- **Tests:** n/a
- **Confidence:** high

**Open questions:**

- The effective Kotlin Gradle plugin and compiler version under AGP 9.0.1 built-in Kotlin plus compose plugin 2.3.10 is not verified. The cache holds both 2.2.10 and 2.3.10; confirm with ./gradlew :app:buildEnvironment once Gradle may run (the X1 guard hook currently blocks Gradle).
- Does AGP 9.0.1 fail at configuration or dependency resolution when files("libs/*.aar") points at a missing file, or only at Kotlin compile? Not verified.
- Does ':core:test' actually fail under Gradle 9.1 with the current harness-only test sources? This is inferred from Gradle 9 behaviour, not run.
- Does Gradle or AGP need any NDK for packaging, given useLegacyPackaging=true and keepDebugSymbols '**/*.so'? NDK 29.0.14206865 exists in the local SDK alongside 27.3.13750724, and it is not known whether AGP installed it.
- Is the official SHA-256 of the tracked gradle-wrapper.jar the Gradle 9.1.0 wrapper's? Not checked against Gradle's published checksums.
- Do the tracked netbird-*-sources.jar files (committed at 8f279b4, pre-Stage 9) match the current v0.80.0 AAR's method signatures, not just its class names (41/41 match)? Must be confirmed before relying on them for a stub.
- Licence and source location of phoenix-spawnprep.jar 0.1.1, and which licence Phoenix itself will be published under.
- Whether the app unit tests pass on JDK 17 or 21, or only on the owner's JDK 25: the only evidence is local runs with JDK 25.

## H. Known-bug follow-ups

I checked the status of the nine known release bugs against the code at phoenix/polish-integration @ 00ecc65, the git log, the unit tests, the polish, P0 and GO/NO-GO reports, and the Phoenium repo. Paths are relative to android-stage10a unless stated otherwise.

**Status per bug**
- **Fixed in code but not proven on a phone:** bugs 1, 2, 3, 5 and 7. Each has a fix commit and unit tests. Bug 1 had only partial device checks, on a debuggable uiCheck build. Bugs 2, 3 and 5 have no device run at all. Bug 7 (the Phoenium spawner crash) is fixed in Phoenium acbb640, which is on release/0.2.0-beta.1 (0cb648b + acbb640 + 0182b70, dated 2026-10-27). That fix was never exercised on a phone, and the desktop repro never hit the crash window.
- **Fixed, with device evidence from the uiCheck build only (no R8):** bugs 6 (console) and 9 (Phoenium mode setting). Their release-config versions have not been checked.
- **Environment, not a Phoenix bug:** bug 4. The router DNS blocks b-cdn.net; mobile data was not tested.
- **Known limitation:** bug 8. "Faster starts" (Java AOT cache) is only defaulted off by f7c2182. The cache key still ignores mods/, and the Settings toggle has no warning.

**Not yet done for the 00ecc65 release build**
- The release-config (rcCheck) smoke test has not run.
- The APK still says 0.4.0-netbird-spike / versionCode 4 and is unsigned.
- The Bedrock 11-step checklist is NOT RUN.
- Two-client voice and legacy Java through Via are NOT VALIDATED.

### KB-1 (medium): Phoenium RC1/D45 spawner crash: any player can stop the server; Phoenix cannot detect a jar without the fix

- **Threat:** A remote player crashes the server through ordinary play: a respawn, teleport or join near terrain that is still loading while a custom spawner fires. The crash rolls the world back to the last autosave. Phoenix does not bundle Phoenium. ModManager finds Phoenium by its mod id and finds the engine by a class name, so it cannot warn a user who imports D45 or another jar without acbb640 (D45 reports version 0.2.0-stage2).
- **Evidence:** P0_INCIDENT_REPORT.md (stack CatSpawner.tick -> getBlockState -> beforeWait); the fix exists only in Phoenium acbb640 / release/0.2.0-beta.1; ModManager.kt:61-62, :281; PhoeniumTuning.kt ENGINE_CLASS
- **Repro:** Desktop repro p0rep-fix-2 did not hit the window. The live failure was seen on Phone B (2026-10-20 04:39:50Z).
- **Minimal fix:** Publish only Phoenium 0.2.0-beta.1 (contains acbb640). Optionally, have ModManager.stackWarnings warn when the Phoenium jar's fabric.mod.json version is 0.2.0-stage2 (pre-fix).
- **Tests:** RegionalCustomSpawnerBoundaryTest (acbb640); suite 2060/0/2
- **Confidence:** high for the cause; the fix is not proven live

### KB-2 (low): AOT 'Faster starts' cache key ignores mods/, and the toggle carries no risk warning

- **Threat:** An owner who turns Faster starts on and later imports or changes mods reuses a stale AOT cache. The JVM can then crash with SIGSEGV in C1 and lose everything since the last autosave. Opt-in only; default-off since f7c2182.
- **Evidence:** JvmTuning.kt:93 (key = versionCode, installedAt, runtime manifest, jar name/length/mtime); PhoenixBackend.kt:786-788 (only server.jar and fabric-server-launch.jar); SettingsScreen.kt:670-674 (text promises about 10 s faster starts, no warning)
- **Minimal fix:** Include a digest of the mods/ listing (names, sizes, mtimes) and the effective JVM flags in AotCache.key. Alternatively, hide or label the switch as experimental and warn in its text.
- **Tests:** JvmTuningTest.kt:51, :67 cover only the mode and default logic
- **Confidence:** high

### KB-4 (low): Reconnect at launch reads the whole latest.log into memory

- **Threat:** After a long-running session, reconnect() calls File.readLines() on the full logs/latest.log at app launch, which can cost large memory or time on low-RAM phones. A local file only; no remote vector.
- **Evidence:** PhoenixBackend.kt:937; w3/RESULT.md §4 'scanLog reads all of latest.log ... not bounded'
- **Minimal fix:** Read a bounded tail, for example the last 256 KB as AdoptedProcess already does.
- **Confidence:** high

### KB-5 (low): The release candidate still carries spike versioning and is unsigned; no release-config check of 00ecc65

- **Threat:** A public beta APK named 0.4.0-netbird-spike / versionCode 4 that is unsigned cannot be published as is. All device evidence for 00ecc65 comes from a debuggable, non-R8 uiCheck build, so R8 or shrink regressions in the new code are unverified.
- **Evidence:** app/build.gradle.kts:57-58, :106-112, :124-132; FINAL_POLISH_REPORT §3, §10
- **Minimal fix:** Set versionName and versionCode, sign with the owner key, and run the rcCheck smoke test on Phone B before publishing.
- **Confidence:** high

### KB-3 (info): 8ced69b restored SIGTERM of an unadopted leftover server on Start; untested and never produced on a device

- **Threat:** If a takeover fails or is skipped (exception in adopt, no saved pid), Start sends SIGTERM to any libphoenixjvm.so process whose parent is not the app, then starts a new server. The SIGTERM shutdown hook saves, so data risk is low. The path has no unit test and was never exercised on a device. Matching is by executable basename only (Reliability.kt:138-144), which relies on Android's per-UID /proc visibility.
- **Evidence:** git show 8ced69b; PhoenixBackend.kt:533-551; Reliability.kt:138-144; w3/RESULT.md §3 'Not unit-tested: ... the endLeftovers change'
- **Minimal fix:** Add a unit-testable seam for the endLeftovers decision, and keep the LeftoverServer.isOurServer check for logging. Run W3 device step D.
- **Confidence:** medium

### KB-6 (info): Phoenium Acceleration is not labelled experimental in the UI

- **Threat:** Users may assume the mode is supported or faster, while there is no release-config measurement and the P0 fix is not in D45.
- **Evidence:** SettingsScreen.kt:683-687; FINAL_POLISH_REPORT §9 recommendation 'opt-in experimental'
- **Minimal fix:** Rename the option to 'Acceleration (experimental)' and add one line of caveat.
- **Confidence:** high

**Open questions:**

- Was jvm-experiment.txt (D43-bigcore, including -Dphoenium.bigcore=true) moved aside during the uiCheck Phoenium-mode device check? The uiCheck build reads it because BuildConfig.DEBUG is true, and it comes after the PhoeniumTuning flags. The report says only that it was restored afterwards (device/p0check-20261020/after-restore-experiment.txt was not read).
- b1-a3par-on and the later beta.1 gates have no recorded result in gates_beta1.out yet. Did they complete?
- Is release/0.2.0-beta.1 (0182b70) the Phoenium build the owner intends to publish beside Phoenix 00ecc65? There is no owner approval record in the files read.
- W3 §1 left unreconciled whether the bind check or endLeftovers runs first when an orphan holds the Java port. W3 device step D was never run.
- Is the W2 hypothesis B3 (the name shows OFF when public-route-v2 is missing on first launch) still present? It was not confirmed from first-launch prefs.
- Is minecraft.how unstyled over mobile data? This was not tested.
