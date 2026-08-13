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


if __name__ == "__main__":
    unittest.main()
