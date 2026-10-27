# Keep this intentionally small. Secrets are never strings baked into this APK.

# NetBird gomobile bindings: Go calls back into these classes by name over JNI. The AAR ships the same rules as
# consumer rules; they are repeated so the release build stays safe if the AAR is rebuilt without them.
-keep class go.** { *; }
-keep class io.netbird.gomobile.** { *; }
# The Java 25 runtime runs in a separate child process from assets/runtime/phoenix-runtime.jar, not from app DEX.
# RuntimeLayout is shared source called directly by the app; keep it whole so manifest-driven unpacking is unchanged.
-keep class org.phoenix.runtime.** { *; }
# JNI-registered methods anywhere in app code.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
