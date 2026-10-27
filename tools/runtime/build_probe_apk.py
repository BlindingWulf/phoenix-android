"""Build a separate diagnostic APK, leaving the Phoenix application identity alone."""
from pathlib import Path
import subprocess
import zipfile
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", type=Path, default=Path("android-stage10a/.tools/android-sdk"))
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
runtime = root / "android-stage10a/runtime"
sdk = args.sdk.resolve()
tools = sdk / "build-tools/36.0.0"
android_jar = sdk / "platforms/android-36/android.jar"
build = runtime / "build/probe-apk"
classes = build / "classes"; dex = build / "dex"
classes.mkdir(parents=True, exist_ok=True); dex.mkdir(parents=True, exist_ok=True)
def run(*cmd): subprocess.run([str(v) for v in cmd], check=True, cwd=root)
sources = list((runtime / "probe-app/src").rglob("*.java")) + list((runtime / "src/main/java").rglob("*.java"))
run("javac", "--release", "17", "-classpath", android_jar, "-d", classes, *sources)
class_files = list(classes.rglob("*.class"))
run(tools / "d8.bat", "--min-api", "26", "--lib", android_jar, "--output", dex, *class_files)
unsigned = build / "probe-unsigned.apk"
run(tools / "aapt2.exe", "link", "-o", unsigned, "--manifest", runtime / "probe-app/AndroidManifest.xml", "-I", android_jar)
with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write(dex / "classes.dex", "classes.dex")
    for base, prefix in [(runtime / "generated/jniLibs", "lib"), (runtime / "generated/assets", "assets")]:
        for path in base.rglob("*"):
            if path.is_file(): apk.write(path, prefix + "/" + path.relative_to(base).as_posix())
    agent = root / "android-stage10a/app/src/main/assets/phoenix-metrics.jar"
    if agent.exists(): apk.write(agent, "assets/runtime/phoenix-metrics.jar")
aligned = build / "probe-aligned.apk"
run(tools / "zipalign.exe", "-f", "-p", "4", unsigned, aligned)
key = build / "debug.keystore"
if not key.exists(): run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-validity", "3650", "-dname", "CN=Phoenix Runtime Diagnostic")
signed = build / "phoenix-runtime-probe.apk"
run(tools / "apksigner.bat", "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, aligned)
run(tools / "apksigner.bat", "verify", signed)
print(signed)
