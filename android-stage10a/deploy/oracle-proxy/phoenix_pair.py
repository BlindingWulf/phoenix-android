#!/usr/bin/env python3
"""Phoenix proxy pairing client (runs once on the owner's Oracle VM).

Reads the pairing code the owner pasted into Oracle's deployment form, dials the owner's phone through the temporary
NetBird pairing service, proves it holds the pairing secret and receives the NetBird proxy token over an ECDH-encrypted
channel. The token is written only to /etc/phoenix-proxy/proxy.env (root, 0600); it is never printed or logged.
The protocol is specified in ProxyPairing.java in the Phoenix sources; both ends must change together.

Exit codes: 0 paired, 3 gave up (the pairing window passed). systemd restarts the unit on any other failure.
"""
import base64
import hashlib
import hmac
import json
import os
import platform
import re
import socket
import struct
import sys
import time
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

LABEL = b"phoenix-pair-v1"
MAX_LINE = 8192
CODE = re.compile(r"phx1:([a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?):(\d{1,5}):(\d{1,5}):(\d{1,5}):(\d{1,5}):([A-Z2-7]{26})")
ETC = "/etc/phoenix-proxy"
WINDOW_SECONDS = 48 * 3600


class AuthError(Exception):
    """The other side could not prove the pairing secret: the code is wrong or stale."""


def parse_code(text):
    match = CODE.fullmatch(text.strip())
    if not match:
        raise ValueError("not a Phoenix pairing code")
    ports = [int(match.group(i)) for i in (2, 3, 4, 5)]
    if not all(1 <= p <= 65535 for p in ports[:3]) or not (ports[3] == 0 or 1 <= ports[3] <= 65535):
        raise ValueError("invalid port in pairing code")
    secret = base64.b32decode(match.group(6) + "======")[:16]
    return {"host": match.group(1), "port": ports[0], "java": ports[1], "bedrock": ports[2], "voice": ports[3], "secret": secret}


def b64(data):
    return base64.b64encode(data).decode("ascii")


def unb64(value, max_length):
    data = base64.b64decode(value, validate=True)
    if not data or len(data) > max_length:
        raise ValueError("invalid field")
    return data


def read_line(stream):
    line = stream.readline(MAX_LINE + 1)
    if not line.endswith(b"\n"):
        raise ConnectionError("pairing connection closed")
    return json.loads(line[:-1].decode("utf-8"))


def write_line(stream, text):
    stream.write(text.encode("utf-8") + b"\n")
    stream.flush()


def transcript(hello, phone_pub, phone_nonce):
    return hashlib.sha256(LABEL + b"\0" + hello + b"\0" + phone_pub + phone_nonce).digest()


def mac(secret, role, digest):
    return hmac.new(secret, role + digest, hashlib.sha256).digest()


def pair(stream, secret, info):
    """Runs the VM side of the protocol over a file-like binary stream; returns (token, management_url)."""
    own = ec.generate_private_key(ec.SECP256R1())
    own_pub = own.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    nonce = os.urandom(16)
    hello = json.dumps({"v": 1, "pub": b64(own_pub), "n": b64(nonce), "info": info}, separators=(",", ":"))
    write_line(stream, hello)
    reply = read_line(stream)
    phone_pub = unb64(reply["pub"], 128)
    digest = transcript(hello.encode("utf-8"), phone_pub, unb64(reply["n"], 16))
    if not hmac.compare_digest(unb64(reply["mac"], 32), mac(secret, b"phone", digest)):
        raise AuthError("the phone did not prove the pairing code")
    write_line(stream, json.dumps({"mac": b64(mac(secret, b"vm", digest))}))
    sealed = read_line(stream)
    if "error" in sealed:
        raise ConnectionError("phone refused: %s" % str(sealed["error"])[:160])
    peer = serialization.load_der_public_key(phone_pub)
    shared = own.exchange(ec.ECDH(), peer)
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=secret, info=LABEL + b" key" + digest).derive(shared)
    payload = json.loads(AESGCM(key).decrypt(unb64(sealed["iv"], 12), unb64(sealed["ct"], 4096), digest))
    token, management = payload["token"], payload["management"]
    if not re.fullmatch(r"[A-Za-z0-9_.\-]{16,1024}", token) or management != "https://api.netbird.io":
        write_line(stream, json.dumps({"ok": False, "error": "unexpected token or management address"}))
        raise ConnectionError("unexpected pairing payload")
    return token, management, lambda ok, error=None: write_line(stream, json.dumps({"ok": True} if ok else {"ok": False, "error": error}))


def stun_ipv4(server=("stun.netbird.io", 3478), timeout=3.0):
    """Public IPv4 from a STUN binding request (RFC 5389 XOR-MAPPED-ADDRESS) to NetBird's own STUN server."""
    txid = os.urandom(12)
    request = struct.pack("!HHI", 0x0001, 0, 0x2112A442) + txid
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        sock.sendto(request, server)
        data, _ = sock.recvfrom(2048)
    if len(data) < 20 or data[8:20] != txid:
        raise ValueError("bad STUN reply")
    offset = 20
    while offset + 4 <= len(data):
        kind, length = struct.unpack("!HH", data[offset:offset + 4])
        value = data[offset + 4:offset + 4 + length]
        if kind == 0x0020 and len(value) >= 8 and value[1] == 1:
            raw = struct.unpack("!I", value[4:8])[0] ^ 0x2112A442
            return socket.inet_ntoa(struct.pack("!I", raw))
        offset += 4 + ((length + 3) & ~3)
    raise ValueError("no mapped address in STUN reply")


def public_ipv4():
    for attempt in range(3):
        try:
            return stun_ipv4()
        except (OSError, ValueError):
            time.sleep(2)
    for url in ("https://checkip.amazonaws.com", "https://api.ipify.org"):
        try:
            text = urllib.request.urlopen(url, timeout=10).read(64).decode().strip()
            socket.inet_aton(text)
            if re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", text):
                return text
        except (OSError, ValueError):
            continue
    raise RuntimeError("could not learn this VM's public IPv4 address")


def oracle_metadata():
    """Region, shape and availability domain from Oracle's instance metadata service (v2)."""
    try:
        request = urllib.request.Request("http://169.254.169.254/opc/v2/instance/", headers={"Authorization": "Bearer Oracle"})
        data = json.loads(urllib.request.urlopen(request, timeout=5).read(65536))
        return {"region": data.get("canonicalRegionName") or data.get("region", ""), "shape": data.get("shape", ""),
                "ad": data.get("availabilityDomain", "")}
    except (OSError, ValueError):
        return {"region": "", "shape": "", "ad": ""}


def clean(value):
    return re.sub(r"[^A-Za-z0-9 ._:()+-]", "", str(value))[:128]


def write_env(directory, token, ip, wg_port=51820):
    apex = ip.replace(".", "-") + ".sslip.io"
    path = os.path.join(directory, "proxy.env")
    temporary = path + ".tmp"
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", newline="\n") as out:
        out.write("NB_PROXY_TOKEN=%s\n" % token)
        out.write("NB_PROXY_MANAGEMENT_ADDRESS=https://api.netbird.io\n")
        out.write("NB_PROXY_DOMAIN=%s\n" % apex)
        out.write("NB_PROXY_ADDRESS=127.0.0.1:8443\nNB_PROXY_HEALTH_ADDRESS=127.0.0.1:8080\n")
        out.write("NB_PROXY_ACME_CERTIFICATES=false\nNB_PROXY_CERTIFICATE_DIRECTORY=/certs\n")
        out.write("NB_PROXY_WG_PORT=%d\nNB_PROXY_SUPPORTS_CUSTOM_PORTS=true\nNB_PROXY_LOG_LEVEL=warn\n" % wg_port)
    os.replace(temporary, path)
    return apex


def attempt(code, info, out_dir):
    with socket.create_connection((code["host"], code["port"]), timeout=20) as sock:
        sock.settimeout(60)
        stream = sock.makefile("rwb")
        token, _, confirm = pair(stream, code["secret"], info)
        try:
            apex = write_env(out_dir, token, info["ip"])
        except OSError as failure:
            confirm(False, "could not store the token: %s" % failure.strerror)
            raise
        confirm(True)
        return apex


def main(argv):
    # Test mode: python3 phoenix_pair.py --once CODE OUT_DIR INFO_JSON_FILE
    if len(argv) == 5 and argv[1] == "--once":
        with open(argv[4]) as handle:
            info = json.load(handle)
        print("paired " + attempt(parse_code(argv[2]), info, argv[3]))
        return 0
    code_path = os.path.join(ETC, "pairing")
    if os.path.exists(os.path.join(ETC, "proxy.env")) and not os.path.exists(code_path):
        print("phoenix: already paired")
        return 0
    with open(code_path) as handle:
        code = parse_code(handle.read())
    started = os.stat(code_path).st_mtime
    info = dict(oracle_metadata(), arch=platform.machine())
    info["ip"] = public_ipv4()
    info = {k: clean(v) for k, v in info.items()}
    delay = 15
    while True:
        if time.time() - started > WINDOW_SECONDS:
            print("phoenix: no pairing within 48 hours; deploy again with a new pairing code from Phoenix", file=sys.stderr)
            return 3
        try:
            apex = attempt(code, info, ETC)
            os.remove(code_path)  # The secret is useless now; remove it from the disk.
            print("phoenix: paired, proxy cluster %s" % apex)
            return 0
        except AuthError:
            print("phoenix: the pairing code does not match this phone's current one; waiting for the right phone", file=sys.stderr)
            delay = 300
        except (OSError, ValueError, KeyError, ConnectionError) as failure:
            # Phoenix may be closed or NetBird offline; keep trying with a gentle backoff.
            print("phoenix: phone not reachable yet (%s); retrying in %ds" % (type(failure).__name__, delay), file=sys.stderr)
        time.sleep(delay)
        delay = min(delay * 2, 300)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
