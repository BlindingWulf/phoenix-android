"""Source-only alternative to dladdr compatibility, for a future port rebuild.

Use on the pinned official OpenJDK25 source after applying the FCL Android patch.
This does not alter a downloaded binary. A full NDK cross build and all runtime
acceptance probes are required before substituting any resulting libjvm.
"""
from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
args = parser.parse_args()
target = args.source / "src/hotspot/os/linux/os_linux.cpp"
text = target.read_text()
needle = "    os::jvm_path(buf, bufsize);\n"
if text.count(needle) != 1: raise SystemExit("Unexpected pinned source layout; refusing patch")
replacement = """#ifdef __ANDROID__
    // APK native libraries are installed flat. Their filesystem location is
    // distinct from data-only JAVA_HOME; honor the process-owned runtime path
    // before boot-class-path initialization, rather than after option parsing.
    const char* phoenix_java_home = ::getenv("JAVA_HOME");
    if (phoenix_java_home != nullptr && phoenix_java_home[0] == '/') {
      os::snprintf_checked(buf, bufsize, "%s/lib/server/libjvm.so", phoenix_java_home);
    } else {
      os::jvm_path(buf, bufsize);
    }
#else
    os::jvm_path(buf, bufsize);
#endif
"""
target.write_text(text.replace(needle, replacement))
print("Applied source-only APK JAVA_HOME discovery patch:", target)
