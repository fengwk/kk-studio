"""Tests for shared provider credential synchronization primitives.

This test suite verifies the internal primitives in scripts.dev.lib.provider_credentials:
- Safe base URL validation and normalization without secret/URL leakage.
- Provider update payload construction preserving existing versions, metadata, and timeouts.
- Low-level backend provider catalog HTTP operations (get_providers, put_provider).
- The core provider-spec synchronization engine (pre-HTTP validations, pre-PUT validations,
  idempotent execution, and secret sanitization).
"""

import json
import os
from pathlib import Path
import sys
import unittest
import urllib.error


def repository_root() -> Path:
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


if str(repository_root()) not in sys.path:
    sys.path.insert(0, str(repository_root()))

from scripts.dev.lib.provider_credentials import (  # noqa: E402
    DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS,
    DEFAULT_MODEL_CALL_TIMEOUT_MILLIS,
    ProviderSpec,
    build_payload,
    get_providers,
    normalize_base_url,
    put_provider,
    sync_provider_specs,
)


def make_mock_http(rows=None, put_error=None, get_error=None):
    """Return a fake urlopen recorder for testing HTTP dispatch and payload transmission."""
    calls = []

    def fake_urlopen(url, data=None, timeout=30, headers=None):
        payload = json.loads(data.decode("utf-8")) if data is not None else None
        calls.append((url, payload, headers))
        if data is None:
            if get_error is not None:
                raise get_error
            return {"data": {"results": rows if rows is not None else []}}
        if put_error is not None:
            raise put_error
        return {"data": {"configured": True, "baseUrl": payload.get("baseUrl")}}

    return fake_urlopen, calls


class TestNormalizeBaseUrl(unittest.TestCase):
    """Verify provider base URL normalization and strict safety rules against credential leakage."""

    def test_normalizes_trailing_slashes_when_v1_suffix_not_required(self):
        # 意图：当不需要 /v1 后缀时（例如 Google Gemini 或自定义网关），仅剥除多余的末尾斜杠，保留自定义路径。
        normalized = normalize_base_url(
            "https://generativelanguage.googleapis.com///",
            requires_v1_suffix=False,
            env_name="TEST_BASE_URL",
            provider_name="custom-provider",
        )
        self.assertEqual("https://generativelanguage.googleapis.com", normalized)

        custom_path = normalize_base_url(
            "https://api.gateway.example/custom/path///",
            requires_v1_suffix=False,
        )
        self.assertEqual("https://api.gateway.example/custom/path", custom_path)

    def test_ensures_v1_suffix_when_required(self):
        # 意图：当 provider 契约要求 /v1 后缀时（如 OpenAI、DeepSeek 等），无论输入是否包含 /v1 或末尾斜杠，都规范化为单个 /v1。
        self.assertEqual(
            "https://api.openai.example/v1",
            normalize_base_url(
                "https://api.openai.example///",
                requires_v1_suffix=True,
                env_name="TEST_OPENAI_BASE_URL",
                provider_name="openai",
            ),
        )
        self.assertEqual(
            "https://api.openai.example/v1",
            normalize_base_url(
                "https://api.openai.example/v1///",
                requires_v1_suffix=True,
                env_name="TEST_OPENAI_BASE_URL",
                provider_name="openai",
            ),
        )

    def test_invalid_urls_rejected_without_leaking_url_or_secrets(self):
        # 意图：非法 URL（空、非 HTTP/HTTPS、无 host、带认证信息、带查询参或片段、端口非法）必须拒绝抛出 ValueError，
        # 且异常信息只能指示环境变量与 provider 名称，绝对不得泄露传入的 URL 内容、Token 或密码。
        invalid_cases = [
            ("", "empty"),
            ("   ", "whitespace"),
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
                        env_name="TEST_TARGET_BASE_URL",
                        provider_name="target-provider",
                    )
                msg = str(ctx.exception)
                self.assertIn("target-provider", msg)
                self.assertIn("TEST_TARGET_BASE_URL", msg)
                if url.strip():
                    self.assertNotIn(url.strip(), msg)
                self.assertNotIn("secret123", msg)
                self.assertNotIn("hash_token", msg)
                self.assertNotIn("pass", msg)


class TestBuildPayload(unittest.TestCase):
    """Verify backend update payload construction preserving metadata and timeout defaults."""

    def test_inherits_current_timeouts_and_metadata(self):
        # 意图：当后端已有 provider 配置时，更新 payload 应保留后端的 description、providerType、乐观锁 version 及自定义超时配置。
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
        self.assertEqual("https://api.example.com/v1", payload["baseUrl"])
        self.assertEqual("test-secret", payload["credential"])

    def test_defaults_missing_timeouts_and_applies_spec_metadata(self):
        # 意图：如果后端已有行缺失超时配置或描述，使用平台统一默认超时，并应用 spec 提供的默认描述与 providerType。
        spec = ProviderSpec(
            name="mock-provider",
            provider_type="google",
            base_url_env="TEST_MOCK_BASE_URL",
            api_key_env="TEST_MOCK_API_KEY",
            requires_v1_suffix=False,
            default_description="Default Mock Description.",
        )
        payload = build_payload(
            "https://api.example.com",
            "test-secret",
            {"version": 3},
            spec=spec,
        )

        self.assertEqual("3", payload["expectedVersion"])
        self.assertEqual("google", payload["providerType"])
        self.assertEqual("Default Mock Description.", payload["description"])
        self.assertEqual(
            DEFAULT_MODEL_CALL_TIMEOUT_MILLIS, payload["modelCallTimeoutMillis"]
        )
        self.assertEqual(
            DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS,
            payload["modelCallIdleTimeoutMillis"],
        )


class TestProviderHttpPrimitives(unittest.TestCase):
    """Verify get_providers and put_provider network error handling and URL encoding."""

    def test_get_providers_dispatches_get_request(self):
        # 意图：get_providers 应该向正确的 catalog 分页端点发起 GET 请求。
        fake_http, calls = make_mock_http(rows=[{"name": "test-p"}])
        res = get_providers("http://backend:18080/", urlopen=fake_http)

        self.assertEqual(1, len(calls))
        url, data, _ = calls[0]
        self.assertTrue(url.startswith("http://backend:18080/api/ai/catalog/providers?"))
        self.assertIsNone(data)
        self.assertEqual([{"name": "test-p"}], res["data"]["results"])

    def test_get_providers_error_sanitization(self):
        # 意图：get_providers 在网络异常时统一包装为 RuntimeError，不向外暴露内部异常或 backend URL 详情。
        fake_http, _ = make_mock_http(get_error=Exception("raw socket failed"))
        with self.assertRaisesRegex(RuntimeError, "Failed to fetch existing providers from backend"):
            get_providers("http://backend:18080", urlopen=fake_http)

    def test_put_provider_encodes_provider_name_safely(self):
        # 意图：put_provider 应该对 provider name 执行 URL 编码，避免名称中包含斜杠或特殊字符时破坏 API 路由。
        fake_http, calls = make_mock_http()
        put_provider(
            "http://backend:18080",
            "custom/provider:v1",
            {"baseUrl": "https://api.example.com"},
            urlopen=fake_http,
        )

        self.assertEqual(1, len(calls))
        url, payload, headers = calls[0]
        self.assertEqual(
            "http://backend:18080/api/ai/catalog/providers/custom%2Fprovider%3Av1",
            url,
        )
        self.assertEqual("application/json", headers.get("Content-Type"))
        self.assertEqual("https://api.example.com", payload.get("baseUrl"))

    def test_put_provider_sanitizes_http_error(self):
        # 意图：put_provider 发生 HTTP 错误时，仅暴露状态码与 provider 名称，不泄漏 payload 内容。
        error = urllib.error.HTTPError("url", 409, "Conflict", {}, None)
        fake_http, _ = make_mock_http(put_error=error)
        with self.assertRaisesRegex(RuntimeError, r"Failed to update provider my-prov \(HTTP 409\)"):
            put_provider("http://backend:18080", "my-prov", {"credential": "secret"}, urlopen=fake_http)


class TestSyncProviderSpecs(unittest.TestCase):
    """Verify sync_provider_specs pre-HTTP validation, pre-PUT validation, and execution."""

    def setUp(self):
        self.spec_a = ProviderSpec(
            name="prov-a",
            provider_type="openai_response",
            base_url_env="TEST_A_BASE_URL",
            api_key_env="TEST_A_API_KEY",
            requires_v1_suffix=True,
            default_description="Provider A",
        )
        self.spec_b = ProviderSpec(
            name="prov-b",
            provider_type="google",
            base_url_env="TEST_B_BASE_URL",
            api_key_env="TEST_B_API_KEY",
            requires_v1_suffix=False,
            default_description="Provider B",
        )
        self.specs = [self.spec_a, self.spec_b]

    def test_skip_when_all_credential_pairs_are_empty(self):
        # 意图：当所有 provider 的环境变量均为空时，直接跳过并返回 skip 状态，不发起任何 HTTP 请求。
        fake_http, calls = make_mock_http()
        lines = sync_provider_specs(
            "http://backend:18080",
            self.specs,
            env_values={},
            urlopen=fake_http,
        )

        self.assertEqual(0, len(calls))
        self.assertEqual(
            [
                "prov-a: skip (no credential pair)",
                "prov-b: skip (no credential pair)",
            ],
            lines,
        )

    def test_half_pair_raises_before_any_http_request(self):
        # 意图：任一 provider 出现半对凭据（有 URL 缺 Key，或有 Key 缺 URL）时，必须在发起任何 HTTP 请求前抛出 ValueError。
        cases = [
            {"TEST_A_BASE_URL": "https://api.example.com"},
            {"TEST_A_API_KEY": "test-key"},
        ]
        for env in cases:
            with self.subTest(env=list(env.keys())[0]):
                fake_http, calls = make_mock_http()
                with self.assertRaises(ValueError) as ctx:
                    sync_provider_specs(
                        "http://backend:18080",
                        self.specs,
                        env_values=env,
                        urlopen=fake_http,
                    )
                self.assertEqual(0, len(calls))
                self.assertIn("prov-a", str(ctx.exception))
                self.assertIn("TEST_A_BASE_URL", str(ctx.exception))
                self.assertIn("TEST_A_API_KEY", str(ctx.exception))
                self.assertNotIn("test-key", str(ctx.exception))
                self.assertNotIn("https://api.example.com", str(ctx.exception))

    def test_missing_seed_row_fails_before_any_put(self):
        # 意图：在发起任何 PUT 请求前，后端必须存在对应的 seeded provider 记录，否则报错且不执行任何 PUT。
        fake_http, calls = make_mock_http(
            rows=[
                {"name": "prov-a", "providerType": "openai_response", "version": "1"}
            ]
        )
        env = {
            "TEST_A_BASE_URL": "https://api.a.example",
            "TEST_A_API_KEY": "key-a",
            "TEST_B_BASE_URL": "https://api.b.example",
            "TEST_B_API_KEY": "key-b",
        }
        with self.assertRaisesRegex(RuntimeError, "missing seeded provider name=prov-b"):
            sync_provider_specs("http://backend:18080", self.specs, env, urlopen=fake_http)

        # 仅执行了 1 次 GET，没有执行 PUT
        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_missing_seed_version_fails_before_any_put(self):
        # 意图：若后端行缺失乐观锁 version 字段，在发起任何 PUT 之前必须阻断并报错。
        fake_http, calls = make_mock_http(
            rows=[
                {"name": "prov-a", "providerType": "openai_response", "version": ""},
            ]
        )
        env = {
            "TEST_A_BASE_URL": "https://api.a.example",
            "TEST_A_API_KEY": "key-a",
        }
        with self.assertRaisesRegex(RuntimeError, "missing required version"):
            sync_provider_specs("http://backend:18080", [self.spec_a], env, urlopen=fake_http)

        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_provider_type_mismatch_fails_before_any_put(self):
        # 意图：若后端已有的 providerType 与 spec 不匹配，在发起任何 PUT 之前阻断并报错。
        fake_http, calls = make_mock_http(
            rows=[
                {"name": "prov-a", "providerType": "unexpected_type", "version": "1"},
            ]
        )
        env = {
            "TEST_A_BASE_URL": "https://api.a.example",
            "TEST_A_API_KEY": "key-a",
        }
        with self.assertRaisesRegex(RuntimeError, "unexpected providerType"):
            sync_provider_specs("http://backend:18080", [self.spec_a], env, urlopen=fake_http)

        self.assertEqual(1, len(calls))
        self.assertIsNone(calls[0][1])

    def test_successful_synchronization_with_partial_specs_configured(self):
        # 意图：当部分 spec 配置了凭据而部分未配置时，未配置的输出 skip，已配置的完成更新并保持 deterministic 顺序。
        fake_http, calls = make_mock_http(
            rows=[
                {
                    "name": "prov-b",
                    "providerType": "google",
                    "version": "5",
                    "description": "Seeded B",
                }
            ]
        )
        env = {
            "TEST_B_BASE_URL": "https://api.b.example///",
            "TEST_B_API_KEY": "key-b",
        }
        lines = sync_provider_specs("http://backend:18080", self.specs, env, urlopen=fake_http)

        self.assertEqual(2, len(calls))
        self.assertIsNone(calls[0][1])  # GET
        self.assertEqual(
            "http://backend:18080/api/ai/catalog/providers/prov-b",
            calls[1][0],
        )
        put_payload = calls[1][1]
        self.assertEqual("https://api.b.example", put_payload["baseUrl"])
        self.assertEqual("key-b", put_payload["credential"])
        self.assertEqual("5", put_payload["expectedVersion"])
        self.assertEqual("Seeded B", put_payload["description"])

        self.assertEqual(
            [
                "prov-a: skip (no credential pair)",
                "prov-b: configured=True baseUrl_set=True",
            ],
            lines,
        )


if __name__ == "__main__":
    unittest.main()
