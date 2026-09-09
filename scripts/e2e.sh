#!/usr/bin/env bash
# scripts/e2e.sh — kk-studio 矩阵化 E2E 入口
#
# 快速开始：
#   ./scripts/e2e.sh                  # 复用已启动服务，跑 L1（免费）
#   ./scripts/e2e.sh --rebuild        # 强制 Java 21 clean package 并重启 backend/frontend
#   ./scripts/e2e.sh --real           # + 真实四 Provider 轮次（需 8 个 TEST_* 变量）
#   ./scripts/e2e.sh --real --with-branch
#   ./scripts/e2e.sh --real --with-tools --with-canvas-storage  # 真实 Tool turn（Resource 外部化需 S3）
#   ./scripts/e2e.sh --distributed    # + 双节点 distributed mock topology（免费）
#   ./scripts/e2e.sh --ui              # + Playwright UI E2E（截图进报告）
#   ./scripts/e2e.sh --list           # 只打印矩阵，不执行
#
# 文档事实源：
#   docs/operations/development-and-testing.md
#   node scripts/e2e/run-matrix.mjs --docs
#
# 报告（gitignore）：
#   reports/e2e/<runId>/report.md
#   reports/e2e/latest/report.md
#
# 维护约定：
# - Runner 使用 Node（与前端 npm 同栈）；环境启停仍用 bash
# - 前端 UI 易变：默认矩阵断言 HTTP 契约/状态机，不把脆弱 selector 当门槛
# - 改 API 字段/首发顺序/usage 语义时，必须同步更新矩阵 case 与文档
# - 真模型默认关闭，避免 CI/本地无意识扣费
# - --distributed 是正交 capability，只启停 deploy/distributed 栈，不改变默认单实例路径

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=e2e/lib.sh
source "$SCRIPT_DIR/e2e/lib.sh"

REBUILD=false
WITH_TOOLS=false
WITH_BRANCH=false
WITH_CANVAS_STORAGE=false
WITH_CANVAS_FUNCTION=false
REAL=false
WITH_UI=false
DISTRIBUTED=false
LIST_ONLY=false
DOCS_ONLY=false
ONLY_ARGS=()
LEVEL_ARGS=()

usage() {
  cat <<'EOF'
Usage: ./scripts/e2e.sh [options]

Options:
  --rebuild         Force clean package backend + restart backend/frontend (+daemon if tools)
  --real            Enable real provider cases (requires all 8 TEST_*_BASE_URL/API_KEY credentials)
  --with-tools      Enable daemon/tool cases (starts/reuses daemon)
  --with-branch     Enable branch usage cases (implies --real)
  --with-canvas-storage  Enable Canvas Resource/Blob storage contract (backend S3 config required; also a precondition of real tool.read_turn)
  --with-canvas-function Enable free fake Canvas Function E2E (implies storage + rebuild)
  --distributed     Run the two-node distributed mock topology (free; no real provider)
  --ui              Enable Playwright UI E2E (screenshots in report)
  --only <caseId>   Run one case id (repeatable)
  --level <Lx>      Filter by level L1/L2/L3/L4/L5 (repeatable)
  --list            List matrix cases
  --docs            Print case docs
  -h, --help        Show help

Env:
  BACKEND_PORT=18081
  FRONTEND_PORT=5173
  TEST_GOOGLE_BASE_URL / TEST_GOOGLE_API_KEY
  TEST_OPENAI_BASE_URL / TEST_OPENAI_API_KEY
  TEST_ANTHROPIC_BASE_URL / TEST_ANTHROPIC_API_KEY
  TEST_DEEPSEEK_BASE_URL / TEST_DEEPSEEK_API_KEY
  # consumed only with --real; free modes never synchronize host credentials
  # --real validates all 8 variables are non-empty before startup and synchronizes them to backend
  JAVA_HOME_21=...
  E2E_MAVEN_OFFLINE=true  # opt into Maven -o; default is online
  E2E_WORK_DIR=...        # default: $REPO_ROOT/runtime/e2e
  DAEMON_ENV_ROOT=...     # optional override; default: $WORK_DIR/environment
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --rebuild) REBUILD=true; shift ;;
    --real) REAL=true; shift ;;
    --with-tools) WITH_TOOLS=true; shift ;;
    --with-branch) WITH_BRANCH=true; REAL=true; shift ;;
    --with-canvas-storage) WITH_CANVAS_STORAGE=true; shift ;;
    --with-canvas-function)
      WITH_CANVAS_FUNCTION=true
      WITH_CANVAS_STORAGE=true
      REBUILD=true
      export KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED=true
      shift
      ;;
    --ui) WITH_UI=true; shift ;;
    --distributed) DISTRIBUTED=true; shift ;;
    --only) ONLY_ARGS+=(--only "$2"); shift 2 ;;
    --level) LEVEL_ARGS+=(--level "$2"); shift 2 ;;
    --list) LIST_ONLY=true; shift ;;
    --docs) DOCS_ONLY=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

if [ "$LIST_ONLY" = "true" ]; then
  node "$SCRIPT_DIR/e2e/run-matrix.mjs" --list
  exit 0
fi
if [ "$DOCS_ONLY" = "true" ]; then
  node "$SCRIPT_DIR/e2e/run-matrix.mjs" --docs
  exit 0
fi

# Free Canvas Function regression needs both the E2E catalog/permission seed and the
# disposable canvas-test S3/integration seed. Preserve an explicit caller override.
if [ "$WITH_CANVAS_FUNCTION" = "true" ]; then
  export SPRING_FLYWAY_LOCATIONS=${SPRING_FLYWAY_LOCATIONS:-classpath:db/migration,classpath:db/seed/e2e,classpath:db/seed/canvas-test}
fi

DISTRIBUTED_RUNNER="$SCRIPT_DIR/../deploy/distributed/run.sh"

# --distributed 是正交 capability：默认单实例路径完全不变；只有显式 --distributed
# 才启停隔离的双节点免费 mock 栈，不与真实 Provider、宿主 Daemon、UI 或 storage
# capability 组合。
if [ "$DISTRIBUTED" = "true" ]; then
  for flag in REAL WITH_TOOLS WITH_BRANCH WITH_CANVAS_STORAGE WITH_CANVAS_FUNCTION WITH_UI; do
    if [ "${!flag}" = "true" ]; then
      die "--distributed cannot be combined with --${flag} capability"
    fi
  done
  command -v docker >/dev/null 2>&1 || die "docker is required for --distributed"
  [ -x "$DISTRIBUTED_RUNNER" ] || die "missing distributed runner: $DISTRIBUTED_RUNNER"
  step "Ensuring distributed two-node stack"
  "$DISTRIBUTED_RUNNER" up --skip-build || "$DISTRIBUTED_RUNNER" up
  APP_A_PORT=${DISTRIBUTED_APP_A_PORT:-18082}
  APP_B_PORT=${DISTRIBUTED_APP_B_PORT:-18083}
  BACKEND_URL="http://127.0.0.1:$APP_A_PORT"
  # 双节点模式不启动宿主 frontend；置空可让 runner 自动排除 frontend case。
  FRONTEND_URL=""
  cleanup_distributed() {
    local status=$?
    set +e
    "$DISTRIBUTED_RUNNER" down --volumes >/dev/null 2>&1
    exit $status
  }
  trap cleanup_distributed EXIT
fi

# Gate real provider credentials before any process startup or HTTP request
if [ "$REAL" = "true" ]; then
  REAL_CREDENTIAL_VARS=(
    TEST_GOOGLE_BASE_URL
    TEST_GOOGLE_API_KEY
    TEST_OPENAI_BASE_URL
    TEST_OPENAI_API_KEY
    TEST_ANTHROPIC_BASE_URL
    TEST_ANTHROPIC_API_KEY
    TEST_DEEPSEEK_BASE_URL
    TEST_DEEPSEEK_API_KEY
  )
  missing_vars=()
  for var_name in "${REAL_CREDENTIAL_VARS[@]}"; do
    if [ -z "${!var_name-}" ]; then
      missing_vars+=("$var_name")
    fi
  done
  if [ ${#missing_vars[@]} -gt 0 ]; then
    die "--real requires all 8 credential variables to be set non-empty; missing: ${missing_vars[*]}"
  fi
fi

# 1) ensure stack（--distributed 时栈即 deploy/distributed，跳过宿主单实例栈）
if [ "$DISTRIBUTED" != "true" ]; then
  ensure_stack "$REBUILD" "$WITH_TOOLS"
fi

# 2) synchronize provider credentials; --real uses seeded providers
if [ "$REAL" = "true" ]; then
  sync_e2e_provider_credentials
fi
if [ "$REAL" = "true" ]; then
  providers_ready=$(curl -fsS "$BACKEND_URL/api/ai/catalog/providers?pageNumber=1&pageSize=50" \
    | python3 -c 'import sys,json
d=json.load(sys.stdin)
rows=((d.get("data") or {}).get("results") or [])
target_names={"google", "openai", "minimax-anthropic", "deepseek"}
configured=set(r.get("name") for r in rows if r.get("configured") and r.get("baseUrl"))
missing=target_names - configured
print("missing:" + ",".join(sorted(missing)) if missing else "ok")')
  if [ "$providers_ready" != "ok" ]; then
    die "All 4 target providers (google, openai, minimax-anthropic, deepseek) must be configured for --real ($providers_ready)"
  fi
fi

# 3) run matrix
MATRIX_ARGS=(
  --base-url "$BACKEND_URL"
  --daemon-env "$DAEMON_ENV_NAME"
)
if [ "$DISTRIBUTED" != "true" ]; then
  MATRIX_ARGS+=(--frontend-url "$FRONTEND_URL")
fi
if [ "$DISTRIBUTED" = "true" ]; then
  MATRIX_ARGS+=(--distributed --base-url-b "http://127.0.0.1:$APP_B_PORT")
fi
if [ "$REAL" = "true" ]; then MATRIX_ARGS+=(--real); fi
if [ "$WITH_TOOLS" = "true" ]; then MATRIX_ARGS+=(--with-tools); fi
if [ "$WITH_BRANCH" = "true" ]; then MATRIX_ARGS+=(--with-branch); fi
if [ "$WITH_CANVAS_STORAGE" = "true" ]; then MATRIX_ARGS+=(--with-canvas-storage); fi
if [ "$WITH_CANVAS_FUNCTION" = "true" ]; then MATRIX_ARGS+=(--with-canvas-function); fi
MATRIX_ARGS+=("${ONLY_ARGS[@]}")
MATRIX_ARGS+=("${LEVEL_ARGS[@]}")

step "Running matrix"
set +e
node "$SCRIPT_DIR/e2e/run-matrix.mjs" "${MATRIX_ARGS[@]}"
MATRIX_RC=$?
set -e

# UI E2E 复用 latest 报告目录，把截图并入同一 run
if [ "$WITH_UI" = "true" ]; then
  LATEST_DIR="$REPO_ROOT/reports/e2e/latest"
  if [ ! -d "$LATEST_DIR" ]; then
    die "matrix report latest dir missing; cannot attach UI artifacts"
  fi
  step "Running UI E2E into $LATEST_DIR"
  set +e
  UI_ARGS=(
    --base-url "$FRONTEND_URL"
    --backend-url "$BACKEND_URL"
    --report-dir "$LATEST_DIR"
  )
  if [ "$REAL" = "true" ]; then
    UI_ARGS+=(--real)
  fi
  if [ "$WITH_TOOLS" = "true" ]; then
    UI_ARGS+=(--with-tools --daemon-env "$DAEMON_ENV_NAME")
  fi
  node "$SCRIPT_DIR/e2e/ui-smoke.mjs" "${UI_ARGS[@]}"
  UI_RC=$?
  set -e
  # 将 UI 结果附加进 report.md
  if [ -f "$LATEST_DIR/ui-report.md" ]; then
    {
      echo ""
      echo "---"
      echo ""
      cat "$LATEST_DIR/ui-report.md"
    } >> "$LATEST_DIR/report.md"
  fi
  # 同步回 timestamped run 目录（latest 是 copy）
  if [ -f "$REPO_ROOT/reports/e2e/LATEST_RUN.txt" ]; then
    RUN_DIR=$(head -n1 "$REPO_ROOT/reports/e2e/LATEST_RUN.txt")
    if [ -n "$RUN_DIR" ] && [ -d "$RUN_DIR" ] && [ "$RUN_DIR" != "$LATEST_DIR" ]; then
      cp -a "$LATEST_DIR/." "$RUN_DIR/" 2>/dev/null || true
    fi
  fi
else
  UI_RC=0
fi

echo "Read report: reports/e2e/latest/report.md"
if [ "$MATRIX_RC" -ne 0 ] || [ "$UI_RC" -ne 0 ]; then
  exit 1
fi
