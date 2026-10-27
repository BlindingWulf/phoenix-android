#!/usr/bin/env python3
"""Release check: no credentials or personal account identifiers in the sources or a built APK.

Usage:
  python -I tools/scan_secrets.py [--deny TEXT ...] PATH [PATH ...]

PATH may be a directory (git-tracked and untracked-but-not-ignored files are scanned when it is a git work tree) or an
.apk/.aar/.zip (every entry is scanned, including classes.dex string data). --deny adds literal strings that must not
appear, such as the publisher's own email, hostname or NetBird peer id; keep those on the command line or in an
untracked file, never in this script. Exits 1 when anything is found and prints only where, never the matched value.
"""
import os
import re
import subprocess
import sys
import zipfile

PATTERNS = {
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "NetBird token": re.compile(rb"\bnb[pxs]_[A-Za-z0-9]{20,}"),
    "Oracle OCID": re.compile(rb"\bocid1\.(?:tenancy|user|instance|compartment|vcn|subnet)\.oc1\.[a-z0-9-]*\.[a-z0-9]{20,}"),
    "Google API key": re.compile(rb"\bAIza[0-9A-Za-z_\-]{35}\b"),
    "AWS access key": re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "JSON web token": re.compile(rb"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
    "client secret": re.compile(rb"(?i)client[_-]?secret\"?\s*[:=]\s*\"[^\"\s]{8,}\""),
    "Phoenix pairing code": re.compile(rb"\bphx1:[a-z0-9.-]+:\d+:\d+:\d+:\d+:[A-Z2-7]{26}\b"),
}
# Test fixtures that look like secrets on purpose.
ALLOWED = [re.compile(rb"nbx_TESTONLY")]
SKIP_DIRS = {".git", ".gradle", "build", ".tools", "node_modules", "__pycache__", ".idea"}
SKIP_SUFFIX = (".png", ".jpg", ".webp", ".so", ".jar", ".aar", ".apk", ".zip", ".gz", ".ttf", ".otf", ".keystore", ".jks")


def scan_bytes(name, data, deny, findings):
    for label, pattern in PATTERNS.items():
        for match in pattern.finditer(data):
            if not any(allowed.match(match.group(0)) for allowed in ALLOWED):
                findings.append((name, label))
                break
    lowered = data.lower()
    for text in deny:
        if text.lower().encode() in lowered:
            findings.append((name, "denied string #%d" % (deny.index(text) + 1)))


def files_of(root):
    try:
        listed = subprocess.run(["git", "-C", root, "ls-files", "-co", "--exclude-standard", "-z"], capture_output=True, check=True).stdout
        return [os.path.join(root, f) for f in listed.decode().split("\0") if f]
    except (OSError, subprocess.CalledProcessError):
        out = []
        for base, dirs, names in os.walk(root):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            out.extend(os.path.join(base, n) for n in names)
        return out


def main(argv):
    deny, paths = [], []
    args = iter(argv[1:])
    for arg in args:
        if arg == "--deny":
            deny.append(next(args))
        else:
            paths.append(arg)
    if not paths:
        print(__doc__)
        return 2
    findings, scanned = [], 0
    for path in paths:
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for entry in archive.infolist():
                    if entry.file_size <= 64 * 1024 * 1024 and not entry.filename.endswith(".so"):
                        scan_bytes("%s!%s" % (path, entry.filename), archive.read(entry), deny, findings)
                        scanned += 1
            continue
        for name in files_of(path):
            if name.endswith(SKIP_SUFFIX) or any(part in SKIP_DIRS for part in re.split(r"[\\/]", name)) or not os.path.isfile(name):
                continue
            if os.path.getsize(name) > 8 * 1024 * 1024:
                continue
            with open(name, "rb") as handle:
                scan_bytes(name, handle.read(), deny, findings)
            scanned += 1
    for name, label in findings:
        print("FOUND %s in %s" % (label, name))
    print("%s: %d files scanned, %d findings" % ("FAIL" if findings else "PASS", scanned, len(findings)))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
