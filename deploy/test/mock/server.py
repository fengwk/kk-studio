#!/usr/bin/env python3
"""Small exact-route HTTP mock used by the isolated Canvas test stack."""

import argparse
import json
import re
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

    def _send_bytes(self, status: int, media_type: str, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", media_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _result(self, data: object, status: int = 200) -> None:
        self._send_json(
            status,
            {
                "status": status,
                "code": "CREATED" if status == 201 else "OK",
                "success": True,
                "data": data,
            },
        )

    def _execution(self, execution_id: str, stdout: str, resources: list[dict]) -> dict:
        return {
            "id": execution_id,
            "status": "SUCCEEDED",
            "stdout": stdout,
            "stdoutTruncated": False,
            "stderr": "",
            "stderrTruncated": False,
            "resources": resources,
        }

    def _handle_opencli_fake(self, path: str, body: bytes) -> bool:
        fixtures = self.server.opencli_fake_fixtures
        if fixtures is None:
            return False

        if self.command == "POST" and path == "/api/resources/uploads":
            disposition = re.search(
                br'Content-Disposition:[^\r\n]*filename="([^"]+)"', body
            )
            filename = (
                disposition.group(1).decode("utf-8", errors="replace")
                if disposition
                else "resource.bin"
            )
            self.server.upload_counter += 1
            resource_path = (
                f"/resources/fake/upload-{self.server.upload_counter}/{filename}"
            )
            self._result(
                {"items": [{"resourcePath": resource_path}]},
                status=201,
            )
            return True

        if self.command == "POST" and path == "/api/opencli/execute":
            request = json.loads(body)
            argv = request.get("argv")
            if not isinstance(argv, list):
                self._send_json(400, {"error": "argv required"})
                return True
            self.server.execution_counter += 1
            execution_id = f"fake-{self.server.execution_counter}"
            resources = []
            if argv[:2] == ["chatgpt-agent", "ask"]:
                resource_path = f"/api/resources/fake/{execution_id}/output.png"
                media = (fixtures / "tiny.png").read_bytes()
                self.server.resources[resource_path] = ("image/png", media)
                resources = [
                    {
                        "fileName": "output.png",
                        "mimeType": "image/png",
                        "size": len(media),
                        "contentUrl": resource_path + "?inline=true",
                        "downloadUrl": resource_path,
                    }
                ]
                stdout = "[]"
            elif argv[:2] == ["jimeng-agent", "video"]:
                submit_index = argv.index("--submit")
                submitted = argv[submit_index + 1] == "1"
                stdout = json.dumps(
                    [
                        {
                            "status": "submitted" if submitted else "prepared",
                            "submitted": submitted,
                            "assetId": "0123456789abcdef",
                        }
                    ],
                    separators=(",", ":"),
                )
            elif argv[:2] == ["jimeng-agent", "status"]:
                resource_path = f"/api/resources/fake/{execution_id}/output.mp4"
                media = (fixtures / "tiny.mp4").read_bytes()
                self.server.resources[resource_path] = ("video/mp4", media)
                resources = [
                    {
                        "fileName": "output.mp4",
                        "mimeType": "video/mp4",
                        "size": len(media),
                        "contentUrl": resource_path + "?inline=true",
                        "downloadUrl": resource_path,
                    }
                ]
                stdout = '[{"status":"ready","downloaded":true}]'
            else:
                self._send_json(400, {"error": "unsupported fake argv", "argv": argv})
                return True
            execution = self._execution(execution_id, stdout, resources)
            self.server.executions[execution_id] = execution
            self._result(execution)
            return True

        execution_match = re.fullmatch(r"/api/executions/([A-Za-z0-9-]+)", path)
        if self.command == "GET" and execution_match:
            execution = self.server.executions.get(execution_match.group(1))
            if execution is None:
                self._send_json(404, {"error": "execution not found"})
            else:
                self._result(execution)
            return True

        if self.command == "POST" and path.endswith("/cancel"):
            execution_id = path.removeprefix("/api/executions/").removesuffix("/cancel")
            execution = self.server.executions.get(execution_id)
            if execution is None:
                self._send_json(404, {"error": "execution not found"})
            else:
                self._result(execution)
            return True

        if self.command == "GET" and path in self.server.resources:
            media_type, media = self.server.resources[path]
            self._send_bytes(200, media_type, media)
            return True

        return False

    def _handle(self) -> None:
        path = urlsplit(self.path).path
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length) if content_length else b""

        if path == "/health":
            self._send_json(200, {"status": "ok"})
            return

        try:
            if self._handle_opencli_fake(path, body):
                return
        except (OSError, ValueError, json.JSONDecodeError) as error:
            self._send_json(500, {"error": f"opencli fake failed: {error}"})
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
    parser.add_argument("--opencli-fake-fixtures", type=Path)
    args = parser.parse_args()

    load_routes(args.routes)
    server = ThreadingHTTPServer(("0.0.0.0", 8080), MockHandler)
    server.routes_path = args.routes
    server.opencli_fake_fixtures = args.opencli_fake_fixtures
    server.execution_counter = 0
    server.upload_counter = 0
    server.executions = {}
    server.resources = {}
    server.serve_forever()


if __name__ == "__main__":
    main()
