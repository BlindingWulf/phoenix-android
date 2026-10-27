#!/usr/bin/env python3
"""Resolve official Mojang + Fabric metadata into a checksum-pinned lock file.

This is a maintainer-time resolver. The Android installer consumes the checked-in
manifest; it does not call version discovery endpoints or choose a current release.
Only Python's standard library is used. No downloaded JAR is executed.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_SERVER_JAR_BYTES = 256 * 1024 * 1024
MAX_FABRIC_JAR_BYTES = 64 * 1024 * 1024
CHUNK_SIZE = 128 * 1024
USER_AGENT = "Phoenix-Stage10A-ProvisionManifestResolver/1.0"


def require_official_https(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    host = (parsed.hostname or "").lower()
    official = (
        host == "mojang.com"
        or host.endswith(".mojang.com")
        or host == "minecraft.net"
        or host.endswith(".minecraft.net")
        or host == "fabricmc.net"
        or host.endswith(".fabricmc.net")
    )
    if parsed.scheme != "https" or not official:
        raise ValueError(f"refusing non-official or non-HTTPS URL: {url}")


def request(url: str):
    require_official_https(url)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    response = urllib.request.urlopen(req, timeout=30)
    require_official_https(response.geturl())
    return response


def read_bounded(url: str, maximum: int) -> bytes:
    with request(url) as response:
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) > maximum:
            raise ValueError(f"metadata exceeds {maximum} bytes: {url}")
        chunks = []
        length = 0
        while True:
            chunk = response.read(min(CHUNK_SIZE, maximum + 1 - length))
            if not chunk:
                break
            length += len(chunk)
            if length > maximum:
                raise ValueError(f"response exceeds {maximum} bytes: {url}")
            chunks.append(chunk)
        return b"".join(chunks)


def read_json(url: str) -> tuple[object, bytes]:
    raw = read_bounded(url, MAX_METADATA_BYTES)
    value = json.loads(raw.decode("utf-8"))
    return value, raw


def digest_artifact(url: str, maximum: int, *, expected_size: int | None = None,
                    expected_sha1: str | None = None,
                    capture: bool = False,
                    download_to: Path | None = None) -> tuple[dict, bytes | None]:
    sha256 = hashlib.sha256()
    sha1 = hashlib.sha1()
    body = bytearray() if capture else None
    temporary = None
    output = None
    length = 0
    try:
        if download_to is not None:
            download_to.parent.mkdir(parents=True, exist_ok=True)
            temporary = download_to.with_name(download_to.name + ".part")
            output = temporary.open("wb")
        with request(url) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > maximum:
                raise ValueError(f"artifact exceeds {maximum} bytes: {url}")
            while True:
                chunk = response.read(CHUNK_SIZE)
                if not chunk:
                    break
                length += len(chunk)
                if length > maximum:
                    raise ValueError(f"artifact exceeds {maximum} bytes: {url}")
                sha256.update(chunk)
                sha1.update(chunk)
                if body is not None:
                    body.extend(chunk)
                if output is not None:
                    output.write(chunk)
        if output is not None:
            output.close()
            output = None
        actual_sha1 = sha1.hexdigest()
        if expected_size is not None and length != expected_size:
            raise ValueError(f"size mismatch for {url}: expected {expected_size}, got {length}")
        if expected_sha1 is not None and actual_sha1.lower() != expected_sha1.lower():
            raise ValueError(f"SHA-1 mismatch for {url}: expected {expected_sha1}, got {actual_sha1}")
        if download_to is not None and temporary is not None:
            temporary.replace(download_to)
    except Exception:
        if output is not None:
            output.close()
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        raise
    result = {"sizeBytes": length, "sha1": actual_sha1, "sha256": sha256.hexdigest()}
    return result, bytes(body) if body is not None else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-version", default="26.2")
    parser.add_argument("--out", type=Path, help="write manifest here; defaults to stdout")
    parser.add_argument("--download-artifacts", action="store_true",
                        help="also save verified artifacts under tools/provision/artifacts/")
    args = parser.parse_args()
    if args.minecraft_version != "26.2":
        parser.error("this Stage10A resolver is intentionally scoped to Minecraft 26.2")

    version_manifest_url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    version_manifest, _ = read_json(version_manifest_url)
    version_entry = next((v for v in version_manifest.get("versions", [])
                          if v.get("id") == args.minecraft_version), None)
    if not version_entry or version_entry.get("type") != "release":
        raise ValueError(f"official release {args.minecraft_version} is absent")

    version_json, version_json_raw = read_json(version_entry["url"])
    if hashlib.sha1(version_json_raw).hexdigest().lower() != version_entry["sha1"].lower():
        raise ValueError("Mojang version JSON SHA-1 does not match the version manifest")
    server = version_json.get("downloads", {}).get("server")
    if not isinstance(server, dict):
        raise ValueError("official Minecraft metadata has no server artifact")
    artifact_directory = Path(__file__).resolve().parent / "artifacts" if args.download_artifacts else None
    minecraft_artifact, minecraft_body = digest_artifact(
        server["url"], MAX_SERVER_JAR_BYTES,
        expected_size=int(server["size"]), expected_sha1=server["sha1"],
        capture=True,
        download_to=artifact_directory / "server.jar" if artifact_directory else None)
    assert minecraft_body is not None
    with zipfile.ZipFile(io.BytesIO(minecraft_body)) as archive:
        bundled_version = json.loads(archive.read("version.json").decode("utf-8"))
    if bundled_version.get("id") != args.minecraft_version:
        raise ValueError("Mojang server bundle version.json does not match the requested version")
    minecraft_data_version = bundled_version.get("world_version")
    if not isinstance(minecraft_data_version, int) or minecraft_data_version <= 0:
        raise ValueError("Mojang server bundle version.json has no valid world_version")

    loader_url = f"https://meta.fabricmc.net/v2/versions/loader/{args.minecraft_version}"
    loader_versions, loader_raw = read_json(loader_url)
    if not isinstance(loader_versions, list):
        raise ValueError("Fabric loader metadata is not a JSON array")
    loader_entry = next((v for v in loader_versions
                         if v.get("loader", {}).get("stable") is True), None)
    if not loader_entry:
        raise ValueError(f"Fabric publishes no stable loader for {args.minecraft_version}")
    loader_version = loader_entry["loader"]["version"]

    installer_url = "https://meta.fabricmc.net/v2/versions/installer"
    installers, installer_raw = read_json(installer_url)
    if not isinstance(installers, list):
        raise ValueError("Fabric installer metadata is not a JSON array")
    installer_entry = next((v for v in installers if v.get("stable") is True), None)
    if not installer_entry:
        raise ValueError("Fabric publishes no stable installer")
    installer_version = installer_entry["version"]

    server_metadata_url = (
        f"https://meta.fabricmc.net/v2/versions/loader/{args.minecraft_version}/"
        f"{loader_version}/server/json")
    server_metadata, server_metadata_raw = read_json(server_metadata_url)
    if not isinstance(server_metadata, dict):
        raise ValueError("Fabric server metadata is not a JSON object")
    if server_metadata.get("inheritsFrom") != args.minecraft_version:
        raise ValueError("Fabric server metadata does not inherit the requested Minecraft version")
    if not isinstance(server_metadata.get("mainClass"), str):
        raise ValueError("Fabric server metadata has no server main class")

    fabric_url = (
        f"https://meta.fabricmc.net/v2/versions/loader/{args.minecraft_version}/"
        f"{loader_version}/{installer_version}/server/jar")
    fabric_artifact, body = digest_artifact(
        fabric_url, MAX_FABRIC_JAR_BYTES, capture=True,
        download_to=artifact_directory / "fabric-server-launch.jar" if artifact_directory else None)
    assert body is not None

    # The official generated launcher embeds its exact game and loader pins.
    # Check that the generated artifact agrees before locking its digest.
    with zipfile.ZipFile(io.BytesIO(body)) as archive:
        properties = archive.read("install.properties").decode("utf-8")
    expected_properties = {
        "game-version": args.minecraft_version,
        "fabric-loader-version": loader_version,
    }
    parsed_properties = {}
    for line in properties.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            parsed_properties[key.strip()] = value.strip()
    for key, expected in expected_properties.items():
        if parsed_properties.get(key) != expected:
            raise ValueError(f"Fabric launcher embedded {key} does not match resolved pin")

    manifest = {
        "schemaVersion": 1,
        "resolvedAtUtc": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "minecraftVersion": args.minecraft_version,
        "minecraftDataVersion": minecraft_data_version,
        "fabricLoaderVersion": loader_version,
        "fabricInstallerVersion": installer_version,
        "fabricServerMainClass": server_metadata["mainClass"],
        "sources": {
            "minecraftVersionManifest": {
                "url": version_manifest_url,
                "releaseMetadataUrl": version_entry["url"],
                "releaseMetadataSha1": version_entry["sha1"],
                "releaseTime": version_entry.get("releaseTime"),
            },
            "fabricLoaderMetadata": {
                "url": loader_url,
                "sha256": hashlib.sha256(loader_raw).hexdigest(),
            },
            "fabricInstallerMetadata": {
                "url": installer_url,
                "sha256": hashlib.sha256(installer_raw).hexdigest(),
            },
            "fabricServerMetadata": {
                "url": server_metadata_url,
                "sha256": hashlib.sha256(server_metadata_raw).hexdigest(),
            },
        },
        "artifacts": [
            {
                "id": "minecraft-server",
                "fileName": "server.jar",
                "url": server["url"],
                **minecraft_artifact,
                "expectedSha1FromMojang": server["sha1"],
                "integrityBasis": "Mojang metadata supplies the expected size and SHA-1; resolver also pins the downloaded SHA-256",
                "license": "Minecraft EULA; downloaded from Mojang-hosted metadata",
            },
            {
                "id": "fabric-server-launcher",
                "fileName": "fabric-server-launch.jar",
                "url": fabric_url,
                **fabric_artifact,
                "integrityBasis": "SHA-256 and size computed from the official Fabric Meta HTTPS response; endpoint did not publish a checksum",
                "embeddedMinecraftVersion": args.minecraft_version,
                "embeddedFabricLoaderVersion": loader_version,
                "license": "Fabric Installer Apache-2.0; included components retain their licenses",
            },
        ],
        "defaults": {
            "eulaAccepted": False,
            "optimizationMods": [],
            "createDefaultWorld": False,
        },
        "notes": [
            "The checked-in artifact hashes pin installation; this resolver is only for deliberate lock refreshes.",
            "Fabric's server launcher performs its own first-run setup for the exact embedded Minecraft and loader versions.",
        ],
    }
    rendered = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(rendered, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, urllib.error.URLError, ValueError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        print(f"resolve_manifest: {exc}", file=sys.stderr)
        raise SystemExit(2)
