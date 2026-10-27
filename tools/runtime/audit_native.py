"""Check ELF SONAME closure for generated headless APK payloads."""
import argparse
import json
from pathlib import Path
import re
import subprocess
parser = argparse.ArgumentParser()
parser.add_argument("--readelf", required=True, type=Path)
parser.add_argument("--native", type=Path, default=Path("android-stage10a/runtime/generated/jniLibs"))
parser.add_argument("--out", type=Path, default=Path("tools/runtime/evidence/native-dependency-closure.json"))
args = parser.parse_args()
system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so"}
result = {}
missing = []
for directory in args.native.iterdir():
    if not directory.is_dir(): continue
    names = {path.name for path in directory.glob("*.so")}
    dependencies = {}
    for path in directory.glob("*.so"):
        output = subprocess.run([str(args.readelf), "-d", str(path)], capture_output=True, text=True, check=True).stdout
        needed = re.findall(r"NEEDED.*?\[(.*?)\]", output)
        dependencies[path.name] = needed
        for name in needed:
            if name not in names | system: missing.append(f"{directory.name}/{path.name}: {name}")
    result[directory.name] = dependencies
args.out.write_text(json.dumps({"systemAllowlist": sorted(system), "dependencies": result, "missing": missing}, indent=2))
if missing: raise SystemExit("Missing native dependencies: " + ", ".join(missing))
print("Native dependency closure PASS", ", ".join(result))
