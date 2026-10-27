"""Build-time only: verify official runtimes, separate immutable code from JRE data.

No device executes files from this tool's cache or assets. Runtime libraries are
packaged under lib/<abi>, extracted by Android's installer, and linked as data
path symlinks only when installed. The original libraries are never modified.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import struct
import tarfile
import urllib.request
import zipfile

RUNTIMES = {
    "arm64-v8a": ("arm64", "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37", "aarch64-linux-android"),
    "x86_64": ("x86_64", "7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d", "x86_64-linux-android"),
}
# Keep the Android-built headless java.desktop image/font stack. The upstream
# Android JRE archive's libawt_xawt.so is only an X11 compatibility stub and
# libjawt.so exposes desktop windowing; neither is needed by a server process.
EXCLUDED_NATIVE = {"libawt_xawt.so", "libjawt.so"}
def digest(data): return hashlib.sha256(data).hexdigest()
def elf_info(data):
    if data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01": raise ValueError("Expected ELF64 little-endian")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry, count = struct.unpack_from("<HH", data, 54)
    align = [struct.unpack_from("<Q", data, offset+i*entry+48)[0] for i in range(count)
             if struct.unpack_from("<I", data, offset+i*entry)[0] == 1]
    return {"machine": struct.unpack_from("<H", data, 18)[0], "loadSegmentAlignment": align}

def package(abi, cache, output, ndk):
    arch, expected, triple = RUNTIMES[abi]
    name = f"jre25-android-{arch}.tar.xz"
    archive = cache / name
    url = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/" + name
    cache.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix(".partial")
        urllib.request.urlretrieve(url, temporary)
        if digest(temporary.read_bytes()) != expected: raise ValueError("Runtime archive SHA256 mismatch")
        temporary.replace(archive)
    if digest(archive.read_bytes()) != expected: raise ValueError("Runtime archive SHA256 mismatch")
    native = output / "jniLibs" / abi
    assets = output / "assets" / "runtime" / abi
    native.mkdir(parents=True, exist_ok=True); assets.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": 1, "abi": abi, "archiveUrl": url, "archiveSha256": expected,
                "jreBuildRun": "https://github.com/AngelAuraMC/angelauramc-openjdk-build/actions/runs/31798797699",
                "portCommit": "7a0266e745d9b4acf400afa189b58e672900f710", "native": [], "data": []}
    binaries = {}
    with tarfile.open(archive) as source, zipfile.ZipFile(assets / "jre-data.zip", "w", zipfile.ZIP_DEFLATED) as data_zip:
        for member in source:
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or ".." in relative.parts: raise ValueError("Unsafe archive path")
            if not member.isfile(): continue
            relative_name = str(relative)
            data = source.extractfile(member).read()
            if relative_name.startswith("bin/"): continue
            if relative.name.endswith(".so"):
                if relative.name in EXCLUDED_NATIVE: continue
                if relative.name in binaries and binaries[relative.name] != data: raise ValueError("Flattened native name collision")
                binaries[relative.name] = data
                info = elf_info(data)
                expected_machine = 183 if abi == "arm64-v8a" else 62
                if info["machine"] != expected_machine: raise ValueError("Wrong ELF ABI")
                manifest["native"].append({"runtimePath": relative_name, "installedName": relative.name, "sha256": digest(data), **info})
            else:
                zip_info = zipfile.ZipInfo(relative_name, date_time=(2026, 10, 8, 0, 0, 0))
                zip_info.compress_type = zipfile.ZIP_DEFLATED
                data_zip.writestr(zip_info, data)
                manifest["data"].append({"path": relative_name, "sha256": digest(data), "size": len(data)})
                if relative_name == "release": manifest["release"] = data.decode()
    host_prebuilt = "windows-x86_64" if (ndk / "toolchains/llvm/prebuilt/windows-x86_64").exists() else "linux-x86_64"
    cpp = ndk / "toolchains/llvm/prebuilt" / host_prebuilt / "sysroot/usr/lib" / triple / "libc++_shared.so"
    if not cpp.is_file(): raise ValueError(f"Missing NDK libc++: {cpp}")
    cpp_data = cpp.read_bytes()
    manifest["native"].append({"runtimePath": "lib/libc++_shared.so", "installedName": cpp.name, "sha256": digest(cpp_data), **elf_info(cpp_data)})
    manifest["ndkSourceProperties"] = (ndk / "source.properties").read_text()
    binaries[cpp.name] = cpp_data
    for name, data in binaries.items(): (native / name).write_bytes(data)
    shutil.copyfile(ndk / "NOTICE", assets / "NDK-NOTICE.txt")
    (assets / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Packaged {abi}: {len(binaries)} native libraries, {len(manifest['data'])} data entries")
    return manifest

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("android-stage10a/runtime/generated"))
    parser.add_argument("--cache", type=Path, default=Path("tools/runtime/.cache"))
    parser.add_argument("--abis", nargs="+", choices=list(RUNTIMES), default=list(RUNTIMES))
    args = parser.parse_args()
    for selected in args.abis: package(selected, args.cache, args.output, args.ndk)
