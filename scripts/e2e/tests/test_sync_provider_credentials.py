"""Tests for four-provider E2E credential synchronization."""

import json
import unittest

from scripts.e2e.sync_provider_credentials import (
    DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS,
    DEFAULT_MODEL_CALL_TIMEOUT_MILLIS,
    PROVIDER_SPECS,
    TEST_DEEPSEEK_API_KEY,
    TEST_DEEPSEEK_BASE_URL,
    TEST_GEMINI_API_KEY,
    TEST_GEMINI_BASE_URL,
    TEST_MINIMAX_ANTHROPIC_API_KEY,
    TEST_MINIMAX_ANTHROPIC_BASE_URL,
    TEST_OPENAI_API_KEY,
    TEST_OPENAI_BASE_URL,
    build_payload,
    normalize_base_url,
    normalize_minimax_base_url,
    sync_provider_credentials,
)
from scripts.reliability.sync_minimax_credentials import (
    TEST_MINIMAX_API_KEY,
    TEST_MINIMAX_BASE_URL,
    sync_minimax_credentials,
)


def seed_rows(**overrides):
    """Return four default seed rows representing seeded providers in E2E DB."""
    defaults = {
        "google": {
            "name": "google",
            "description": "Google Gemini.",
            "providerType": "google",
            "baseUrl": None,
            "version": "0",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        },
        "openai": {
            "name": "openai",
            "description": "OpenAI (OpenAI Responses).",
            "providerType": "openai_response",
            "baseUrl": None,
            "version": "0",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        },
        "minimax-anthropic": {
            "name": "minimax-anthropic",
            "description": "MiniMax (Anthropic).",
            "providerType": "anthropic",
            "baseUrl": None,
            "version": "0",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        },
        "deepseek": {
            "name": "deepseek",
            "description": "DeepSeek (OpenAI Chat Completions).",
            "providerType": "openai",
            "baseUrl": None,
            "version": "0",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        },
    }
    for k, v in overrides.items():
        if v is None:
            defaults.pop(k, None)
        elif k in defaults:
            defaults[k].update(v)
        else:
            defaults[k] = v
    return list(defaults.values())


def routed_http(rows, calls):
    """Return a urlopen-compatible fake recording each GET or PUT."""

    def fake(url, data=None, timeout=30, headers=None):
        payload = json.loads(data) if data is not None else None
        calls.append((url, payload, headers))
        if payload is None:
            return {"data": {"results": rows}}
        return {"data": {"configured": True, "baseUrl": payload["baseUrl"]}}

    return fake


class TestNormalizeBaseUrl(unittest.TestCase):
    """Verify URL normalization and safety rules for all provider types."""

    def test_google_normalization_removes_trailing_slashes_only(self):
        self.assertEqual(
            "https://generativelanguage.googleapis.com",
            normalize_base_url(
                "https://generativelanguage.googleapis.com///",
                requires_v1_suffix=False,
                env_name=TEST_GEMINI_BASE_URL,
                provider_name="google",
            ),
        )

    def test_openai_normalization_ensures_v1_suffix(self):
        self.assertEqual(
            "https://api.openai.example/v1",
            normalize_base_url(
                "https://api.openai.example///",
                requires_v1_suffix=True,
                env_name=TEST_OPENAI_BASE_URL,
                provider_name="openai",
            ),
        )
        self.assertEqual(
            "https://api.openai.example/v1",
            normalize_base_url(
                "https://api.openai.example/v1///",
                requires_v1_suffix=True,
                env_name=TEST_OPENAI_BASE_URL,
                provider_name="openai",
            ),
        )

    def test_minimax_anthropic_normalization_preserves_custom_path_without_adding_v1(self):
        self.assertEqual(
            "https://api.minimax.example/anthropic/v1",
            normalize_base_url(
                "https://api.minimax.example/anthropic/v1///",
                requires_v1_suffix=False,
                env_name=TEST_MINIMAX_ANTHROPIC_BASE_URL,
                provider_name="minimax-anthropic",
            ),
        )

    def test_deepseek_normalization_ensures_v1_suffix(self):
        self.assertEqual(
            "https://api.deepseek.example/v1",
            normalize_base_url(
                "https://api.deepseek.example///",
                requires_v1_suffix=True,
                env_name=TEST_DEEPSEEK_BASE_URL,
                provider_name="deepseek",
            ),
        )
        self.assertEqual(
            "https://api.deepseek.example/v1",
            normalize_base_url(
                "https://api.deepseek.example/v1///",
                requires_v1_suffix=True,
                env_name=TEST_DEEPSEEK_BASE_URL,
                provider_name="deepseek",
            ),
        )

    def test_legacy_minimax_helper_compat(self):
        self.assertEqual(
            "https://api.minimax.example/v1",
            normalize_minimax_base_url("https://api.minimax.example///"),
        )

    def test_invalid_urls_rejected_without_leaking_url_or_secret(self):
        invalid_cases = [
            ("", "empty"),
            ("ftp://api.example.com", "scheme"),
            ("http:///missing-host", "host"),
            ("https://user:pass@api.example.com/v1", "credentials"),
            ("https://api.example.com/v1?token=secret123", "query"),
            ("https://api.example.com/v1#hash_token", "fragment"),
            ("https://api.example.com:not-a-port/v1", "port"),
        ]
        for url, reason in invalid_cases:
            with self.subTest(reason=reason, url=url):
                with self.assertRaises(ValueError) as ctx:
                    normalize_base_url(
                        url,
                        requires_v1_suffix=True,
                        env_name="TEST_OPENAI_BASE_URL",
                        provider_name="openai",
                    )
                msg = str(ctx.exception)
                self.assertIn("openai", msg)
                self.assertIn("TEST_OPENAI_BASE_URL", msg)
                if url:
                    self.assertNotIn(url, msg)
                self.assertNotIn("secret123", msg)
                self.assertNotIn("hash_token", msg)


class TestBuildPayload(unittest.TestCase):
    """Payload construction preserves seed configuration, version, and timeouts."""

    def test_inherits_current_timeouts(self):
        payload = build_payload(
            "https://api.example.com/v1",
            "test-secret",
            {
                "description": "Custom Description",
                "providerType": "openai_response",
                "modelCallTimeoutMillis": 600000,
                "modelCallIdleTimeoutMillis": 50000,
                "version": "7",
            },
        )

        self.assertEqual("Custom Description", payload["description"])
        self.assertEqual("openai_response", payload["providerType"])
        self.assertEqual("7", payload["expectedVersion"])
        self.assertEqual(600000, payload["modelCallTimeoutMillis"])
        self.assertEqual(50000, payload["modelCallIdleTimeoutMillis"])

    def test_defaults_missing_timeouts(self):
        payload = build_payload(
            "https://api.example.com/v1",
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


class TestSyncProviderCredentials(unittest.TestCase):
    """Verify behavior of sync_provider_credentials."""

    def test_no_env_skips_all_without_http(self):
        calls = []
        lines = sync_provider_credentials(
            "http://backend", env={}, urlopen=routed_http([], calls)
        )

        expected = [f"{s.name}: skip (no credential pair)" for s in PROVIDER_SPECS]
        self.assertEqual(expected, lines)
        self.assertEqual([], calls)

    def test_half_pair_fails_before_http(self):
        for spec in PROVIDER_SPECS:
            for env in (
                {spec.base_url_env: "https://api.example.com"},
                {spec.api_key_env: "test-secret"},
            ):
                with self.subTest(spec=spec.name, env=list(env.keys())[0]):
                    calls = []
                    with self.assertRaises(ValueError) as ctx:
                        sync_provider_credentials(
                            "http://backend",
                            env=env,
                            urlopen=routed_http([], calls),
                        )
                    msg = str(ctx.exception)
                    self.assertIn(spec.name, msg)
                    self.assertIn(spec.base_url_env, msg)
                    self.assertIn(spec.api_key_env, msg)
                    self.assertNotIn("test-secret", msg)
                    self.assertNotIn("https://api.example.com", msg)
                    self.assertEqual([], calls)

    def test_complete_four_pairs_execute_one_get_and_four_puts(self):
        calls = []
        env = {
            TEST_GEMINI_BASE_URL: "https://generativelanguage.googleapis.com///",
            TEST_GEMINI_API_KEY: "secret-gemini",
            TEST_OPENAI_BASE_URL: "https://api.openai.com///",
            TEST_OPENAI_API_KEY: "secret-openai",
            TEST_MINIMAX_ANTHROPIC_BASE_URL: "https://api.minimax.example/anthropic/v1///",
            TEST_MINIMAX_ANTHROPIC_API_KEY: "secret-minimax-anthropic",
            TEST_DEEPSEEK_BASE_URL: "https://api.deepseek.com///",
            TEST_DEEPSEEK_API_KEY: "secret-deepseek",
        }

        lines = sync_provider_credentials(
            "http://backend/",
            env=env,
            urlopen=routed_http(seed_rows(), calls),
        )

        # 1 GET + 4 PUTs = 5 calls
        self.assertEqual(5, len(calls))
        get_url, get_payload, _ = calls[0]
        self.assertTrue(get_url.endswith("/api/ai/catalog/providers?pageNumber=1&pageSize=50"))
        self.assertIsNone(get_payload)

        put_calls = calls[1:]
        self.assertEqual(4, len(put_calls))

        # Check each PUT URL, providerType, baseUrl, and credential
        expected_puts = [
            ("google", "google", "https://generativelanguage.googleapis.com", "secret-gemini"),
            ("openai", "openai_response", "https://api.openai.com/v1", "secret-openai"),
            ("minimax-anthropic", "anthropic", "https://api.minimax.example/anthropic/v1", "secret-minimax-anthropic"),
            ("deepseek", "openai", "https://api.deepseek.com/v1", "secret-deepseek"),
        ]

        for i, (name, p_type, base, secret) in enumerate(expected_puts):
            put_url, payload, headers = put_calls[i]
            self.assertEqual(f"http://backend/api/ai/catalog/providers/{name}", put_url)
            self.assertEqual("application/json", headers["Content-Type"])
            self.assertEqual(p_type, payload["providerType"])
            self.assertEqual(base, payload["baseUrl"])
            self.assertEqual(secret, payload["credential"])
            self.assertEqual("0", payload["expectedVersion"])

        self.assertEqual(
            [
                "google: configured=True baseUrl_set=True",
                "openai: configured=True baseUrl_set=True",
                "minimax-anthropic: configured=True baseUrl_set=True",
                "deepseek: configured=True baseUrl_set=True",
            ],
            lines,
        )

    def test_missing_seed_provider_fails_before_any_put(self):
        calls = []
        env = {
            TEST_GEMINI_BASE_URL: "https://generativelanguage.googleapis.com",
            TEST_GEMINI_API_KEY: "secret-gemini",
            TEST_OPENAI_BASE_URL: "https://api.openai.com",
            TEST_OPENAI_API_KEY: "secret-openai",
        }
        # Seed rows omit 'openai'
        rows = seed_rows(openai=None)

        with self.assertRaisesRegex(RuntimeError, "name=openai"):
            sync_provider_credentials(
                "http://backend",
                env=env,
                urlopen=routed_http(rows, calls),
            )

        # Only 1 GET call, 0 PUT calls
        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_provider_type_mismatch_fails_before_any_put(self):
        calls = []
        env = {
            TEST_GEMINI_BASE_URL: "https://generativelanguage.googleapis.com",
            TEST_GEMINI_API_KEY: "secret-gemini",
        }
        # google row with wrong providerType
        rows = seed_rows(google={"providerType": "wrong_type"})

        with self.assertRaisesRegex(RuntimeError, "unexpected providerType"):
            sync_provider_credentials(
                "http://backend",
                env=env,
                urlopen=routed_http(rows, calls),
            )

        # Only 1 GET call, 0 PUT calls
        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_missing_seed_version_fails_before_any_put(self):
        calls = []
        env = {
            TEST_DEEPSEEK_BASE_URL: "https://api.deepseek.com",
            TEST_DEEPSEEK_API_KEY: "secret-deepseek",
        }
        rows = seed_rows(deepseek={"version": ""})

        with self.assertRaisesRegex(RuntimeError, "missing required version"):
            sync_provider_credentials(
                "http://backend",
                env=env,
                urlopen=routed_http(rows, calls),
            )

        # Only 1 GET call, 0 PUT calls
        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_rerun_inherits_timeout_and_version(self):
        calls = []
        env = {
            TEST_DEEPSEEK_BASE_URL: "https://api.deepseek.com",
            TEST_DEEPSEEK_API_KEY: "secret-deepseek",
        }
        rows = seed_rows(
            deepseek={
                "version": "42",
                "modelCallTimeoutMillis": 999999,
                "modelCallIdleTimeoutMillis": 88888,
            }
        )

        sync_provider_credentials(
            "http://backend",
            env=env,
            urlopen=routed_http(rows, calls),
        )

        self.assertEqual(2, len(calls))
        payload = calls[1][1]
        self.assertEqual("42", payload["expectedVersion"])
        self.assertEqual(999999, payload["modelCallTimeoutMillis"])
        self.assertEqual(88888, payload["modelCallIdleTimeoutMillis"])

    def test_status_output_and_exceptions_never_contain_secret_or_endpoint(self):
        calls = []
        secret = "secret-super-sensitive-12345"
        base_endpoint = "https://private.secret.endpoint.com"
        env = {
            TEST_GEMINI_BASE_URL: f"{base_endpoint}/gemini",
            TEST_GEMINI_API_KEY: secret,
        }

        lines = sync_provider_credentials(
            "http://backend",
            env=env,
            urlopen=routed_http(seed_rows(), calls),
        )

        combined_output = "\n".join(lines)
        self.assertNotIn(secret, combined_output)
        self.assertNotIn(base_endpoint, combined_output)
        self.assertIn("google: configured=True baseUrl_set=True", combined_output)


class TestReliabilityMiniMaxCredentialSync(unittest.TestCase):
    """Verify the legacy reliability matrix remains isolated from the four-provider E2E inputs."""

    def test_no_env_skips_without_http(self):
        calls = []
        lines = sync_minimax_credentials(
            "http://backend", env={}, urlopen=routed_http([], calls)
        )

        self.assertEqual(["minimax: skip (no credential pair)"], lines)
        self.assertEqual([], calls)

    def test_complete_pair_updates_legacy_responses_provider(self):
        calls = []
        rows = [
            {
                "name": "minimax",
                "description": "MiniMax.",
                "providerType": "openai_response",
                "baseUrl": None,
                "version": "7",
                "modelCallTimeoutMillis": 1800000,
                "modelCallIdleTimeoutMillis": 120000,
            }
        ]

        lines = sync_minimax_credentials(
            "http://backend",
            env={
                TEST_MINIMAX_BASE_URL: "https://api.minimax.example///",
                TEST_MINIMAX_API_KEY: "secret-minimax",
            },
            urlopen=routed_http(rows, calls),
        )

        self.assertEqual(2, len(calls))
        self.assertEqual(
            "http://backend/api/ai/catalog/providers/minimax",
            calls[1][0],
        )
        self.assertEqual("https://api.minimax.example/v1", calls[1][1]["baseUrl"])
        self.assertEqual("openai_response", calls[1][1]["providerType"])
        self.assertEqual("secret-minimax", calls[1][1]["credential"])
        self.assertEqual(["minimax: configured=True baseUrl_set=True"], lines)

    def test_half_pair_fails_before_http_without_secret_leakage(self):
        calls = []

        with self.assertRaises(ValueError) as ctx:
            sync_minimax_credentials(
                "http://backend",
                env={TEST_MINIMAX_API_KEY: "secret-minimax"},
                urlopen=routed_http([], calls),
            )

        self.assertIn(TEST_MINIMAX_BASE_URL, str(ctx.exception))
        self.assertIn(TEST_MINIMAX_API_KEY, str(ctx.exception))
        self.assertNotIn("secret-minimax", str(ctx.exception))
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
