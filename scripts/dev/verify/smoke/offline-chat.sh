#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# 仓库根解析：KK_STUDIO_REPO_ROOT 优先；否则从脚本位置向上寻找 worktree 根（.git 文件或目录），
# 不依赖本脚本在仓库中的深度。
if [ -n "${KK_STUDIO_REPO_ROOT:-}" ]; then
  REPO_ROOT=$(cd "$KK_STUDIO_REPO_ROOT" && pwd)
else
  REPO_ROOT=$SCRIPT_DIR
  while [ "$REPO_ROOT" != "/" ] && [ ! -e "$REPO_ROOT/.git" ]; do
    REPO_ROOT=$(dirname "$REPO_ROOT")
  done
  if [ ! -e "$REPO_ROOT/.git" ]; then
    echo "ERROR: cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT" >&2
    exit 1
  fi
fi
# Compose、Dockerfile 与 mock 运行时资产属于 deploy/；本脚本只做宿主 smoke 编排。
COMPOSE_FILE="$REPO_ROOT/deploy/test/compose.yaml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")
APP_IMAGE=${CANVAS_TEST_APP_IMAGE:-kk-studio-app:canvas-test}
WITH_APP=false

usage() {
  cat <<'EOF'
Usage: scripts/dev/verify/smoke/offline-chat.sh [--with-app]

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
  command -v node >/dev/null 2>&1 || die "node is required for the Canvas API smoke"
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
  --file "$REPO_ROOT/deploy/local/Dockerfile" \
  --tag "$APP_IMAGE" \
  --network "${CANVAS_TEST_BUILD_NETWORK:-default}" \
  --build-arg "KK_STUDIO_BUILD_HTTP_PROXY=${CANVAS_TEST_BUILD_HTTP_PROXY:-}" \
  --build-arg "KK_STUDIO_BUILD_HTTPS_PROXY=${CANVAS_TEST_BUILD_HTTPS_PROXY:-}" \
  --build-arg "KK_STUDIO_BUILD_NO_PROXY=${CANVAS_TEST_BUILD_NO_PROXY:-}" \
  --build-arg "KK_STUDIO_MAVEN_BUILD_OPTS=${CANVAS_TEST_BUILD_MAVEN_OPTS:-}" \
  "$REPO_ROOT"

step "Building the shared MinIO client image"
"${COMPOSE[@]}" build minio-init

step "Starting isolated dependencies and waiting for health checks"
if [[ "$WITH_APP" == "true" ]]; then
  # app 以 service_completed_successfully 等待一次性的 minio-init，因此 --wait 能收敛。
  "${COMPOSE[@]}" --profile app up -d --wait --no-build
else
  # 未启用 app 时没有任何服务以 service_completed_successfully 依赖 minio-init，
  # compose up --wait 会把已成功退出的一次性容器当作失败；这里只等待常驻依赖健康，
  # bucket 初始化改由下面显式的一次性运行承担。
  "${COMPOSE[@]}" up -d --wait postgres minio http-mock
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

step "Initializing and checking the MinIO bucket"
# 一次性、幂等：每次都以全新容器运行完整 bucket 初始化，失败（非 0 退出）即失败。
"${COMPOSE[@]}" run --rm --no-deps minio-init

step "Checking HTTP mock"
"${COMPOSE[@]}" exec -T http-mock node -e '
const assert = require("node:assert/strict")

async function main() {
  const health = await fetch("http://127.0.0.1:8080/health", { signal: AbortSignal.timeout(2000) })
  assert.equal(health.status, 200)
  assert.deepEqual(await health.json(), { status: "ok" })

  const response = await fetch("http://127.0.0.1:8080/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ model: "acceptance-stub", messages: [{ role: "user", content: "probe" }] }),
    signal: AbortSignal.timeout(5000),
  })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get("content-type"), "text/event-stream")
  const body = await response.text()
  assert.ok(body.endsWith("data: [DONE]\n\n"), body)
  const chunks = []
  for (const event of body.split("\n\n")) {
    if (!event) {
      continue
    }
    assert.ok(event.startsWith("data: "), event)
    const data = event.slice("data: ".length)
    if (data !== "[DONE]") {
      chunks.push(JSON.parse(data))
    }
  }
  assert.ok(chunks.length > 0, "no chat completion chunks")
  assert.ok(chunks.every((chunk) => chunk.object === "chat.completion.chunk"), JSON.stringify(chunks))
  assert.equal(chunks[0].choices[0].delta.role, "assistant")
  const text = chunks.map((chunk) => chunk.choices[0].delta.content ?? "").join("")
  assert.equal(text, "This is a deterministic offline acceptance stub response.")
  assert.equal(chunks.at(-1).choices[0].finish_reason, "stop")
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
'

if [[ "$WITH_APP" == "true" ]]; then
  step "Checking global Blob upload, Canvas consumption, Function runs, signed media GET, and offline Chat"
  CANVAS_TEST_APP_URL="http://127.0.0.1:${CANVAS_TEST_APP_PORT:-18088}" \
  CANVAS_TEST_IMAGE_FIXTURE="$REPO_ROOT/platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/canvas/resource/tiny.png" \
    node "$SCRIPT_DIR/offline-smoke.mjs"
fi

step "All isolated container checks passed"
