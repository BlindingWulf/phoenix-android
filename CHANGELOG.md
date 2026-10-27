# Changelog

## 0.5.0-beta.1 (2026-10-27): source pre-release for review
First public source release. Corresponds to private commit `00ecc65` plus the version bump; versionCode 5.

### Fixed since the previous internal build (0.4.0-netbird-spike)
- **Faster starts (Java AOT cache) is off by default.** Enabling it could crash HotSpot's C1 compiler.
- **Server recovery:**
  - Phoenix reconnects to a server that outlived the app instead of showing Offline.
  - A leftover server from an earlier run is classified, and still gets a clean stop before a new start.
  - *Unit-tested; the original Offline sighting did not reproduce on a device.*
- **Crash reporting:** server crashes are told apart from world damage. Chunk loads cancelled during a stop are not reported as damage, and a crash banner shows the cause and report file.
- **NetBird:** a switch turned off stays off. There is no auto-start from the front door, the 10-minute recheck or a service restart.
- **Minecraft.how and NetBird after first launch:** they no longer look unlinked. An unreachable check keeps the last known state.
- **Minecraft.how sign-in:** the in-app Google button, which Google blocks in embedded views, is hidden; the browser hand-off remains. A hostname is shown green only once it is verified.
- **Console:**
  - stack traces fold into their entry, with one crash card;
  - a diagnostics filter, warning/error counts and "Export full log";
  - the console follows new lines while you are at the newest line.
- **Phoenium mode** (Settings › Performance): Standard (default) or Accelerated (opt-in, experimental).

### Known limitations
See [`docs/MYTHOS_START_HERE.md`](docs/MYTHOS_START_HERE.md#known-limitations-do-not-file-as-new) and the review findings in [`docs/review/FINDINGS_BETA1.md`](docs/review/FINDINGS_BETA1.md).

### Not verified for this snapshot
- A release-configuration (`rcCheck`) phone smoke test of this exact source.
- A fresh-clone desktop build.
- A signed APK; none is published.
