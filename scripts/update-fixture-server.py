#!/usr/bin/env python3
"""Serves a fake Hangar payload for the update smoke test.

Two responses matter:

* ``/api/v1/projects/RealTime/versions?limit=20`` — in the shape Hangar uses,
  pointing at a jar this process also serves;
* ``/RealTime-<version>.jar`` — a real plugin jar, so a server that swaps it in
  still starts.

``--corrupt-digest`` publishes a version whose checksum does not match the bytes,
which is how the smoke test proves an unverified download is refused.
"""
import argparse
import hashlib
import json
import pathlib
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    jar: bytes = b""
    version: str = "9.9.9"
    corrupt_digest: bool = False

    def log_message(self, fmt, *args):  # keep CI logs readable
        print("fixture: " + fmt % args, flush=True)

    def do_GET(self):  # noqa: N802 - http.server API
        path = self.path.split("?", 1)[0]
        if path == "/api/v1/projects/RealTime/versions":
            self._json(self._payload())
            return
        if path == f"/RealTime-{self.version}.jar":
            self.send_response(200)
            self.send_header("Content-Type", "application/java-archive")
            self.send_header("Content-Length", str(len(self.jar)))
            self.end_headers()
            self.wfile.write(self.jar)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _payload(self):
        digest = hashlib.sha256(self.jar).hexdigest()
        if self.corrupt_digest:
            digest = "0" * 64
        return {
            "result": [
                {
                    "name": self.version,
                    "channel": {"name": "Release"},
                    "platformDependencies": {"PAPER": ["26.3"]},
                    "downloads": {
                        "PAPER": {
                            "fileInfo": {
                                "name": f"RealTime-{self.version}.jar",
                                "sizeBytes": len(self.jar),
                                "sha256Hash": digest,
                            },
                            "externalUrl": None,
                            "downloadUrl": f"http://127.0.0.1:{self.server.server_port}/RealTime-{self.version}.jar",
                        }
                    },
                }
            ]
        }

    def _json(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=pathlib.Path)
    parser.add_argument("--version", default="9.9.9")
    parser.add_argument("--corrupt-digest", action="store_true")
    parser.add_argument("--port-file", type=pathlib.Path, help="writes the chosen port here")
    arguments = parser.parse_args()

    Handler.jar = arguments.jar.read_bytes()
    Handler.version = arguments.version
    Handler.corrupt_digest = arguments.corrupt_digest

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    if arguments.port_file:
        arguments.port_file.write_text(str(server.server_port))
    print(f"fixture: serving {arguments.version} on 127.0.0.1:{server.server_port}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    threading.current_thread().name = "update-fixture"
    raise SystemExit(main())
