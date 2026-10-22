#!/usr/bin/env bash
# 校验前端传输契约（frontend/src/shared/api/generated/contracts.ts）与 Daemon 协议常量
# （daemon/src/generated/protocol-contract.ts）是否与 Java 事实源漂移。只读：缺文件、字段/enum 变化或产物被手改都会失败，绝不修改源码；更新必须显式运行 write.sh。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# 仓库根解析：KK_STUDIO_REPO_ROOT 优先；否则从脚本位置向上寻找 worktree 根（.git 文件或目录）。
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

# 生成器用真实 Boot4/Jackson3 mapper，必须与 CI 相同的 JDK 21。
JDK21=${JAVA_HOME_21:-${JAVA_HOME:-}}
if [ -z "$JDK21" ]; then
  echo "ERROR: set JAVA_HOME_21 (or JAVA_HOME) to a JDK 21 before running the targeted web reactor" >&2
  exit 1
fi

cd "$REPO_ROOT"
exec env JAVA_HOME="$JDK21" mvn -q -pl web -am \
  -Dtest=TsContractArtifactTest,DaemonContractArtifactTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
