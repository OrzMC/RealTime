#!/usr/bin/env python3
"""Minimal Minecraft RCON client for CI smoke tests."""
import socket
import struct
import sys


def receive_exact(sock: socket.socket, size: int) -> bytes:
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError("RCON server closed the connection")
        data += chunk
    return data


def packet(sock: socket.socket, request_id: int, kind: int, body: str) -> tuple[int, int, str]:
    payload = struct.pack("<ii", request_id, kind) + body.encode() + b"\0\0"
    sock.sendall(struct.pack("<i", len(payload)) + payload)
    size = struct.unpack("<i", receive_exact(sock, 4))[0]
    data = receive_exact(sock, size)
    response_id, response_type = struct.unpack("<ii", data[:8])
    return response_id, response_type, data[8:-2].decode(errors="replace")


def main() -> int:
    host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], " ".join(sys.argv[4:])
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.settimeout(5)
        request_id = 42
        response_id, _, _ = packet(sock, request_id, 3, password)
        if response_id != request_id:
            raise RuntimeError("RCON authentication failed")
        response_id, _, output = packet(sock, request_id + 1, 2, command)
        if response_id != request_id + 1:
            raise RuntimeError("RCON command response ID mismatch")
        print(output)
        if "RealTime" not in output:
            raise RuntimeError(f"Unexpected /{command} output: {output!r}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
