#!/usr/bin/env python3
"""Synchronize E2E credential pairs for the four real target providers.

Usage:
    sync_provider_credentials.py --backend-url http://localhost:18080

Targets:
    google:            TEST_GOOGLE_BASE_URL / TEST_GOOGLE_API_KEY
    openai:            TEST_OPENAI_BASE_URL / TEST_OPENAI_API_KEY
    minimax-anthropic: TEST_ANTHROPIC_BASE_URL / TEST_ANTHROPIC_API_KEY
    deepseek:          TEST_DEEPSEEK_BASE_URL / TEST_DEEPSEEK_API_KEY

When all credential pairs are absent, the script skips without contacting the backend.
When any provider has an incomplete (half) pair, it raises ValueError before any HTTP request.
Preconditions (database seed row, non-empty version, matching providerType) for all configured
providers are validated before any PUT request is performed.
No secrets, raw URLs, or response bodies are printed or exposed in exceptions.
"""

import argparse
import os
from pathlib import Path
import sys
from typing import List, Optional


def repository_root() -> Path:
    """Resolve the checkout root without depending on this file's directory depth."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError(
        "cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT"
    )


REPOSITORY_ROOT = repository_root()
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.dev.lib.provider_credentials import (  # noqa: E402
    ProviderSpec,
    sync_provider_specs,
)

TEST_GOOGLE_BASE_URL = "TEST_GOOGLE_BASE_URL"
TEST_GOOGLE_API_KEY = "TEST_GOOGLE_API_KEY"
TEST_OPENAI_BASE_URL = "TEST_OPENAI_BASE_URL"
TEST_OPENAI_API_KEY = "TEST_OPENAI_API_KEY"
TEST_ANTHROPIC_BASE_URL = "TEST_ANTHROPIC_BASE_URL"
TEST_ANTHROPIC_API_KEY = "TEST_ANTHROPIC_API_KEY"
TEST_DEEPSEEK_BASE_URL = "TEST_DEEPSEEK_BASE_URL"
TEST_DEEPSEEK_API_KEY = "TEST_DEEPSEEK_API_KEY"


PROVIDER_SPECS: List[ProviderSpec] = [
    ProviderSpec(
        name="google",
        provider_type="google",
        base_url_env=TEST_GOOGLE_BASE_URL,
        api_key_env=TEST_GOOGLE_API_KEY,
        requires_v1_suffix=False,
        default_description="Google Gemini.",
    ),
    ProviderSpec(
        name="openai",
        provider_type="openai_response",
        base_url_env=TEST_OPENAI_BASE_URL,
        api_key_env=TEST_OPENAI_API_KEY,
        requires_v1_suffix=True,
        default_description="OpenAI (OpenAI Responses).",
    ),
    ProviderSpec(
        name="minimax-anthropic",
        provider_type="anthropic",
        base_url_env=TEST_ANTHROPIC_BASE_URL,
        api_key_env=TEST_ANTHROPIC_API_KEY,
        requires_v1_suffix=False,
        default_description="MiniMax (Anthropic).",
    ),
    ProviderSpec(
        name="deepseek",
        provider_type="openai",
        base_url_env=TEST_DEEPSEEK_BASE_URL,
        api_key_env=TEST_DEEPSEEK_API_KEY,
        requires_v1_suffix=True,
        default_description="DeepSeek (OpenAI Chat Completions).",
    ),
]


def sync_provider_credentials(backend_url: str, env: Optional[dict] = None, urlopen=None) -> List[str]:
    """Synchronize real provider credentials from environment to backend.

    Returns a list of status-line strings suitable for printing.
    """
    if env is None:
        env = os.environ
    backend_url = backend_url.rstrip("/")

    # Explicit reads to satisfy static guard inspections
    env_values = {
        TEST_GOOGLE_BASE_URL: env.get(TEST_GOOGLE_BASE_URL),
        TEST_GOOGLE_API_KEY: env.get(TEST_GOOGLE_API_KEY),
        TEST_OPENAI_BASE_URL: env.get(TEST_OPENAI_BASE_URL),
        TEST_OPENAI_API_KEY: env.get(TEST_OPENAI_API_KEY),
        TEST_ANTHROPIC_BASE_URL: env.get(TEST_ANTHROPIC_BASE_URL),
        TEST_ANTHROPIC_API_KEY: env.get(TEST_ANTHROPIC_API_KEY),
        TEST_DEEPSEEK_BASE_URL: env.get(TEST_DEEPSEEK_BASE_URL),
        TEST_DEEPSEEK_API_KEY: env.get(TEST_DEEPSEEK_API_KEY),
    }
    return sync_provider_specs(
        backend_url,
        PROVIDER_SPECS,
        env_values,
        urlopen=urlopen,
    )


def main():
    parser = argparse.ArgumentParser(
        description="Synchronize real provider E2E credentials.")
    parser.add_argument(
        "--backend-url", required=True,
        help="Backend base URL, e.g. http://localhost:18080")
    args = parser.parse_args()
    for line in sync_provider_credentials(args.backend_url):
        print(line)


if __name__ == "__main__":
    main()
