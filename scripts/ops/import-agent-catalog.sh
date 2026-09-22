#!/usr/bin/env bash
#
# 把导出的 Agent catalog 包回灌到目标库。
#
# 前置条件由脚本自己验证，不管理任何服务的生命周期：
#
#   1. 目标库已经由外部 schema/Flyway 初始化执行过 V1：flyway_schema_history 里的 V1 checksum
#      必须等于本仓库 revision 的 V1 checksum，列集合必须正是当前 V1 的形状；
#   2. agent_provider / agent_model / agent_definition 三张表必须为空；
#   3. 包内 manifest 的 V1 checksum 与本地一致，bundle 的 sha256 与 manifest 一致。
#
# 步骤 2 只是快速的提前拒绝：权威检查在回灌事务内部。包内的恢复脚本会先对三张表加排他锁、再确认它们
# 为空、然后 COPY 全部行、最后比对三张表的期望指纹，全部通过才提交（锁等待上限 5 秒）。因此并发写入、
# 脏表或指纹不符都会让整次回灌回滚，不会留下半份 catalog，也不会提交一份与包不符的数据。
#
# 恢复脚本自身用 `\set VERBOSITY sqlstate`：失败时 psql 只报错误码，日志里不会出现任何行值。
# stdout 全部丢弃，失败时只保留一份 mode-0600 的、只含 SQLSTATE 与安全类别说明的日志。
# 提交之后仍会再核对一次指纹（纵深防御）：此时不符只可能来自并发写入。
#
# 连接来源按优先级合并：--host/--port/--username/--database > VPS_POSTGRES_* > 标准 libpq。

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$SCRIPT_DIR/lib/database-maintenance.sh"

WORK_DIR=${KK_STUDIO_IMPORT_DIR:-"$DEFAULT_MAINTENANCE_DIR/log"}
DRY_RUN=false
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

PACKAGE_DIR=
FAILURE_LOG=
TARGET_DB=
V1_CHECKSUM=
ACTUAL_V1_CHECKSUM=
PACKAGE_REPORT=
TARGET_REPORT=
RAW_LOG=
CLI_HOST=
CLI_PORT=
CLI_USERNAME=
CLI_DATABASE=

usage() {
  cat <<'EOF'
Usage: scripts/ops/import-agent-catalog.sh --package PATH [options]

Restore an Agent catalog package exported by export-agent-catalog.sh into the connected
database.  The target must already have V1 applied by the external schema/Flyway initialization,
so the V1 history is recorded and the three catalog tables exist empty with the current V1
columns.

Options:
  --package PATH   exported package directory (required)
  --work-dir PATH  owner-only directory for a sanitized failure log
                   (default: ~/.local/state/kk-studio/maintenance/log)
  --dry-run        validate the package and the target without changing anything
  -h, --help       Show this help

Connection (no password is ever taken from the command line):
  --host HOST      database host to connect to
  --port PORT      database port, 1-65535
  --username USER  role to connect as
  --database NAME  plain database name to import into; the database libpq connects to when omitted

  A flag wins over the matching VPS_POSTGRES_HOST, VPS_POSTGRES_PORT, VPS_POSTGRES_USERNAME or
  VPS_POSTGRES_DATABASE variable; without any of them the standard libpq settings apply
  (PGSERVICE + PGPASSFILE, or PGHOST/PGPORT/PGUSER/PGDATABASE and the TLS settings).  The
  password only ever comes from VPS_POSTGRES_PASSWORD, PGPASSWORD or PGPASSFILE: psql always runs
  with --no-password, so a missing credential fails instead of prompting.

The import never starts, stops or inspects any service and never needs one to be paused: the
restore runs in a single transaction that locks the three catalog tables, re-checks that they are
empty, copies every row and compares all three fingerprints before committing, so a concurrent
writer, a dirty table or a mismatch rolls the whole import back.  A failure log keeps SQLSTATE
codes and safe categories only, never a row value.

Environment:
  VPS_POSTGRES_HOST, VPS_POSTGRES_PORT, VPS_POSTGRES_USERNAME, VPS_POSTGRES_PASSWORD,
  VPS_POSTGRES_DATABASE        connection overrides (see above)
  KK_STUDIO_IMPORT_DIR        default: $KK_STUDIO_MAINTENANCE_DIR/log
  KK_STUDIO_MAINTENANCE_DIR   default: ${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance
  KK_STUDIO_REPO_ROOT         repository root override
EOF
}

preflight() {
  configure_connection "$CLI_HOST" "$CLI_PORT" "$CLI_USERNAME" "$CLI_DATABASE"
  require_command python3
  require_command psql
  require_command mktemp
  [ -n "$PACKAGE_DIR" ] || fail "--package is required"
  PACKAGE_DIR=$(resolve_absolute_path "$PACKAGE_DIR")
  [ -d "$PACKAGE_DIR" ] || fail "the package directory does not exist: $PACKAGE_DIR"
  [ -f "$V1_MIGRATION" ] || fail "V1 migration not found: $V1_MIGRATION"
  require_external_directory "$PACKAGE_DIR" "the catalog package directory"
  require_external_directory "$WORK_DIR" "the failure log directory"
  resolve_target_database
  V1_CHECKSUM=$(v1_checksum)
  case "$V1_CHECKSUM" in
    '' | *[!0-9-]*)
      fail "cannot compute the Flyway checksum of $V1_MIGRATION"
      ;;
  esac

  step "Validate the catalog package"
  PACKAGE_REPORT=$(catalog_tool verify --package "$PACKAGE_DIR" --v1-checksum "$V1_CHECKSUM") \
    || fail "the catalog package failed validation; nothing was imported"

  step "Inspect the import target"
  TARGET_REPORT=$(catalog_tool target) \
    || fail "the target database is not ready for an import (see the reason above)"

  local table
  local rows
  for table in $CATALOG_TABLES; do
    rows=$(require_report_value "$TARGET_REPORT" "rows.$table" "the row count of $table")
    [ "$rows" = 0 ] \
      || fail "import requires an empty $table (found $rows rows); export and reset the database first"
  done
  # 这里的空表检查会被并发写入绕过，因此恢复事务内部会重新加锁复查；这里只为尽早给出明确原因。

  step "Verify the target Flyway history"
  local history_present
  history_present=$(database_scalar "$TARGET_DB" \
    "select to_regclass('public.flyway_schema_history') is not null")
  [ "$history_present" = t ] \
    || fail "the target database has no Flyway history; apply the V1 schema first"
  ACTUAL_V1_CHECKSUM=$(database_scalar "$TARGET_DB" \
    "select checksum from flyway_schema_history
      where version = '1' and success order by installed_rank desc limit 1")
  [ -n "$ACTUAL_V1_CHECKSUM" ] || fail "the target database has no successful Flyway V1"
  [ "$ACTUAL_V1_CHECKSUM" = "$V1_CHECKSUM" ] \
    || fail "the target Flyway V1 checksum $ACTUAL_V1_CHECKSUM differs from this checkout's $V1_CHECKSUM; build the image from this revision"
}

show_plan() {
  echo
  echo "Import:"
  echo "  database:    $TARGET_DB"
  echo "  package:     $PACKAGE_DIR"
  echo "  source:      $(report_value "$PACKAGE_REPORT" source_schema)"
  echo "  V1 checksum: $V1_CHECKSUM (local, package and target agree)"
  echo "  tables:      ${CATALOG_TABLES// /, } (all empty)"
}

restore_package() {
  step "Restore the package in a single transaction"
  prepare_owner_only_directory "$WORK_DIR"
  local bundle
  bundle=$(require_report_value "$PACKAGE_REPORT" bundle "the package bundle path")
  RAW_LOG=$(mktemp "$WORK_DIR/.restore-${STAMP}.XXXXXX") \
    || fail "cannot create a private restore log"
  local log_name=${RAW_LOG##*/}
  FAILURE_LOG="$WORK_DIR/${log_name#.}.log"

  # stdout 一律丢弃：bundle 内的行值不允许出现在终端或日志索引里。
  if ! psql_database "$TARGET_DB" --single-transaction --file "$bundle" \
      > /dev/null 2> "$RAW_LOG"; then
    if ! catalog_tool sanitize --log "$RAW_LOG"; then
      rm -f "$RAW_LOG"
      RAW_LOG=
      fail "the catalog restore failed and was rolled back; the raw failure output was deleted because it could not be sanitized"
    fi
    mv "$RAW_LOG" "$FAILURE_LOG"
    RAW_LOG=
    fail "the catalog restore failed and was rolled back; sanitized failure log: $FAILURE_LOG"
  fi
  rm -f "$RAW_LOG"
  RAW_LOG=
}

cleanup_raw_log() {
  if [ -n "$RAW_LOG" ]; then
    rm -f "$RAW_LOG" || true
  fi
}

verify_restored_catalog() {
  step "Verify the restored catalog after the commit"
  local report
  report=$(catalog_tool target) || fail "cannot read the restored catalog"
  local table
  local expected
  local actual
  for table in $CATALOG_TABLES; do
    expected=$(require_report_value "$PACKAGE_REPORT" "fingerprint.$table" \
      "the package fingerprint of $table")
    actual=$(require_report_value "$report" "fingerprint.$table" "the restored fingerprint of $table")
    # 回灌事务内部已经比对过同一份指纹；这里不符只可能是提交之后有并发写入动了这三张表。
    [ "$actual" = "$expected" ] \
      || fail "the imported $table changed after the import committed: a concurrent writer modified the catalog (the import itself matched the package inside its transaction)"
    printf '  %-18s %s rows verified\n' "$table" "${actual%%:*}"
  done
  TARGET_REPORT=$report
}

show_result() {
  local source_schema
  source_schema=$(require_report_value "$PACKAGE_REPORT" source_schema "the package source schema")
  echo
  echo "Agent catalog import complete."
  echo "database=$TARGET_DB"
  echo "source_schema=$source_schema"
  echo "v1_checksum=$V1_CHECKSUM"
  local table
  for table in $CATALOG_TABLES; do
    echo "rows.$table=$(report_value "$TARGET_REPORT" "rows.$table")"
    echo "fingerprint.$table=$(report_value "$TARGET_REPORT" "fingerprint.$table")"
  done
  echo "package_dir=$PACKAGE_DIR"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --package)
        [ $# -ge 2 ] || fail "--package requires a path"
        PACKAGE_DIR=$2
        shift 2
        ;;
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
  preflight
  show_plan

  if [ "$DRY_RUN" = true ]; then
    echo
    echo "Dry-run complete: the package and the target are ready, nothing was changed."
    return
  fi

  restore_package
  verify_restored_catalog
  show_result
  echo
  echo "The imported catalog is held in $TARGET_DB; delete the package when it is no longer needed."
}

trap cleanup_raw_log EXIT
main "$@"
