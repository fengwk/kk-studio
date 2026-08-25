"""Tests for MiniMax-only E2E credential synchronization."""

import json
import unittest

from scripts.e2e.sync_provider_credentials import (
    DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS,
    DEFAULT_MODEL_CALL_TIMEOUT_MILLIS,
    build_payload,
    normalize_minimax_base_url,
    sync_minimax,
)


def minimax_row(**overrides):
    """Return the deterministic MiniMax seed provider representation."""
    row = {
        "name": "minimax",
        "description": "MiniMax (OpenAI Responses).",
        "providerType": "openai_response",
        "baseUrl": None,
        "version": "0",
        "modelCallTimeoutMillis": 1800000,
        "modelCallIdleTimeoutMillis": 120000,
    }
    row.update(overrides)
    return row


def routed_http(rows, calls):
    """Return a urlopen-compatible fake recording each GET or PUT."""

    def fake(url, data=None, timeout=30, headers=None):
        payload = json.loads(data) if data is not None else None
        calls.append((url, payload, headers))
        if payload is None:
            return {"data": {"results": rows}}
        return {"data": {"configured": True, "baseUrl": payload["baseUrl"]}}

    return fake


class TestNormalizeMiniMaxBaseUrl(unittest.TestCase):
    """MiniMax endpoint normalization always uses exactly one /v1 suffix."""

    def test_removes_trailing_slashes_and_appends_v1(self):
        self.assertEqual(
            "https://api.minimax.example/v1",
            normalize_minimax_base_url("https://api.minimax.example///"),
        )

    def test_keeps_existing_v1_after_trailing_slashes_are_removed(self):
        self.assertEqual(
            "https://api.minimax.example/v1",
            normalize_minimax_base_url("https://api.minimax.example/v1///"),
        )


class TestBuildPayload(unittest.TestCase):
    """The update preserves seed semantics and current timeout configuration."""

    def test_inherits_current_timeouts(self):
        payload = build_payload(
            "https://api.minimax.example/v1",
            "test-secret",
            {
                "modelCallTimeoutMillis": 600000,
                "modelCallIdleTimeoutMillis": 50000,
                "version": "7",
            },
        )

        self.assertEqual("7", payload["expectedVersion"])
        self.assertEqual(600000, payload["modelCallTimeoutMillis"])
        self.assertEqual(50000, payload["modelCallIdleTimeoutMillis"])

    def test_defaults_missing_timeouts(self):
        payload = build_payload(
            "https://api.minimax.example/v1",
            "test-secret",
            {"version": 3},
        )

        self.assertEqual("3", payload["expectedVersion"])
        self.assertEqual(
            DEFAULT_MODEL_CALL_TIMEOUT_MILLIS, payload["modelCallTimeoutMillis"]
        )
        self.assertEqual(
            DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS,
            payload["modelCallIdleTimeoutMillis"],
        )


class TestSyncMiniMax(unittest.TestCase):
    """Credential pair validation happens before any backend request."""

    def test_no_env_skips_without_http(self):
        calls = []

        lines = sync_minimax(
            "http://backend", env={}, urlopen=routed_http([], calls)
        )

        self.assertEqual(["minimax: skip (no credential pair)"], lines)
        self.assertEqual([], calls)

    def test_incomplete_pair_fails_without_http(self):
        for env in (
            {"TEST_MINIMAX_BASE_URL": "https://api.minimax.example"},
            {"TEST_MINIMAX_API_KEY": "test-secret"},
        ):
            with self.subTest(env=env):
                calls = []

                with self.assertRaisesRegex(ValueError, "TEST_MINIMAX_BASE_URL"):
                    sync_minimax(
                        "http://backend",
                        env=env,
                        urlopen=routed_http([], calls),
                    )

                self.assertEqual([], calls)

    def test_complete_pair_updates_minimax_by_name(self):
        calls = []
        secret = "test-minimax-secret"

        lines = sync_minimax(
            "http://backend/",
            env={
                "TEST_MINIMAX_BASE_URL": "https://api.minimax.example/",
                "TEST_MINIMAX_API_KEY": secret,
            },
            urlopen=routed_http([minimax_row()], calls),
        )

        self.assertEqual(2, len(calls))
        self.assertTrue(calls[0][0].endswith("/api/ai/catalog/providers?pageNumber=1&pageSize=50"))
        put_url, payload, headers = calls[1]
        self.assertEqual("http://backend/api/ai/catalog/providers/minimax", put_url)
        self.assertEqual("application/json", headers["Content-Type"])
        self.assertNotIn("name", payload)
        self.assertEqual("openai_response", payload["providerType"])
        self.assertEqual("https://api.minimax.example/v1", payload["baseUrl"])
        self.assertEqual(secret, payload["credential"])
        self.assertEqual("0", payload["expectedVersion"])
        self.assertEqual(
            ["minimax: configured=True baseUrl_set=True"],
            lines,
        )

    def test_missing_seed_provider_fails_without_put(self):
        calls = []

        with self.assertRaisesRegex(RuntimeError, "name=minimax"):
            sync_minimax(
                "http://backend",
                env={
                    "TEST_MINIMAX_BASE_URL": "https://api.minimax.example",
                    "TEST_MINIMAX_API_KEY": "test-secret",
                },
                urlopen=routed_http([minimax_row(name="unexpected")], calls),
            )

        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_missing_seed_version_fails_without_put(self):
        calls = []

        with self.assertRaisesRegex(RuntimeError, "required version"):
            sync_minimax(
                "http://backend",
                env={
                    "TEST_MINIMAX_BASE_URL": "https://api.minimax.example",
                    "TEST_MINIMAX_API_KEY": "test-secret",
                },
                urlopen=routed_http([minimax_row(version=None)], calls),
            )

        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_status_output_never_contains_secret(self):
        calls = []
        secret = "test-secret-must-not-appear"

        lines = sync_minimax(
            "http://backend",
            env={
                "TEST_MINIMAX_BASE_URL": "https://api.minimax.example",
                "TEST_MINIMAX_API_KEY": secret,
            },
            urlopen=routed_http([minimax_row()], calls),
        )

        self.assertNotIn(secret, "\n".join(lines))

    def test_unsupported_openai_environment_has_no_effect_or_http(self):
        """Unsupported provider variables do not affect the current synchronization contract."""
        calls = []

        lines = sync_minimax(
            "http://backend",
            env={
                "TEST_OPENAI_BASE_URL": "https://api.openai.example",
                "TEST_OPENAI_API_KEY": "test-openai-secret",
            },
            urlopen=routed_http([], calls),
        )

        self.assertEqual(["minimax: skip (no credential pair)"], lines)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
