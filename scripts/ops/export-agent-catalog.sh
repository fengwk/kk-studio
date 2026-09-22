#!/usr/bin/env bash
#
# 导出 durable Agent catalog（Provider / Model / Agent 定义）为可导入的版本化包。
#
# 只读操作：不修改数据库，也不管理任何服务的生命周期。产物写到仓库外的 owner-only 目录：
#
#   catalog.sql      mode 0600，含 Provider credential 的 SQL COPY bundle
#   manifest.json    mode 0600，非敏感：源结构、V1 checksum、逐表行数与内容摘要
#   sha256sums.txt   mode 0600，bundle 的 sha256，可直接用 sha256sum -c 校验
#
# 只迁移这三张表：environment、skill_package、plugin_credential 与运行数据都不在包内。
# 数据库访问全部走原生 libpq 客户端与继承的连接设置，脚本不会提示输入口令。
#
# 连接来源按优先级合并：--host/--port/--username/--database > VPS_POSTGRES_* > 标准 libpq。

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$SCRIPT_DIR/lib/database-maintenance.sh"

WORK_DIR=${KK_STUDIO_CATALOG_DIR:-"$DEFAULT_MAINTENANCE_DIR/catalog"}
DRY_RUN=false
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

TARGET_DB=
PACKAGE_DIR=
V1_CHECKSUM=
CLI_HOST=
CLI_PORT=
CLI_USERNAME=
CLI_DATABASE=

usage() {
  cat <<'EOF'
Usage: scripts/ops/export-agent-catalog.sh [options]

Read the durable Agent catalog from the connected database and write a versioned package
(SQL COPY bundle + manifest + checksum) that import-agent-catalog.sh can restore.

Options:
  --work-dir PATH  owner-only package root (default: ~/.local/state/kk-studio/maintenance/catalog)
  --dry-run        read-only: detect the source shape and report counts without writing
  -h, --help       Show this help

Connection (no password is ever taken from the command line):
  --host HOST      database host to connect to
  --port PORT      database port, 1-65535
  --username USER  role to connect as
  --database NAME  plain database name to read; the database libpq connects to when omitted

  A flag wins over the matching VPS_POSTGRES_HOST, VPS_POSTGRES_PORT, VPS_POSTGRES_USERNAME or
  VPS_POSTGRES_DATABASE variable; without any of them the standard libpq settings apply
  (PGSERVICE + PGPASSFILE, or PGHOST/PGPORT/PGUSER/PGDATABASE and the TLS settings).  The
  password only ever comes from VPS_POSTGRES_PASSWORD, PGPASSWORD or PGPASSFILE: psql always runs
  with --no-password, so a missing credential fails instead of prompting.

Migrated tables (restore order): agent_provider, agent_model, agent_definition
Not migrated: environment, skill_package, plugin_credential and all runtime data
Source shapes: current (the current V1 columns) and legacy-main (the origin/main columns,
  converted to the current Agent config wire shape; unrepresentable rows abort the export)

Environment:
  VPS_POSTGRES_HOST, VPS_POSTGRES_PORT, VPS_POSTGRES_USERNAME, VPS_POSTGRES_PASSWORD,
  VPS_POSTGRES_DATABASE         connection overrides (see above)
  KK_STUDIO_CATALOG_DIR         default: $KK_STUDIO_MAINTENANCE_DIR/catalog
  KK_STUDIO_MAINTENANCE_DIR     default: ${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance
  KK_STUDIO_REPO_ROOT           repository root override
EOF
}

configure_paths() {
  PACKAGE_DIR="$WORK_DIR/$STAMP"
}

preflight() {
  configure_connection "$CLI_HOST" "$CLI_PORT" "$CLI_USERNAME" "$CLI_DATABASE"
  require_command python3
  require_command psql
  [ -f "$V1_MIGRATION" ] || fail "V1 migration not found: $V1_MIGRATION"
  require_external_directory "$WORK_DIR" "the catalog package directory"
  resolve_target_database
  V1_CHECKSUM=$(v1_checksum)
  case "$V1_CHECKSUM" in
    '' | *[!0-9-]*)
      fail "cannot compute the Flyway checksum of $V1_MIGRATION"
      ;;
  esac
}

show_plan() {
  echo
  echo "Export:"
  echo "  database:      $TARGET_DB"
  echo "  tables:        ${CATALOG_TABLES// /, }"
  echo "  expected V1:   $V1_CHECKSUM"
  echo "  package dir:   $PACKAGE_DIR"
}

dry_run_report() {
  step "Inspect the source catalog (read-only)"
  local report
  report=$(catalog_tool plan) \
    || fail "the source catalog cannot be exported to the current V1 (see the reason above)"
  printf '%s\n' "$report"
}

export_package() {
  step "Write the catalog package"
  prepare_owner_only_directory "$WORK_DIR"
  local report
  report=$(catalog_tool export --package "$PACKAGE_DIR" --v1-checksum "$V1_CHECKSUM") \
    || fail "the export was rejected; the source catalog was left unchanged"
  printf '%s\n' "$report"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      --work-dir)
        [ $# -ge 2 ] || fail "--work-dir requires a path"
        WORK_DIR=$2
        shift 2
        ;;
      --host)
        [ $# -ge 2 ] || fail "--host requires a value"
        CLI_HOST=$2
        shift 2
        ;;
      --port)
        [ $# -ge 2 ] || fail "--port requires a value"
        CLI_PORT=$2
        shift 2
        ;;
      --username)
        [ $# -ge 2 ] || fail "--username requires a value"
        CLI_USERNAME=$2
        shift 2
        ;;
      --database)
        [ $# -ge 2 ] || fail "--database requires a value"
        CLI_DATABASE=$2
        shift 2
        ;;
      -h | --help)
        usage
        return
        ;;
      *)
        usage >&2
        fail "unknown argument: $1"
        ;;
    esac
  done

  WORK_DIR=$(resolve_absolute_path "$WORK_DIR")
  configure_paths
  preflight
  show_plan

  if [ "$DRY_RUN" = true ]; then
    dry_run_report
    echo
    echo "Dry-run complete: nothing was written and the database was not changed."
    return
  fi

  export_package

  echo
  echo "Agent catalog export complete."
}

main "$@"
