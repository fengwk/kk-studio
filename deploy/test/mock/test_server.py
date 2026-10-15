#!/usr/bin/env python3
"""Focused protocol tests for the deploy/test HTTP mock (stdlib unittest).

Run from the repository root:

    python3 -m unittest discover -s deploy/test/mock -v
"""

import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

import server


class MockServerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._routes_dir = tempfile.TemporaryDirectory()
        routes_path = Path(cls._routes_dir.name) / "routes.json"
        routes_path.write_text('{"routes": []}', encoding="utf-8")
        cls.server = server.build_server(routes_path, None, host="127.0.0.1", port=0)
        cls.port = cls.server.server_address[1]
        cls._thread = threading.Thread(
            target=cls.server.serve_forever, daemon=True
        )
        cls._thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls._routes_dir.cleanup()

    def request(self, method: str, path: str, body: str | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            connection.request(
                method,
                path,
                body=body,
                headers={"Content-Type": "application/json"} if body else {},
            )
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def post_json(self, path: str, document: dict):
        return self.request("POST", path, body=json.dumps(document))

    def test_chat_completions_returns_deterministic_openai_sse(self):
        status, headers, payload = self.post_json(
            "/v1/chat/completions",
            {"model": "acceptance-stub", "messages": [{"role": "user", "content": "ping"}]},
        )
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Type"), "text/event-stream")
        body = payload.decode("utf-8")
        self.assertTrue(body.endswith("data: [DONE]\n\n"), body)

        events = body.strip().split("\n\n")
        chunks = []
        for event in events[:-1]:
            self.assertTrue(event.startswith("data: "), event)
            chunks.append(json.loads(event.removeprefix("data: ")))
        self.assertGreaterEqual(len(chunks), 2)
        for chunk in chunks:
            self.assertEqual(chunk["id"], "chatcmpl-acceptance-stub-1")
            self.assertEqual(chunk["object"], "chat.completion.chunk")
            self.assertEqual(chunk["model"], "acceptance-stub")
            self.assertEqual(len(chunk["choices"]), 1)
            self.assertEqual(chunk["choices"][0]["index"], 0)

        self.assertEqual(chunks[0]["choices"][0]["delta"]["role"], "assistant")
        text = "".join(
            chunk["choices"][0]["delta"].get("content", "") for chunk in chunks
        )
        self.assertEqual(text, "This is a deterministic offline acceptance stub response.")
        self.assertEqual(chunks[-1]["choices"][0]["finish_reason"], "stop")

    def test_chat_completions_echoes_request_model(self):
        status, _, payload = self.post_json(
            "/v1/chat/completions",
            {"model": "custom-model", "messages": [{"role": "user", "content": "ping"}]},
        )
        self.assertEqual(status, 200)
        first = payload.decode("utf-8").split("\n\n")[0].removeprefix("data: ")
        self.assertEqual(json.loads(first)["model"], "custom-model")

    def test_chat_completions_rejects_invalid_json(self):
        status, _, payload = self.request("POST", "/v1/chat/completions", body="{not json")
        self.assertEqual(status, 500)
        self.assertIn("chat completions mock failed", payload.decode("utf-8"))

    def test_chat_completions_requires_post(self):
        status, _, payload = self.request("GET", "/v1/chat/completions")
        self.assertEqual(status, 404)
        self.assertIn("no mock route", payload.decode("utf-8"))


class OpenCliFakeHubTest(unittest.TestCase):

    TOKEN = "synthetic-opencli-key"

    @classmethod
    def setUpClass(cls):
        cls._temp_dir = tempfile.TemporaryDirectory()
        temp_path = Path(cls._temp_dir.name)
        routes_path = temp_path / "routes.json"
        routes_path.write_text('{"routes": []}', encoding="utf-8")
        fixtures_dir = temp_path / "fixtures"
        fixtures_dir.mkdir()
        (fixtures_dir / "tiny.png").write_bytes(b"\x89PNG\r\n\x1a\nfake-png")
        (fixtures_dir / "tiny.mp4").write_bytes(b"fake-mp4")
        cls.server = server.build_server(
            routes_path,
            fixtures_dir,
            opencli_bearer_token=cls.TOKEN,
            host="127.0.0.1",
            port=0,
        )
        cls.port = cls.server.server_address[1]
        cls._thread = threading.Thread(
            target=cls.server.serve_forever, daemon=True
        )
        cls._thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls._temp_dir.cleanup()

    def request(
        self,
        method: str,
        path: str,
        body: bytes | str | None = None,
        headers: dict | None = None,
    ):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            req_headers = dict(headers or {})
            if isinstance(body, str):
                body = body.encode("utf-8")
            connection.request(method, path, body=body, headers=req_headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_health_check_requires_no_auth(self):
        status, _, payload = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(payload), {"status": "ok"})

    def test_protected_endpoints_reject_missing_or_invalid_bearer_token(self):
        endpoints = [
            ("POST", "/api/resources/uploads", b"fake-body"),
            ("POST", "/api/opencli/execute", json.dumps({"argv": ["chatgpt-agent", "ask"]})),
            ("GET", "/api/executions/fake-1", None),
            ("POST", "/api/executions/fake-1/cancel", None),
            ("GET", "/api/resources/fake/fake-1/output.png", None),
        ]
        invalid_headers_list = [
            {},
            {"Authorization": "Bearer wrong-token"},
            {"Authorization": f"Basic {self.TOKEN}"},
            {"Authorization": self.TOKEN},
        ]
        for method, path, body in endpoints:
            for invalid_headers in invalid_headers_list:
                with self.subTest(method=method, path=path, headers=invalid_headers):
                    status, _, payload = self.request(method, path, body=body, headers=invalid_headers)
                    self.assertEqual(status, 401, f"{method} {path} must reject {invalid_headers}")
                    data = json.loads(payload.decode("utf-8"))
                    self.assertEqual(data.get("error"), "unauthorized")

    def test_protected_endpoints_succeed_with_valid_bearer_token(self):
        auth = {"Authorization": f"Bearer {self.TOKEN}"}

        # 1. Upload resource
        multipart = (
            b'--boundary\r\n'
            b'Content-Disposition: form-data; name="file"; filename="sample.png"\r\n\r\n'
            b'sample-data\r\n--boundary--'
        )
        status, _, payload = self.request(
            "POST",
            "/api/resources/uploads",
            body=multipart,
            headers={**auth, "Content-Type": "multipart/form-data; boundary=boundary"},
        )
        self.assertEqual(status, 201)
        upload_data = json.loads(payload.decode("utf-8"))["data"]
        self.assertTrue(upload_data["items"][0]["resourcePath"].startswith("/resources/fake/upload-"))

        # 2. Execute chatgpt-agent ask
        status, _, payload = self.request(
            "POST",
            "/api/opencli/execute",
            body=json.dumps({"argv": ["chatgpt-agent", "ask"]}),
            headers={**auth, "Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        exec_data = json.loads(payload.decode("utf-8"))["data"]
        exec_id = exec_data["id"]
        self.assertEqual(exec_data["status"], "SUCCEEDED")
        self.assertEqual(len(exec_data["resources"]), 1)
        resource_path = exec_data["resources"][0]["downloadUrl"]

        # 3. Query execution
        status, _, payload = self.request("GET", f"/api/executions/{exec_id}", headers=auth)
        self.assertEqual(status, 200)
        get_data = json.loads(payload.decode("utf-8"))["data"]
        self.assertEqual(get_data["id"], exec_id)

        # 4. Download resource
        status, headers, payload = self.request("GET", resource_path, headers=auth)
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Type"), "image/png")
        self.assertEqual(payload, b"\x89PNG\r\n\x1a\nfake-png")

        # 5. Cancel execution
        status, _, payload = self.request("POST", f"/api/executions/{exec_id}/cancel", headers=auth)
        self.assertEqual(status, 200)
        cancel_data = json.loads(payload.decode("utf-8"))["data"]
        self.assertEqual(cancel_data["id"], exec_id)

        # 6. Execute jimeng-agent video (submit=0)
        status, _, payload = self.request(
            "POST",
            "/api/opencli/execute",
            body=json.dumps({"argv": ["jimeng-agent", "video", "--submit", "0"]}),
            headers={**auth, "Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        jimeng_video = json.loads(payload.decode("utf-8"))["data"]
        self.assertIn('"status":"prepared"', jimeng_video["stdout"])

        # 7. Execute jimeng-agent status
        status, _, payload = self.request(
            "POST",
            "/api/opencli/execute",
            body=json.dumps({"argv": ["jimeng-agent", "status"]}),
            headers={**auth, "Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        jimeng_status = json.loads(payload.decode("utf-8"))["data"]
        self.assertIn('"status":"ready"', jimeng_status["stdout"])


if __name__ == "__main__":
    unittest.main()
