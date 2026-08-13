#!/usr/bin/env python3
"""Small exact-route HTTP mock used by the isolated Canvas test stack."""

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

# Deterministic OpenAI Chat Completions stub: the offline dev-seed agent
# (default-assistant -> stub/acceptance-stub) resolves to this endpoint inside
# the Compose network (http://http-mock:8080/v1).
CHAT_COMPLETIONS_STUB_TEXT = "This is a deterministic offline acceptance stub response."
CHAT_COMPLETIONS_ID = "chatcmpl-acceptance-stub-1"
CHAT_COMPLETIONS_CREATED = 1700000000


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


def chat_completions_chunk(model: str, delta: dict, finish_reason: str | None) -> bytes:
    """OpenAI Chat Completions SSE `data:` event for one streamed chunk."""
    chunk = {
        "id": CHAT_COMPLETIONS_ID,
        "object": "chat.completion.chunk",
        "created": CHAT_COMPLETIONS_CREATED,
        "model": model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
    }
    return b"data: " + json.dumps(chunk, separators=(",", ":")).encode() + b"\n\n"


def chat_completions_sse(model: str) -> bytes:
    """Deterministic OpenAI Chat Completions SSE body: role delta, content deltas,
    stop chunk, then the `[DONE]` sentinel. Content chunks are derived from
    CHAT_COMPLETIONS_STUB_TEXT (4 words per chunk, space-delimited)."""
    words = CHAT_COMPLETIONS_STUB_TEXT.split()
    pieces = [" ".join(words[index : index + 4]) for index in range(0, len(words), 4)]
    content_deltas = [{"content": piece + " "} for piece in pieces[:-1]] + [
        {"content": pieces[-1]}
    ]
    events = [
        chat_completions_chunk(model, {"role": "assistant", "content": ""}, None),
        *[chat_completions_chunk(model, delta, None) for delta in content_deltas],
        chat_completions_chunk(model, {}, "stop"),
    ]
    return b"".join(events) + b"data: [DONE]\n\n"


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

    def _send_sse(self, status: int, body: bytes) -> None:
        """Stream a body without Content-Length: the connection closes after the
        handler returns, so clients read the SSE stream until EOF."""
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
            self.wfile.flush()

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

    def _handle_chat_completions(self, path: str, body: bytes) -> bool:
        """Built-in OpenAI Chat Completions stub: deterministic SSE stream for
        langchain4j's OpenAI streaming client."""
        if self.command != "POST" or path != "/v1/chat/completions":
            return False
        request = json.loads(body) if body else {}
        if not isinstance(request, dict):
            raise ValueError("chat completions request must be a JSON object")
        model = request.get("model")
        if not isinstance(model, str) or not model:
            model = "acceptance-stub"
        self._send_sse(200, chat_completions_sse(model))
        return True

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
            if self._handle_chat_completions(path, body):
                return
        except (OSError, ValueError, json.JSONDecodeError) as error:
            self._send_json(500, {"error": f"chat completions mock failed: {error}"})
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


def build_server(
    routes_path: Path,
    opencli_fake_fixtures: Path | None = None,
    *,
    host: str = "0.0.0.0",
    port: int = 8080,
) -> ThreadingHTTPServer:
    """Create a configured mock server; tests pass port 0 for an ephemeral port."""
    load_routes(routes_path)
    server = ThreadingHTTPServer((host, port), MockHandler)
    server.routes_path = routes_path
    server.opencli_fake_fixtures = opencli_fake_fixtures
    server.execution_counter = 0
    server.upload_counter = 0
    server.executions = {}
    server.resources = {}
    return server


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--routes", required=True, type=Path)
    parser.add_argument("--opencli-fake-fixtures", type=Path)
    args = parser.parse_args()

    server = build_server(args.routes, args.opencli_fake_fixtures)
    server.serve_forever()


if __name__ == "__main__":
    main()
