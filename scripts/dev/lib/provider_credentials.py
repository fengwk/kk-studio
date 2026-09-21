"""Shared provider credential synchronization primitives for dev test workflows.

This module provides internal building blocks shared across development verification
tools (such as E2E real provider credential synchronization and reliability matrix
credential synchronization). It is a private implementation module, not an external CLI
entry point.
"""

from dataclasses import dataclass
import json
from typing import List, Optional, Sequence
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_MODEL_CALL_TIMEOUT_MILLIS = 1800000
DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS = 120000


@dataclass(frozen=True)
class ProviderSpec:
    name: str
    provider_type: str
    base_url_env: str
    api_key_env: str
    requires_v1_suffix: bool
    default_description: str


def normalize_base_url(
    base_url_raw: str,
    requires_v1_suffix: bool,
    env_name: str = "",
    provider_name: str = "",
) -> str:
    """Validate and normalize a provider base URL safely without leaking URL values."""
    context = (
        f"for provider '{provider_name}' ({env_name})"
        if provider_name and env_name
        else f"in {env_name}"
        if env_name
        else f"for provider '{provider_name}'"
        if provider_name
        else ""
    ).strip()
    prefix = f" {context}" if context else ""

    cleaned = (base_url_raw or "").strip()
    if not cleaned:
        raise ValueError(f"Base URL{prefix} must not be empty")

    try:
        parsed = urllib.parse.urlsplit(cleaned)
        parsed.port
    except (ValueError, TypeError):
        raise ValueError(f"Invalid base URL format{prefix}") from None

    if parsed.scheme.lower() not in ("http", "https"):
        raise ValueError(f"Invalid base URL scheme{prefix}; must be http or https")
    if not parsed.hostname:
        raise ValueError(f"Invalid base URL{prefix}; missing hostname")
    if parsed.username is not None or parsed.password is not None or "@" in (parsed.netloc or ""):
        raise ValueError(f"Invalid base URL{prefix}; must not contain user credentials")
    if parsed.query:
        raise ValueError(f"Invalid base URL{prefix}; must not contain query parameters")
    if parsed.fragment:
        raise ValueError(f"Invalid base URL{prefix}; must not contain a fragment")

    trimmed = cleaned.rstrip("/")
    if requires_v1_suffix:
        if not trimmed.endswith("/v1"):
            trimmed += "/v1"
    return trimmed


def _default_urlopen(url: str, data: Optional[bytes] = None, timeout: int = 30, headers: Optional[dict] = None):
    """Default HTTP helper wrapping urllib.request."""
    if headers is None:
        headers = {}
    method = "GET" if data is None else "PUT"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def get_providers(backend_url: str, urlopen=None):
    """Fetch existing providers from the backend."""
    if urlopen is None:
        urlopen = _default_urlopen
    backend_url = backend_url.rstrip("/")
    try:
        return urlopen(f"{backend_url}/api/ai/catalog/providers?pageNumber=1&pageSize=50")
    except Exception:
        raise RuntimeError("Failed to fetch existing providers from backend") from None


def put_provider(backend_url: str, provider_name: str, payload: dict, urlopen=None):
    """Update a single provider on the backend with URL-encoded provider path."""
    if urlopen is None:
        urlopen = _default_urlopen
    backend_url = backend_url.rstrip("/")
    data = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"}
    encoded_name = urllib.parse.quote(provider_name, safe="")
    try:
        return urlopen(
            f"{backend_url}/api/ai/catalog/providers/{encoded_name}",
            data=data,
            headers=headers,
        )
    except urllib.error.HTTPError as err:
        raise RuntimeError(f"Failed to update provider {provider_name} (HTTP {err.code})") from None
    except Exception:
        raise RuntimeError(f"Failed to update provider {provider_name}") from None


def build_payload(
    base_url: str,
    api_key: str,
    current: dict,
    spec: Optional[ProviderSpec] = None,
) -> dict:
    """Construct provider update payload while preserving current metadata and timeouts."""
    provider_type = (
        spec.provider_type
        if spec is not None
        else (current.get("providerType") or "openai_response")
    )
    description = current.get("description") or (
        spec.default_description if spec is not None else ""
    )
    return {
        "description": description,
        "providerType": provider_type,
        "baseUrl": base_url,
        "credential": api_key,
        "expectedVersion": str(current["version"]),
        "modelCallTimeoutMillis": int(
            current.get("modelCallTimeoutMillis") or DEFAULT_MODEL_CALL_TIMEOUT_MILLIS
        ),
        "modelCallIdleTimeoutMillis": int(
            current.get("modelCallIdleTimeoutMillis")
            or DEFAULT_MODEL_CALL_IDLE_TIMEOUT_MILLIS
        ),
    }


def sync_provider_specs(
    backend_url: str,
    specs: Sequence[ProviderSpec],
    env_values: dict,
    urlopen=None,
) -> List[str]:
    """Synchronize a fixed provider-spec set from an explicit environment snapshot."""
    backend_url = backend_url.rstrip("/")

    # Step 1: Pre-HTTP validation of pairs and URL formats across all specs
    to_configure = []
    for spec in specs:
        raw_base = (env_values.get(spec.base_url_env) or "").strip()
        raw_key = (env_values.get(spec.api_key_env) or "").strip()

        if not raw_base and not raw_key:
            continue
        if not raw_base or not raw_key:
            raise ValueError(
                f"Provider {spec.name} credentials require both "
                f"{spec.base_url_env} and {spec.api_key_env}"
            )

        normalized_url = normalize_base_url(
            raw_base,
            requires_v1_suffix=spec.requires_v1_suffix,
            env_name=spec.base_url_env,
            provider_name=spec.name,
        )
        to_configure.append((spec, normalized_url, raw_key))

    # Step 2: Skip without HTTP when no provider credentials are provided
    if not to_configure:
        return [f"{spec.name}: skip (no credential pair)" for spec in specs]

    # Step 3: Fetch existing providers once
    listed = get_providers(backend_url, urlopen=urlopen)
    rows = (listed.get("data") or {}).get("results") or []
    row_map = {
        row.get("name"): row
        for row in rows
        if isinstance(row, dict) and "name" in row
    }

    # Step 4: Pre-PUT validation for all configured providers (must fail before any PUT)
    validations = []
    for spec, normalized_url, api_key in to_configure:
        current = row_map.get(spec.name)
        if current is None:
            raise RuntimeError(
                f"E2E database is missing seeded provider name={spec.name}"
            )
        if current.get("version") is None or not str(current["version"]).strip():
            raise RuntimeError(
                f"E2E database provider row for {spec.name} is missing required version"
            )
        if current.get("providerType") != spec.provider_type:
            raise RuntimeError(
                f"E2E database provider row for {spec.name} has unexpected providerType: "
                f"{current.get('providerType')}"
            )
        payload = build_payload(normalized_url, api_key, current, spec)
        validations.append((spec, payload))

    # Step 5: Execute PUT requests
    results = {}
    for spec, payload in validations:
        body = put_provider(backend_url, spec.name, payload, urlopen=urlopen)
        data = body.get("data") or {}
        results[spec.name] = (
            f"{spec.name}: configured={bool(data.get('configured'))} "
            f"baseUrl_set={bool(data.get('baseUrl'))}"
        )

    # Step 6: Return formatted lines in deterministic PROVIDER_SPECS order
    output_lines = []
    for spec in specs:
        if spec.name in results:
            output_lines.append(results[spec.name])
        else:
            output_lines.append(f"{spec.name}: skip (no credential pair)")
    return output_lines
