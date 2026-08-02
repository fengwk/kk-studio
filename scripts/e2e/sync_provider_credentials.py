#!/usr/bin/env python3
"""Synchronize the MiniMax E2E credential pair from environment to backend.

Usage:
    sync_provider_credentials.py --backend-url http://localhost:18080

Reads TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY only. When both are
absent it safely skips without contacting the backend. When both are present,
it updates the deterministic MiniMax seed provider through the backend API.
No secrets are printed to stdout/stderr.
"""

import argparse
import json
import os
import urllib.parse
import urllib.request


MINIMAX_PROVIDER_NAME = "minimax"
MINIMAX_PROVIDER_DESCRIPTION = "MiniMax (OpenAI Responses)."
MINIMAX_PROVIDER_TYPE = "openai_response"
MINIMAX_BASE_URL_ENV = "TEST_MINIMAX_BASE_URL"
MINIMAX_API_KEY_ENV = "TEST_MINIMAX_API_KEY"
DEFAULT_MODEL_CALL_TIMEOUT_MILLIS = 1800000
DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS = 120000


def normalize_minimax_base_url(base):
    """Remove trailing slashes and ensure the MiniMax OpenAI endpoint ends in /v1."""
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


def put_provider(backend_url, provider_name, payload, urlopen=None):
    """Update a single provider on the backend."""
    if urlopen is None:
        urlopen = _default_urlopen
    data = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"}
    encoded_name = urllib.parse.quote(provider_name, safe="")
    return urlopen(
        f"{backend_url}/api/ai/catalog/providers/{encoded_name}",
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


def build_payload(base_url, api_key, current):
    """Construct the complete deterministic MiniMax provider update payload."""
    return {
        "description": MINIMAX_PROVIDER_DESCRIPTION,
        "providerType": MINIMAX_PROVIDER_TYPE,
        "baseUrl": base_url,
        "credential": api_key,
        "expectedVersion": str(current["version"]),
        "modelCallTimeoutMillis": int(
            current.get("modelCallTimeoutMillis") or DEFAULT_MODEL_CALL_TIMEOUT_MILLIS),
        "modelCallIdleTimeoutMillis": int(
            current.get("modelCallIdleTimeoutMillis")
            or DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS),
    }


def sync_minimax(backend_url, env=None, urlopen=None):
    """Synchronize the complete MiniMax credential pair, parameterized for testing.

    Returns a list of status-line strings suitable for printing.
    """
    if env is None:
        env = os.environ
    if urlopen is None:
        urlopen = _default_urlopen
    backend_url = backend_url.rstrip("/")

    base_url = (env.get(MINIMAX_BASE_URL_ENV) or "").strip()
    api_key = (env.get(MINIMAX_API_KEY_ENV) or "").strip()
    if not base_url and not api_key:
        return ["minimax: skip (no credential pair)"]
    if not base_url or not api_key:
        raise ValueError(
            "MiniMax E2E credentials require both "
            f"{MINIMAX_BASE_URL_ENV} and {MINIMAX_API_KEY_ENV}")

    listed = get_providers(backend_url, urlopen=urlopen)
    rows = (listed.get("data") or {}).get("results") or []
    current = next(
        (
            row
            for row in rows
            if row.get("name") == MINIMAX_PROVIDER_NAME
        ),
        None)
    if current is None:
        raise RuntimeError(
            "E2E seed is missing deterministic MiniMax provider name=minimax")
    if current.get("version") is None or not str(current["version"]).strip():
        raise RuntimeError(
            "E2E seed MiniMax provider is missing the required version")

    payload = build_payload(normalize_minimax_base_url(base_url), api_key, current)
    body = put_provider(
        backend_url, MINIMAX_PROVIDER_NAME, payload, urlopen=urlopen)
    data = body.get("data") or {}
    return [
        "minimax: "
        f"configured={data.get('configured')} baseUrl_set={bool(data.get('baseUrl'))}"
    ]


def main():
    parser = argparse.ArgumentParser(
        description="Synchronize MiniMax E2E credentials.")
    parser.add_argument(
        "--backend-url", required=True,
        help="Backend base URL, e.g. http://localhost:18080")
    args = parser.parse_args()
    for line in sync_minimax(args.backend_url):
        print(line)


if __name__ == "__main__":
    main()
