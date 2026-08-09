#!/usr/bin/env python3
"""Small exact-route HTTP mock used by the isolated Canvas test stack."""

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def load_routes(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as route_file:
        document = json.load(route_file)

    routes = document.get("routes")
    if not isinstance(routes, list):
        raise ValueError("routes.json must contain a 'routes' array")

    for route in routes:
        if not isinstance(route, dict):
            raise ValueError("each route must be an object")
        if not isinstance(route.get("method"), str) or not isinstance(
            route.get("path"), str
        ):
            raise ValueError("each route requires string 'method' and 'path'")
        if not route["path"].startswith("/"):
            raise ValueError("route paths must start with '/'")

    return routes


class MockHandler(BaseHTTPRequestHandler):
    server_version = "CanvasTestMock/1.0"

    def _send_json(self, status: int, document: object) -> None:
        body = json.dumps(document, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _handle(self) -> None:
        path = urlsplit(self.path).path
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length:
            self.rfile.read(content_length)

        if path == "/health":
            self._send_json(200, {"status": "ok"})
            return

        try:
            routes = load_routes(self.server.routes_path)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            self._send_json(500, {"error": f"invalid route configuration: {error}"})
            return

        route = next(
            (
                candidate
                for candidate in routes
                if candidate["method"].upper() == self.command
                and candidate["path"] == path
            ),
            None,
        )
        if route is None:
            self._send_json(
                404, {"error": "no mock route", "method": self.command, "path": path}
            )
            return

        status = int(route.get("status", 200))
        headers = route.get("headers", {})
        if not isinstance(headers, dict):
            self._send_json(500, {"error": "route headers must be an object"})
            return

        if "json" in route:
            body = json.dumps(route["json"], separators=(",", ":")).encode()
            headers = {"Content-Type": "application/json", **headers}
        else:
            body_value = route.get("body", "")
            if not isinstance(body_value, str):
                self._send_json(500, {"error": "route body must be a string"})
                return
            body = body_value.encode()

        self.send_response(status)
        for name, value in headers.items():
            self.send_header(str(name), str(value))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    do_DELETE = _handle
    do_GET = _handle
    do_HEAD = _handle
    do_PATCH = _handle
    do_POST = _handle
    do_PUT = _handle


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--routes", required=True, type=Path)
    args = parser.parse_args()

    load_routes(args.routes)
    server = ThreadingHTTPServer(("0.0.0.0", 8080), MockHandler)
    server.routes_path = args.routes
    server.serve_forever()


if __name__ == "__main__":
    main()
