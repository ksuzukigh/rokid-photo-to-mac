#!/usr/bin/env python3
"""Temporary, LAN-only receiver for Photo to Mac."""
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import BaseRequestHandler, ThreadingUDPServer
from threading import Thread
from urllib.parse import parse_qs, urlparse

INBOX = Path.home() / "Pictures" / "Rokid Inbox"
MAX_BYTES = 15 * 1024 * 1024
DISCOVERY_REQUEST = b"ROKID_PHOTO_BRIDGE_DISCOVER 1"
DISCOVERY_RESPONSE = b"ROKID_PHOTO_BRIDGE 1 8765"


class Discovery(BaseRequestHandler):
    def handle(self):
        data, socket = self.request
        if data.strip() == DISCOVERY_REQUEST:
            print(f"Discovery request from {self.client_address[0]}", flush=True)
            socket.sendto(DISCOVERY_RESPONSE, self.client_address)


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
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_BYTES:
            self.send_error(413, "Photo is empty or too large")
            return
        body = self.rfile.read(length)
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
    INBOX.mkdir(parents=True, exist_ok=True)
    discovery = DiscoveryServer(("0.0.0.0", 8766), Discovery)
    Thread(target=discovery.serve_forever, daemon=True).start()
    print("Receiver discovery is ready on UDP port 8766")
    print(f"Waiting for Rokid photos in {INBOX}")
    ThreadingHTTPServer(("0.0.0.0", 8765), Receiver).serve_forever()
