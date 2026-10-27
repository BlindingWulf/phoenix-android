# Security and hosting

This describes how Phoenix exposes a Minecraft server, what is reachable from where, and the threat model used for the beta review.

## Threat model
**Assets:**
- the user's worlds and backups;
- availability of the server and of the Phoenix app;
- account-grade secrets: NetBird device key and OAuth tokens, the playit agent key, Minecraft.how session cookies, the Minecraft management API secret, the Oracle relay pairing secret;
- player identifiers (names, UUIDs, IPs in logs).

**Attackers in scope:**
1. **Remote players and anyone who can reach a game port**, through LAN, playit.gg, NetBird or the custom-name front door. They control chat text, login names and packets to app-owned listeners.
2. **Malicious archives**: world ZIPs, mod jars and `.mrpack` files the user imports.
3. **Other apps on the same phone**: they can open loopback ports, race for ports, and register the same custom URI scheme.
4. **Network peers on the user's NetBird network.**
5. **Installed mods.** A mod runs in the server JVM under Phoenix's UID. It is trusted code, equivalent to the app itself. This is a documented trust boundary, not something the beta sandboxes.

**Out of scope:** volumetric DDoS against the tunnel endpoints, which needs upstream mitigation (playit.gg, NetBird or the relay host); a rooted or compromised device; and attacks on Mojang, Microsoft, playit.gg, NetBird or Minecraft.how infrastructure.

**Defaults that matter:**
- `online-mode=true`;
- `enable-rcon=false`, `enable-query=false`;
- only an allowlist of `server.properties` keys can be changed from the UI;
- AOT off, Phoenium mode Standard.

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

## Known weaknesses and planned fixes
- See [`MYTHOS_BACKEND_SECURITY_BRIEF.md`](MYTHOS_BACKEND_SECURITY_BRIEF.md) (prioritised) and [`review/FINDINGS_BETA1.md`](review/FINDINGS_BETA1.md) (complete).
- The most important class is **untrusted server-log text**. Minecraft logs player chat to stdout, and Phoenix parses stdout for status. Every stdout parser must accept only the server's own prefixed lines and must never throw on malformed input.

## DoS posture (practical, not absolute)
- **Game connections:** these are handled by Minecraft, Geyser and Simple Voice Chat themselves. Phoenix adds no per-packet work and should not.
- **App-owned listeners** (front door, pairing listener) need bounded workers, total per-connection deadlines and bounded logging.
- **Behind playit.gg and NetBird**, all players share one source address, so per-IP limits there are global limits: use them carefully.
- **Archive imports, log reading and remote responses** must be size-bounded.
- **Large floods** need upstream protection. playit.gg and NetBird relays absorb some; a public IP needs provider-side filtering.

## Credential storage
- **Keystore AES-GCM (backup-excluded):** NetBird account tokens and the proxy token.
- **App-private but not encrypted:** the NetBird device key (written by the Go client) and the Minecraft.how WebView cookies.
- **Passed in a 0600 file:** the playit agent key.

Details and gaps: findings E1–E8.
