#!/usr/bin/env python3
"""Synchronize e2e provider credentials from environment to backend.

Usage:
    sync_provider_credentials.py --backend-url http://localhost:18080

Reads TEST_* environment variables for 7 known providers, fetches current
provider state from the backend, and PUTs only env-provided fields back.
Missing env vars leave existing DB values unchanged.
No secrets are printed to stdout/stderr.
"""

import argparse
import json
import os
import typing
import urllib.request


class ProviderSpec(typing.NamedTuple):
    provider_id: int
    name: str
    description: str
    provider_type: str
    base_url_env: str
    api_key_env: str


PROVIDERS = (
    ProviderSpec(
        provider_id=1, name="minimax",
        description="MiniMax (OpenAI Responses).",
        provider_type="openai_response",
        base_url_env="TEST_MINIMAX_BASE_URL",
        api_key_env="TEST_MINIMAX_API_KEY",
    ),
    ProviderSpec(
        provider_id=2, name="openai",
        description="OpenAI (OpenAI Responses).",
        provider_type="openai_response",
        base_url_env="TEST_OPENAI_BASE_URL",
        api_key_env="TEST_OPENAI_API_KEY",
    ),
    ProviderSpec(
        provider_id=3, name="xai",
        description="xAI / Grok (OpenAI Responses).",
        provider_type="openai_response",
        base_url_env="TEST_XAI_BASE_URL",
        api_key_env="TEST_XAI_API_KEY",
    ),
    ProviderSpec(
        provider_id=4, name="deepseek",
        description="DeepSeek (OpenAI Chat Completions).",
        provider_type="openai",
        base_url_env="TEST_DEEPSEEK_BASE_URL",
        api_key_env="TEST_DEEPSEEK_API_KEY",
    ),
    ProviderSpec(
        provider_id=5, name="google",
        description="Google Gemini.",
        provider_type="google",
        base_url_env="TEST_GOOGLE_BASE_URL",
        api_key_env="TEST_GOOGLE_API_KEY",
    ),
    ProviderSpec(
        provider_id=6, name="anthropic",
        description="Anthropic.",
        provider_type="anthropic",
        base_url_env="TEST_ANTHROPIC_BASE_URL",
        api_key_env="TEST_ANTHROPIC_API_KEY",
    ),
    ProviderSpec(
        provider_id=7, name="zai",
        description="ZAI (OpenAI Chat Completions).",
        provider_type="openai",
        base_url_env="TEST_ZAI_BASE_URL",
        api_key_env="TEST_ZAI_API_KEY",
    ),
)


def normalize_openai_compatible_base_url(base, provider_type):
    """When env already sets a base URL, ensure OpenAI-compatible hosts
    end with /v1.

    Never invent a default host; google and blank values are left unchanged.
    """
    if not base:
        return base
    if provider_type not in ("openai", "openai_response"):
        return base
    cleaned = base.rstrip("/")
    if cleaned.endswith("/v1"):
        return cleaned
    return cleaned + "/v1"


def get_providers(backend_url, urlopen=None):
    """Fetch existing providers from the backend."""
    if urlopen is None:
        urlopen = _default_urlopen
    return urlopen(
        f"{backend_url}/api/ai/catalog/providers?pageNumber=1&pageSize=50")


def put_provider(backend_url, provider_id, payload, urlopen=None):
    """Update a single provider on the backend."""
    if urlopen is None:
        urlopen = _default_urlopen
    data = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"}
    return urlopen(
        f"{backend_url}/api/ai/catalog/providers/{provider_id}",
        data=data, headers=headers)


def _default_urlopen(url, data=None, timeout=30, headers=None):
    """Default HTTP helper wrapping urllib.request."""
    if headers is None:
        headers = {}
    method = "GET" if data is None else "PUT"
    req = urllib.request.Request(
        url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def build_payload(spec, base, key, current):
    """Construct the provider update payload from a ProviderSpec.

    Only sets credential when env provides it; otherwise the backend keeps
    the existing secret. Timeouts default or inherit from current DB state.
    """
    payload = {
        "name": spec.name,
        "description": spec.description,
        "providerType": spec.provider_type,
        "baseUrl": base if base is not None else current.get("baseUrl"),
        "modelCallTimeoutMillis": int(
            current.get("modelCallTimeoutMillis") or 1800000),
        "modelCallIdleTimeoutMillis": int(
            current.get("modelCallIdleTimeoutMillis") or 120000),
    }
    if key is not None:
        payload["credential"] = key
    return payload


def sync_all(backend_url, env=None, urlopen=None):
    """Main sync logic, parameterized for testability.

    Returns a list of status-line strings suitable for printing.
    """
    if env is None:
        env = os.environ
    if urlopen is None:
        urlopen = _default_urlopen
    backend_url = backend_url.rstrip("/")

    listed = get_providers(backend_url, urlopen=urlopen)
    rows = (listed.get("data") or {}).get("results") or []
    by_id = {str(r.get("id")): r for r in rows}

    results = []
    for spec in PROVIDERS:
        base = (env.get(spec.base_url_env) or "").strip() or None
        key = (env.get(spec.api_key_env) or "").strip() or None
        if base is None and key is None:
            results.append(
                f"provider {spec.name}: skip"
                f" (no {spec.base_url_env}/{spec.api_key_env})")
            continue
        if base is not None:
            normalized = normalize_openai_compatible_base_url(
                base, spec.provider_type)
            if normalized != base:
                results.append(
                    f"provider {spec.name}: normalize baseUrl"
                    f" {base} -> {normalized}")
            base = normalized
        current = by_id.get(str(spec.provider_id)) or {}
        payload = build_payload(spec, base, key, current)
        body = put_provider(
            backend_url, spec.provider_id, payload, urlopen=urlopen)
        data = body.get("data") or {}
        results.append(
            f"provider {spec.name}:"
            f" configured={data.get('configured')}"
            f" baseUrl_set={bool(data.get('baseUrl'))}")
    return results


def main():
    parser = argparse.ArgumentParser(
        description="Synchronize e2e provider credentials.")
    parser.add_argument(
        "--backend-url", required=True,
        help="Backend base URL, e.g. http://localhost:18080")
    args = parser.parse_args()
    for line in sync_all(args.backend_url):
        print(line)


if __name__ == "__main__":
    main()
