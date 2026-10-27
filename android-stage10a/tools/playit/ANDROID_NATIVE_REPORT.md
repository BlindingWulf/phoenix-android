# Playit Android native build report

Reviewed 2026-10-08. The official `playit-cloud/playit-agent` source was pinned to commit `633d650869a9cdb2c906efa9effc58aa8b7725a3` (v1.0.12). No Playit account was authorized or contacted. The only device probe ran the binary's local `--help` output and supplied no credentials.

## Build and artifact

The upstream `playitd` daemon cross-compiles successfully to `aarch64-linux-android` using Rust 1.98.0, Android NDK 27.3.13750724 (r27d), and the NDK API-24 clang wrapper. The build used Cargo's pinned lockfile and a release profile with 16 KiB ELF load alignment. The upstream source was not patched.

The artifact is `runtime/generated/jniLibs/arm64-v8a/libplayitagent.so` (6,709,264 bytes). Its SHA-256 is `18520FB75197EEB08E162A8DD44D93AA1B29B0028CCD9EF9A44D53847C1A00A2`. `llvm-readelf` reported ELF64 little-endian, `ET_DYN`, AArch64, a non-zero entry point, `/system/bin/linker64`, and `PT_LOAD` alignment `0x4000`. The binary ran `--help` on the connected Android arm64 phone, then the temporary probe file was deleted. This validates execution but does not prove a live Playit session or tunnel.

`build.ps1` clones the official repository into a unique temporary directory, checks out the fixed revision, builds with `--locked`, verifies the ELF header and interpreter, copies the binary to the one staged runtime path, copies the upstream license, and writes `BUILD_METADATA.txt`. It deletes only its generated temporary source directory after verifying that the resolved path is under the system temporary directory. Run it from any directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\android-stage10a\tools\playit\build.ps1
```

Add `-ProbeConnectedDevice` to push the artifact temporarily to `/data/local/tmp`, invoke `--help` without a secret, and remove the probe. The initial manual build attempt did not provide the NDK compiler environment to Cargo's `ring` build script and stopped with `failed to find tool "aarch64-linux-android-clang"`; setting the NDK clang wrapper through Cargo's target linker and `CC_aarch64_linux_android` resolved it without source changes. The checked-in script reproduces the working setup.

## Process launch, key, and local IPC

The binary is an executable PIE with a `.so` package suffix; it is not a JNI library. Start it as a process, not through `System.loadLibrary`. Upstream `playitd` has no `run` or `daemon` subcommand. Its entry point accepts options and calls the daemon directly. Use path-only arguments such as:

```text
<private executable> --secret-path <private secret file> --socket-path <private directory>/playitd.sock
```

Upstream accepts either raw hexadecimal secret text or TOML with a root `secret_key` field. Never use `--secret`, which puts the key in process arguments. A Phoenix Android adapter should decrypt its Keystore-stored key into an app-private file readable by the app's own UID and pass only that file's path. Do not put the key in the file name or path; upstream daemon startup/status reports the path. The audited startup paths do not print the key contents.

The Rust Android target is `target_os = "android"`, so upstream's `target_os = "linux"` socket chmod hook does not run. The upstream default socket path also falls back to `./playitd.sock`. Pass an explicit Unix-socket path inside a private app directory and keep that directory inaccessible to other apps. This local IPC protocol supports administrative operations and has no authentication. Do not place the socket in shared storage or expose a TCP listener for it.

`ClaimAgentType` does not affect the daemon's key parser: `playitd` validates a non-empty hexadecimal key and then uses it as an API credential. The upstream `playit setup` flow requests `assignable`; direct `claim url` and `claim exchange` default to `self-managed`. Both produce a key the daemon can parse, but the account/assignment meaning is server-side and was not tested against a live Playit account.

## Licensing and provenance

The pinned upstream repository is BSD-2-Clause; the exact upstream `LICENSE.txt` is copied to `LICENSE-PLAYIT-AGENT.txt`. The built binary links 231 packages reachable for this target. `DEPENDENCY_LICENSES.md` records the pinned Cargo metadata license expressions; notable expressions include `CDLA-Permissive-2.0`, `MPL-2.0`, `Unicode-3.0`, BSD, ISC, Apache-2.0, MIT, Zlib, 0BSD, Unlicense, and BSL-1.0. `DEPENDENCY_LICENSES.md` is an inventory, not a complete third-party notice bundle. Before distributing the APK publicly, include the applicable license and notice texts for every bundled dependency, as well as the upstream BSD-2-Clause notice.

Provenance hashes:

| Input or artifact | SHA-256 |
| --- | --- |
| Upstream `Cargo.lock` at the pinned commit | `785919E9E05A64A021270C63BACF5C8240052787DE978810962FF8D7C579D2E9` |
| Copied upstream `LICENSE.txt` | `DD940A7BFF7C184A05FD6BA5BA5A46EE61DCE2EC0E3F2A882650D3075A037F5F` |
| Staged Android arm64 executable | `18520FB75197EEB08E162A8DD44D93AA1B29B0028CCD9EF9A44D53847C1A00A2` |

## Scope and unknowns

The staged executable and device `--help` probe establish that upstream code builds and executes on the tested Android arm64 phone. APK packaging/extraction behavior, executable permissions in `nativeLibraryDir`, app service lifecycle, encrypted-key handoff, daemon IPC from Java, authenticated agent startup, local Minecraft forwarding, public tunnel reachability, and Bedrock behavior remain unverified. The API client that provisions Java tunnels is separate from the daemon data plane. See [`../../PLAYIT_INTEGRATION_REPORT.md`](../../PLAYIT_INTEGRATION_REPORT.md) for the upstream API contract and current TCP/Bedrock limits.

Official source links: [pinned upstream repository](https://github.com/playit-cloud/playit-agent/tree/633d650869a9cdb2c906efa9effc58aa8b7725a3), [daemon CLI entry](https://github.com/playit-cloud/playit-agent/blob/633d650869a9cdb2c906efa9effc58aa8b7725a3/packages/playitd/src/bin/playitd.rs), [secret-file parser](https://github.com/playit-cloud/playit-agent/blob/633d650869a9cdb2c906efa9effc58aa8b7725a3/packages/playitd/src/daemon.rs), [IPC default path](https://github.com/playit-cloud/playit-agent/blob/633d650869a9cdb2c906efa9effc58aa8b7725a3/packages/playit-ipc/src/paths.rs), [claim flow and claim types](https://github.com/playit-cloud/playit-agent/blob/633d650869a9cdb2c906efa9effc58aa8b7725a3/packages/playit-cli/src/main.rs), and [Rust Android target support](https://doc.rust-lang.org/rustc/platform-support/android.html).
