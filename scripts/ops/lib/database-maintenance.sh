# shellcheck shell=bash
# shellcheck disable=SC2034 # sourced library exports shared variables to its entrypoints
#
# scripts/ops 下三个数据库维护入口的私有共享实现，不是公开入口。
#
# 三个入口都只通过原生 libpq 客户端（psql/pg_dump/pg_restore/createdb）与继承的连接设置访问
# 数据库，因此这里集中承载它们共有的、只有一份实现才有意义的机制：
#
#   * 仓库根解析，以及「产物必须落在仓库外」的强制约束；
#   * 连接解析：CLI 非敏感参数 > VPS_POSTGRES_* > 标准 libpq PG*/PGSERVICE，并集中校验端口与库名；
#   * 库名与标识符校验：库名只接受纯标识符，URI/conninfo 一律拒绝；
#   * libpq 调用封装：永不交互提示、永不把口令放进参数、固定可复现的会话默认值；
#   * 仓库 V1 baseline 的 Flyway checksum 与文件 sha256。
#
# 维护约定：本文件不实现业务步骤（顺序由各入口持有）、不管理任何服务的生命周期、不打印任何行值；
# 错误信息不回显可能带口令的连接设置值。
#
# 目标库的选择（psql -d、pg_dump --dbname、createdb --maintenance-db）是工具自身的行为，库名已在
# 校验后由脚本打印；host/port/username/password 只经环境变量下发，其中 password 不出现在 argv。

OPS_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
OPS_DIR=$(dirname "$OPS_LIB_DIR")
CATALOG_TOOL="$OPS_DIR/agent_catalog.py"
V1_MIGRATION_RELATIVE=schema/src/main/resources/db/migration/V1__schema.sql

# 维护入口只迁移这三张表，顺序即外键依赖顺序：Provider -> Model -> Agent 定义。
CATALOG_TABLES='agent_provider agent_model agent_definition'

# 会话固定项：指纹、导出与回灌必须在任何服务器默认值下都可复现。
DATABASE_SESSION_OPTIONS='-c timezone=UTC -c datestyle=ISO'

# 产物默认落在仓库外的 owner-only 目录，可通过各入口的 --work-dir 覆盖。
DEFAULT_MAINTENANCE_DIR=${KK_STUDIO_MAINTENANCE_DIR:-${XDG_STATE_HOME:-${HOME:-/var/lib}/.local/state}/kk-studio/maintenance}
# reset 用它执行 alter database/createdb；它是可配置的非敏感库名，默认 postgres。
MAINTENANCE_DB=${KK_STUDIO_MAINTENANCE_DB:-postgres}

step() {
  echo "==> $1"
}

fail() {
  echo "ERROR: $1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

# 带值选项不接受空值或另一个选项。解析必须在任何连接尝试之前 fail closed，且错误不回显取值。
require_option_value() {
  local option=$1
  local argument_count=$2
  local value=${3:-}
  if [ "$argument_count" -lt 2 ] || [ -z "$value" ] || [[ "$value" == -* ]]; then
    fail "$option requires a value"
  fi
}

resolve_repository_root() {
  if [ -n "${KK_STUDIO_REPO_ROOT:-}" ]; then
    cd "$KK_STUDIO_REPO_ROOT" && pwd -P
    return
  fi
  local candidate=$OPS_DIR
  while [ "$candidate" != "/" ] && [ ! -e "$candidate/.git" ]; do
    candidate=$(dirname "$candidate")
  done
  if [ ! -e "$candidate/.git" ]; then
    echo "ERROR: cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT" >&2
    return 1
  fi
  printf '%s\n' "$candidate"
}

resolve_absolute_path() {
  python3 -c 'import os, sys; print(os.path.realpath(sys.argv[1]))' "$1"
}

# 出参：要求目录解析后不在仓库内。含敏感凭据的包里任何文件都不该有被提交的机会。
require_external_directory() {
  local directory=$1
  local label=$2
  local resolved
  resolved=$(resolve_absolute_path "$directory")
  case "$resolved" in
    "$REPO_ROOT" | "$REPO_ROOT"/*)
      fail "$label must be outside the repository: $directory"
      ;;
  esac
}

# 库名只接受纯标识符。URI 与 libpq conninfo 一律拒绝：reset 必须能覆盖维护库名，而带口令的连接
# 串既可能被回显进日志，也会让「连到哪个库」变成隐式行为。失败信息不回显取值。
require_identifier() {
  local value=$1
  local label=$2
  case "$value" in
    '' | *[!A-Za-z0-9_]* | [0-9]*)
      fail "$label must be a plain PostgreSQL identifier (letters, digits, underscore), not a URI or connection string"
      ;;
  esac
}

# 端口必须是 1-65535 的十进制数字。取值不回显：带口令的连接串不允许借端口位进入日志。
require_port() {
  local value=$1
  local label=$2
  case "$value" in
    '' | *[!0-9]*)
      fail "$label must be a decimal port number between 1 and 65535"
      ;;
  esac
  # 先按长度拒绝过长取值，再做数值比较，避免依赖某种整数解析的溢出行为。
  if [ "${#value}" -gt 5 ] || [ "$((10#$value))" -lt 1 ] || [ "$((10#$value))" -gt 65535 ]; then
    fail "$label must be a decimal port number between 1 and 65535"
  fi
}

# 连接解析。三个入口共用的唯一一份映射实现：
#
#   优先级 CLI 非敏感参数 > VPS_POSTGRES_* > 标准 libpq（PG*/PGSERVICE）；
#   结果只写进 PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSWORD 环境变量，由 psql、pg_dump、pg_restore、
#   createdb 与私有 Python helper 继承；
#   口令没有参数形式：它只可能来自 VPS_POSTGRES_PASSWORD，或 libpq 自己的 PGPASSWORD/PGPASSFILE。
#
# 入参：CLI 提供的 host/port/username/database，空字符串表示该入口没有提供对应参数。
configure_connection() {
  local cli_host=$1
  local cli_port=$2
  local cli_username=$3
  local cli_database=$4

  local host=${cli_host:-${VPS_POSTGRES_HOST:-}}
  local port=${cli_port:-${VPS_POSTGRES_PORT:-}}
  local username=${cli_username:-${VPS_POSTGRES_USERNAME:-}}
  local database=${cli_database:-${VPS_POSTGRES_DATABASE:-}}

  # 先校验、后导出：非法取值在任何数据库访问之前失败，且不回显取值。
  if [ -n "$port" ]; then
    if [ -n "$cli_port" ]; then
      require_port "$port" "--port"
    else
      require_port "$port" "VPS_POSTGRES_PORT"
    fi
  fi
  if [ -n "$database" ]; then
    if [ -n "$cli_database" ]; then
      require_identifier "$database" "--database"
    else
      require_identifier "$database" "VPS_POSTGRES_DATABASE"
    fi
  fi

  if [ -n "$host" ]; then
    PGHOST=$host
    export PGHOST
    # libpq 优先用 PGHOSTADDR 建立连接；显式 CLI/VPS host 必须同时成为真实 socket 目标。
    unset PGHOSTADDR
  fi
  if [ -n "$port" ]; then
    PGPORT=$port
    export PGPORT
  fi
  if [ -n "$username" ]; then
    PGUSER=$username
    export PGUSER
  fi
  if [ -n "$database" ]; then
    PGDATABASE=$database
    export PGDATABASE
  fi
  # 未提供口令时保留 libpq 自己的 PGPASSWORD/PGPASSFILE，由 libpq 决定用哪一个。
  if [ -n "${VPS_POSTGRES_PASSWORD:-}" ]; then
    PGPASSWORD=$VPS_POSTGRES_PASSWORD
    export PGPASSWORD
    unset VPS_POSTGRES_PASSWORD
  fi

  # libpq service file 会覆盖同名的 PG* 默认值。只要选择了 CLI/VPS 的直接连接模式，就不混用
  # PGSERVICE；未指定的字段仍可从标准 PGHOST/PGPORT/PGUSER/PGDATABASE 继承。仅提供密码时保留
  # PGSERVICE，使 VPS_POSTGRES_PASSWORD 也能为既有 service 提供认证。
  if [ -n "$cli_host$cli_port$cli_username$cli_database" ] \
      || [ -n "${VPS_POSTGRES_HOST:-}${VPS_POSTGRES_PORT:-}${VPS_POSTGRES_USERNAME:-}${VPS_POSTGRES_DATABASE:-}" ]; then
    unset PGSERVICE
  fi
}

libpq_psql() {
  PGOPTIONS="${PGOPTIONS:+$PGOPTIONS }$DATABASE_SESSION_OPTIONS" \
    psql -X -q -v ON_ERROR_STOP=1 --no-password "$@"
}

psql_database() {
  local database=$1
  shift
  libpq_psql -d "$database" "$@"
}

libpq_default_scalar() {
  libpq_psql -A -t -c "$1"
}

database_scalar() {
  local database=$1
  local query=$2
  psql_database "$database" -A -t -c "$query"
}

# 目标库解析：PGDATABASE 给出纯库名时以它为准（它可能来自 --database/VPS_POSTGRES_DATABASE 的映射），
# 否则用 libpq 默认连接的当前库。
# 出参：TARGET_DB。
resolve_target_database() {
  local resolved
  if [ -n "${PGDATABASE:-}" ]; then
    require_identifier "$PGDATABASE" "PGDATABASE"
    resolved=$PGDATABASE
  else
    resolved=$(libpq_default_scalar 'select current_database()') \
      || fail "cannot determine the target database; name it with --database, VPS_POSTGRES_DATABASE or PGDATABASE"
    require_identifier "$resolved" "the connected database name"
  fi
  TARGET_DB=$resolved
}

report_value() {
  local report=$1
  local key=$2
  printf '%s\n' "$report" | sed -n "s/^$key=//p"
}

# 把任意文本安全地包进 SQL 字面量：角色名等来自数据库的名字可以包含单引号。
sql_literal() {
  printf "'%s'" "${1//\'/\'\'}"
}

# 私有 Python helper 只通过这一个入口调用：显式依赖 python3，不要求 helper 自身可执行。
catalog_tool() {
  python3 "$CATALOG_TOOL" "$@"
}

require_report_value() {
  local report=$1
  local key=$2
  local label=$3
  local value
  value=$(report_value "$report" "$key")
  [ -n "$value" ] || fail "$label is missing from $key"
  printf '%s\n' "$value"
}

prepare_owner_only_directory() {
  mkdir -p "$1"
  chmod 700 "$1"
}

# Fingerprint 与凭证都不允许出现两套算法：这里只保留一份 sha256 实现（不依赖宿主是否有
# sha256sum 二进制，产物可能很大因此流式读取）。
file_sha256() {
  python3 - "$1" <<'PY'
import hashlib
import sys

digest = hashlib.sha256()
with open(sys.argv[1], "rb") as artifact:
    for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
        digest.update(chunk)
print(digest.hexdigest())
PY
}

# Flyway 对 SQL migration 的 checksum：去掉 BOM 后按 "\n" 分行，逐行 CRC32 累积。
# 仓库 V1 是唯一 migration，导入必须证明目标库的 V1 与本仓库 revision 一致。
v1_checksum() {
  python3 - "$V1_MIGRATION" <<'PY'
import sys
import zlib

with open(sys.argv[1], encoding="utf-8") as migration:
    content = migration.read()
if content.startswith("\ufeff"):
    content = content[1:]
checksum = 0
for line in content.split("\n"):
    checksum = zlib.crc32(line.encode("utf-8"), checksum)
if checksum >= 2**31:
    checksum -= 2**32
print(checksum)
PY
}

REPO_ROOT=$(resolve_repository_root)
V1_MIGRATION="$REPO_ROOT/$V1_MIGRATION_RELATIVE"
