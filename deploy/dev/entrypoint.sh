#!/usr/bin/env bash
#
# kk-studio Dev 节点 entrypoint。
#
# 职责顺序（每一步失败都 fail closed，不留下半启动状态）：
#   1. 校验运行契约：prod profile、Flyway disabled、Harness worker disabled、
#      registration token 与 control-plane origin 齐备且合法。
#   2. 把外部只读挂载的 SSH 凭据装进 `$HOME/.ssh`：只复制 `id_*`，私钥 0600、`.pub`
#      0644；github.com 的 host key 由镜像内的 `/etc/ssh/ssh_known_hosts` 提供。
#   3. 准备持久 Git 工作区：已存在的 checkout 永不覆盖；首次启动只能从配置的 clean
#      remote 克隆，或显式允许时使用镜像内的源码快照。
#   4. 校验工作区修订：把持久 checkout 无损快进到 `origin/$GIT_BRANCH`；fetch 失败或
#      两侧历史互不包含时容器启动失败，落后且 git 拒绝快进（本地修改或未跟踪文件会被
#      覆盖）时也启动失败，不允许静默运行未验证的修订。未 push 的本地提交按原样服务，
#      entrypoint 永不 reset/rebase/stash/checkout。
#   5. 用仓库既有 lifecycle `scripts/dev.sh start` 启动 Backend（prod profile、
#      Flyway/worker disabled、loopback 8080）和 Vite（0.0.0.0:5173、代理 Backend）。
#   6. 以前台 Environment Daemon 作为容器主进程，经内部 Docker 网络连接稳定 Main
#      App（唯一 Harness worker 与 Flyway owner）的 WebSocket，`environment-root`
#      指向持久工作区根。
#
# 凭据边界：SSH 私钥只在容器启动时从挂载目录复制进 `$HOME/.ssh`，镜像、命令行和日志
# 都不保存 key 内容，源目录的 `config`/`known_hosts`/`authorized_keys` 也不继承；
# Daemon registration token 只作为 Daemon 参数传递。
set -euo pipefail

WORKSPACE_ROOT=${KK_STUDIO_WORKSPACE_ROOT:-/workspace}
REPOSITORY_DIR=${KK_STUDIO_REPOSITORY_DIR:-$WORKSPACE_ROOT/kk-studio}
SOURCE_SEED=${KK_STUDIO_SOURCE_SEED:-/opt/kk-studio/source}
ALLOW_SOURCE_SEED=${KK_STUDIO_DEV_ALLOW_SOURCE_SEED:-false}
GIT_REMOTE_URL=${KK_STUDIO_GIT_REMOTE_URL:-}
GIT_BRANCH=${KK_STUDIO_GIT_BRANCH:-dev}
SSH_CREDENTIALS_DIR=${KK_STUDIO_SSH_CREDENTIALS_DIR:-/run/kk-studio/ssh}
SSH_DIR=$HOME/.ssh

SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}
SPRING_FLYWAY_ENABLED=${SPRING_FLYWAY_ENABLED:-false}
# Dev Backend 的 Harness 层只提供 control/query preview：进程内 dispatcher 必须保持
# 关闭，否则 Dev 会与 Main 竞争同一 durable database 上的 Harness Work。
HARNESS_RUNTIME_WORKERS_ENABLED=${KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED:-false}
BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}
BACKEND_PORT=${BACKEND_PORT:-8080}
FRONTEND_HOST=${FRONTEND_HOST:-0.0.0.0}
FRONTEND_PORT=${FRONTEND_PORT:-5173}
DEV_WORK_DIR=${DEV_WORK_DIR:-/var/kk-studio/dev}
# 容器首次启动会先做一次完整 Maven 构建和前端依赖安装，Backend 起服务的 90 秒默认
# 预算在冷 cache/弱 CPU 下不够用；这里给出一个有界的容器预算。
DEV_READY_TIMEOUT_SECONDS=${DEV_READY_TIMEOUT_SECONDS:-600}

# Daemon 的 gateway URI 由外部 Compose 提供的 HTTP(S) control-plane origin 派生：Dev 节点
# 不连接本容器 Backend，而是连接稳定 Main（唯一 Harness worker 与 Flyway owner）。
CONTROL_PLANE_BASE_URL=${KK_STUDIO_CONTROL_PLANE_BASE_URL:-}
DAEMON_GATEWAY_PATH=/api/harness/environment-daemon/v1
DAEMON_GATEWAY_URI=
DAEMON_NOTE=${KK_STUDIO_DAEMON_NOTE:-kk-studio dev node}
DAEMON_DATA_DIR=${KK_STUDIO_DAEMON_DATA_DIR:-$WORKSPACE_ROOT/.kkstudio/daemon}
DAEMON_JAR=/opt/kk-studio/daemon.jar
DAEMON_LIB=/opt/kk-studio/lib

step() {
  echo "==> $1"
}

# 立即摘除自身环境中的 secret：后续只能显式交给真正需要它的子进程。
registration_token=${KK_STUDIO_DAEMON_REGISTRATION_TOKEN:-}
unset KK_STUDIO_DAEMON_REGISTRATION_TOKEN

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 1
  fi
}

validate_runtime_contract() {
  if [ "$SPRING_PROFILES_ACTIVE" != "prod" ]; then
    echo "ERROR: Dev node requires SPRING_PROFILES_ACTIVE=prod; got $SPRING_PROFILES_ACTIVE." >&2
    exit 1
  fi
  if [ "$SPRING_FLYWAY_ENABLED" != "false" ]; then
    echo "ERROR: Dev node must keep SPRING_FLYWAY_ENABLED=false; Main is the only Flyway owner." >&2
    exit 1
  fi
  if [ "$HARNESS_RUNTIME_WORKERS_ENABLED" != "false" ]; then
    echo "ERROR: Dev node must keep KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false;" >&2
    echo "       Main is the only Harness worker." >&2
    exit 1
  fi
  if [ -z "$CONTROL_PLANE_BASE_URL" ]; then
    echo "ERROR: KK_STUDIO_CONTROL_PLANE_BASE_URL is required: the Daemon registers with Main." >&2
    echo "       Set it to a bare http(s) origin such as http://vps-kk-studio:8080." >&2
    exit 1
  fi
  if [ -z "$registration_token" ]; then
    echo "ERROR: KK_STUDIO_DAEMON_REGISTRATION_TOKEN is required to register the Daemon." >&2
    exit 1
  fi
}

# 把外部 Compose 提供的 HTTP(S) control-plane origin 归一化为 Daemon 的 WebSocket gateway
# URI：只接受由 DNS/IPv4 host 与可选端口组成的裸 origin（允许一个结尾 `/`），ws/wss
# 等其它 scheme、路径、query、fragment、userinfo 和空白都拒绝；错误信息不回显输入值，
# 避免把私密环境文件的配置写进日志。
resolve_daemon_gateway_uri() {
  local base_url=${1:-}
  local port scheme
  if [[ ! "$base_url" =~ ^(https?)://(([[:alnum:]]|[[:alnum:]][[:alnum:]_.-]*[[:alnum:]_])(:([[:digit:]]{1,5}))?)/?$ ]]; then
    echo "ERROR: KK_STUDIO_CONTROL_PLANE_BASE_URL must be a bare http:// or https:// origin." >&2
    echo "       Point it at the internal Main App origin, for example http://vps-kk-studio:8080." >&2
    return 1
  fi
  scheme=${BASH_REMATCH[1]}
  port=${BASH_REMATCH[5]}
  if [ -n "$port" ] && ((10#$port == 0 || 10#$port > 65535)); then
    echo "ERROR: KK_STUDIO_CONTROL_PLANE_BASE_URL contains an invalid port." >&2
    return 1
  fi
  if [ "$scheme" = "https" ]; then
    scheme=wss
  else
    scheme=ws
  fi
  printf '%s://%s%s\n' "$scheme" "${BASH_REMATCH[2]}" "$DAEMON_GATEWAY_PATH"
}

# SSH key 是运行时挂载的：挂载目录缺失/不可读或没有私钥时直接失败，容器不会以无法
# push 的状态继续启动。只复制 `id_*`，源目录的 config/known_hosts/authorized_keys
# 不进入容器，避免继承宿主或旧代理配置。
install_ssh_credentials() {
  if [ ! -d "$SSH_CREDENTIALS_DIR" ] || [ ! -r "$SSH_CREDENTIALS_DIR" ]; then
    echo "ERROR: $SSH_CREDENTIALS_DIR must be a readable directory holding the mounted SSH keys." >&2
    echo "       Mount the SSH credentials read-only for uid $(id -u) and restart." >&2
    exit 1
  fi
  local key name private_keys=0
  mkdir -p "$SSH_DIR"
  chmod 0700 "$SSH_DIR"
  # 容器 restart 会保留 writable layer；先移除上次注入的 identity，避免 key 轮换后
  # 继续携带已从挂载源删除的旧私钥。
  rm -f "$SSH_DIR"/id_*
  for key in "$SSH_CREDENTIALS_DIR"/id_*; do
    if [ ! -f "$key" ]; then
      continue
    fi
    name=$(basename "$key")
    case "$name" in
      *.pub)
        install -m 0644 "$key" "$SSH_DIR/$name"
        ;;
      *)
        install -m 0600 "$key" "$SSH_DIR/$name"
        private_keys=$((private_keys + 1))
        ;;
    esac
  done
  if [ "$private_keys" -eq 0 ]; then
    echo "ERROR: $SSH_CREDENTIALS_DIR must contain at least one private id_* key." >&2
    exit 1
  fi
  step "Installed $private_keys private SSH key(s) into $SSH_DIR"
}

assert_expected_git_branch() {
  local current_branch
  current_branch=$(git -C "$REPOSITORY_DIR" symbolic-ref --quiet --short HEAD || true)
  if [ "$current_branch" != "$GIT_BRANCH" ]; then
    echo "ERROR: $REPOSITORY_DIR must stay on branch $GIT_BRANCH; found ${current_branch:-detached HEAD}." >&2
    echo "       Switch it explicitly before restarting; the entrypoint never checks out or resets existing work." >&2
    exit 1
  fi
}

# 持久工作区已经被初始化过就不再触碰：只按已存在的目录内容继续构建，不做 checkout/reset。
workspace_is_repository() {
  [ -f "$REPOSITORY_DIR/pom.xml" ] && [ -f "$REPOSITORY_DIR/scripts/dev.sh" ]
}

prepare_workspace() {
  if [ ! -d "$WORKSPACE_ROOT" ] || [ ! -w "$WORKSPACE_ROOT" ]; then
    echo "ERROR: $WORKSPACE_ROOT must exist and be writable by uid $(id -u)." >&2
    echo "       Mount the persistent workspace volume with owner 10001:10001." >&2
    exit 1
  fi
  if [ -d "$REPOSITORY_DIR/.git" ]; then
    step "Reusing the persistent git workspace $REPOSITORY_DIR"
    assert_expected_git_branch
    git -C "$REPOSITORY_DIR" rev-parse --short HEAD
    return
  fi
  if [ -d "$REPOSITORY_DIR" ] && [ -n "$(ls -A "$REPOSITORY_DIR")" ]; then
    if workspace_is_repository; then
      step "Reusing the persistent workspace $REPOSITORY_DIR (initialized from the source snapshot)"
      return
    fi
    echo "ERROR: $REPOSITORY_DIR is not a kk-studio checkout and is not empty; not overwriting it." >&2
    echo "       Move the directory aside to let this entrypoint initialize the workspace." >&2
    exit 1
  fi
  if [ -n "$GIT_REMOTE_URL" ]; then
    # 固定的 SSH remote 配合已注入的 key 完成认证，`.git/config` 保持无凭据。
    step "Initializing $REPOSITORY_DIR from the configured remote on branch $GIT_BRANCH"
    git clone --branch "$GIT_BRANCH" "$GIT_REMOTE_URL" "$REPOSITORY_DIR"
    assert_expected_git_branch
    git -C "$REPOSITORY_DIR" rev-parse --short HEAD
    return
  fi
  if [ "$ALLOW_SOURCE_SEED" = "true" ]; then
    step "Initializing $REPOSITORY_DIR from the embedded source snapshot (no git remote configured)"
    mkdir -p "$REPOSITORY_DIR"
    cp -a "$SOURCE_SEED/." "$REPOSITORY_DIR/"
    return
  fi
  echo "ERROR: KK_STUDIO_GIT_REMOTE_URL is required to initialize $REPOSITORY_DIR." >&2
  echo "       Set KK_STUDIO_GIT_BRANCH for a branch other than $GIT_BRANCH, or set" >&2
  echo "       KK_STUDIO_DEV_ALLOW_SOURCE_SEED=true for an explicit non-git fallback." >&2
  exit 1
}

# 持久 checkout 的修订必须在启动进程之前被验证：把工作区无损快进到
# `origin/$GIT_BRANCH`，任何无法验证的修订（无 origin remote、fetch 失败）和任何无法
# 无损推进的历史（本地与远端互不包含）都直接让容器启动失败，节点不会静默运行落后或
# 未验证的代码。未 push 的本地提交是 Dev 节点上合法的 durable 工作：`HEAD` 包含
# 远端时按原样继续服务，entrypoint 不移动任何引用。
# 是否被快进覆盖交给 git 判定：`merge --ff-only` 会拒绝覆盖本地修改与未跟踪文件，
# 本函数不预判工作区状态，也不改写成 stash/reset。唯一允许的写操作是 `merge --ff-only`。
# 源码快照工作区（没有 `.git`）不参与同步。
sync_workspace_revision() {
  if [ ! -d "$REPOSITORY_DIR/.git" ]; then
    step "Skipping revision sync: $REPOSITORY_DIR is a source snapshot workspace without git metadata"
    return
  fi
  if ! git -C "$REPOSITORY_DIR" remote get-url origin >/dev/null 2>&1; then
    echo "ERROR: $REPOSITORY_DIR has no origin remote, so its revision cannot be verified." >&2
    echo "       Add the workspace remote explicitly, then restart the container." >&2
    exit 1
  fi
  # fetch 失败的诊断来自 git 自身，这里只补充容器视角的指引；远端 URL 与凭据都不由本
  # 脚本回显。
  if ! git -C "$REPOSITORY_DIR" fetch origin "$GIT_BRANCH"; then
    echo "ERROR: git fetch origin $GIT_BRANCH failed in $REPOSITORY_DIR." >&2
    echo "       Check the mounted SSH credentials and the network access to the remote," >&2
    echo "       then restart the container; an unverified revision is never started." >&2
    exit 1
  fi
  local head remote_head
  head=$(git -C "$REPOSITORY_DIR" rev-parse HEAD)
  remote_head=$(git -C "$REPOSITORY_DIR" rev-parse "origin/$GIT_BRANCH")
  if [ "$head" = "$remote_head" ]; then
    step "Workspace revision is at origin/$GIT_BRANCH (${head:0:7})"
    return
  fi
  # 本地领先：未 push 的提交是节点上合法的 durable 工作，按原样服务，不移动引用。
  if git -C "$REPOSITORY_DIR" merge-base --is-ancestor "$remote_head" "$head"; then
    step "Workspace is ahead of origin/$GIT_BRANCH (${head:0:7}); serving the local commits as is"
    return
  fi
  if ! git -C "$REPOSITORY_DIR" merge-base --is-ancestor "$head" "$remote_head"; then
    echo "ERROR: $REPOSITORY_DIR has diverged from origin/$GIT_BRANCH." >&2
    echo "       HEAD ${head:0:7} and origin/$GIT_BRANCH ${remote_head:0:7} contain commits the" >&2
    echo "       other does not, so the workspace cannot be fast-forwarded." >&2
    echo "       Reconcile that history explicitly, then restart the container." >&2
    exit 1
  fi
  # 落后：只允许快进，是否会被本地修改或未跟踪文件覆盖由 git 自己判定并拒绝。
  if ! git -C "$REPOSITORY_DIR" merge --ff-only "origin/$GIT_BRANCH"; then
    echo "ERROR: fast-forwarding $REPOSITORY_DIR to origin/$GIT_BRANCH failed." >&2
    echo "       Resolve the reported obstacle (local modifications or untracked files the" >&2
    echo "       incoming revision would overwrite) explicitly, then restart the container." >&2
    exit 1
  fi
  step "Fast-forwarded $REPOSITORY_DIR $head -> $(git -C "$REPOSITORY_DIR" rev-parse HEAD)"
}

start_managed_servers() {
  step "Starting backend and Vite via scripts/dev.sh (${SPRING_PROFILES_ACTIVE}, flyway=${SPRING_FLYWAY_ENABLED}, workers=${HARNESS_RUNTIME_WORKERS_ENABLED})"
  env \
    SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" \
    SPRING_FLYWAY_ENABLED="$SPRING_FLYWAY_ENABLED" \
    KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED="$HARNESS_RUNTIME_WORKERS_ENABLED" \
    BACKEND_HOST="$BACKEND_HOST" \
    BACKEND_PORT="$BACKEND_PORT" \
    FRONTEND_HOST="$FRONTEND_HOST" \
    FRONTEND_PORT="$FRONTEND_PORT" \
    DEV_WORK_DIR="$DEV_WORK_DIR" \
    DEV_READY_TIMEOUT_SECONDS="$DEV_READY_TIMEOUT_SECONDS" \
    "$REPOSITORY_DIR/scripts/dev.sh" start
}

run_daemon() {
  step "Starting Environment Daemon against $DAEMON_GATEWAY_URI with environment-root $WORKSPACE_ROOT"
  exec java -XX:MaxRAMPercentage=75.0 \
    -cp "$DAEMON_JAR:$DAEMON_LIB/*" \
    fun.fengwk.kkstudio.harness.daemon.DaemonMain \
    --gateway-uri "$DAEMON_GATEWAY_URI" \
    --registration-token "$registration_token" \
    --note "$DAEMON_NOTE" \
    --environment-root "$WORKSPACE_ROOT" \
    --data-dir "$DAEMON_DATA_DIR"
}

main() {
  require_cmd gh
  require_cmd git
  require_cmd java
  require_cmd mvn
  require_cmd npm
  require_cmd ssh

  validate_runtime_contract
  # 解析在校验之后、任何工作区准备与进程启动之前完成：非法 origin 直接失败。
  DAEMON_GATEWAY_URI=$(resolve_daemon_gateway_uri "$CONTROL_PLANE_BASE_URL")
  install_ssh_credentials
  prepare_workspace
  sync_workspace_revision
  start_managed_servers
  run_daemon
}

# 同一个文件既作为容器主进程执行，也被契约测试 `source` 后逐函数行为化验证；只有直接
# 执行时才运行启动序列。
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main
fi
