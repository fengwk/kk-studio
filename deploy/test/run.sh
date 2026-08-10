#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")
APP_IMAGE=${CANVAS_TEST_APP_IMAGE:-kk-studio-app:canvas-test}
WITH_APP=false

usage() {
  cat <<'EOF'
Usage: deploy/test/run.sh [--with-app]

Builds the current application image, starts the isolated test dependencies,
waits for health checks, verifies ffmpeg/ffprobe and dependency reachability,
then always removes containers, networks, and volumes.

Options:
  --with-app  Also start the application service and wait for its healthcheck.
  -h, --help  Show this help.
EOF
}

step() {
  printf '\n==> %s\n' "$*"
}

configure_build_proxy() {
  export CANVAS_TEST_BUILD_HTTP_PROXY="${CANVAS_TEST_BUILD_HTTP_PROXY:-${HTTP_PROXY:-${http_proxy:-}}}"
  export CANVAS_TEST_BUILD_HTTPS_PROXY="${CANVAS_TEST_BUILD_HTTPS_PROXY:-${HTTPS_PROXY:-${https_proxy:-}}}"
  export CANVAS_TEST_BUILD_NO_PROXY="${CANVAS_TEST_BUILD_NO_PROXY:-${NO_PROXY:-${no_proxy:-}}}"

  local http_options=""
  local https_options=""
  http_options=$(java_proxy_options http "$CANVAS_TEST_BUILD_HTTP_PROXY") || true
  https_options=$(java_proxy_options https "$CANVAS_TEST_BUILD_HTTPS_PROXY") || true
  if [[ -z "$http_options" && -z "$https_options" ]]; then
    return
  fi

  export CANVAS_TEST_BUILD_NETWORK="${CANVAS_TEST_BUILD_NETWORK:-host}"
  if [[ -n "${CANVAS_TEST_BUILD_MAVEN_OPTS:-}" ]]; then
    step "Using host build proxy for Maven and npm"
    return
  fi
  export CANVAS_TEST_BUILD_MAVEN_OPTS="$http_options $https_options -Dhttp.nonProxyHosts=localhost|127.*|[::1]"
  step "Using host build proxy for Maven and npm"
}

java_proxy_options() {
  local protocol=$1
  local proxy=$2
  [[ -n "$proxy" ]] || return 1

  local authority=${proxy#http://}
  authority=${authority#https://}
  authority=${authority%/}
  [[ "$authority" != *"/"* && "$authority" != *"@"* && "$authority" == *":"* ]] || return 1

  local host=${authority%:*}
  local port=${authority##*:}
  [[ -n "$host" && "$port" =~ ^[0-9]+$ ]] || return 1
  printf -- '-D%s.proxyHost=%s -D%s.proxyPort=%s' "$protocol" "$host" "$protocol" "$port"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while (($#)); do
  case "$1" in
    --with-app)
      WITH_APP=true
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $1"
      ;;
  esac
  shift
done

command -v docker >/dev/null 2>&1 || die "docker is required"
docker info >/dev/null 2>&1 || die "Docker daemon is not available"
docker compose version >/dev/null 2>&1 || die "docker compose is required"
if [[ "$WITH_APP" == "true" ]]; then
  command -v python3 >/dev/null 2>&1 || die "python3 is required for the Canvas API smoke"
fi

configure_build_proxy

cleanup() {
  local status=$?
  if ((status != 0)); then
    step "Failure diagnostics"
    "${COMPOSE[@]}" ps -a || true
    "${COMPOSE[@]}" logs --no-color || true
  fi

  step "Cleaning isolated test stack"
  "${COMPOSE[@]}" --profile app down --volumes --remove-orphans || true
}
trap cleanup EXIT

step "Validating Compose configuration"
"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" --profile app config --quiet

step "Building the current application Dockerfile"
docker build \
  --file "$PROJECT_ROOT/deploy/local/Dockerfile" \
  --tag "$APP_IMAGE" \
  --network "${CANVAS_TEST_BUILD_NETWORK:-default}" \
  --build-arg "KK_STUDIO_BUILD_HTTP_PROXY=${CANVAS_TEST_BUILD_HTTP_PROXY:-}" \
  --build-arg "KK_STUDIO_BUILD_HTTPS_PROXY=${CANVAS_TEST_BUILD_HTTPS_PROXY:-}" \
  --build-arg "KK_STUDIO_BUILD_NO_PROXY=${CANVAS_TEST_BUILD_NO_PROXY:-}" \
  --build-arg "KK_STUDIO_MAVEN_BUILD_OPTS=${CANVAS_TEST_BUILD_MAVEN_OPTS:-}" \
  "$PROJECT_ROOT"

step "Starting isolated dependencies and waiting for health checks"
if [[ "$WITH_APP" == "true" ]]; then
  "${COMPOSE[@]}" --profile app up -d --wait --no-build
else
  "${COMPOSE[@]}" up -d --wait
fi

step "Checking ffmpeg, ffprobe, and the non-root runtime user"
"${COMPOSE[@]}" --profile app run --rm --no-deps --entrypoint /bin/sh app -ec '
  test "$(id -un)" = "kkstudio"
  command -v ffmpeg
  command -v ffprobe
  ffmpeg -version | head -n 1
  ffprobe -version | head -n 1
'

step "Checking PostgreSQL"
postgres_result=$(
  "${COMPOSE[@]}" exec -T postgres \
    psql -U canvas_test -d canvas_test -v ON_ERROR_STOP=1 -Atqc "SELECT 1"
)
[[ "$postgres_result" == "1" ]] || die "PostgreSQL SELECT 1 returned '$postgres_result'"

step "Checking MinIO and initialized bucket"
"${COMPOSE[@]}" exec -T minio-init \
  /bin/sh -ec 'mc ready local >/dev/null && mc stat "local/$MINIO_BUCKET" >/dev/null'

step "Checking HTTP mock"
"${COMPOSE[@]}" exec -T http-mock python -c '
import json
import urllib.request

with urllib.request.urlopen("http://127.0.0.1:8080/health", timeout=2) as response:
    assert response.status == 200
    assert json.load(response) == {"status": "ok"}
'

if [[ "$WITH_APP" == "true" ]]; then
  step "Checking Canvas Resource reserve, direct PUT, finalize, and preview GET"
  CANVAS_TEST_APP_URL="http://127.0.0.1:${CANVAS_TEST_APP_PORT:-18088}" \
  CANVAS_TEST_IMAGE_FIXTURE="$SCRIPT_DIR/../../core/src/test/resources/fun/fengwk/kkstudio/core/studio/resource/tiny.png" \
    python3 - <<'PY'
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

base_url = os.environ["CANVAS_TEST_APP_URL"]
image = Path(os.environ["CANVAS_TEST_IMAGE_FIXTURE"]).read_bytes()


def json_call(method: str, path: str, body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    request = urllib.request.Request(
        base_url + path, data=data, headers=headers, method=method
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        assert 200 <= response.status < 300
        return json.load(response)["data"]


canvas = json_call("POST", "/api/canvases", {"title": "container-resource-smoke"})
reservation = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/uploads",
    {
        "kind": "IMAGE",
        "filename": "tiny.png",
        "mediaType": "image/png",
        "size": str(len(image)),
    },
)
assert "bucket" not in reservation and "key" not in reservation
assert next(
    value
    for name, value in reservation["headers"].items()
    if name.lower() == "if-none-match"
) == "*"
put = urllib.request.Request(
    reservation["url"],
    data=image,
    headers=reservation.get("headers") or {},
    method=reservation["method"],
)
with urllib.request.urlopen(put, timeout=30) as response:
    assert response.status == 200

replacement = bytes([image[0] ^ 0xFF]) + image[1:]
overwrite = urllib.request.Request(
    reservation["url"],
    data=replacement,
    headers=reservation.get("headers") or {},
    method=reservation["method"],
)
try:
    urllib.request.urlopen(overwrite, timeout=30)
except urllib.error.HTTPError as error:
    assert error.code in (409, 412), error.code
else:
    raise AssertionError("create-only presigned PUT unexpectedly overwrote original")

resource = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/uploads/{reservation['uploadId']}/complete",
)
assert resource["id"] == reservation["uploadId"]
assert resource["mediaType"] == "image/png"
metadata = json.loads(resource["metadataJson"])
assert metadata["width"] > 0 and metadata["height"] > 0

original = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/resources/{resource['id']}/download-url",
)
with urllib.request.urlopen(original["url"], timeout=30) as response:
    assert response.status == 200
    assert response.read() == image

preview = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/resources/{resource['id']}/preview-url",
)
assert "bucket" not in preview and "key" not in preview
with urllib.request.urlopen(preview["url"], timeout=30) as response:
    body = response.read()
    assert response.status == 200
    assert body.startswith(b"RIFF") and b"WEBP" in body[:16]

snapshot = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/commands",
    {
        "expectedRevision": "0",
        "commandId": "container-function-node",
        "commands": [
            {
                "type": "CREATE_FUNCTION_NODE",
                "name": "generated",
                "modelKey": "fake-image",
                "configJson": json.dumps(
                    {
                        "prompt": {
                            "segments": [
                                {"type": "TEXT", "text": "free deterministic image"}
                            ]
                        },
                        "parameters": {"ratio": "16:9"},
                    },
                    separators=(",", ":"),
                ),
                "transform": {"x": 100, "y": 100, "width": 320, "height": 260},
            }
        ],
    },
)
function_node = next(node for node in snapshot["nodes"] if node["name"] == "generated")
assert snapshot["document"]["graphRevision"] == "1"
started = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/nodes/{function_node['id']}/runs",
    {"requestId": "container-fake-image"},
)
assert started["requestId"] == "container-fake-image"
for _ in range(120):
    current = json_call(
        "GET",
        f"/api/canvases/{canvas['id']}/nodes/{function_node['id']}/run",
    )
    if current["status"] != "RUNNING":
        break
    time.sleep(0.25)
else:
    raise AssertionError("fake-image FunctionRun did not reach terminal state")
assert current["status"] == "SUCCEEDED", current
assert "stateJson" not in current

generated_snapshot = json_call("GET", f"/api/canvases/{canvas['id']}")
generated_node = next(
    node for node in generated_snapshot["nodes"] if node["id"] == function_node["id"]
)
assert generated_snapshot["document"]["graphRevision"] == "1"
assert len(generated_node["resources"]) == 1
generated_resource = generated_node["resources"][0]
assert generated_resource["kind"] == "IMAGE"
assert generated_resource["name"] == "generated.png"
generated_preview = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/resources/{generated_resource['id']}/preview-url",
)
with urllib.request.urlopen(generated_preview["url"], timeout=30) as response:
    body = response.read()
    assert response.status == 200
    assert body.startswith(b"RIFF") and b"WEBP" in body[:16]

models = json_call("GET", "/api/canvas-function-models")
for model_key in (
    "gpt-image-2",
    "seedance2.0",
    "seedance2.0fast",
    "seedance2.0_vip",
    "seedance2.0fast_vip",
):
    model = next(item for item in models if item["key"] == model_key)
    assert model["available"] is True, model
assert all(item["key"] != "seedance2.0mini" for item in models)


def create_and_run(model_key: str, name: str, parameters: dict) -> dict:
    current = json_call("GET", f"/api/canvases/{canvas['id']}")
    command = json_call(
        "POST",
        f"/api/canvases/{canvas['id']}/commands",
        {
            "expectedRevision": current["document"]["graphRevision"],
            "commandId": f"container-{model_key}",
            "commands": [
                {
                    "type": "CREATE_FUNCTION_NODE",
                    "name": name,
                    "modelKey": model_key,
                    "configJson": json.dumps(
                        {
                            "prompt": {
                                "segments": [
                                    {"type": "TEXT", "text": f"mock {model_key}"}
                                ]
                            },
                            "parameters": parameters,
                        },
                        separators=(",", ":"),
                    ),
                    "transform": {"x": 100, "y": 100, "width": 320, "height": 260},
                }
            ],
        },
    )
    node = next(item for item in command["nodes"] if item["name"] == name)
    run = json_call(
        "POST",
        f"/api/canvases/{canvas['id']}/nodes/{node['id']}/runs",
        {"requestId": f"container-{model_key}-run"},
    )
    for _ in range(160):
        if run["status"] != "RUNNING":
            break
        time.sleep(0.1)
        run = json_call(
            "GET", f"/api/canvases/{canvas['id']}/nodes/{node['id']}/run"
        )
    assert run["status"] == "SUCCEEDED", run
    snapshot = json_call("GET", f"/api/canvases/{canvas['id']}")
    return next(item for item in snapshot["nodes"] if item["id"] == node["id"])


gpt_node = create_and_run("gpt-image-2", "mock-gpt", {"ratio": "1:1"})
assert len(gpt_node["resources"]) == 1
assert gpt_node["resources"][0]["kind"] == "IMAGE"

seedance_node = create_and_run(
    "seedance2.0fast", "mock-seedance", {"ratio": "16:9", "duration": 4}
)
assert len(seedance_node["resources"]) == 1
assert seedance_node["resources"][0]["kind"] == "VIDEO"
PY
fi

step "All isolated container checks passed"
