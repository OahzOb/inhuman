#!/usr/bin/env python3
"""Serve the update manifest/APK and collect diagnostic logs.

This is deliberately a single-file, standard-library server for a LAN or
Tailscale link, not a release service. It has no accounts and no database:

    GET  /update.json   the manifest written by tools/release-apk.ps1
    GET  /...           any file under --dist (the APK)
    POST /log           the app's diagnostic log, saved under --logs

There is no authentication. Bind it only on a network you trust, and prefer
Tailscale when the phone is not on the same LAN. Anyone who can reach the port
can read the APK/manifest and upload a file, so do not expose it to the public
internet.
"""
from __future__ import annotations

import argparse
import datetime
import shutil
import socket
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_LOG_BYTES = 8 * 1024 * 1024


def local_ipv4_addresses() -> list[str]:
    addresses: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in addresses and not ip.startswith("127."):
                addresses.append(ip)
    except OSError:
        pass
    return addresses


def make_handler(directory: Path, logs: Path):
    class Handler(SimpleHTTPRequestHandler):
        # HTTP/1.1 makes the response length explicit and lets the client reuse
        # the connection. The explicit flush in copyfile is not cosmetic: with
        # the default handler a large APK over the emulator's slow link left
        # the last few bytes in the Python buffer, and OkHttp correctly called
        # that "unexpected end of stream".
        protocol_version = "HTTP/1.1"

        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(directory), **kwargs)

        def copyfile(self, source, outputfile) -> None:
            shutil.copyfileobj(source, outputfile, length=64 * 1024)
            outputfile.flush()

        def do_POST(self) -> None:  # noqa: N802 (http.server API)
            if self.path.split("?", 1)[0] != "/log":
                self.send_error(404, "only /log is writable")
                return
            raw_length = self.headers.get("Content-Length")
            if raw_length is None:
                self.send_error(411, "Content-Length required")
                return
            try:
                length = int(raw_length)
            except ValueError:
                self.send_error(400, "bad Content-Length")
                return
            if length <= 0:
                self.send_error(400, "empty log")
                return
            if length > MAX_LOG_BYTES:
                self.send_error(413, "log too large")
                return
            body = self.rfile.read(length)
            stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            target = logs / f"{stamp}.log"
            suffix = 1
            while target.exists():
                target = logs / f"{stamp}-{suffix}.log"
                suffix += 1
            target.write_bytes(body)
            print(f"log {len(body)} bytes -> {target}", flush=True)
            payload = target.name.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, fmt: str, *args) -> None:
            print(f"{self.address_string()} {fmt % args}", flush=True)

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    parser.add_argument("--logs", type=Path, default=Path("logs"))
    args = parser.parse_args()

    dist = args.dist.resolve()
    logs = args.logs.resolve()
    dist.mkdir(parents=True, exist_ok=True)
    logs.mkdir(parents=True, exist_ok=True)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(dist, logs))
    print(f"serving {dist}", flush=True)
    print(f"logs    {logs}", flush=True)
    for ip in local_ipv4_addresses():
        print(f"  http://{ip}:{args.port}", flush=True)
    print(
        "If Tailscale is up, use this machine's 100.x address. "
        "Do not expose this port to the internet.",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
