"""Tests for the reliability matrix's legacy MiniMax credential synchronization."""

import json
import os
from pathlib import Path
import sys
import unittest


def repository_root() -> Path:
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


# 以仓库根为导入根：脚本模块以 `scripts.dev.verify.*` 为包路径，测试不依赖调用者 cwd。
if str(repository_root()) not in sys.path:
    sys.path.insert(0, str(repository_root()))

from scripts.dev.verify.reliability.sync_minimax_credentials import (
    TEST_MINIMAX_API_KEY,
    TEST_MINIMAX_BASE_URL,
    sync_minimax_credentials,
)


def routed_http(rows, calls):
    """Return a urlopen-compatible fake recording each GET or PUT."""

    def fake(url, data=None, timeout=30, headers=None):
        payload = json.loads(data) if data is not None else None
        calls.append((url, payload, headers))
        if payload is None:
            return {"data": {"results": rows}}
        return {"data": {"configured": True, "baseUrl": payload["baseUrl"]}}

    return fake


class TestReliabilityMiniMaxCredentialSync(unittest.TestCase):
    """Verify the reliability matrix's dedicated MiniMax synchronization remains isolated."""

    def test_no_env_skips_without_http(self):
        # 意图：当 TEST_MINIMAX_* 环境变量未设置时，直接 skip，不向后端发送 HTTP 请求。
        calls = []
        lines = sync_minimax_credentials(
            "http://backend", env={}, urlopen=routed_http([], calls)
        )

        self.assertEqual(["minimax: skip (no credential pair)"], lines)
        self.assertEqual([], calls)

    def test_complete_pair_updates_legacy_responses_provider(self):
        # 意图：当提供完整的 MiniMax 凭据对时，通过共享原语更新 legacy openai_response provider，
        # 并规范化 base URL 追加 /v1。
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
        # 意图：仅提供单个环境变量时抛出 ValueError，且报错信息不泄露凭据明文。
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
