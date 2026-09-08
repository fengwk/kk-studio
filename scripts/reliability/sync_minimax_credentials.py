#!/usr/bin/env python3
"""Synchronize the reliability matrix's legacy MiniMax Responses provider."""

import argparse
import os
from pathlib import Path
import sys
from typing import List, Optional


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.e2e.sync_provider_credentials import (  # noqa: E402
    ProviderSpec,
    sync_provider_specs,
)


TEST_MINIMAX_BASE_URL = "TEST_MINIMAX_BASE_URL"
TEST_MINIMAX_API_KEY = "TEST_MINIMAX_API_KEY"
MINIMAX_SPEC = ProviderSpec(
    name="minimax",
    provider_type="openai_response",
    base_url_env=TEST_MINIMAX_BASE_URL,
    api_key_env=TEST_MINIMAX_API_KEY,
    requires_v1_suffix=True,
    default_description="MiniMax.",
)


def sync_minimax_credentials(
    backend_url: str,
    env: Optional[dict] = None,
    urlopen=None,
) -> List[str]:
    """Synchronize the reliability-only credential pair without widening E2E inputs."""
    if env is None:
        env = os.environ
    env_values = {
        TEST_MINIMAX_BASE_URL: env.get(TEST_MINIMAX_BASE_URL),
        TEST_MINIMAX_API_KEY: env.get(TEST_MINIMAX_API_KEY),
    }
    return sync_provider_specs(
        backend_url,
        [MINIMAX_SPEC],
        env_values,
        urlopen=urlopen,
    )


def main():
    parser = argparse.ArgumentParser(
        description="Synchronize reliability MiniMax credentials.")
    parser.add_argument("--backend-url", required=True)
    args = parser.parse_args()
    for line in sync_minimax_credentials(args.backend_url):
        print(line)


if __name__ == "__main__":
    main()
