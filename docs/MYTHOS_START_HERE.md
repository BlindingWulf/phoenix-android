# Mythos: start here

You are reviewing **Phoenix 0.5.0-beta.1**, an Android app that hosts Minecraft Java 26.2 servers (with Bedrock crossplay) on a phone. Your job is backend correctness and security: find real weaknesses and fix them through pull requests, without breaking hosting or adding latency.

## 1. Read (about 20 minutes)
1. [`MYTHOS_BACKEND_SECURITY_BRIEF.md`](MYTHOS_BACKEND_SECURITY_BRIEF.md): rules, priorities, P1/P2 issue list.
2. [`ARCHITECTURE.md`](ARCHITECTURE.md): modules, lifecycle, where untrusted input enters.
3. [`SECURITY_AND_HOSTING.md`](SECURITY_AND_HOSTING.md): listeners, exposure, threat model.
4. [`review/FINDINGS_BETA1.md`](review/FINDINGS_BETA1.md): the full static-review findings. Reproduce each before fixing.

## 2. Build (no phone, no adb)
Follow [`DEVELOPMENT_SETUP.md`](DEVELOPMENT_SETUP.md). In short:
```bash
git clone https://github.com/BlindingWulf/phoenix-android.git
cd phoenix-android/android-stage10a
gh release download v0.5.0-beta.1 -R BlindingWulf/phoenix-android -p 'netbird-android-v0.80.0-*.aar' -D app/libs
grep '\.aar$' tools/netbird/SHA256SUMS | sha256sum -c -
./gradlew :core:acceptance :core:networkingAcceptance :core:worldAcceptance :app:testDebugUnitTest --console=plain
```
Expected: the core harnesses print PASS lines, and `:app:testDebugUnitTest` reports 342 tests with 0 failures. If yours differs before you change anything, report it: a fresh-clone run has not yet been verified.

## 3. Work loop
1. Pick an item from the P1 list.
2. Write a failing test that demonstrates it.
3. Make the minimal fix and run all suites.
4. If the change touches a network path, measure latency before and after (loopback).
5. Open a pull request using the reporting format in the brief.

Branch naming: `mythos/<finding-id>-<short-name>`, for example `mythos/F1-log-line-classifier`.

## 4. Boundaries
- Do not rewrite subsystems that work. Do not remove playit.gg, NetBird, Minecraft.how, Geyser/Floodgate or Simple Voice Chat support.
- Do not change defaults:
  - AOT stays off;
  - Phoenium mode stays Standard;
  - there is no device-specific scheduling.
- Do not touch the build signing (`keystore.properties` is owner-only and never committed).
- Loopback-only network tests. No traffic to public services, no DoS testing of third parties.
- No destructive world "repair" logic.

## Phone-only checks
These need real Android hardware. Do not claim them from desktop tests. Put the steps in your pull request; the maintainer's phone testers (Phone A/B) will run them on a release-configuration build (`rcCheck`):
1. **Lifecycle:** start, then Online, then BACK/reopen; the app shows Online and Stop works. Also swipe-kill the app while serving: on Android 16 the server dies with the app, and the app must say so on reopen without claiming world damage.
2. **Foreground service and Doze:** screen off for 30 minutes while hosting; the server stays up and TPS is about 20.
3. **Memory:** heap defaults on a 4 GB and an 8 GB phone; no LMK kill during a 45-minute session.
4. **Networking:**
   - Java join over LAN, playit.gg and NetBird;
   - Bedrock join via Geyser;
   - Simple Voice Chat with two clients;
   - median ping before and after your change.
5. **Archive import:** import a large world, a mod and a .mrpack; the app stays responsive with no OOM.
6. **Front door:** a custom-name join through Minecraft.how, plus a status ping.
7. **Release configuration:** the R8-minified build starts, all features above work, and there are no `ClassNotFound`/reflection failures.

## Known limitations (do not file as new)
- **Android 16 kills the server JVM** together with Phoenix's own process. The server rolls back to its last autosave; Phoenix reports this on reopen.
- **Java AOT "Faster starts"** can crash HotSpot C1 when enabled. It is off by default.
- **Google sign-in for Minecraft.how** cannot work inside the app (Google blocks embedded WebViews). Use Discord in-app, or the browser hand-off.
- **Minecraft.how shows unstyled** on networks whose DNS blocks `*.b-cdn.net`.
- **Bedrock formal acceptance and two-client voice** are not yet validated.
- **16 KB-page devices** are unsupported (bundled JRE alignment).
- **NetBird with the switch off:** the custom name moves back to Playit only after two failed checks (10–20 minutes).

## Contact / provenance
- This repository is a sanitized snapshot of the maintainer's private development repository. The paths are identical, so your patches apply there unchanged.
- The source snapshot corresponds to private commit `00ecc65`, plus the version bump to `0.5.0-beta.1`.
