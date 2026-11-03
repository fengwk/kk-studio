#!/usr/bin/env bash
# 校验前端传输契约（frontend/src/shared/api/generated/contracts.ts）、Daemon 控制协议代码
# （daemon/src/generated/control_pb.ts）与能力 schema 是否漂移。只读：缺文件、字段/enum 变化或产物被手改都会失败，绝不修改源码；更新必须显式运行 write.sh。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/../../lib/repo-root.sh"
resolve_repo_root "$SCRIPT_DIR"

# 生成器用真实 Boot4/Jackson3 mapper，必须与 CI 相同的 JDK 21。
JDK21=${JAVA_HOME_21:-${JAVA_HOME:-}}
if [ -z "$JDK21" ]; then
  echo "ERROR: set JAVA_HOME_21 (or JAVA_HOME) to a JDK 21 before running the targeted web reactor" >&2
  exit 1
fi

cd "$REPO_ROOT"
node "$SCRIPT_DIR/capabilities.mjs" --check
node "$REPO_ROOT/platform/daemon-server/src/main/protocol/ts/generate.mjs" --check
exec env JAVA_HOME="$JDK21" mvn -q -pl web -am \
  -Dtest=TsContractArtifactTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
