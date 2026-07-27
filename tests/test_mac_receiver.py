import hashlib
import hmac
import socket
import socketserver
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import mac_receiver


TOKEN = "0123456789abcdef0123456789abcdef"
JPEG = b"\xff\xd8photo-data\xff\xd9"


class LocalHTTPServer(mac_receiver.ThreadingHTTPServer):
    daemon_threads = True

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name = self.server_address[0]
        self.server_port = self.server_address[1]


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        mac_receiver.INBOX = Path(self.temporary.name)
        mac_receiver.TOKEN = TOKEN
        self.server = LocalHTTPServer(("127.0.0.1", 0), mac_receiver.Receiver)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = (
            f"http://127.0.0.1:{self.server.server_address[1]}"
            "/upload?filename=test.jpg"
        )

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temporary.cleanup()

    def upload(self, body=JPEG, token=TOKEN):
        return urlopen(
            Request(
                self.endpoint,
                data=body,
                headers={"Content-Type": "image/jpeg", "X-Photo-Token": token},
            ),
            timeout=2,
        )

    def raw_request(self, content_length, body):
        request = (
            b"POST /upload?filename=partial.jpg HTTP/1.1\r\n"
            b"Host: 127.0.0.1\r\n"
            b"Content-Type: image/jpeg\r\n"
            + f"X-Photo-Token: {TOKEN}\r\n".encode("ascii")
            + f"Content-Length: {content_length}\r\n".encode("ascii")
            + b"Connection: close\r\n\r\n"
            + body
        )
        with socket.create_connection(self.server.server_address, timeout=2) as client:
            client.sendall(request)
            client.shutdown(socket.SHUT_WR)
            response = bytearray()
            while True:
                chunk = client.recv(4096)
                if not chunk:
                    break
                response.extend(chunk)
        return bytes(response)

    def test_requires_pairing_token(self):
        with self.assertRaises(HTTPError) as caught:
            self.upload(token="wrong")
        self.assertEqual(caught.exception.code, 403)
        caught.exception.close()
        self.assertEqual(list(mac_receiver.INBOX.iterdir()), [])

    def test_rejects_non_jpeg(self):
        with self.assertRaises(HTTPError) as caught:
            self.upload(body=b"not-a-jpeg")
        self.assertEqual(caught.exception.code, 415)
        caught.exception.close()

    def test_rejects_bad_content_length(self):
        response = self.raw_request("invalid", b"")
        self.assertIn(b" 400 ", response.split(b"\r\n", 1)[0])

    def test_rejects_interrupted_upload(self):
        response = self.raw_request(100, JPEG)
        self.assertIn(b" 400 ", response.split(b"\r\n", 1)[0])
        self.assertEqual(list(mac_receiver.INBOX.iterdir()), [])

    def test_saves_atomically_without_overwriting(self):
        self.assertEqual(self.upload().status, 201)
        self.assertEqual(self.upload().status, 201)
        self.assertEqual((mac_receiver.INBOX / "test.jpg").read_bytes(), JPEG)
        self.assertEqual((mac_receiver.INBOX / "test-2.jpg").read_bytes(), JPEG)
        self.assertEqual(list(mac_receiver.INBOX.glob("*.part")), [])

    def test_connection_timeout_is_bounded(self):
        self.assertEqual(mac_receiver.Receiver.timeout, 30)


class DiscoveryTests(unittest.TestCase):
    def setUp(self):
        mac_receiver.TOKEN = TOKEN
        self.server = mac_receiver.DiscoveryServer(
            ("127.0.0.1", 0), mac_receiver.Discovery
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_authenticated_discovery(self):
        nonce = b"00112233445566778899aabbccddeeff"
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(1)
            client.sendto(
                b"ROKID_PHOTO_BRIDGE_DISCOVER 2 " + nonce,
                self.server.server_address,
            )
            response, _ = client.recvfrom(256)
        fields = response.split(b" ")
        expected = hmac.new(TOKEN.encode("ascii"), nonce, hashlib.sha256).hexdigest()
        self.assertEqual(fields[:3], [b"ROKID_PHOTO_BRIDGE", b"2", b"8765"])
        self.assertTrue(hmac.compare_digest(fields[3].decode("ascii"), expected))

    def test_legacy_discovery_is_ignored(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(0.2)
            client.sendto(
                b"ROKID_PHOTO_BRIDGE_DISCOVER 1", self.server.server_address
            )
            with self.assertRaises(socket.timeout):
                client.recvfrom(256)


if __name__ == "__main__":
    unittest.main()
