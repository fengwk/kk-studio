#!/usr/bin/env bash
#
# 把目标库替换成一个「完全空、但元数据与原来一致」的新库。
#
# 不依赖导出包、不依赖应用、不接触任何容器：
#
#   1. 只读预检：目标库存在、不是模板、允许连接、没有其他活动会话，且当前角色确实有需要的权限；
#   2. 写一份完整的 custom-format 备份到仓库外（owner-only），并用 pg_restore --list 验证；
#   3. 把旧库改名为带时间戳的快照库并禁止连接（不删除、也不 kill 任何应用会话）；
#   4. 用原 owner/encoding/locale provider/tablespace/connection limit 建一个空库；
#   5. 任何一步失败都回到「目标库名仍指向原有数据」的状态，或准确报告无法回滚到什么程度。
#
# 建库阶段先在临时名下把库建完（含连接数限制），最后才改名成目标库名：目标库名要么不存在，
# 要么已经是一个完整可用的空库，不会短暂暴露一个还没套用连接数限制的新库。
#
# 之后由应用正常启动执行 Flyway V1：本脚本不启动、不检查、也不知道应用的存在。
# 数据库访问全部走原生 libpq 客户端与继承的连接设置，脚本不会提示输入口令。

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$SCRIPT_DIR/lib/database-maintenance.sh"

WORK_DIR=${KK_STUDIO_RESET_DIR:-"$DEFAULT_MAINTENANCE_DIR/backup"}
DRY_RUN=false
ASSUME_YES=false
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

TARGET_DB=
SNAPSHOT_DB=
PARTIAL_DB=
BACKUP_FILE=
CHECKSUM_FILE=

# 变更阶段的状态，只用于让 on_exit 准确回滚或准确报告：
#   RENAMED         快照库名已经持有原库（目标库名空闲）
#   CREATED_DB      本次运行已经建出、但还没有成为目标库的库名（空表示没有）
#   TARGET_REPLACED 目标库名已经指向本次新建的空库
RENAMED=false
CREATED_DB=
TARGET_REPLACED=false

DB_OWNER=
DB_ENCODING=
DB_LOCALE_PROVIDER=
DB_COLLATE=
DB_CTYPE=
DB_LOCALE=
DB_ICU_RULES=
DB_TABLESPACE=
DB_CONNECTION_LIMIT=
DB_SIZE_BYTES=
PRIVILEGE_MODE=

usage() {
  cat <<'EOF'
Usage: scripts/ops/reset-database.sh [options]

Back up, freeze and replace the target database with a completely empty database that keeps
its owner, encoding, locale provider/settings, tablespace and connection limit.  The
application then runs Flyway on the empty database during its normal start.

Options:
  --work-dir PATH  owner-only backup directory (default: ~/.local/state/kk-studio/maintenance/backup)
  --yes            skip the interactive database-name confirmation
  --dry-run        read-only preflight and plan; writes nothing and changes nothing
  -h, --help       Show this help

Connection (inherited libpq settings; no connection flag, no password in argv):
  PGSERVICE + PGPASSFILE are the recommended pair; PGHOST, PGPORT, PGUSER, PGDATABASE,
  PGSSLMODE and the certificate settings also work.  PGDATABASE is required to be a plain
  database name (a URI/conninfo is rejected: the maintenance connection overrides the
  database, so the target must be named explicitly).  psql/pg_dump/pg_restore/createdb are
  always used with --no-password, so the script fails instead of prompting.

Refused before any change:
  other sessions still connected to the target (nothing is killed), a template database,
  a database that does not allow connections, a target equal to the maintenance database,
  a custom database ACL or role setting, an existing snapshot name, and a role that cannot
  rename the target (the database owner or a superuser is required), cannot create a database
  (CREATEDB) or cannot hand the new database to the original owner.

  Providers that forbid renaming or creating a database (some managed services) cannot run this
  script: export-agent-catalog.sh and import-agent-catalog.sh remain usable there, but the reset
  step has to be done with whatever lifecycle feature the provider offers.

Environment:
  KK_STUDIO_RESET_DIR         default: $KK_STUDIO_MAINTENANCE_DIR/backup
  KK_STUDIO_MAINTENANCE_DIR   default: ${XDG_STATE_HOME:-$HOME/.local/state}/kk-studio/maintenance
  KK_STUDIO_MAINTENANCE_DB    default: postgres
  KK_STUDIO_REPO_ROOT         repository root override
EOF
}

configure_paths() {
  SNAPSHOT_DB="${TARGET_DB}_pre_${STAMP}"
  PARTIAL_DB=$(partial_database_name)
  BACKUP_FILE="$WORK_DIR/${TARGET_DB}_${STAMP}.dump"
  CHECKSUM_FILE="$WORK_DIR/${TARGET_DB}_${STAMP}.dump.sha256"
}

# 新库先在临时名下建完，再改名为目标库名。标识符上限 63 字节，临时名带时间戳，因此既超不出
# 长度上限，也不会与并发的维护运行冲突。
partial_database_name() {
  local suffix="__kk_partial_${STAMP}"
  printf '%s%s\n' "${TARGET_DB:0:$((63 - ${#suffix}))}" "$suffix"
}

require_maintenance_database() {
  require_identifier "$MAINTENANCE_DB" "KK_STUDIO_MAINTENANCE_DB"
  [ "$MAINTENANCE_DB" != "$TARGET_DB" ] \
    || fail "the target database must differ from the maintenance database: $MAINTENANCE_DB"
}

preflight() {
  require_command python3
  require_command psql
  require_command pg_dump
  require_command pg_restore
  require_command createdb
  resolve_target_database
  require_maintenance_database
  [ ${#SNAPSHOT_DB} -le 63 ] || fail "the snapshot database name exceeds 63 bytes"
  require_external_directory "$WORK_DIR" "the backup directory"

  local connected
  connected=$(database_scalar "$TARGET_DB" "select current_database()")
  [ "$connected" = "$TARGET_DB" ] || fail "connected to an unexpected database"

  local exists
  exists=$(database_scalar "$MAINTENANCE_DB" \
    "select count(*) from pg_database where datname = '$TARGET_DB'")
  [ "$exists" = 1 ] || fail "the target database does not exist"

  local is_template
  is_template=$(database_scalar "$MAINTENANCE_DB" \
    "select datistemplate from pg_database where datname = '$TARGET_DB'")
  [ "$is_template" = f ] || fail "the target database must not be a template"

  local allows_connections
  allows_connections=$(database_scalar "$MAINTENANCE_DB" \
    "select datallowconn from pg_database where datname = '$TARGET_DB'")
  [ "$allows_connections" = t ] || fail "the target database is frozen (allow_connections false)"

  local sessions
  sessions=$(database_scalar "$MAINTENANCE_DB" \
    "select count(*) from pg_stat_activity
      where datname = '$TARGET_DB' and pid <> pg_backend_pid()")
  [ "$sessions" = 0 ] \
    || fail "refusing to reset: $sessions other session(s) are still connected to the target; stop the writer first"

  local custom_acl
  custom_acl=$(database_scalar "$MAINTENANCE_DB" \
    "select coalesce(cardinality(datacl), 0) from pg_database where datname = '$TARGET_DB'")
  [ "$custom_acl" = 0 ] \
    || fail "the target carries a custom database ACL that this script does not recreate; grant it again after the reset"

  local role_settings
  role_settings=$(database_scalar "$MAINTENANCE_DB" \
    "select count(*) from pg_db_role_setting
      where setdatabase = (select oid from pg_database where datname = '$TARGET_DB')")
  [ "$role_settings" = 0 ] \
    || fail "the target carries custom database role settings that this script does not recreate; set them again after the reset"

  local snapshot_exists
  snapshot_exists=$(database_scalar "$MAINTENANCE_DB" \
    "select count(*) from pg_database where datname = '$SNAPSHOT_DB'")
  [ "$snapshot_exists" = 0 ] || fail "the snapshot database already exists: $SNAPSHOT_DB"

  local partial_exists
  partial_exists=$(database_scalar "$MAINTENANCE_DB" \
    "select count(*) from pg_database where datname = '$PARTIAL_DB'")
  [ "$partial_exists" = 0 ] \
    || fail "a database left behind by an earlier failed run still exists: $PARTIAL_DB; drop it before retrying"

  capture_database_metadata
  require_privileges
}

# 一次往返读一个字段。owner/locale/tablespace 名可以包含任意字符（包括 '|'），把多个字段拼成一行
# 再用分隔符切分会把它们读错，因此这里逐字段读取。
capture_database_metadata() {
  DB_OWNER=$(database_scalar "$MAINTENANCE_DB" \
    "select pg_get_userbyid(datdba) from pg_database where datname = '$TARGET_DB'")
  DB_ENCODING=$(database_scalar "$MAINTENANCE_DB" \
    "select pg_encoding_to_char(encoding) from pg_database where datname = '$TARGET_DB'")
  DB_COLLATE=$(database_scalar "$MAINTENANCE_DB" \
    "select datcollate from pg_database where datname = '$TARGET_DB'")
  DB_CTYPE=$(database_scalar "$MAINTENANCE_DB" \
    "select datctype from pg_database where datname = '$TARGET_DB'")
  DB_LOCALE=$(database_scalar "$MAINTENANCE_DB" \
    "select coalesce(to_jsonb(d)->>'datlocale', '') from pg_database d where d.datname = '$TARGET_DB'")
  DB_ICU_RULES=$(database_scalar "$MAINTENANCE_DB" \
    "select coalesce(to_jsonb(d)->>'daticurules', '') from pg_database d where d.datname = '$TARGET_DB'")
  DB_TABLESPACE=$(database_scalar "$MAINTENANCE_DB" \
    "select coalesce((select t.spcname from pg_tablespace t where t.oid = d.dattablespace), '')
       from pg_database d where d.datname = '$TARGET_DB'")
  DB_CONNECTION_LIMIT=$(database_scalar "$MAINTENANCE_DB" \
    "select datconnlimit from pg_database where datname = '$TARGET_DB'")
  DB_SIZE_BYTES=$(database_scalar "$MAINTENANCE_DB" \
    "select pg_database_size(datname) from pg_database where datname = '$TARGET_DB'")
  # ICU 的 datlocale 与规则用 to_jsonb 读取，避免绑定到某个服务器版本。
  local provider_code
  provider_code=$(database_scalar "$MAINTENANCE_DB" \
    "select coalesce(to_jsonb(d)->>'datlocprovider', 'c') from pg_database d where d.datname = '$TARGET_DB'")

  [ -n "$DB_OWNER" ] || fail "cannot read the owner of the target database"
  [ -n "$DB_ENCODING" ] || fail "cannot read the encoding of the target database"
  [ -n "$DB_COLLATE" ] || fail "cannot read the collation of the target database"
  [ -n "$DB_CTYPE" ] || fail "cannot read the character type of the target database"
  [ -n "$DB_TABLESPACE" ] || fail "cannot read the tablespace of the target database"
  case "$DB_SIZE_BYTES" in
    '' | *[!0-9]*) fail "cannot read the size of the target database" ;;
  esac
  case "$DB_CONNECTION_LIMIT" in
    '' | *[!0-9-]*) fail "cannot read the connection limit of the target database" ;;
  esac
  case "$provider_code" in
    c) DB_LOCALE_PROVIDER=libc ;;
    i) DB_LOCALE_PROVIDER=icu ;;
    b) DB_LOCALE_PROVIDER=builtin ;;
    *) fail "unsupported database locale provider" ;;
  esac
  if [ "$DB_LOCALE_PROVIDER" = icu ] && [ -z "$DB_LOCALE" ]; then
    # PostgreSQL 15 kept the ICU locale in datcollate and has no datlocale column.
    DB_LOCALE=$DB_COLLATE
  fi
  if [ "$DB_LOCALE_PROVIDER" = icu ] && [ -z "$DB_LOCALE" ]; then
    fail "cannot read the ICU locale of the target database"
  fi
}

# 自管数据库的常见形态是「一个非超级用户的 owner 角色」：reset 需要的只是能改目标库、能建库、能把
# 新库交给原 owner，以及 pg_dump 能读全部业务表（这一步由备份本身在任何变更之前验证）。缺少哪一项
# 就只读失败，不动数据库。
require_privileges() {
  local is_superuser
  local can_createdb
  local owns_target
  local can_own_target
  is_superuser=$(database_scalar "$MAINTENANCE_DB" \
    "select rolsuper from pg_roles where rolname = current_user")
  can_createdb=$(database_scalar "$MAINTENANCE_DB" \
    "select rolcreatedb from pg_roles where rolname = current_user")
  owns_target=$(database_scalar "$MAINTENANCE_DB" \
    "select datdba = (select oid from pg_roles where rolname = current_user)
       from pg_database where datname = '$TARGET_DB'")
  can_own_target=$(database_scalar "$MAINTENANCE_DB" \
    "select pg_has_role(current_user, $(sql_literal "$DB_OWNER"), 'SET')")

  if [ "$is_superuser" = t ]; then
    PRIVILEGE_MODE="superuser"
    return
  fi
  [ "$owns_target" = t ] \
    || fail "the connected role cannot rename or freeze $TARGET_DB; connect as the database owner ($DB_OWNER) or as a superuser"
  [ "$can_createdb" = t ] \
    || fail "the connected role cannot create databases; grant CREATEDB to it or connect as a superuser"
  [ "$can_own_target" = t ] \
    || fail "the connected role cannot create a database owned by $DB_OWNER; grant it membership of that role or connect as a superuser"
  PRIVILEGE_MODE="database owner with CREATEDB"
}

show_plan() {
  echo
  echo "Reset:"
  echo "  database:       $TARGET_DB"
  echo "  maintenance db: $MAINTENANCE_DB"
  echo "  role:           $PRIVILEGE_MODE"
  echo "  owner:          $DB_OWNER"
  echo "  encoding:       $DB_ENCODING"
  echo "  locale:         $DB_LOCALE_PROVIDER ${DB_LOCALE:-$DB_COLLATE}"
  echo "  tablespace:     $DB_TABLESPACE"
  echo "  connect limit:  $DB_CONNECTION_LIMIT"
  echo "  database size:  $DB_SIZE_BYTES bytes"
  echo "  backup:         $BACKUP_FILE"
  echo "  snapshot:       $SNAPSHOT_DB (frozen with allow_connections=false)"
  echo
  echo "Plan:"
  echo "  1. Write and validate a full custom-format backup."
  echo "  2. Rename the target to $SNAPSHOT_DB and disable connections to it."
  echo "  3. Create the empty database as $PARTIAL_DB, apply the metadata, then rename it"
  echo "     to $TARGET_DB; a failure here drops it and restores $TARGET_DB."
  echo "  4. Start the application normally so Flyway applies V1 on the empty database."
}

confirm_operation() {
  if [ "$ASSUME_YES" = true ]; then
    return
  fi
  echo
  echo "Everything in $TARGET_DB outside the exported catalog package will be removed."
  local confirmation
  if ! read -r -p "Type the database name to continue: " confirmation; then
    fail "confirmation input was not available"
  fi
  [ "$confirmation" = "$TARGET_DB" ] || fail "confirmation did not match the database name"
}

prepare_work_directory() {
  prepare_owner_only_directory "$WORK_DIR"
  local path
  for path in "$BACKUP_FILE" "$CHECKSUM_FILE"; do
    [ ! -e "$path" ] || fail "refusing to overwrite an existing artifact: $path"
  done

  local available_kib
  local required_kib
  available_kib=$(df -Pk "$WORK_DIR" | awk 'NR == 2 { print $4 }')
  required_kib=$(((DB_SIZE_BYTES + 67108864 + 1023) / 1024))
  [ "$available_kib" -ge "$required_kib" ] \
    || fail "the backup filesystem has less than database-size + 64 MiB free"
}

write_backup() {
  step "Write a full custom-format backup: $BACKUP_FILE"
  local partial="$BACKUP_FILE.partial"
  rm -f "$partial"
  if ! pg_dump --no-password --format=custom --create \
      --dbname="$TARGET_DB" --file="$partial"; then
    rm -f "$partial"
    fail "the full backup failed; the database was not changed"
  fi
  if [ ! -s "$partial" ]; then
    rm -f "$partial"
    fail "the full backup is empty; the database was not changed"
  fi
  if ! pg_restore --no-password --list "$partial" > /dev/null; then
    rm -f "$partial"
    fail "the full backup failed validation; the database was not changed"
  fi
  mv "$partial" "$BACKUP_FILE"
  printf '%s  %s\n' "$(file_sha256 "$BACKUP_FILE")" "$(basename "$BACKUP_FILE")" \
    > "$CHECKSUM_FILE"
}

# 解冻：先放开连接，再把快照库改回目标库名。改名必须先确认目标库名是空闲的（调用方负责先清掉本次
# 运行建出的新库），否则改名会失败。
unfreeze_database() {
  psql_database "$MAINTENANCE_DB" \
    -c "alter database \"$SNAPSHOT_DB\" allow_connections true" > /dev/null || return 1
  psql_database "$MAINTENANCE_DB" \
    -c "alter database \"$SNAPSHOT_DB\" rename to \"$TARGET_DB\"" > /dev/null || return 1
}

# 改名与禁连不能写进同一个事务（ALTER DATABASE ... RENAME 不允许在事务块内执行），因此这里逐条执行
# 并逐条记录状态：改名成功后目标库名立即空闲，on_exit 能凭 RENAMED 准确回滚或准确报告。
freeze_target_database() {
  step "Freeze the old database as $SNAPSHOT_DB"
  psql_database "$MAINTENANCE_DB" \
    -c "alter database \"$TARGET_DB\" rename to \"$SNAPSHOT_DB\"" > /dev/null
  RENAMED=true
  psql_database "$MAINTENANCE_DB" \
    -c "alter database \"$SNAPSHOT_DB\" allow_connections false" > /dev/null
  # preflight 后仍可能有写入端抢在禁连前接入。禁连后不会再产生新会话，此时复查可把这个竞态
  # 收敛为安全失败；on_exit 会解冻并改回原名，本脚本从不主动终止会话。
  local sessions
  sessions=$(database_scalar "$MAINTENANCE_DB" \
    "select count(*) from pg_stat_activity
      where datname = '$SNAPSHOT_DB' and pid <> pg_backend_pid()")
  [ "$sessions" = 0 ] \
    || fail "refusing to reset: $sessions session(s) connected after preflight; the original database will be restored"
}

# createdb 没有连接数限制选项，因此分三步完成、逐步记录：一旦 createdb 返回成功，CREATED_DB 立刻记下
# 这个新库，任何后续失败都不会把它误判成「从未建库」。三步都针对临时名，目标库名只在最后一步出现。
create_empty_database() {
  local arguments=(
    --no-password
    --maintenance-db="$MAINTENANCE_DB"
    --template=template0
    --owner="$DB_OWNER"
    --encoding="$DB_ENCODING"
    --locale-provider="$DB_LOCALE_PROVIDER"
  )
  case "$DB_LOCALE_PROVIDER" in
    libc)
      arguments+=(--lc-collate="$DB_COLLATE" --lc-ctype="$DB_CTYPE")
      ;;
    icu)
      arguments+=(--icu-locale="$DB_LOCALE")
      if [ -n "$DB_ICU_RULES" ]; then
        arguments+=(--icu-rules="$DB_ICU_RULES")
      fi
      ;;
    builtin)
      arguments+=(--builtin-locale="$DB_LOCALE")
      ;;
  esac
  if [ "$DB_TABLESPACE" != "pg_default" ]; then
    arguments+=(--tablespace="$DB_TABLESPACE")
  fi

  createdb "${arguments[@]}" "$PARTIAL_DB"
  CREATED_DB=$PARTIAL_DB
  if [ "$DB_CONNECTION_LIMIT" != "-1" ]; then
    psql_database "$MAINTENANCE_DB" \
      -c "alter database \"$PARTIAL_DB\" connection limit $DB_CONNECTION_LIMIT" > /dev/null
  fi
  psql_database "$MAINTENANCE_DB" \
    -c "alter database \"$PARTIAL_DB\" rename to \"$TARGET_DB\"" > /dev/null
  CREATED_DB=
  TARGET_REPLACED=true
}

replace_database() {
  freeze_target_database
  step "Create a completely empty database with the original metadata"
  create_empty_database
}

on_exit() {
  local status=$?
  if [ "$status" -eq 0 ] || [ "$TARGET_REPLACED" = true ]; then
    if [ "$status" -ne 0 ]; then
      # 变更已经完成，只是后续步骤失败：不再回滚，只报告现状。
      set +e
      echo "Reset failed after the database was replaced; $TARGET_DB is empty and the" >&2
      echo "pre-reset database is frozen as $SNAPSHOT_DB (full backup: $BACKUP_FILE)." >&2
    fi
    return
  fi
  if [ "$RENAMED" = false ] && [ -z "$CREATED_DB" ]; then
    return
  fi

  set +e
  echo "ERROR: reset failed; restoring the pre-reset state" >&2
  if [ -n "$CREATED_DB" ]; then
    # 先清掉本次建出的库，目标库名才会空闲；否则改名回退一定失败。
    if psql_database "$MAINTENANCE_DB" \
        -c "drop database if exists \"$CREATED_DB\" with (force)" > /dev/null; then
      echo "Dropped the incomplete database $CREATED_DB" >&2
      CREATED_DB=
    else
      echo "ERROR: the incomplete database $CREATED_DB could not be dropped; remove it manually" >&2
    fi
  fi
  if [ "$RENAMED" = true ]; then
    if unfreeze_database; then
      echo "The original database is back in place: $TARGET_DB (backup kept: $BACKUP_FILE)" >&2
    else
      echo "ERROR: $SNAPSHOT_DB could not be renamed back to $TARGET_DB; the pre-reset data" >&2
      echo "       is kept as $SNAPSHOT_DB (connections re-enabled when PostgreSQL allowed it)" >&2
      echo "       and as $BACKUP_FILE" >&2
    fi
  fi
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      --yes)
        ASSUME_YES=true
        shift
        ;;
      --work-dir)
        [ $# -ge 2 ] || fail "--work-dir requires a path"
        WORK_DIR=$2
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
  resolve_target_database
  configure_paths
  preflight
  show_plan

  if [ "$DRY_RUN" = true ]; then
    echo
    echo "Dry-run complete: nothing was written and the database was not changed."
    return
  fi

  confirm_operation
  prepare_work_directory
  write_backup
  replace_database

  echo
  echo "Database reset complete."
  echo "  full backup:     $BACKUP_FILE"
  echo "  backup checksum: $CHECKSUM_FILE"
  echo "  frozen snapshot: $SNAPSHOT_DB"
  echo
  echo "Next: start the application normally so Flyway applies V1 on the empty database,"
  echo "      then import the catalog package."
}

trap on_exit EXIT
main "$@"
