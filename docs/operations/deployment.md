# 部署与运行

本文覆盖从源码构建可运行产物、在容器里运行 App、接入外部 PostgreSQL 与 S3、生产反向代理，
以及本仓库提供的隔离栈和 NAS Main/Dev 外部部署边界。跨模块边界的整体模型见
[系统设计](../system-design.md)。

日常本地使用走 [deploy/local](../../deploy/local/README.md) 的 Compose 栈，隔离测试走
[deploy/test](../../deploy/test/README.md)；两者都有独立的一键入口，本文只说明它们在生产视角
下的定位，不重复其步骤。开发、质量检查和 NAS 自迭代流程见
[开发与测试](development-and-testing.md)。

## 构建可运行产物

一个 Fat JAR 同时包含后端和 React 静态资源，`app` 镜像与本地栈都用它。

```bash
env JAVA_HOME="$JAVA_HOME_21" \
  mvn -B -ntp -Pdistribution -pl web -am clean package
test -f web/target/kk-studio-web-1.0.0.jar
"$JAVA_HOME_21/bin/java" -jar web/target/kk-studio-web-1.0.0.jar
```

`distribution` profile 在 `prepare-package` 阶段由 [web/pom.xml](../../web/pom.xml) 的
`frontend-maven-plugin` 安装 Node `v24.14.0`、npm `11.9.0` 并执行 `npm ci` 与
`npm run build`，把 Vite 产物复制到 `web/target/classes/static`，再由
`spring-boot-maven-plugin` 写入 `BOOT-INF/classes/static`。普通 `mvn test` / `mvn package`
不激活该 profile。

验证静态资源已进入产物：

```bash
jar tf web/target/kk-studio-web-1.0.0.jar \
  | grep -E '^BOOT-INF/classes/static/(index.html|assets/)'
curl -fsS http://127.0.0.1:8080/actuator/health
```

`server.port` 默认 `8080`，gzip 压缩开启，Actuator 暴露 `health,prometheus,offline,online`。
SPA BrowserRouter 的刷新路径由 Web app fallback 到 `index.html`，因此不需要额外的
Nginx 或前端容器。

## 运行 App 容器

App 镜像由 [deploy/local/Dockerfile](../../deploy/local/Dockerfile) 构建，也是本地栈、测试
栈、性能基线和供应链扫描共用的 Dockerfile：

| 阶段 | 内容 |
| --- | --- |
| builder | `maven:3.9.11-eclipse-temurin-21`，BuildKit Maven/npm cache，`mvn -U -Pdistribution -pl web -am -DskipTests -B -ntp clean package` |
| runtime | `eclipse-temurin:21.0.8_9-jre-jammy`，安装 `ffmpeg`/`ffprobe`，清理 apt lists |
| user | `kkstudio:kkstudio`，uid/gid `10001`，`USER kkstudio` |
| process | `java -jar /app/app.jar`，`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` |
| health | `curl -fsS http://127.0.0.1:8080/actuator/health`，15s interval、5s timeout、60s start period、6 retries |

builder 接受 `KK_STUDIO_BUILD_HTTP_PROXY`、`KK_STUDIO_BUILD_HTTPS_PROXY`、
`KK_STUDIO_BUILD_NO_PROXY` 和 `KK_STUDIO_MAVEN_BUILD_OPTS`；这些值只用于 build，不复制进
runtime image。runtime image 不含 Maven、Node 和源码。

App 依赖 PostgreSQL、MinIO 与 `minio-init` healthy 后才启动，启动期严格验证 S3 连接属性并
探测 bucket 可访问性。Storage 与 Canvas 是常驻服务，没有 disabled 503 状态。用生产 profile
运行时必须提供外部 durable 服务：

```bash
docker build -f deploy/local/Dockerfile -t kk-studio-app:production .
docker run -d --name kk-studio-app -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e KK_STUDIO_DB_URL=jdbc:postgresql://<pg-host>:5432/<database> \
  -e KK_STUDIO_DB_USER=<user> -e KK_STUDIO_DB_PASSWORD=<password> \
  -e KK_STUDIO_STORAGE_S3_ENDPOINT=http://<s3-host>:9000 \
  -e KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT=https://<browser-reachable-s3-origin> \
  -e KK_STUDIO_STORAGE_S3_REGION=<region> \
  -e KK_STUDIO_STORAGE_S3_BUCKET=<bucket> \
  -e KK_STUDIO_STORAGE_S3_ACCESS_KEY=<access-key> \
  -e KK_STUDIO_STORAGE_S3_SECRET_KEY=<secret-key> \
  kk-studio-app:production
curl -fsS http://127.0.0.1:8080/actuator/health
```

- `KK_STUDIO_STORAGE_S3_ENDPOINT` 是服务端读写地址；`KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT` 是
  返回给浏览器的预签名直传/直下地址，必须是浏览器可访问的 origin，服务端 `PUT`/`GET`/
  `HEAD`/`COPY`/`DELETE` 始终使用前者。
- `prod` profile 不做 `dev`/`e2e` seed；空库的 schema 由 Flyway 的
  `V1__schema.sql` 建立，migration 只由唯一 Flyway owner 执行。
- `prod` profile 的数据源只来自 `KK_STUDIO_DB_URL`/`KK_STUDIO_DB_USER`/`KK_STUDIO_DB_PASSWORD`，
  没有默认值，缺失时启动失败而不是回退到开发数据库；`SPRING_PROFILES_ACTIVE` 与全部
  `KK_STUDIO_STORAGE_S3_*` 同样必须显式提供。

## 生产部署与反向代理

Studio 当前没有内置登录鉴权。向局域网或公网暴露前，必须在外部入口配置 TLS 和访问控制；
容器本身保持 `127.0.0.1` 或内部网络绑定，由反向代理承担对外职责。

反向代理需要覆盖三类流量：

| 流量 | 端点 | 要求 |
| --- | --- | --- |
| UI 与 REST API | 全部路径，含 SPA 深层路径 | 透传到 App `8080`；App 自行返回 `index.html` fallback |
| Environment Daemon gateway | `/api/harness/environment-daemon/v1` | 透传 WebSocket，并协商 `permessage-deflate`；未协商时 Daemon 以 RFC 6455 close code `1010` 断开，不会退化为未压缩会话 |
| 浏览器 Application Event WebSocket | `/api/events/v1` | 透传 WebSocket；断线后客户端靠 REST snapshot 重新同步 |

`prod` profile 启用 `server.forward-headers-strategy=framework`，因此代理必须正确设置
`X-Forwarded-Proto`/`X-Forwarded-Host`，否则重定向与 cookies 会指向内部地址。

预签名 URL 与 WebSocket 是部署时最容易出错的两处：前者要求
`KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT` 使用浏览器可达的 origin，后者要求每个终止 WebSocket
的代理层都启用压缩扩展。Daemon 侧安装与故障处理见
[Environment Daemon 安装与运行](environment-daemon.md)。

## 隔离栈与可靠性栈

以下栈都使用各自的 Compose project name、network 和 named volume，清理命令只影响本 project。
它们使用固定的 disposable 凭据，只用于本机验证，不代表生产配置。

### `deploy/test`：Canvas/Storage 离线栈

`kk-studio-canvas-test` project 提供 PostgreSQL、MinIO、HTTP mock 与可选的 App，用于 Canvas
Resource、fake Function、OpenCLI fake Hub adapter 和离线 Chat smoke：

```bash
./deploy/test/run.sh
./deploy/test/run.sh --with-app
```

完整步骤、mock routes 与真实 Seedance prepare-only 边界见
[deploy/test/README.md](../../deploy/test/README.md)。

### `deploy/distributed`：双节点零 App-to-App 网络栈

`kk-studio-distributed` project 是免费 mock 拓扑：App 镜像复用 `deploy/local/Dockerfile`，
Daemon 镜像复用 [deploy/reliability/daemon.Dockerfile](../../deploy/reliability/daemon.Dockerfile)，
HTTP mock 直接挂载 `deploy/test/mock`。

```bash
./deploy/distributed/run.sh up [--skip-build]
./deploy/distributed/run.sh status
./deploy/distributed/run.sh verify       # 静态拓扑校验，不启动容器
./deploy/distributed/run.sh down --volumes
```

`node-a-db`、`node-b-db`、`daemon-a`、`daemon-b` 都是 `internal: true`，没有任何网络同时包含
App-A 与 App-B；宿主只经 `app-ingress-a`/`app-ingress-b` 发布 loopback 端口（默认 app-a
`18082`、app-b `18083`，PostgreSQL `15433`、MinIO `19001`、mock `18090`）。两个 App 共享同一
PostgreSQL database 与 MinIO bucket，节点身份用可覆盖的 disposable 变量表达：

```text
DISTRIBUTED_ENV_A_NAME=distributed-a
DISTRIBUTED_ENV_B_NAME=distributed-b
DISTRIBUTED_DAEMON_A_REGISTRATION_TOKEN=e2e-token-dist-a
DISTRIBUTED_DAEMON_B_REGISTRATION_TOKEN=e2e-token-dist-b
DISTRIBUTED_APP_A_PORT=18082
DISTRIBUTED_APP_B_PORT=18083
```

`run.sh disconnect-db-a` / `reconnect-db-a` 是按容器与网络精确操作的幂等故障注入，不会影响
`daemon-a` 网络与 daemon workspace volume。`./scripts/e2e.sh --distributed` 通过该入口启停栈
并在退出时清理。

### `deploy/reliability`：App + Environment Daemon

`kk-studio-reliability` project 运行 `e2e` profile 的 App 与容器内 Daemon，用于 Environment
工具隔离、S3 和显式可靠性矩阵。`reliability` network 是 `internal: true`，App 另加入
`app-ingress` 发布 loopback API（默认 `RELIABILITY_APP_PORT=18091`，MinIO
`RELIABILITY_MINIO_PORT=19002`）。

```bash
./scripts/reliability/stack.sh up
./scripts/reliability/stack.sh status
./scripts/reliability/stack.sh inspect
./scripts/reliability/stack.sh logs postgres app workspace-init daemon
./scripts/reliability/stack.sh down --volumes
```

Daemon 不发布宿主端口，只经 `ws://app:8080/api/harness/environment-daemon/v1` 连接 App。

```text
--gateway-uri ws://app:8080/api/harness/environment-daemon/v1
--note "Isolated Docker reliability environment."
--environment-root /workspace
--data-dir /workspace/.kkstudio/daemon
```

注册凭证不经 argv 传递：Compose 只向容器注入 `KK_STUDIO_DAEMON_REGISTRATION_TOKEN`，
[daemon-entrypoint.sh](../../deploy/reliability/daemon-entrypoint.sh) 把它写成
`/home/kkdaemon/.kkstudio/daemon-registration.token`（目录 0700、文件 0600）并从环境中 `unset`，
再用 `--registration-token-file <绝对路径>` 传入。凭证不出现在 `ps` 可见的 argv，也不留在
`/proc/<pid>/environ`。

[daemon.Dockerfile](../../deploy/reliability/daemon.Dockerfile) 以 JDK 21 builder 构建
`harness/daemon` 单文件 shaded JAR，runtime 使用 `eclipse-temurin:21.0.8_9-jdk-jammy`，预装
Node `22.19.0`/npm `11.19.0`、bash、git，创建 `kkdaemon` uid/gid `10001`，入口先物化凭证再
`exec java -jar /opt/kk-studio/daemon.jar`。Daemon 只挂载一个 `/workspace` named volume，
`workspace-init` 完成 owner 初始化（`chown 10001:10001`）后 Daemon 才启动。

`up` 等待 PostgreSQL health、App `/actuator/health` 和公共 `GET /api/harness/environments` 的
`status=READY`。`inspect` fail closed 检查：

- Daemon uid 不是 root；
- mount 只有 `volume -> /workspace` 且可写、没有 bind mount；
- JDK/`javap`/Node/npm/git/bash 可执行，`rg`/`fd` 不存在；
- workspace 可写、Environment `READY`；
- Compose config 不含 provider credential。

向 Daemon named volume 写入跨仓基线是独立的显式操作，必须给出 clean Git worktree：

```bash
PI_ANCHOR=/path/to/pi \
PI_BASE_ANCHOR=/path/to/pi-base \
  ./scripts/reliability/stack.sh snapshot
```

可靠性与 Agent 矩阵的运行入口见[开发与测试](development-and-testing.md)。

## 运行配置与凭据

| Stack/用途 | 责任组 | 典型变量 |
| --- | --- | --- |
| local | host mapping/database/S3/profile | `KK_STUDIO_APP_*`、`KK_STUDIO_PG_*`、`KK_STUDIO_S3_*`、`KK_STUDIO_STORAGE_S3_*`、`KK_STUDIO_SPRING_PROFILES_ACTIVE` |
| local | Harness dispatcher | `KK_STUDIO_HARNESS_DISPATCHER_*` |
| local/reliability | admission/gateway | `KK_STUDIO_MODEL_MAX_CONCURRENCY`、`KK_STUDIO_TOOL_MAX_CONCURRENCY`、`KK_STUDIO_SUBAGENT_MAX_CONCURRENCY`、`KK_STUDIO_ENVIRONMENT_GATEWAY_*` |
| test | ports/build/mock | `CANVAS_TEST_*` |
| test | S3/media/fake runtime | `KK_STUDIO_STORAGE_S3_*`、`KK_STUDIO_CANVAS_RESOURCE_*`、`KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED` |
| distributed | node identity/ports | `DISTRIBUTED_*` |
| reliability | stack identity | `RELIABILITY_APP_PORT`、`RELIABILITY_MINIO_PORT`、`RELIABILITY_ENV_NAME`、`RELIABILITY_REGISTRATION_TOKEN` |
| supply-chain | reports/images/cache | `SUPPLY_CHAIN_REPORT_ROOT`、`SUPPLY_CHAIN_APP_IMAGE`、`SUPPLY_CHAIN_DAEMON_IMAGE`、`SUPPLY_CHAIN_TRIVY_CACHE_VOLUME`、`TRIVY_SKIP_DB_UPDATE` |
| 显式 `--real` E2E | 仅宿主 credential 同步 | `TEST_GOOGLE_*`、`TEST_OPENAI_*`、`TEST_ANTHROPIC_*`、`TEST_DEEPSEEK_*` |
| reliability Agent 矩阵 | 仅宿主 credential 同步 | `TEST_MINIMAX_BASE_URL`、`TEST_MINIMAX_API_KEY` |
| 显式 Seedance prepare-only | 外部 Hub/workspace | `OPENCLI_HUB_BASE_URL`、`SEEDANCE_WORKSPACE_ID`、可选 `OPENCLI_HUB_INSTANCE_ID` |

Dispatcher、Admission 和 gateway frame/queue 上限是启动配置，不由 SystemSettings editor 修改。
`KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY`、Canvas Function runtime 变量和
`KK_STUDIO_CANVAS_H3_COMFY_BEARER_TOKEN` 也只在启动期读取。

凭据边界：

- [.dockerignore](../../.dockerignore) 与 [.gitignore](../../.gitignore) 排除 `.env`、key/cert/
  credential 文件、`credentials*`、service account JSON 和 `secrets/`。
- local/test/reliability/distributed 的固定 PostgreSQL 与 MinIO 凭据只属于 disposable compose；
  宿主绑定地址一旦改为非 loopback，就必须显式覆盖这些默认值。
- `scripts/e2e.sh` 只在显式 `--real` 时读取四组完整 credential pair，并经 HTTP 写入各自专用
  database；credential 不进入 Compose environment、Dockerfile、image layer、backend/Daemon
  command 或报告。reliability 栈独立使用 `TEST_MINIMAX_BASE_URL`/`TEST_MINIMAX_API_KEY`。
- 各测试栈的 registration token 是栈内隔离配置；`NVD_API_KEY` 只由供应链脚本写入临时
  mode `600` 的 Maven settings，二者都不进 command line、POM、image 或报告。

Proxy 只作用于 build、npm/Maven dependency fetch 或显式 Trivy network：App image build 支持
`KK_STUDIO_BUILD_HTTP_PROXY`、`KK_STUDIO_BUILD_HTTPS_PROXY`、`KK_STUDIO_BUILD_NO_PROXY` 和
`KK_STUDIO_MAVEN_BUILD_OPTS`；[deploy/test/run.sh](../../deploy/test/run.sh) 与
[scripts/performance.sh](../../scripts/performance.sh) 会从宿主 proxy 变量生成 build 参数，
loopback proxy 使用 host build network。proxy 不进入 App/Daemon runtime image，也不作为运行时
业务配置。

## 清理

```bash
# local：保留或删除 PostgreSQL 与 MinIO 数据
docker compose -f deploy/local/compose.yaml down
docker compose -f deploy/local/compose.yaml down -v

# test：删除容器、网络和 PostgreSQL/MinIO volumes
docker compose -f deploy/test/compose.yaml --profile app down -v --remove-orphans

# distributed：删除双节点栈的容器、网络和 PostgreSQL/MinIO/daemon workspace volumes
./deploy/distributed/run.sh down --volumes

# reliability：保留或删除 PostgreSQL/MinIO/workspace named volumes
./scripts/reliability/stack.sh down
./scripts/reliability/stack.sh down --volumes

# 确认宿主端口
ss -ltnp | grep -E ':8080|:5432|:9000|:15432|:15433|:18082|:18083|:18088|:18089|:18090|:19000|:19001|:19002|:18091' || true
```

`down` 保留 named volume，`down -v` / `--volumes` 删除它并让下次启动重新执行 Flyway 与
bucket 初始化。清理命令只作用于对应 Compose project，不影响其它 project 的容器、network 或
volume。任何 app、database、MinIO、mock、Daemon `READY` 或 smoke 失败都应保留诊断并进入失败
路径，而不是把未验证的服务交给上层脚本。

## NAS Main/Dev 外部部署边界

NAS 自迭代拓扑横跨三个职责边界，本仓库只提供前者的产物与脚本：

| 边界 | 职责 |
| --- | --- |
| 本仓库 | Main Fat JAR image、Dev toolchain/source image、运行 profile、Daemon 与自迭代脚本 |
| NAS Compose 仓库 | `vps-kk-studio`、`vps-kk-studio-dev`、共享 PostgreSQL/S3 连接、持久 workspace/cache/`gh` 配置、SSH key 只读挂载、私密环境变量注入 |
| Gateway 仓库 | `studio.kk1.fun`、`studio-dev.kk1.fun` 的 HTTP/WebSocket 路由和访问控制 |

Main runtime image 不含源码、Maven、Node 或 credential，是唯一 Flyway owner 与唯一 Harness
worker。Dev 节点镜像由 [deploy/dev/Dockerfile](../../deploy/dev/Dockerfile) 构建：Maven
3.9.11/JDK 21 与 Node 24.14.0/npm 11.9.0 工具链、`git`/`curl`/`jq`/`lsof`/`python3`/`ffmpeg`/
`ffprobe`、`openssh-client` 与 GitHub CLI `2.100.0`，并以同一源码构建的 Environment Daemon
作为容器主进程。容器以 uid/gid `10001` 运行，工作区、cache 和配置必须由外部 Compose 以
`10001:10001` 属主挂载：

```text
/workspace                     # Daemon environment-root 与 Git checkout 根
/workspace/kk-studio           # 实际源码 checkout
/home/kkdaemon/.m2             # Maven local repository
/home/kkdaemon/.npm            # npm cache
/home/kkdaemon/.config/gh      # gh auth（gh hosts.yml）
/var/kk-studio/dev             # backend/frontend log 与 PID
```

`/workspace`、`/home/kkdaemon/.m2`、`/home/kkdaemon/.npm` 与 `/home/kkdaemon/.config/gh` 是必须
持久化的 volume；entrypoint 启动时校验 `/workspace` 可写性，属主错误直接失败，不会退化成不可写
容器。SSH 私钥由外部以只读 volume 挂载到 `/run/kk-studio/ssh`
（`KK_STUDIO_SSH_CREDENTIALS_DIR`），entrypoint 启动时才把 `id_*` 复制进 `/home/kkdaemon/.ssh`；
github.com 的 host key 固化在镜像内的 `/etc/ssh/ssh_known_hosts`，镜像级 SSH 配置使用
`BatchMode`、`IdentitiesOnly`、严格 host key 校验和 `ConnectTimeout 10`，认证异常直接失败而不是
挂起等待输入。`gh` 使用默认配置目录 `/home/kkdaemon/.config/gh`，首次登录步骤见
[开发与测试](development-and-testing.md)。

Dev Backend 关闭 Flyway 与进程内 Harness dispatcher（`SPRING_FLYWAY_ENABLED=false`、
`KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false`），Harness Thread/Model/Tool Work 只由 Main
执行；这两个开关在镜像与 entrypoint 中都是 fail-closed 默认值。Dev Daemon 经内部 Docker 网络
连接 Main 的 origin，而不是本容器的 Backend：Main 的 HTTP(S) origin 统一保存在外部 Compose
项目的 `.env`，`docker-compose.yml` 只把同名变量显式传入 Dev，entrypoint 再派生出 WebSocket
地址：

```dotenv
# .env
KK_STUDIO_CONTROL_PLANE_BASE_URL=http://vps-kk-studio:8080
```

```yaml
# docker-compose.yml
environment:
  KK_STUDIO_CONTROL_PLANE_BASE_URL: ${KK_STUDIO_CONTROL_PLANE_BASE_URL}
```

origin 必须是使用 DNS/IPv4 host 与可选端口的裸 HTTP(S) origin，可选一个结尾 `/`；`https`
派生 `wss`。不得填写公共 Gateway 域名、`ws`/`wss` scheme、路径、query、fragment、userinfo
或非法端口，非法值在启动服务之前就让容器失败且不回显输入值。上面的值最终派生为
`ws://vps-kk-studio:8080/api/harness/environment-daemon/v1`。Dev 的环境变量、Dev 关闭
worker 的开关与 `gh` 登录契约见[开发与测试](development-and-testing.md)。

每次启动 entrypoint 都把持久工作区无损快进到 `origin/$KK_STUDIO_GIT_BRANCH`，只允许
`merge --ff-only`，永不 reset/rebase/stash/checkout：

- 远端不可达（fetch 失败）、工作区没有 `origin`、快进会被本地修改或未跟踪文件覆盖（git 拒绝）、
  或本地历史与远端互不包含时，容器直接启动失败，节点不会静默运行未验证的修订；
- 存在未 push 的本地提交（本地领先）时按原样启动并提示 ahead；
- 同步后按修订 stamp 决定重建量：后端 JAR 记录在 `web/target/.kk-studio-revision`，前端依赖
  记录在 `frontend` 下的 `node_modules/.kk-studio-package-lock.sha`。

冷 cache 首次启动要完整构建 Backend 并安装前端依赖，实测约 29 分钟（20 核 x86_64），因此镜像
healthcheck 的 start period 取 `--start-period=2700s`，覆盖冷启动并留约 50% 余量。真正的引导
失败不会被掩盖：entrypoint 的 readiness 预算是 `DEV_READY_TIMEOUT_SECONDS`（容器内默认
`600s`），超时直接以非零状态退出。外部 Compose 应继承镜像 healthcheck，不要用更短的
`start_period` 覆盖这条边界。

`.github/workflows/docker-publish.yml` 在推送 `main` 时构建并发布 `<namespace>/kk-studio:main`，
在推送 `dev` 时构建并发布 `<namespace>/kk-studio-dev:dev`，两者都附带 immutable commit SHA
tag、`linux/amd64` 平台和 Buildx GHA cache；Docker Hub 凭据只来自 Actions secrets，不作为
build arg 或 image layer。

外部 Compose 和 Gateway 配置只引用环境变量名。真实 database、S3、Provider、Gateway、
registration credential 和 SSH 私钥不进入本仓库、Docker build context、image layer、日志或
报告；registration token 经 owner-only 凭证文件传递（`--registration-token-file`），不出现在
Daemon argv 或环境变量中。Main 与 Dev 共享逻辑 database 和 bucket，但只有 Main 执行 Flyway 与
Harness 异步工作：Dev 关闭这两者，Harness 层只提供 control/query preview。因此 Dev 中改动
processor/runtime 等异步执行路径必须依靠自动化测试或显式隔离环境验证，不能以 Dev preview 的
行为作为验收依据。Agent 停止边界、共享数据库重建等自迭代约束见
[开发与测试](development-and-testing.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[开发与测试](development-and-testing.md)、
[Environment Daemon 安装与运行](environment-daemon.md)、
[本地一键启动栈](../../deploy/local/README.md)、[Canvas/Storage 隔离测试栈](../../deploy/test/README.md)。
