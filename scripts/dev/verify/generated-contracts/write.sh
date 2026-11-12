#!/usr/bin/env bash
# 重新生成前端传输契约与 Go Daemon 协议代码。会写文件，提交前必须人工审阅 diff；
# 日常校验用 check.sh。
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
node "$SCRIPT_DIR/capabilities.mjs"
"$REPO_ROOT/daemon-go/scripts/generate-protocol.sh"
exec env JAVA_HOME="$JDK21" mvn -q -pl web -am \
  -Dtest=TsContractArtifactTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkstudio.contract.generation.update=true \
  test
