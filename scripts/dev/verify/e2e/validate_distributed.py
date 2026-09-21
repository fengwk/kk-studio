#!/usr/bin/env python3
"""Static validator for the distributed Compose topology invariants.

Validates deploy/distributed/compose.yaml via `docker compose config` without
starting containers:

- postgres / minio / http-mock join both isolated node-a-db and node-b-db
  networks;
- app-a only joins node-a-db + daemon-a + app-ingress-a, app-b only joins
  node-b-db + daemon-b + app-ingress-b; each daemon joins only its own network;
- no network contains both app-a and app-b, so the two apps have no DNS or IP
  path to each other;
- the host publishes only the two app ports plus the disposable test
  dependency ports;
- both apps point at the same distributed PostgreSQL database and MinIO bucket.

Usage:
    scripts/dev/verify/e2e/validate_distributed.py --compose-file deploy/distributed/compose.yaml
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

EXPECTED_SERVICE_NETWORKS = {
    "postgres": {"node-a-db", "node-b-db"},
    "minio": {"node-a-db", "node-b-db"},
    "http-mock": {"node-a-db", "node-b-db"},
    "minio-init": {"node-a-db"},
    "workspace-init": {"daemon-a"},
    "app-a": {"node-a-db", "daemon-a", "app-ingress-a"},
    "app-b": {"node-b-db", "daemon-b", "app-ingress-b"},
    "daemon-a": {"daemon-a"},
    "daemon-b": {"daemon-b"},
}
EXPECTED_NETWORKS = {
    "node-a-db",
    "node-b-db",
    "daemon-a",
    "daemon-b",
    "app-ingress-a",
    "app-ingress-b",
}
EXPECTED_HOST_PORTS = {
    "postgres": {"15433"},
    "minio": {"19001"},
    "http-mock": {"18090"},
    "app-a": {"18082"},
    "app-b": {"18083"},
}
SHARED_APP_ENVIRONMENT = {
    "KK_STUDIO_DB_URL": "jdbc:postgresql://postgres:5432/kk_studio_distributed",
    "KK_STUDIO_STORAGE_S3_BUCKET": "kk-studio-distributed",
}


def fail(message: str) -> None:
    raise SystemExit(f"topology invariant violated: {message}")


def expand_config(compose_file: Path) -> dict:
    result = subprocess.run(
        ["docker", "compose", "-f", str(compose_file), "config", "--format", "json"],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        fail(f"compose config failed for {compose_file}: {result.stderr.strip()}")
    if not result.stdout.strip():
        fail("compose config produced no output")
    return json.loads(result.stdout)


def service_networks(config: dict, service: str) -> set:
    return set(config["services"][service].get("networks") or {})


def published_ports(config: dict, service: str) -> set:
    ports = config["services"][service].get("ports") or []
    return {str(port["published"]) for port in ports}


def validate(config: dict) -> None:
    services = config["services"]
    for service, expected in EXPECTED_SERVICE_NETWORKS.items():
        if service not in services:
            fail(f"missing service {service}")
        actual = service_networks(config, service)
        if actual != expected:
            fail(f"{service} networks {sorted(actual)} != {sorted(expected)}")

    if set(config["networks"]) != EXPECTED_NETWORKS:
        fail(f"unexpected network set: {sorted(config['networks'])}")

    for network in sorted(EXPECTED_NETWORKS):
        members = {
            service for service in services if network in service_networks(config, service)
        }
        if {"app-a", "app-b"} <= members:
            fail(f"network {network} contains both app-a and app-b")

    for service in sorted(services):
        actual = published_ports(config, service)
        expected = EXPECTED_HOST_PORTS.get(service, set())
        if actual != expected:
            fail(f"{service} host ports {sorted(actual)} != {sorted(expected)}")

    for service in ("app-a", "app-b"):
        environment = services[service].get("environment") or {}
        for variable, expected in SHARED_APP_ENVIRONMENT.items():
            if environment.get(variable) != expected:
                fail(f"{service} {variable} must be the shared {expected}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-file", type=Path, required=True)
    args = parser.parse_args()

    config = expand_config(args.compose_file)
    validate(config)
    print(
        "PASS distributed topology: "
        f"{len(services_of(config))} services, networks {sorted(config['networks'])}"
    )
    return 0


def services_of(config: dict) -> dict:
    return config["services"]


if __name__ == "__main__":
    sys.exit(main())
