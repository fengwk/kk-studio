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

step "Resetting isolated test stack"
"${COMPOSE[@]}" --profile app down --volumes --remove-orphans

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

request = urllib.request.Request(
    "http://127.0.0.1:8080/v1/chat/completions",
    data=json.dumps(
        {"model": "acceptance-stub", "messages": [{"role": "user", "content": "probe"}]}
    ).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(request, timeout=5) as response:
    assert response.status == 200
    assert response.headers.get_content_type() == "text/event-stream"
    body = response.read().decode()
    assert body.endswith("data: [DONE]\n\n"), body
    chunks = []
    for event in body.split("\n\n"):
        if not event:
            continue
        assert event.startswith("data: "), event
        data = event.removeprefix("data: ")
        if data == "[DONE]":
            continue
        chunks.append(json.loads(data))
    assert chunks, "no chat completion chunks"
    assert all(chunk["object"] == "chat.completion.chunk" for chunk in chunks), chunks
    assert chunks[0]["choices"][0]["delta"]["role"] == "assistant", chunks[0]
    text = "".join(
        chunk["choices"][0]["delta"].get("content", "") for chunk in chunks
    )
    assert text == "This is a deterministic offline acceptance stub response.", text
    assert chunks[-1]["choices"][0]["finish_reason"] == "stop", chunks[-1]
'

if [[ "$WITH_APP" == "true" ]]; then
  step "Checking global Blob upload, Canvas consumption, Function runs, signed media GET, and offline Chat"
  CANVAS_TEST_APP_URL="http://127.0.0.1:${CANVAS_TEST_APP_PORT:-18088}" \
  CANVAS_TEST_IMAGE_FIXTURE="$SCRIPT_DIR/../../platform/src/test/resources/fun/fengwk/kkstudio/platform/canvas/resource/tiny.png" \
    python3 - <<'PY'
import base64
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.request
import uuid
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


def new_id() -> str:
    return str(uuid.uuid4())


def header(headers: dict, name: str) -> str:
    return next(value for key, value in headers.items() if key.lower() == name.lower())


def decimal_version(value: object) -> int:
    assert isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value), value
    return int(value)


def upserted_node(patch: dict, node_id: str) -> dict:
    return next(
        item["node"]
        for item in patch["nodes"]
        if item["op"] == "UPSERT" and item["node"]["id"] == node_id
    )


canvas = json_call("POST", "/api/canvases", {"title": "container-resource-smoke"})
assert uuid.UUID(canvas["id"]).version == 4
assert decimal_version(canvas["version"]) == 0
assert "threadId" not in canvas
sha256 = hashlib.sha256(image).hexdigest()
reservation = json_call(
    "POST",
    "/api/storage/uploads",
    {
        "filename": "tiny.png",
        "mediaType": "image/png",
        "sizeBytes": len(image),
        "sha256": sha256,
    },
)
assert "bucket" not in reservation and "key" not in reservation
assert reservation["state"] == "PENDING"
assert reservation["blobId"] is None
assert uuid.UUID(reservation["id"]).version == 4
presigned_put = reservation["presignedPut"]
assert header(presigned_put["headers"], "if-none-match") == "*"
assert header(presigned_put["headers"], "x-amz-checksum-sha256") == base64.b64encode(
    bytes.fromhex(sha256)
).decode()
put = urllib.request.Request(
    presigned_put["url"],
    data=image,
    headers=presigned_put.get("headers") or {},
    method=presigned_put["method"],
)
with urllib.request.urlopen(put, timeout=30) as response:
    assert response.status == 200

# 同内容重放验证 create-only；不同内容重放同时受 create-only 与 checksum 保护。
duplicate = urllib.request.Request(
    presigned_put["url"],
    data=image,
    headers=presigned_put.get("headers") or {},
    method=presigned_put["method"],
)
try:
    urllib.request.urlopen(duplicate, timeout=30)
except urllib.error.HTTPError as error:
    assert error.code in (409, 412), error.code
else:
    raise AssertionError("create-only presigned PUT unexpectedly accepted a duplicate")

replacement = bytes([image[0] ^ 0xFF]) + image[1:]
overwrite = urllib.request.Request(
    presigned_put["url"],
    data=replacement,
    headers=presigned_put.get("headers") or {},
    method=presigned_put["method"],
)
try:
    urllib.request.urlopen(overwrite, timeout=30)
except urllib.error.HTTPError as error:
    assert error.code in (400, 409, 412), error.code
else:
    raise AssertionError("create-only presigned PUT unexpectedly overwrote original")

completed = json_call(
    "POST",
    f"/api/storage/uploads/{reservation['id']}/complete",
)
assert completed["id"] == reservation["id"]
assert completed["state"] == "READY"
assert completed["presignedPut"] is None
assert uuid.UUID(completed["blobId"]).version == 4

resource_node_id = new_id()
resource_patch = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/commands",
    {
        "expectedVersion": "0",
        "idempotencyKey": new_id(),
        "commands": [
            {
                "type": "CREATE_RESOURCE_NODE",
                "nodeId": resource_node_id,
                "name": "uploaded",
                "uploadIds": [reservation["id"]],
                "transform": {"x": 0, "y": 0, "width": 320, "height": 260},
            }
        ],
    },
)
assert decimal_version(resource_patch["baseVersion"]) == 0
assert decimal_version(resource_patch["version"]) == 1
resource_node = upserted_node(resource_patch, resource_node_id)
resource = resource_node["resources"][0]
assert resource["blobId"] == completed["blobId"]
assert resource["mediaType"] == "image/png"
assert resource["width"] > 0 and resource["height"] > 0
assert resource["sizeBytes"] == str(len(image))

resource_snapshot = json_call("GET", f"/api/canvases/{canvas['id']}")
assert decimal_version(resource_snapshot["document"]["version"]) == 1
snapshot_resource_node = next(
    node for node in resource_snapshot["nodes"] if node["id"] == resource_node_id
)
assert snapshot_resource_node["resources"][0]["id"] == resource["id"]

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

function_node_id = new_id()
function_patch = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/commands",
    {
        "expectedVersion": "1",
        "idempotencyKey": new_id(),
        "commands": [
            {
                "type": "CREATE_FUNCTION_NODE",
                "nodeId": function_node_id,
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
assert decimal_version(function_patch["baseVersion"]) == 1
assert decimal_version(function_patch["version"]) == 2
function_node = upserted_node(function_patch, function_node_id)
request_id = new_id()
started = json_call(
    "POST",
    f"/api/canvases/{canvas['id']}/nodes/{function_node['id']}/runs",
    {"requestId": request_id},
)
assert started["requestId"] == request_id
for _ in range(120):
    current = json_call(
        "GET",
        f"/api/canvases/{canvas['id']}/nodes/{function_node['id']}/run",
    )
    if current["status"] not in ("READY", "RUNNING"):
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
assert decimal_version(generated_snapshot["document"]["version"]) == 5
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


def create_and_run(
    model_key: str, name: str, parameters: dict, expected_checkpoint_count: int
) -> dict:
    current = json_call("GET", f"/api/canvases/{canvas['id']}")
    current_version = decimal_version(current["document"]["version"])
    node_id = new_id()
    patch = json_call(
        "POST",
        f"/api/canvases/{canvas['id']}/commands",
        {
            "expectedVersion": current["document"]["version"],
            "idempotencyKey": new_id(),
            "commands": [
                {
                    "type": "CREATE_FUNCTION_NODE",
                    "nodeId": node_id,
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
    assert patch["baseVersion"] == current["document"]["version"]
    patch_version = decimal_version(patch["version"])
    assert patch_version == current_version + 1
    node = upserted_node(patch, node_id)
    request_id = new_id()
    run = json_call(
        "POST",
        f"/api/canvases/{canvas['id']}/nodes/{node['id']}/runs",
        {"requestId": request_id},
    )
    for _ in range(160):
        if run["status"] not in ("READY", "RUNNING"):
            break
        time.sleep(0.1)
        run = json_call(
            "GET", f"/api/canvases/{canvas['id']}/nodes/{node['id']}/run"
        )
    assert run["status"] == "SUCCEEDED", run
    snapshot = json_call("GET", f"/api/canvases/{canvas['id']}")
    # start、每个 durable checkpoint 与 terminal success 都独立前进 Canvas version。
    actual_version = decimal_version(snapshot["document"]["version"])
    expected_version = patch_version + expected_checkpoint_count + 2
    assert actual_version == expected_version, {
        "modelKey": model_key,
        "actualVersion": actual_version,
        "expectedVersion": expected_version,
    }
    return next(item for item in snapshot["nodes"] if item["id"] == node["id"])


gpt_node = create_and_run("gpt-image-2", "mock-gpt", {"ratio": "1:1"}, 4)
assert len(gpt_node["resources"]) == 1
assert gpt_node["resources"][0]["kind"] == "IMAGE"

seedance_node = create_and_run(
    "seedance2.0fast", "mock-seedance", {"ratio": "16:9", "duration": 4}, 6
)
assert len(seedance_node["resources"]) == 1
assert seedance_node["resources"][0]["kind"] == "VIDEO"

# Offline Chat smoke: dev seed default-assistant -> stub/acceptance-stub, with the
# stub provider endpoint resolving to the in-stack http-mock SSE stub.
providers = json_call("GET", "/api/ai/catalog/providers?pageNumber=1&pageSize=50")
stub = next(item for item in providers["results"] if item["name"] == "stub")
assert stub["providerType"] == "openai", stub
assert stub["baseUrl"] == "http://stub.local:8080/v1", stub
assert stub["configured"] is True, stub

chat = json_call(
    "POST",
    "/api/ai/chat",
    {"title": "offline-chat-smoke", "agentName": "default-assistant", "yoloEnabled": False},
)
assert chat["agentName"] == "default-assistant", chat
marker = "offline-chat-smoke-" + uuid.uuid4().hex[:8]
# 唯一产品写入口：NEW_SESSION 一次原子物化 Session + ROOT + Thread 并接受首条 USER_MESSAGE。
session_id = str(uuid.uuid4())
thread_id = str(uuid.uuid4())
accepted = json_call(
    "POST",
    "/api/ai/runtime/command-batches",
    {
        "owner": {"type": "CHAT", "id": chat["id"]},
        "target": {
            "type": "NEW_SESSION",
            "sessionId": session_id,
            "threadId": thread_id,
            "rootSettings": {
                "environment": None,
                "agentName": "default-assistant",
                "model": {
                    "providerName": "stub",
                    "modelName": "acceptance-stub",
                    "variant": "default",
                },
            },
            "yoloEnabled": False,
        },
        "commands": [
            {
                "type": "USER_MESSAGE",
                "idempotencyKey": new_id(),
                "contents": [{"type": "TEXT", "text": marker}],
            }
        ],
    },
)
thread = accepted["thread"]
assert accepted["replayed"] is False, accepted
assert accepted["acceptedCommands"][0]["type"] == "USER_MESSAGE", accepted
# 不锁定 accepted 快照的瞬时 status（processor 可能已异步消费）；只锁定 threadId 归属，
# 随后轮询等待 quiescent 收敛。
assert thread["threadId"] == thread_id, thread
for _ in range(240):
    current = json_call(
        "GET", f"/api/ai/runtime/threads/{thread_id}/snapshot"
    )
    candidate = current["thread"]
    if (
        candidate["status"] == "IDLE"
        and candidate["processing"] is False
        and len(current["queuedCommands"]) == 0
        and current["modelInvocation"] is None
        and len(current["toolInvocations"]) == 0
    ):
        break
    time.sleep(0.25)
else:
    raise AssertionError(
        f"offline Chat thread did not become quiescent: {json.dumps(current)}"
    )
assert current["thread"]["status"] == "IDLE", current
assert not any(
    entry["entryType"].upper() == "ASSISTANT_ERROR" for entry in current["entries"]
), current["entries"]
user_entries = [
    entry
    for entry in current["entries"]
    if entry["entryType"].upper() == "MESSAGE"
    and json.loads(entry["payloadJson"])["message"]["role"] == "USER"
]
assert user_entries, f"no durable USER entry: {json.dumps(current['entries'])}"
assert marker in json.dumps(user_entries[-1]), current["entries"]
assistant_entries = [
    entry
    for entry in current["entries"]
    if entry["entryType"].upper() == "MESSAGE"
    and json.loads(entry["payloadJson"])["message"]["role"] == "ASSISTANT"
]
assert assistant_entries, f"no durable assistant reply: {json.dumps(current['entries'])}"
assistant_text = "".join(
    content.get("text", "")
    for content in json.loads(assistant_entries[-1]["payloadJson"])["message"]["contents"]
    if content.get("type") == "text"
)
assert "offline acceptance stub" in assistant_text, assistant_text
turn_end = next(
    entry for entry in current["entries"] if entry["entryType"].upper() == "TURN_END"
)
assert json.loads(turn_end["payloadJson"])["outcome"] == "COMPLETED", turn_end
PY
fi

step "All isolated container checks passed"
