# shellcheck shell=bash
#
# scripts/ops 下三个数据库维护入口的私有共享实现，不是公开入口。
#
# 三个入口都只通过原生 libpq 客户端（psql/pg_dump/pg_restore/createdb）与继承的连接设置访问
# 数据库，因此这里集中承载它们共有的、只有一份实现才有意义的机制：
#
#   * 仓库根解析，以及「产物必须落在仓库外」的强制约束；
#   * 库名与标识符校验：PGDATABASE 只接受纯库名，URI/conninfo 一律拒绝；
#   * libpq 调用封装：永不交互提示、永不把口令放进参数、固定可复现的会话默认值；
#   * 仓库 V1 baseline 的 Flyway checksum 与文件 sha256。
#
# 维护约定：本文件不实现业务步骤（顺序由各入口持有）、不接触容器或应用、不打印任何行值；
# 错误信息不回显可能带口令的连接设置值。

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

# 目标库解析：PGDATABASE 给出纯库名时以它为准，否则用 libpq 默认连接的当前库。
# 出参：TARGET_DB。
resolve_target_database() {
  local resolved
  if [ -n "${PGDATABASE:-}" ]; then
    require_identifier "$PGDATABASE" "PGDATABASE"
    resolved=$PGDATABASE
  else
    resolved=$(libpq_default_scalar 'select current_database()') \
      || fail "cannot determine the target database; set PGDATABASE to the target database name"
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
