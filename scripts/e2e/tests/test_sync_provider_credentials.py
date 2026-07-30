"""Tests for sync_provider_credentials.py using fakes for HTTP calls."""

import json
import unittest

from scripts.e2e.sync_provider_credentials import (
    PROVIDERS,
    build_payload,
    normalize_openai_compatible_base_url,
    sync_all,
)


def _make_get_providers(fake_rows):
    """Return a urlopen-like function that returns fake providers."""

    def fake(_):
        return {"data": {"results": fake_rows}}

    return fake


def _make_put_logger(log):
    """Return a urlopen-like function that records PUT (url, payload) pairs."""

    def fake(url, data=None, timeout=30, headers=None):
        payload = json.loads(data) if data else {}
        log.append((url, payload))
        return {"data": payload}

    return fake


def _make_router(get_fn, put_log):
    """Return a urlopen-like callable that routes GET/PUT."""
    put_fn = _make_put_logger(put_log)

    def router(url, data=None, timeout=30, headers=None):
        if data is None:
            return get_fn(url)
        return put_fn(url, data=data, timeout=timeout, headers=headers)

    return router


class TestNormalizeOpenaiCompatibleBaseUrl(unittest.TestCase):
    """OpenAI-compatible base URL /v1 normalization."""

    def test_openai_response_appends_v1(self):
        result = normalize_openai_compatible_base_url(
            "https://api.minimax.com", "openai_response")
        self.assertEqual(result, "https://api.minimax.com/v1")

    def test_openai_appends_v1(self):
        result = normalize_openai_compatible_base_url(
            "https://api.deepseek.com", "openai")
        self.assertEqual(result, "https://api.deepseek.com/v1")

    def test_openai_already_ends_with_v1(self):
        result = normalize_openai_compatible_base_url(
            "https://api.openai.com/v1", "openai")
        self.assertEqual(result, "https://api.openai.com/v1")

    def test_openai_keeps_v1_with_trailing_slash(self):
        result = normalize_openai_compatible_base_url(
            "https://api.openai.com/v1/", "openai")
        self.assertEqual(result, "https://api.openai.com/v1")

    def test_google_unchanged(self):
        result = normalize_openai_compatible_base_url(
            "https://generativelanguage.googleapis.com", "google")
        self.assertEqual(
            result, "https://generativelanguage.googleapis.com")

    def test_anthropic_unchanged(self):
        result = normalize_openai_compatible_base_url(
            "https://api.anthropic.com", "anthropic")
        self.assertEqual(result, "https://api.anthropic.com")

    def test_empty_base_returns_empty(self):
        result = normalize_openai_compatible_base_url("", "openai")
        self.assertEqual(result, "")

    def test_none_base_returns_none(self):
        result = normalize_openai_compatible_base_url(None, "openai")
        self.assertIsNone(result)


class TestBuildPayload(unittest.TestCase):
    """Provider update payload construction."""

    def test_base_and_key_set(self):
        current = {
            "baseUrl": "http://old",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        payload = build_payload(
            PROVIDERS[0], "https://minimax.com/v1", "sk-test-key", current)
        self.assertEqual(payload["name"], "minimax")
        self.assertEqual(payload["baseUrl"], "https://minimax.com/v1")
        self.assertEqual(payload["credential"], "sk-test-key")
        self.assertEqual(payload["modelCallTimeoutMillis"], 1800000)
        self.assertEqual(payload["modelCallIdleTimeoutMillis"], 120000)

    def test_only_key_set_keeps_current_base(self):
        current = {
            "baseUrl": "http://existing",
            "modelCallTimeoutMillis": 300000,
            "modelCallIdleTimeoutMillis": 60000,
        }
        payload = build_payload(
            PROVIDERS[1], None, "sk-key", current)
        self.assertEqual(payload["baseUrl"], "http://existing")
        self.assertEqual(payload["credential"], "sk-key")

    def test_only_base_set_no_credential_in_payload(self):
        current = {
            "baseUrl": "http://old",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        payload = build_payload(
            PROVIDERS[2], "https://x.ai/v1", None, current)
        self.assertEqual(payload["baseUrl"], "https://x.ai/v1")
        self.assertNotIn("credential", payload)

    def test_timeout_defaults_when_missing(self):
        current = {}
        payload = build_payload(PROVIDERS[3], None, None, current)
        self.assertEqual(payload["modelCallTimeoutMillis"], 1800000)
        self.assertEqual(payload["modelCallIdleTimeoutMillis"], 120000)

    def test_timeout_inherits_from_current(self):
        current = {
            "modelCallTimeoutMillis": 600000,
            "modelCallIdleTimeoutMillis": 50000,
        }
        payload = build_payload(
            PROVIDERS[4], "https://google.com", None, current)
        self.assertEqual(payload["modelCallTimeoutMillis"], 600000)
        self.assertEqual(payload["modelCallIdleTimeoutMillis"], 50000)


class TestSyncAll(unittest.TestCase):
    """End-to-end sync logic with fake HTTP callbacks."""

    def test_no_env_vars_all_skip(self):
        """When no env vars are set, every provider is skipped."""
        env = {}
        fake_rows = []
        get_fn = _make_get_providers(fake_rows)
        put_log = []

        lines = sync_all(
            "http://localhost", env=env,
            urlopen=_make_router(get_fn, put_log))
        self.assertEqual(len(lines), len(PROVIDERS))
        for line in lines:
            self.assertIn("skip", line)
        self.assertEqual(put_log, [])

    def test_openai_compatible_base_gets_v1(self):
        """OpenAI-compatible base without /v1 gets normalized."""
        env = {
            "TEST_OPENAI_BASE_URL": "https://api.openai.com",
            "TEST_OPENAI_API_KEY": "sk-key",
        }
        row = {
            "id": "2", "name": "openai", "description": "...",
            "providerType": "openai_response",
            "baseUrl": None,
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        get_fn = _make_get_providers([row])
        put_log = []

        lines = sync_all(
            "http://localhost", env=env,
            urlopen=_make_router(get_fn, put_log))
        self.assertTrue(any("normalize" in l for l in lines))
        self.assertTrue(any("configured" in l for l in lines))
        self.assertEqual(len(put_log), 1)
        url, payload = put_log[0]
        self.assertIn("/api/ai/catalog/providers/2", url)
        self.assertEqual(payload["baseUrl"], "https://api.openai.com/v1")
        self.assertEqual(payload["credential"], "sk-key")

    def test_non_openai_base_unchanged(self):
        """Google base URL is not modified."""
        env = {
            "TEST_GOOGLE_BASE_URL":
                "https://generativelanguage.googleapis.com",
            "TEST_GOOGLE_API_KEY": "g-key",
        }
        row = {
            "id": "5", "name": "google", "description": "...",
            "providerType": "google",
            "baseUrl": None,
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        get_fn = _make_get_providers([row])
        put_log = []

        lines = sync_all(
            "http://localhost", env=env,
            urlopen=_make_router(get_fn, put_log))
        self.assertFalse(any("normalize" in l for l in lines))
        self.assertEqual(len(put_log), 1)
        _, payload = put_log[0]
        self.assertEqual(
            payload["baseUrl"],
            "https://generativelanguage.googleapis.com")

    def test_only_key_set_with_current_base(self):
        """When only key is set, baseUrl defaults from current provider."""
        env = {"TEST_ANTHROPIC_API_KEY": "sk-ant-key"}
        row = {
            "id": "6", "name": "anthropic", "description": "...",
            "providerType": "anthropic",
            "baseUrl": "https://api.anthropic.com",
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        get_fn = _make_get_providers([row])
        put_log = []

        lines = sync_all(
            "http://localhost", env=env,
            urlopen=_make_router(get_fn, put_log))
        self.assertEqual(len(put_log), 1)
        _, payload = put_log[0]
        self.assertEqual(payload["baseUrl"], "https://api.anthropic.com")
        self.assertEqual(payload["credential"], "sk-ant-key")

    def test_no_secret_in_output(self):
        """Ensure credential values never appear in printed lines."""
        env = {
            "TEST_DEEPSEEK_BASE_URL": "https://api.deepseek.com",
            "TEST_DEEPSEEK_API_KEY": "sk-very-secret-value",
        }
        row = {
            "id": "4", "name": "deepseek", "description": "...",
            "providerType": "openai",
            "baseUrl": None,
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        get_fn = _make_get_providers([row])
        put_log = []

        lines = sync_all(
            "http://localhost", env=env,
            urlopen=_make_router(get_fn, put_log))
        output = "\n".join(lines)
        self.assertNotIn("sk-very-secret-value", output)

    def test_all_seven_providers_processed(self):
        """When every env is set, all 7 providers are updated."""
        env = {}
        row_template = {
            "baseUrl": None,
            "modelCallTimeoutMillis": 1800000,
            "modelCallIdleTimeoutMillis": 120000,
        }
        rows = []
        for spec in PROVIDERS:
            env[spec.base_url_env] = f"https://{spec.name}.com"
            env[spec.api_key_env] = f"key-{spec.name}"
            rows.append({
                "id": str(spec.provider_id),
                "name": spec.name,
                "description": spec.description,
                "providerType": spec.provider_type,
                **row_template,
            })
        get_fn = _make_get_providers(rows)
        put_log = []

        lines = sync_all(
            "http://localhost", env=env,
            urlopen=_make_router(get_fn, put_log))
        self.assertEqual(
            len([l for l in lines if "configured" in l]),
            len(PROVIDERS))
        self.assertEqual(len(put_log), len(PROVIDERS))
        for idx, spec in enumerate(PROVIDERS):
            url, _ = put_log[idx]
            self.assertIn(f"/api/ai/catalog/providers/{spec.provider_id}", url)


if __name__ == "__main__":
    unittest.main()
