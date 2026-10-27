# Native Java 25 runtime build

From the repository root, run `tools/runtime/build_runtime.ps1` with the installed exact Android NDK `27.3.13750724` and a Java 25 JDK. The script downloads only the two pinned official Amethyst JRE25 release archives, verifies their SHA256, copies unmodified headless native libraries into the generated APK `jniLibs`, adds the exact NDK libc++, builds the original PIE launcher, and compiles the Java 25 probe/JAR launcher.

App Gradle source sets need `runtime/generated/jniLibs`, `runtime/generated/assets`, and `runtime/src/main/java`. Set `jniLibs.useLegacyPackaging=true` and preserve all `.so` files through `keepDebugSymbols`; hashes must survive packaging.

`RuntimeLayout.unpackData` requires a fresh/staged app-private JAVA_HOME and the manifest `data` path-to-SHA256 mapping. `RuntimeLayout.linkNative` requires manifest `native` entries and Android's installed `ApplicationInfo.nativeLibraryDir`. It verifies native hashes and creates only symlinks to installer-owned code. Promote a completely prepared runtime atomically and retain its pinned manifest. Do not unpack libraries into writable storage.

Run the installer-owned executable with ProcessBuilder:

```text
<nativeLibraryDir>/libphoenixjvm.so <nativeLibraryDir> <javaHome> [JVM options] -- <mainClass> [main args]
```

Set environment `LD_LIBRARY_PATH=<nativeLibraryDir>`, `JAVA_HOME=<javaHome>`, `HOME=<serverDir>`, `TMPDIR=<cacheDir>` before starting the process. Set working directory to the server directory. Pass `-Djava.io.tmpdir=<cacheDir>` explicitly. The host loads `javaHome/lib/server/libjvm.so`, whose canonical target must lie in the installed native directory; its original path enables HotSpot to discover runtime data. No private linker symbols are used.

The unmodified official HotSpot port derives JAVA_HOME before `-Djava.home` is processed. Android canonicalizes the symlink. The original launcher therefore exports a public `dladdr` compatibility function: it calls the actual public API through `dlsym(RTLD_NEXT)` and changes only libjvm's reported discovery filename to the verified JAVA_HOME symlink path. All other fields/libraries remain the public API's real result; it does not alter memory maps or bypass linker namespaces. This workaround is explicitly recorded in startup logs. A future upstream source patch honoring JAVA_HOME before boot-path inference should replace it. The first no-adapter test failed boot-path initialization; the adapter then passed all Java25 probes physically on the Moto G.

Before either mode starts, the host sets and verifies public Linux/Android `PR_SET_PDEATHSIG=SIGTERM`, rejects a missing/reparented owner, and unblocks SIGTERM. JVM startup installs its ordinary HotSpot handlers, allowing its own signal shutdown hooks to handle parent death. This is an emergency ownership fallback; normal service stop must still send Minecraft `stop` and wait for the world save.

Linux tracks the **creating parent thread**, not merely the parent process. Invoke ProcessBuilder on a retained executor thread that remains alive for the child lifetime. A disposable launch thread exiting immediately would signal its child even while the app remains alive. Process kill/force-stop testing is still required on Android after integration.

The host also exposes one fixed native-agent mode:

```text
<nativeLibraryDir>/libphoenixjvm.so <nativeLibraryDir> --playit --secret-path <private-directory>/agent-key --socket-path <private-directory>/playitd.sock
```

This replaces the wrapper with **only** its own installed sibling `libplayitagent.so` through `execv`; parent-death SIGTERM survives that nonprivileged exec. The supplied native directory must equal the launcher's `/proc/self/exe` directory. The agent cannot be app-owned writable, group/world writable, setuid/setgid or a redirected symlink. The secret must be an app-owned regular 0600 file inside an app-owned 0700 directory, and the socket must be named `playitd.sock` in that same canonical directory. Only the two fixed option/value pairs are passed. There is no arbitrary executable or argument mode. The app should still verify the agent's pinned SHA256 and clean stale active credential files on recovery.

Probe arguments:

```text
-Xms32m -Xmx128m -Djava.class.path=<copied-assets-runtime-jar> -- org.phoenix.runtime.RuntimeProbe <probe-directory>
```

Server arguments:

```text
-Xms128m -Xmx768m -Djava.class.path=<copied-assets-runtime-jar> -- org.phoenix.runtime.JarMain <pinned-server-jar> nogui
```

The memory values are test settings, not a promised safe profile for every device. Preserve stdout/stderr and the process's real exit code. The probe must emit version `25` and all seven operation PASS lines, including shutdown hook after main completion. `PHOENIX_RUNTIME_START` alone proves only the native host executed. Minecraft readiness requires its own log plus a protocol probe.

The application supplies its independent Java instrumentation agent for periodic JVM metrics. The native invocation API accepts `-javaagent:<asset-copied-agent-jar>` as a JVM option. Java instrumentation support is evaluated separately from the seven core runtime probes.

The official JRE25 binaries are currently 4KB aligned. The original host is 16KB aligned. Thus this candidate is blocked on 16KB page-size phones until upstream native runtime is rebuilt and revalidated. Probe execution on a 4KB device does not certify 16KB support.
