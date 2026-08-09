#!/usr/bin/env bash
# scripts/e2e.sh — kk-studio 矩阵化 E2E 入口
#
# 快速开始：
#   ./scripts/e2e.sh                  # 复用已启动服务，跑 L1（免费）
#   ./scripts/e2e.sh --rebuild        # 强制 Java 21 clean package 并重启 backend/frontend
#   ./scripts/e2e.sh --real           # + 真 MiniMax 文本轮次（需 TEST_MINIMAX_*）
#   ./scripts/e2e.sh --real --with-branch
#   ./scripts/e2e.sh --real --with-tools
#   ./scripts/e2e.sh --ui              # + Playwright UI smoke（截图进报告）
#   ./scripts/e2e.sh --list           # 只打印矩阵，不执行
#
# 文档事实源：
#   docs/technical-solution/e2e-regression.md
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

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=e2e/lib.sh
source "$SCRIPT_DIR/e2e/lib.sh"

REBUILD=false
WITH_TOOLS=false
WITH_BRANCH=false
WITH_CANVAS_STORAGE=false
REAL=false
WITH_UI=false
LIST_ONLY=false
DOCS_ONLY=false
ONLY_ARGS=()
LEVEL_ARGS=()

usage() {
  cat <<'EOF'
Usage: ./scripts/e2e.sh [options]

Options:
  --rebuild         Force clean package backend + restart backend/frontend (+daemon if tools)
  --real            Enable real provider cases (requires TEST_MINIMAX_BASE_URL/API_KEY)
  --with-tools      Enable daemon/tool cases (starts/reuses daemon)
  --with-branch     Enable branch usage cases (implies --real)
  --with-canvas-storage  Enable Canvas Resource reserve contract (backend S3 config required)
  --ui              Enable Playwright UI smoke (screenshots in report)
  --only <caseId>   Run one case id (repeatable)
  --level <Lx>      Filter by level L1/L2/L3/L4 (repeatable)
  --list            List matrix cases
  --docs            Print case docs
  -h, --help        Show help

Env:
  BACKEND_PORT=18081
  FRONTEND_PORT=5173
  TEST_MINIMAX_API_KEY / TEST_MINIMAX_BASE_URL
  # the complete MiniMax pair is synchronized to seed provider name=minimax after backend readiness
  # the base URL is normalized to end with /v1
  JAVA_HOME_21=...
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --rebuild) REBUILD=true; shift ;;
    --real) REAL=true; shift ;;
    --with-tools) WITH_TOOLS=true; shift ;;
    --with-branch) WITH_BRANCH=true; REAL=true; shift ;;
    --with-canvas-storage) WITH_CANVAS_STORAGE=true; shift ;;
    --ui) WITH_UI=true; shift ;;
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

# 1) ensure stack
ensure_stack "$REBUILD" "$WITH_TOOLS"

# 2) synchronize the MiniMax pair; --real uses the seeded MiniMax model
if [ "$REAL" = "true" ] || [ "$REBUILD" = "true" ]; then
  sync_e2e_provider_credentials
fi
if [ "$REAL" = "true" ]; then
  minimax_ready=$(curl -fsS "$BACKEND_URL/api/ai/catalog/providers?pageNumber=1&pageSize=20" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); rows=((d.get("data") or {}).get("results") or []); p=next((r for r in rows if r.get("name")=="minimax"), {}); print("1" if p.get("configured") and p.get("baseUrl") else "0")')
  if [ "$minimax_ready" != "1" ]; then
    die "TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY are required for --real"
  fi
fi

# 3) run matrix
MATRIX_ARGS=(
  --base-url "$BACKEND_URL"
  --frontend-url "$FRONTEND_URL"
  --daemon-env "$DAEMON_ENV_NAME"
)
if [ "$REAL" = "true" ]; then MATRIX_ARGS+=(--real); fi
if [ "$WITH_TOOLS" = "true" ]; then MATRIX_ARGS+=(--with-tools); fi
if [ "$WITH_BRANCH" = "true" ]; then MATRIX_ARGS+=(--with-branch); fi
if [ "$WITH_CANVAS_STORAGE" = "true" ]; then MATRIX_ARGS+=(--with-canvas-storage); fi
MATRIX_ARGS+=("${ONLY_ARGS[@]}")
MATRIX_ARGS+=("${LEVEL_ARGS[@]}")

step "Running matrix"
set +e
node "$SCRIPT_DIR/e2e/run-matrix.mjs" "${MATRIX_ARGS[@]}"
MATRIX_RC=$?
set -e

# UI smoke 复用 latest 报告目录，把截图并入同一 run
if [ "$WITH_UI" = "true" ]; then
  LATEST_DIR="$REPO_ROOT/reports/e2e/latest"
  if [ ! -d "$LATEST_DIR" ]; then
    die "matrix report latest dir missing; cannot attach UI artifacts"
  fi
  step "Running UI smoke into $LATEST_DIR"
  set +e
  UI_ARGS=(
    --base-url "$FRONTEND_URL"
    --backend-url "$BACKEND_URL"
    --report-dir "$LATEST_DIR"
  )
  if [ "$REAL" = "true" ]; then
    UI_ARGS+=(--real)
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
