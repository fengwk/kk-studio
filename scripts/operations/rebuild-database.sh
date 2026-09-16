#!/usr/bin/env bash
#
# Rebuild the shared kk-studio database from the repository's single V1 baseline.
#
# The operation preserves only durable Catalog and Environment configuration:
#   environment -> environment_skill_source -> environment_skill
#               -> environment_inventory
#   agent_provider -> agent_model -> agent_definition
#
# Runtime leases and transient product data are intentionally not restored.
# system_setting is recreated from the canonical V1 default.
#
# Secrets never pass through command arguments or logs. Dumps stay on the host
# with mode 0600 and database commands use the PostgreSQL container's local socket.

set -euo pipefail
umask 077

DB_CONTAINER=${KK_STUDIO_REBUILD_DB_CONTAINER:-vps-postgres}
DB_NAME=${KK_STUDIO_REBUILD_DB_NAME:-kk_studio}
DB_USER=${KK_STUDIO_REBUILD_DB_USER:-postgres}
MAIN_CONTAINER=${KK_STUDIO_REBUILD_MAIN_CONTAINER:-vps-kk-studio}
FOLLOWER_CONTAINER_NAMES=${KK_STUDIO_REBUILD_FOLLOWER_CONTAINERS:-vps-kk-studio-dev}
READY_TIMEOUT_SECONDS=${KK_STUDIO_REBUILD_READY_TIMEOUT_SECONDS:-900}

SCRIPT_HOME=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
APP_HOME=$(cd "$SCRIPT_HOME/../.." && pwd -P)
V1_FILE="$APP_HOME/schema/src/main/resources/db/migration/V1__schema.sql"
DEFAULT_STATE_HOME=${XDG_STATE_HOME:-"${HOME:-/var/lib}/.local/state"}
WORK_DIR=${KK_STUDIO_REBUILD_WORK_DIR:-"$DEFAULT_STATE_HOME/kk-studio/database-rebuild"}
STAMP=$(date -u +%Y%m%d_%H%M%S)

PRESERVED_TABLES=(
  environment
  environment_skill_source
  environment_inventory
  environment_skill
  agent_provider
  agent_model
  agent_definition
)

DRY_RUN=false
ASSUME_YES=false
SKIP_SNAPSHOT=false
SUCCESS=false
MAINTENANCE_STARTED=false
DATABASE_REPLACED=false
SNAPSHOT_CREATED=false

FULL_BACKUP_FILE=
PRESERVED_DUMP_FILE=
CHECKSUM_FILE=
MANIFEST_FILE=
RESTORE_LOG=
SNAPSHOT_DB=

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
EXPECTED_V1_CHECKSUM=
ACTUAL_V1_CHECKSUM=
EXPECTED_READY_CONNECTIONS=0

declare -a FOLLOWER_CONTAINERS=()
declare -a APP_CONTAINERS=()
declare -a INITIAL_RUNNING_CONTAINERS=()
declare -A EXPECTED_FINGERPRINTS=()

usage() {
  cat <<'EOF'
Usage: ./scripts/operations/rebuild-database.sh [options]

Rebuild the shared database from V1 and restore durable Catalog/Environment data.

Options:
  --dry-run          Run read-only preflight and print the exact plan
  --yes              Skip the interactive database-name confirmation
  --skip-snapshot    Do not retain the renamed pre-rebuild database
                     (the full custom-format backup is still mandatory)
  --work-dir PATH    Secure output directory for backup artifacts
  -h, --help         Show this help

Preserved tables:
  environment  environment_skill_source  environment_inventory
  environment_skill  agent_provider  agent_model  agent_definition

Not preserved:
  chat / canvas / project / issue / harness / storage
  environment_connection (a runtime lease recreated by Daemon reconnect)
  system_setting (the canonical default is inserted by V1)

Environment:
  KK_STUDIO_REBUILD_DB_CONTAINER          default: vps-postgres
  KK_STUDIO_REBUILD_DB_NAME               default: kk_studio
  KK_STUDIO_REBUILD_DB_USER               default: postgres
  KK_STUDIO_REBUILD_MAIN_CONTAINER        default: vps-kk-studio
  KK_STUDIO_REBUILD_FOLLOWER_CONTAINERS   default: vps-kk-studio-dev
  KK_STUDIO_REBUILD_READY_TIMEOUT_SECONDS default: 900
  KK_STUDIO_REBUILD_WORK_DIR              default: ~/.local/state/kk-studio/database-rebuild
EOF
}

step() {
  echo "==> $1"
}

fail() {
  echo "ERROR: $1" >&2
  exit 1
}

configure_paths() {
  SNAPSHOT_DB="${DB_NAME}_pre_${STAMP}"
  FULL_BACKUP_FILE="$WORK_DIR/${DB_NAME}_full_${STAMP}.dump"
  PRESERVED_DUMP_FILE="$WORK_DIR/${DB_NAME}_preserved_${STAMP}.dump"
  CHECKSUM_FILE="$WORK_DIR/${DB_NAME}_${STAMP}.sha256"
  MANIFEST_FILE="$WORK_DIR/${DB_NAME}_${STAMP}.manifest"
  RESTORE_LOG="$WORK_DIR/${DB_NAME}_restore_${STAMP}.log"
}

load_container_names() {
  FOLLOWER_CONTAINERS=()
  if [ -n "$FOLLOWER_CONTAINER_NAMES" ]; then
    read -r -a FOLLOWER_CONTAINERS <<< "$FOLLOWER_CONTAINER_NAMES"
  fi
  APP_CONTAINERS=("$MAIN_CONTAINER" "${FOLLOWER_CONTAINERS[@]}")
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_identifier() {
  local value=$1
  local label=$2
  [[ "$value" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] \
    || fail "$label must be an unquoted PostgreSQL identifier"
}

require_positive_integer() {
  local value=$1
  local label=$2
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail "$label must be a positive integer"
}

require_external_work_directory() {
  local resolved_work_dir
  resolved_work_dir=$(python3 - "$WORK_DIR" <<'PY'
import os
import sys

print(os.path.realpath(sys.argv[1]))
PY
  )
  case "$resolved_work_dir" in
    "$APP_HOME"|"$APP_HOME"/*)
      fail "backup output must be outside the repository"
      ;;
  esac
}

require_container() {
  docker inspect "$1" >/dev/null 2>&1 || fail "container does not exist: $1"
}

container_running() {
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]
}

db_psql() {
  docker exec -i "$DB_CONTAINER" \
    psql -X -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

db_admin_psql() {
  docker exec -i "$DB_CONTAINER" \
    psql -X -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 "$@"
}

db_scalar() {
  docker exec -i "$DB_CONTAINER" \
    psql -X -U "$DB_USER" -d "$DB_NAME" -At -c "$1"
}

db_admin_scalar() {
  docker exec -i "$DB_CONTAINER" \
    psql -X -U "$DB_USER" -d postgres -At -c "$1"
}

flyway_checksum() {
  python3 - "$1" <<'PY'
import sys
import zlib

with open(sys.argv[1], encoding="utf-8-sig", newline=None) as migration:
    checksum = 0
    for line in migration.read().splitlines():
        checksum = zlib.crc32(line.encode("utf-8"), checksum)

if checksum >= 2**31:
    checksum -= 2**32
print(checksum)
PY
}

table_fingerprint() {
  local table=$1
  db_scalar "
    select count(*)::text || ':' ||
           md5(coalesce(string_agg(row_value, E'\\n' order by row_value), ''))
      from (
        select to_jsonb(t)::text as row_value
          from public.$table t
      ) rows"
}

table_count() {
  local table=$1
  db_scalar "select count(*) from public.$table"
}

show_preserved_counts() {
  local table
  for table in "${PRESERVED_TABLES[@]}"; do
    printf '  %-28s %s\n' "$table" "$(table_count "$table")"
  done
}

capture_database_metadata() {
  DB_OWNER=$(db_admin_scalar \
    "select pg_get_userbyid(datdba) from pg_database where datname = '$DB_NAME'")
  DB_ENCODING=$(db_admin_scalar \
    "select pg_encoding_to_char(encoding) from pg_database where datname = '$DB_NAME'")
  DB_LOCALE_PROVIDER=$(db_admin_scalar \
    "select case datlocprovider when 'c' then 'libc' when 'i' then 'icu' when 'b' then 'builtin' end
       from pg_database where datname = '$DB_NAME'")
  DB_COLLATE=$(db_admin_scalar \
    "select datcollate from pg_database where datname = '$DB_NAME'")
  DB_CTYPE=$(db_admin_scalar \
    "select datctype from pg_database where datname = '$DB_NAME'")
  DB_LOCALE=$(db_admin_scalar \
    "select coalesce(datlocale, '') from pg_database where datname = '$DB_NAME'")
  DB_ICU_RULES=$(db_admin_scalar \
    "select coalesce(daticurules, '') from pg_database where datname = '$DB_NAME'")
  DB_TABLESPACE=$(db_admin_scalar \
    "select t.spcname from pg_database d join pg_tablespace t on t.oid = d.dattablespace
      where d.datname = '$DB_NAME'")
  DB_CONNECTION_LIMIT=$(db_admin_scalar \
    "select datconnlimit from pg_database where datname = '$DB_NAME'")
  DB_SIZE_BYTES=$(db_admin_scalar \
    "select pg_database_size('$DB_NAME')")

  [ -n "$DB_OWNER" ] || fail "database metadata is missing for $DB_NAME"
  [ -n "$DB_LOCALE_PROVIDER" ] || fail "unsupported database locale provider"
}

preflight() {
  require_command docker
  require_command python3
  require_command sha256sum
  require_identifier "$DB_NAME" "database name"
  require_identifier "$DB_USER" "database user"
  require_positive_integer "$READY_TIMEOUT_SECONDS" "ready timeout"
  [ ${#SNAPSHOT_DB} -le 63 ] || fail "snapshot database name exceeds 63 bytes"
  [ -f "$V1_FILE" ] || fail "V1 migration not found: $V1_FILE"
  require_external_work_directory

  require_container "$DB_CONTAINER"
  container_running "$DB_CONTAINER" || fail "database container is not running: $DB_CONTAINER"
  local container
  for container in "${APP_CONTAINERS[@]}"; do
    require_container "$container"
  done
  [ "$DB_CONTAINER" != "$MAIN_CONTAINER" ] || fail "database and Main containers must differ"

  local actual_database
  actual_database=$(db_scalar "select current_database()") \
    || fail "cannot connect to $DB_NAME in $DB_CONTAINER"
  [ "$actual_database" = "$DB_NAME" ] || fail "connected to unexpected database"
  [ "$(db_scalar "select rolsuper from pg_roles where rolname = current_user")" = "t" ] \
    || fail "database user must be a PostgreSQL superuser"
  [ "$(db_admin_scalar "select datallowconn from pg_database where datname = '$DB_NAME'")" = "t" ] \
    || fail "target database does not allow connections"
  [ "$(db_admin_scalar "select datistemplate from pg_database where datname = '$DB_NAME'")" = "f" ] \
    || fail "target database must not be a template"
  [ "$(db_admin_scalar \
    "select coalesce(cardinality(datacl), 0) from pg_database where datname = '$DB_NAME'")" = "0" ] \
    || fail "custom database ACLs are not supported by this rebuild script"
  [ "$(db_admin_scalar \
    "select count(*) from pg_db_role_setting
      where setdatabase = (select oid from pg_database where datname = '$DB_NAME')")" = "0" ] \
    || fail "custom database role settings are not supported by this rebuild script"

  local table
  for table in "${PRESERVED_TABLES[@]}"; do
    [ "$(db_scalar "select to_regclass('public.$table') is not null")" = "t" ] \
      || fail "required preserved table is missing: $table"
  done

  if [ "$SKIP_SNAPSHOT" = false ] \
    && [ "$(db_admin_scalar "select count(*) from pg_database where datname = '$SNAPSHOT_DB'")" != "0" ]; then
    fail "snapshot database already exists: $SNAPSHOT_DB"
  fi

  if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$MAIN_CONTAINER" \
      | grep -qx 'SPRING_FLYWAY_ENABLED=false'; then
    fail "Main container has Flyway explicitly disabled"
  fi

  capture_database_metadata
  EXPECTED_V1_CHECKSUM=$(flyway_checksum "$V1_FILE")
  EXPECTED_READY_CONNECTIONS=$(db_scalar \
    "select count(*) from environment_connection where status = 'READY'")
}

show_plan() {
  echo
  echo "Target:"
  echo "  database:       $DB_NAME ($DB_CONTAINER)"
  echo "  Main:           $MAIN_CONTAINER"
  echo "  followers:      ${FOLLOWER_CONTAINERS[*]:-(none)}"
  echo "  database size:  $DB_SIZE_BYTES bytes"
  echo "  expected V1:    $EXPECTED_V1_CHECKSUM"
  echo "  output:         $WORK_DIR"
  echo
  echo "Preserved row counts:"
  show_preserved_counts
  echo
  echo "Plan:"
  echo "  1. Stop all App containers."
  echo "  2. Write and validate a full custom-format backup."
  echo "  3. Export the seven preserved tables to a mode-0600 custom archive."
  if [ "$SKIP_SNAPSHOT" = true ]; then
    echo "  4. Drop the old database (--skip-snapshot)."
  else
    echo "  4. Rename the old database to $SNAPSHOT_DB and freeze it."
  fi
  echo "  5. Create an empty database with the original owner/locale."
  echo "  6. Start Main and require its Flyway V1 checksum to match this workspace."
  echo "  7. Stop Main, restore atomically, and compare all preserved row fingerprints."
  echo "  8. Start Main and followers, then verify health and Daemon reconnect."
}

confirm_operation() {
  if [ "$ASSUME_YES" = true ]; then
    return
  fi
  echo
  echo "All non-preserved data in $DB_NAME will be deleted."
  local confirmation
  if ! read -r -p "Type the database name to continue: " confirmation; then
    fail "confirmation input was not available"
  fi
  [ "$confirmation" = "$DB_NAME" ] || fail "confirmation did not match the database name"
}

prepare_work_directory() {
  mkdir -p "$WORK_DIR"
  chmod 700 "$WORK_DIR"
  local path
  for path in \
    "$FULL_BACKUP_FILE" \
    "$PRESERVED_DUMP_FILE" \
    "$CHECKSUM_FILE" \
    "$MANIFEST_FILE" \
    "$RESTORE_LOG"; do
    [ ! -e "$path" ] || fail "refusing to overwrite artifact: $path"
  done

  local available_kib
  local required_kib
  available_kib=$(df -Pk "$WORK_DIR" | awk 'NR == 2 { print $4 }')
  required_kib=$(((DB_SIZE_BYTES + 67108864 + 1023) / 1024))
  [ "$available_kib" -ge "$required_kib" ] \
    || fail "output filesystem has less than database-size + 64 MiB free"
}

capture_initial_container_states() {
  INITIAL_RUNNING_CONTAINERS=()
  local container
  for container in "${APP_CONTAINERS[@]}"; do
    if container_running "$container"; then
      INITIAL_RUNNING_CONTAINERS+=("$container")
    fi
  done
}

stop_app_containers() {
  local running=()
  local container
  for container in "${APP_CONTAINERS[@]}"; do
    if container_running "$container"; then
      running+=("$container")
    fi
  done
  if [ ${#running[@]} -gt 0 ]; then
    step "Stop App containers: ${running[*]}"
    docker stop "${running[@]}" >/dev/null
  fi
}

terminate_database_connections() {
  step "Terminate remaining connections to $DB_NAME"
  db_admin_psql -q -c "
    select pg_terminate_backend(pid)
      from pg_stat_activity
     where datname = '$DB_NAME'
       and pid <> pg_backend_pid()" >/dev/null
}

capture_preserved_state() {
  step "Capture preserved-table fingerprints"
  local table
  for table in "${PRESERVED_TABLES[@]}"; do
    EXPECTED_FINGERPRINTS["$table"]=$(table_fingerprint "$table")
  done
}

write_backups() {
  step "Write full custom-format backup"
  local full_tmp="${FULL_BACKUP_FILE}.tmp"
  docker exec -i "$DB_CONTAINER" \
    pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --create > "$full_tmp"
  [ -s "$full_tmp" ] || fail "full backup is empty"
  docker exec -i "$DB_CONTAINER" pg_restore --list < "$full_tmp" >/dev/null \
    || fail "full backup validation failed"
  mv "$full_tmp" "$FULL_BACKUP_FILE"

  step "Export preserved data to custom archive"
  local preserved_tmp="${PRESERVED_DUMP_FILE}.tmp"
  local table_args=()
  local table
  for table in "${PRESERVED_TABLES[@]}"; do
    table_args+=(--table="public.$table")
  done
  docker exec -i "$DB_CONTAINER" \
    pg_dump -U "$DB_USER" -d "$DB_NAME" \
      --format=custom --data-only --no-owner --no-privileges \
      "${table_args[@]}" > "$preserved_tmp"
  [ -s "$preserved_tmp" ] || fail "preserved-data dump is empty"
  docker exec -i "$DB_CONTAINER" pg_restore --list < "$preserved_tmp" >/dev/null \
    || fail "preserved-data backup validation failed"
  mv "$preserved_tmp" "$PRESERVED_DUMP_FILE"

  sha256sum "$FULL_BACKUP_FILE" "$PRESERVED_DUMP_FILE" > "$CHECKSUM_FILE"
  {
    echo "database=$DB_NAME"
    echo "snapshot=$SNAPSHOT_DB"
    echo "expected_v1_checksum=$EXPECTED_V1_CHECKSUM"
    for table in "${PRESERVED_TABLES[@]}"; do
      echo "$table.count=${EXPECTED_FINGERPRINTS[$table]%%:*}"
    done
  } > "$MANIFEST_FILE"
}

create_fresh_database() {
  local args=(
    -U "$DB_USER"
    --maintenance-db=postgres
    --template=template0
    --owner="$DB_OWNER"
    --encoding="$DB_ENCODING"
    --locale-provider="$DB_LOCALE_PROVIDER"
  )
  case "$DB_LOCALE_PROVIDER" in
    libc)
      args+=(--lc-collate="$DB_COLLATE" --lc-ctype="$DB_CTYPE")
      ;;
    icu)
      args+=(--icu-locale="$DB_LOCALE")
      if [ -n "$DB_ICU_RULES" ]; then
        args+=(--icu-rules="$DB_ICU_RULES")
      fi
      ;;
    builtin)
      args+=(--builtin-locale="$DB_LOCALE")
      ;;
    *)
      fail "unsupported locale provider: $DB_LOCALE_PROVIDER"
      ;;
  esac
  if [ "$DB_TABLESPACE" != "pg_default" ]; then
    args+=(--tablespace="$DB_TABLESPACE")
  fi

  docker exec "$DB_CONTAINER" createdb "${args[@]}" "$DB_NAME"
  if [ "$DB_CONNECTION_LIMIT" != "-1" ]; then
    db_admin_psql -q -c \
      "alter database \"$DB_NAME\" connection limit $DB_CONNECTION_LIMIT" >/dev/null
  fi
}

replace_database() {
  DATABASE_REPLACED=true
  if [ "$SKIP_SNAPSHOT" = true ]; then
    step "Drop old database"
    docker exec "$DB_CONTAINER" \
      dropdb -U "$DB_USER" --maintenance-db=postgres --force "$DB_NAME"
  else
    step "Rename old database to frozen snapshot: $SNAPSHOT_DB"
    db_admin_psql -q -c \
      "alter database \"$DB_NAME\" rename to \"$SNAPSHOT_DB\"" >/dev/null
    SNAPSHOT_CREATED=true
    db_admin_psql -q -c \
      "alter database \"$SNAPSHOT_DB\" allow_connections false" >/dev/null
  fi

  step "Create empty database with original metadata"
  create_fresh_database
}

start_main_for_migration() {
  step "Start Main to apply Flyway V1"
  docker start "$MAIN_CONTAINER" >/dev/null
}

wait_for_flyway() {
  step "Wait for Flyway V1 (timeout ${READY_TIMEOUT_SECONDS}s)"
  local waited=0
  local checksum
  while [ "$waited" -lt "$READY_TIMEOUT_SECONDS" ]; do
    checksum=$(db_scalar \
      "select checksum from flyway_schema_history where version = '1' and success" \
      2>/dev/null || true)
    if [ -n "$checksum" ]; then
      ACTUAL_V1_CHECKSUM=$checksum
      [ "$ACTUAL_V1_CHECKSUM" = "$EXPECTED_V1_CHECKSUM" ] \
        || fail "Main image V1 checksum $ACTUAL_V1_CHECKSUM does not match workspace V1 $EXPECTED_V1_CHECKSUM"
      step "Flyway V1 complete with checksum $ACTUAL_V1_CHECKSUM"
      return
    fi
    sleep 3
    waited=$((waited + 3))
  done
  fail "timed out waiting for Flyway V1"
}

stop_main_after_migration() {
  step "Stop Main before data restore"
  if container_running "$MAIN_CONTAINER"; then
    docker stop "$MAIN_CONTAINER" >/dev/null
  fi
}

require_empty_preserved_tables() {
  local table
  for table in "${PRESERVED_TABLES[@]}"; do
    [ "$(table_count "$table")" = "0" ] \
      || fail "fresh V1 unexpectedly populated preserved table: $table"
  done
}

restore_preserved_data() {
  step "Restore preserved data in one transaction"
  if ! docker exec -i "$DB_CONTAINER" \
      pg_restore -U "$DB_USER" -d "$DB_NAME" \
        --data-only --single-transaction --exit-on-error \
      < "$PRESERVED_DUMP_FILE" > /dev/null 2> "$RESTORE_LOG"; then
    fail "preserved-data restore failed; details are in the mode-0600 restore log"
  fi
  rm -f "$RESTORE_LOG"
}

verify_preserved_data() {
  step "Verify preserved-table fingerprints"
  local table
  local expected
  local actual
  for table in "${PRESERVED_TABLES[@]}"; do
    expected=${EXPECTED_FINGERPRINTS[$table]}
    actual=$(table_fingerprint "$table")
    [ "$actual" = "$expected" ] \
      || fail "preserved-data mismatch for $table (expected ${expected%%:*} rows, got ${actual%%:*})"
    printf '  %-28s %s rows\n' "$table" "${actual%%:*}"
  done
}

wait_for_container_ready() {
  local container=$1
  local waited=0
  local running
  local health
  while [ "$waited" -lt "$READY_TIMEOUT_SECONDS" ]; do
    running=$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo false)
    health=$(docker inspect \
      -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      "$container" 2>/dev/null || echo missing)
    if [ "$running" = "true" ] && { [ "$health" = "healthy" ] || [ "$health" = "none" ]; }; then
      step "Container ready: $container"
      return
    fi
    sleep 3
    waited=$((waited + 3))
  done
  fail "timed out waiting for container readiness: $container"
}

start_runtime() {
  step "Start Main"
  docker start "$MAIN_CONTAINER" >/dev/null
  wait_for_container_ready "$MAIN_CONTAINER"

  if [ ${#FOLLOWER_CONTAINERS[@]} -gt 0 ]; then
    step "Start follower containers: ${FOLLOWER_CONTAINERS[*]}"
    docker start "${FOLLOWER_CONTAINERS[@]}" >/dev/null
    local container
    for container in "${FOLLOWER_CONTAINERS[@]}"; do
      wait_for_container_ready "$container"
    done
  fi
}

wait_for_daemon_reconnect() {
  if [ "$EXPECTED_READY_CONNECTIONS" -eq 0 ]; then
    step "No pre-rebuild READY Daemon connection required"
    return
  fi

  step "Wait for $EXPECTED_READY_CONNECTIONS READY Daemon connection(s)"
  local waited=0
  local actual
  while [ "$waited" -lt "$READY_TIMEOUT_SECONDS" ]; do
    actual=$(db_scalar \
      "select count(*) from environment_connection where status = 'READY'")
    if [ "$actual" -ge "$EXPECTED_READY_CONNECTIONS" ]; then
      step "Daemon reconnect verified ($actual READY)"
      return
    fi
    sleep 3
    waited=$((waited + 3))
  done
  fail "timed out waiting for Daemon reconnect"
}

rollback_hint() {
  if [ "$SNAPSHOT_CREATED" = true ]; then
    echo "Rollback database:" >&2
    echo "  1. Stop all App containers." >&2
    echo "  2. DROP DATABASE \"$DB_NAME\" WITH (FORCE);" >&2
    echo "  3. ALTER DATABASE \"$SNAPSHOT_DB\" RENAME TO \"$DB_NAME\";" >&2
    echo "  4. ALTER DATABASE \"$DB_NAME\" ALLOW_CONNECTIONS true;" >&2
  else
    echo "Full backup for recovery: $FULL_BACKUP_FILE" >&2
  fi
}

on_exit() {
  local status=$?
  if [ "$status" -eq 0 ] || [ "$SUCCESS" = true ] || [ "$MAINTENANCE_STARTED" = false ]; then
    return
  fi

  set +e
  if [ "$DATABASE_REPLACED" = true ]; then
    echo "ERROR: rebuild failed after database replacement; App containers remain stopped." >&2
    stop_app_containers
    rollback_hint
  elif [ ${#INITIAL_RUNNING_CONTAINERS[@]} -gt 0 ]; then
    echo "Rebuild failed before database replacement; restoring prior container state." >&2
    docker start "${INITIAL_RUNNING_CONTAINERS[@]}" >/dev/null
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
      --skip-snapshot)
        SKIP_SNAPSHOT=true
        shift
        ;;
      --work-dir)
        [ $# -ge 2 ] || fail "--work-dir requires a path"
        WORK_DIR=$2
        shift 2
        ;;
      -h|--help)
        usage
        return
        ;;
      *)
        usage >&2
        fail "unknown argument: $1"
        ;;
    esac
  done

  configure_paths
  load_container_names
  preflight
  show_plan

  if [ "$DRY_RUN" = true ]; then
    echo
    echo "Dry-run complete: no files, containers, or databases were changed."
    return
  fi

  confirm_operation
  prepare_work_directory
  capture_initial_container_states
  MAINTENANCE_STARTED=true

  stop_app_containers
  terminate_database_connections
  capture_preserved_state
  write_backups
  replace_database

  start_main_for_migration
  wait_for_flyway
  stop_main_after_migration
  require_empty_preserved_tables
  restore_preserved_data
  verify_preserved_data

  start_runtime
  wait_for_daemon_reconnect

  {
    echo "actual_v1_checksum=$ACTUAL_V1_CHECKSUM"
    echo "status=complete"
  } >> "$MANIFEST_FILE"
  SUCCESS=true

  echo
  echo "Database rebuild complete."
  echo "  full backup:    $FULL_BACKUP_FILE"
  echo "  preserved dump: $PRESERVED_DUMP_FILE"
  echo "  checksums:      $CHECKSUM_FILE"
  echo "  manifest:       $MANIFEST_FILE"
  if [ "$SNAPSHOT_CREATED" = true ]; then
    echo "  frozen snapshot: $SNAPSHOT_DB"
  fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  trap on_exit EXIT
  main "$@"
fi
