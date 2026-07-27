#!/usr/bin/env python3
"""Paired, LAN-only receiver for Photo to Mac."""
from datetime import datetime
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import BaseRequestHandler, ThreadingUDPServer
from threading import Thread
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
DISCOVERY_PREFIX = b"ROKID_PHOTO_BRIDGE_DISCOVER 2"


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
        proof = hmac.new(TOKEN.encode("ascii"), fields[2], hashlib.sha256).hexdigest()
        response = f"ROKID_PHOTO_BRIDGE 2 8765 {proof}".encode("ascii")
        print(f"Authenticated discovery from {self.client_address[0]}", flush=True)
        socket.sendto(response, self.client_address)


class DiscoveryServer(ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True


class Receiver(BaseHTTPRequestHandler):
    def do_GET(self):
        if urlparse(self.path).path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ready\n")

    def do_POST(self):
        if urlparse(self.path).path != "/upload":
            self.send_error(404)
            return
        supplied_token = self.headers.get("X-Photo-Token", "")
        if not TOKEN or not hmac.compare_digest(supplied_token, TOKEN):
            self.send_error(403, "Not paired")
            return
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BYTES:
            self.send_error(413, "Photo is empty or too large")
            return
        body = self.rfile.read(length)
        if (
            len(body) != length
            or self.headers.get_content_type() != "image/jpeg"
            or not body.startswith(b"\xff\xd8")
            or not body.endswith(b"\xff\xd9")
        ):
            self.send_error(415, "Only complete JPEG photos are accepted")
            return
        supplied = parse_qs(urlparse(self.path).query).get("filename", [""])[0]
        safe_name = Path(supplied).name if supplied.endswith(".jpg") else ""
        if not safe_name:
            safe_name = "rokid-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".jpg"
        INBOX.mkdir(parents=True, exist_ok=True)
        destination = INBOX / safe_name
        destination.write_bytes(body)
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
    ThreadingHTTPServer(("0.0.0.0", 8765), Receiver).serve_forever()
