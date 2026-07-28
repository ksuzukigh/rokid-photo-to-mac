#!/usr/bin/env python3
"""Paired, LAN-only receiver for Photo to Mac."""
from datetime import datetime
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket as socket_module
from socketserver import BaseRequestHandler, ThreadingUDPServer
from threading import Lock, Thread
from urllib.parse import parse_qs, urlparse

INBOX = Path.home() / "Pictures" / "Rokid Inbox"
MAX_BYTES = 15 * 1024 * 1024
TOKEN_PATH = (
    Path.home()
    / "Library"
    / "Application Support"
    / "Rokid Photo Bridge"
    / "token.txt"
)
TOKEN = TOKEN_PATH.read_text(encoding="ascii").strip() if TOKEN_PATH.exists() else ""
if len(TOKEN) != 32 or any(character not in "0123456789abcdef" for character in TOKEN):
    TOKEN = ""
PHOTO_PORT = 8765
DISCOVERY_PREFIX = b"ROKID_PHOTO_BRIDGE_DISCOVER 3"
SAVE_LOCK = Lock()


def local_address_for(peer_ip):
    """Return the Mac address used to reach this peer."""
    probe = socket_module.socket(socket_module.AF_INET, socket_module.SOCK_DGRAM)
    try:
        probe.connect((peer_ip, 9))
        return probe.getsockname()[0]
    finally:
        probe.close()


class Discovery(BaseRequestHandler):
    def handle(self):
        data, socket = self.request
        fields = data.strip().split(b" ")
        if (
            len(fields) != 3
            or fields[0] + b" " + fields[1] != DISCOVERY_PREFIX
            or len(fields[2]) != 32
            or any(character not in b"0123456789abcdef" for character in fields[2])
            or not TOKEN
        ):
            return
        try:
            address = local_address_for(self.client_address[0])
        except OSError:
            return
        nonce = fields[2].decode("ascii")
        signed = f"{nonce} {address} {PHOTO_PORT}".encode("ascii")
        proof = hmac.new(TOKEN.encode("ascii"), signed, hashlib.sha256).hexdigest()
        response = (
            f"ROKID_PHOTO_BRIDGE 3 {address} {PHOTO_PORT} {proof}"
        ).encode("ascii")
        print(f"Authenticated discovery from {self.client_address[0]}", flush=True)
        socket.sendto(response, self.client_address)


class DiscoveryServer(ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True


class Receiver(BaseHTTPRequestHandler):
    timeout = 30

    def do_POST(self):
        if urlparse(self.path).path != "/upload":
            self.send_error(404)
            return
        nonce = self.headers.get("X-Photo-Nonce", "")
        supplied_proof = self.headers.get("X-Photo-Proof", "")
        if (
            not TOKEN
            or len(nonce) != 32
            or any(character not in "0123456789abcdef" for character in nonce)
        ):
            self.send_error(403, "Not paired")
            return
        expected_proof = hmac.new(
            TOKEN.encode("ascii"),
            f"{nonce} upload".encode("ascii"),
            hashlib.sha256,
        ).hexdigest()
        if not hmac.compare_digest(supplied_proof, expected_proof):
            self.send_error(403, "Not paired")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "Bad Content-Length")
            return
        if not 0 < length <= MAX_BYTES:
            self.send_error(413, "Photo is empty or too large")
            return
        body = self.rfile.read(length)
        if len(body) != length:
            self.send_error(400, "Upload was interrupted")
            return
        if (
            self.headers.get_content_type() != "image/jpeg"
            or not body.startswith(b"\xff\xd8")
            or not body.endswith(b"\xff\xd9")
        ):
            self.send_error(415, "Only complete JPEG photos are accepted")
            return
        supplied = parse_qs(urlparse(self.path).query).get("filename", [""])[0]
        safe_name = Path(supplied).name if supplied.endswith(".jpg") else ""
        if not safe_name or safe_name.startswith("."):
            safe_name = "rokid-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".jpg"
        INBOX.mkdir(parents=True, exist_ok=True)
        with SAVE_LOCK:
            destination = INBOX / safe_name
            stem, suffix = destination.stem, destination.suffix
            counter = 2
            while destination.exists():
                destination = INBOX / f"{stem}-{counter}{suffix}"
                counter += 1
            temporary = INBOX / (destination.name + ".part")
            try:
                temporary.write_bytes(body)
                temporary.replace(destination)
            except Exception:
                temporary.unlink(missing_ok=True)
                raise
        print(f"Saved {destination}", flush=True)
        self.send_response(201)
        self.end_headers()
        self.wfile.write(b"saved\n")

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} - {fmt % args}", flush=True)


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit(f"Pairing token is missing: {TOKEN_PATH}")
    INBOX.mkdir(parents=True, exist_ok=True)
    discovery = DiscoveryServer(("0.0.0.0", 8766), Discovery)
    Thread(target=discovery.serve_forever, daemon=True).start()
    print("Receiver discovery is ready on UDP port 8766")
    print(f"Waiting for Rokid photos in {INBOX}")
    ThreadingHTTPServer(("0.0.0.0", PHOTO_PORT), Receiver).serve_forever()
