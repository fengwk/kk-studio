# 部署与运行

部署需要一个内嵌前端的 App、PostgreSQL 数据库和 S3 bucket。首次安装先准备数据面与运行配置，
再启动唯一 Flyway owner；日常升级复用数据面，恢复与重建由数据所有者单独批准。
本文给出构建、运行、升级与维护路径，以及隔离栈和 NAS 拓扑。跨模块边界的整体模型见
[系统设计](../system-design.md)。

日常本地使用走 [deploy/local](../../deploy/local/README.md) 的 Compose 栈，隔离测试走
[deploy/test](../../deploy/test/README.md)；两者都有独立的一键入口，本文只说明它们在生产视角
下的定位，不重复其步骤。开发、质量检查和 NAS 自迭代流程见
[开发与测试](development-and-testing.md)。

local/test/reliability/distributed 共用的 MinIO 镜像、平台限制与 mc 构建事实统一见
[开发栈 MinIO 依赖](../../deploy/dependencies/minio-client/README.md)，这些依赖只用于开发和隔离验证。

## 首次安装

1. 准备 PostgreSQL 与可访问的私有 S3 bucket，确认数据库角色、对象存储凭据和备份策略。
2. 构建下面的 Fat JAR 或 App 镜像，配置 `prod` 数据源、S3 服务端地址与浏览器公开地址。
3. 指定唯一 Flyway owner 初始化空库。共享数据面的其它 App 关闭 Flyway；
   启用 Worker 的节点使用相同产物与 Plugin 集合。
4. 检查 `/actuator/health`、UI、预签名上传/下载与关键业务操作。
   需要宿主工具时再安装 Daemon，并在 Studio 确认 Environment `READY`。
5. 对外开放前完成 TLS、外部认证与 WebSocket 代理配置，见[生产部署与反向代理](#生产部署与反向代理)。

### 构建可运行产物

一个 Fat JAR 同时包含后端和 React 静态资源，`app` 镜像与本地栈都用它。

```bash
env JAVA_HOME="$JAVA_HOME_21" \
  mvn -B -ntp -Pdistribution -pl web -am clean package
test -f web/target/kk-studio-web-1.0.9.jar
sh scripts/dev/lib/extract-convention4j-agent.sh "$JAVA_HOME_21/bin/jar" \
  web/target/kk-studio-web-1.0.9.jar \
  web/target/convention4j-agent/convention4j-agent.jar
"$JAVA_HOME_21/bin/java" \
  -javaagent:web/target/convention4j-agent/convention4j-agent.jar \
  -jar web/target/kk-studio-web-1.0.9.jar
```

`distribution` profile 在 `prepare-package` 阶段由 [web/pom.xml](../../web/pom.xml) 的
`frontend-maven-plugin` 安装 Node `v24.14.0`、npm `11.9.0` 并执行 `npm ci` 与
`npm run build`，把 Vite 产物复制到 `web/target/classes/static`，再由
`spring-boot-maven-plugin` 写入 `BOOT-INF/classes/static`。普通 `mvn test` / `mvn package`
不激活该 profile。

`clean package` 会重建 reactor 的构建产物；提取脚本写入指定输出目录、更新稳定别名，
并删除同目录中其他版本的 `convention4j-agent-*.jar`，不要将输出目录指向需要保留多个版本的安装目录。
运行 JAR 会连接配置的数据面并按 profile 初始化，先确认数据库、S3、Flyway owner 与 worker 的归属。

可选 Plugin 是构建期依赖，不从运行目录动态发现。把对应 artifact 以 runtime scope 加入
[`web/pom.xml`](../../web/pom.xml) 后，上面的 `-pl web -am` 会构建并把它写入 Fat JAR；
移除 dependency 并重新构建后，该 Plugin 的管理端口、调度任务和模型工具都不存在。
顶层 `plugins` aggregator 只定义可构建模块，不决定发行物包含哪些 Plugin。
共享数据库且启用 Worker 的 App 节点必须运行同一 Fat JAR 和 Plugin 集合；改变 Plugin
依赖时先停止旧 Worker 领取新 Work、排空在途调用，再整体切换，不能让不同 Tool catalog
长期混跑。

验证静态资源已进入产物：

```bash
"$JAVA_HOME_21/bin/jar" tf web/target/kk-studio-web-1.0.9.jar \
  | grep -E '^BOOT-INF/classes/static/(index.html|assets/)'
curl -fsS http://127.0.0.1:8080/actuator/health
```

`server.port` 默认 `8080`，gzip 压缩开启，Actuator 暴露 `health,prometheus,offline,online`。
SPA BrowserRouter 的刷新路径由 Web app fallback 到 `index.html`，因此不需要额外的
Nginx 或前端容器。

### 运行 App 容器

App 镜像由 [deploy/local/Dockerfile](../../deploy/local/Dockerfile) 构建，也是本地栈、测试
栈、性能基线和供应链扫描共用的 Dockerfile：

| 阶段 | 内容 |
| --- | --- |
| builder | `maven:3.9.11-eclipse-temurin-21`，BuildKit Maven/npm cache，`mvn -U -Pdistribution -pl web -am -DskipTests -B -ntp clean package` |
| runtime | `eclipse-temurin:21.0.8_9-jre-jammy`，安装 `ffmpeg`/`ffprobe`，清理 apt lists |
| user | `kkstudio:kkstudio`，uid/gid `10001`，`USER kkstudio` |
| process | `java -javaagent:/app/convention4j-agent/convention4j-agent.jar -jar /app/app.jar`，`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` |
| health | `curl -fsS http://127.0.0.1:8080/actuator/health`，15s interval、5s timeout、60s start period、6 retries |

builder 接受 `KK_STUDIO_BUILD_HTTP_PROXY`、`KK_STUDIO_BUILD_HTTPS_PROXY`、
`KK_STUDIO_BUILD_NO_PROXY` 和 `KK_STUDIO_MAVEN_BUILD_OPTS`；这些值只用于 build，不复制进
runtime image。构建时从 Fat JAR 提取同版本 `convention4j-agent`，保留 manifest
`Boot-Class-Path` 要求的版本化文件名并通过稳定别名启动 JVM；它负责线程池中的 TTL/MDC
上下文透传，不替代跨服务的 Trace 传播。runtime image 不含 Maven、Node 和源码。

Compose 栈中的 App 依赖 PostgreSQL 与 MinIO `healthy`，并等待一次性的 `minio-init` 成功退出
（`service_completed_successfully`）后才启动；启动期严格验证 S3 连接属性并探测 bucket 可访问性。
`minio-init` 幂等创建私有 bucket，不清空已有对象；完成后以 0 退出，没有 `healthcheck`，因此它不能作为 `service_healthy`
的目标。Storage 与 Canvas 是常驻服务，没有 disabled 503 状态。用生产 profile
运行时必须提供外部 durable 服务：

```bash
docker build -f deploy/local/Dockerfile -t kk-studio-app:production .
docker run -d --name kk-studio-app -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e 'KK_STUDIO_DB_URL=jdbc:postgresql://<pg-host>:5432/<database>' \
  -e 'KK_STUDIO_DB_USER=<user>' -e 'KK_STUDIO_DB_PASSWORD=<password>' \
  -e 'KK_STUDIO_STORAGE_S3_ENDPOINT=http://<s3-host>:9000' \
  -e 'KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT=https://<browser-reachable-s3-origin>' \
  -e 'KK_STUDIO_STORAGE_S3_REGION=<region>' \
  -e 'KK_STUDIO_STORAGE_S3_BUCKET=<bucket>' \
  -e 'KK_STUDIO_STORAGE_S3_ACCESS_KEY=<access-key>' \
  -e 'KK_STUDIO_STORAGE_S3_SECRET_KEY=<secret-key>' \
  kk-studio-app:production
curl -fsS http://127.0.0.1:8080/actuator/health
```

上面只展示键名与占位值，不可原样运行；真实部署用 owner-only 环境文件或编排 secret 注入，
不要把真实密码直接写进 shell 历史或可见的命令参数。独立 `docker run` 没有 Compose 的等待链，
PostgreSQL 与已配置 bucket 必须先可达，启动失败先诊断连接而不是重建数据面。

- `KK_STUDIO_STORAGE_S3_ENDPOINT` 是服务端读写地址；`KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT` 是
  返回给浏览器的预签名直传/直下地址，必须是浏览器可访问的 origin，服务端 `PUT`/`GET`/
  `HEAD`/`COPY`/`DELETE` 始终使用前者。
- `prod` profile 不做 `dev`/`e2e` seed；空库的 schema 由 Flyway 的
  [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 建立，migration
  只由唯一 Flyway owner 执行。
- `prod` profile 的数据源只来自 `KK_STUDIO_DB_URL`/`KK_STUDIO_DB_USER`/`KK_STUDIO_DB_PASSWORD`，
  没有默认值，缺失时启动失败而不是回退到开发数据库；`SPRING_PROFILES_ACTIVE` 与全部
  `KK_STUDIO_STORAGE_S3_*` 同样必须显式提供。

## 运行配置

生产 App 使用 `SPRING_PROFILES_ACTIVE=prod`，通过 `KK_STUDIO_DB_*` 连接 PostgreSQL，
通过 `KK_STUDIO_STORAGE_S3_*` 连接对象存储。服务端 endpoint 用于读写对象，public endpoint
用于浏览器预签名上传/下载；两者都须可达，bucket 在 App 启动前准备好。
空库初始化只由一个 Flyway owner 执行，Worker、Plugin 集合与密钥在节点间保持一致。

真实连接与凭据由仓库外 owner-only 文件或编排 secret 注入。使用已保存的 Plugin 凭据时，
所有 App 挂载同一份原始 32-byte 主密钥；替换为空文件或新密钥会使已有凭据无法读取。
Dispatcher、admission、gateway 上限属于启动配置，subagent 预算由运行时 SystemSettings 管理。
完整变量分类与凭据文件要求见[运行配置与凭据](#运行配置与凭据)，
对外入口要求见[生产部署与反向代理](#生产部署与反向代理)。

## 升级

部署前核对目标镜像与数据库 schema、持久 JSON、Work wire 和 Plugin 集合的兼容性，
保存当前镜像标识、运行配置、Plugin 主密钥与可恢复的数据备份。镜像构建成功只证明产物生成，
发布前的验证入口见[开发与测试](development-and-testing.md)。

兼容的数据面继续复用。停止 Worker 领取新 Work，排空在途调用，在批准的窗口替换 App 镜像；
由唯一 Flyway owner 完成 schema 校验/初始化，再恢复其它节点。检查健康、关键业务操作和
Daemon 重连后才结束窗口。改变 Tool catalog 时整体切换 Worker，避免不同 Plugin 集合混跑。

Flyway 校验失败时停止切换并查明差异；修改 `flyway_schema_history` 或用 `repair` 伪造 checksum
会破坏校验依据。有数据的数据库不能重放 V1。需要空库时走独立的[共享数据库重建](development-and-testing.md#共享数据库重建)，
它通过产品配置导出、数据库备份与重建、当前 Schema 初始化和产品导入恢复配置，不是自动迁移。

## 恢复与重建

恢复前停止所有 App/Worker、preview 写入和 Daemon，等待在途操作收敛。
先验证备份摘要、可恢复性、权限、S3 对象保留范围以及匹配的镜像与 Plugin 主密钥。
数据库与对象存储的备份应能恢复同一业务状态；仅恢复数据库不能补回已删除的对象。

已执行数据库重建时，原库以 `<db>_pre_<UTCstamp>` 冻结保留，全库 dump 另存仓库之外。
在停机窗口保留并移开当前库、让原库名空闲，再由数据库管理员恢复冻结库的连接权限与原名，
配合对应镜像、配置、主密钥和 S3 数据恢复入口。也可在隔离目标用经过验证的全库 dump 恢复并验收后切换。
新库中的写入不会自动合并回冻结库，切换前由数据所有者决定其保护方式。

若目标是建立新的空数据面，按[共享数据库重建](development-and-testing.md#共享数据库重建)
先在“设置 → 同步”导出配置，执行 reset 的 `--dry-run`，确认数据范围、备份与恢复方案，
再批准停机和实际执行。初始化当前 Schema 后通过产品导入 YAML，恢复七类配置及所需凭据。
会话、项目、画布、运行历史与 Blob 引用保留在冻结库/备份中，新的入口无法直接访问它们。
Environment 注册令牌可恢复；Daemon 重连仍须验收，Plugin 认证和部署级配置需另行准备。
reset 不删除 S3 对象；这些对象仍受备份恢复策略保护，不能凭新库无引用就清理。

验收完成且确认备份可恢复后，可用 `reset-database.sh --cleanup-snapshots --dry-run`
预览，再用 `--cleanup-snapshots` 确认删除目标库的全部冻结快照。默认重建不自动清理；
清理包括最新快照，删除后无法直接切回旧库。连接与范围见[清理冻结快照](development-and-testing.md#清理冻结快照)。

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
它们使用固定的 disposable 凭据，只用于本机验证，不代表生产配置。栈的完整契约与手工命令由本文
持有，开发文档只保留运行入口与自迭代约束。

### [`deploy/test`](../../deploy/test/README.md)：Canvas/Storage 离线栈

`kk-studio-canvas-test` project 提供 PostgreSQL、MinIO、HTTP mock 与可选的 App，用于 Canvas
Resource、fake Function、OpenCLI fake Hub adapter 和离线 Chat smoke。bucket 由一次性的
`minio-init` 容器创建，App 以 `service_completed_successfully` 等它成功退出；不启用 App 时没有
这个依赖条件，smoke 只等待常驻服务 `healthy`，再显式 `compose run --rm` 执行同一份初始化：

```bash
./scripts/dev/verify/smoke/offline-chat.sh
./scripts/dev/verify/smoke/offline-chat.sh --with-app
```

完整步骤、mock routes 与真实 Seedance prepare-only 边界见
[deploy/test/README.md](../../deploy/test/README.md)。
基础模式检查容器、PostgreSQL、bucket 与 mock；`--with-app` 增加 Blob、Canvas revision/typed command、
fake Function 与 Chat 的应用契约检查。一次运行的通过范围以退出状态和实际诊断为准。

### [`deploy/distributed`](../../deploy/distributed)：双节点零 App-to-App 网络栈

`kk-studio-distributed` project 是免费 mock 拓扑：App 镜像复用
[deploy/local/Dockerfile](../../deploy/local/Dockerfile)，Daemon 镜像复用
[deploy/reliability/daemon.Dockerfile](../../deploy/reliability/daemon.Dockerfile)，HTTP mock 直接
挂载 [`deploy/test/mock`](../../deploy/test/mock)。

```bash
./scripts/dev/verify/e2e/distributed.sh up [--skip-build]
./scripts/dev/verify/e2e/distributed.sh status
./scripts/dev/verify/e2e/distributed.sh verify       # 静态拓扑校验，不启动容器
./scripts/dev/verify/e2e/distributed.sh down --volumes
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

distributed.sh disconnect-db-a / reconnect-db-a 是按容器与网络精确操作的幂等故障注入，不会影响
`daemon-a` 网络与 daemon workspace volume。
[`scripts/dev/verify/e2e/run.sh`](../../scripts/dev/verify/e2e/run.sh) 的 `--distributed` 通过该入口启停栈
并在退出时清理。

### [`deploy/reliability`](../../deploy/reliability)：App + Environment Daemon

`kk-studio-reliability` project 运行 `e2e` profile 的 App 与容器内 Daemon，用于 Environment
工具隔离、S3 和显式可靠性矩阵。`reliability` network 是 `internal: true`，App 另加入
`app-ingress` 发布 loopback API（默认 `RELIABILITY_APP_PORT=18091`，MinIO
`RELIABILITY_MINIO_PORT=19002`）。

```bash
./scripts/dev/verify/reliability/stack.sh up
./scripts/dev/verify/reliability/stack.sh status
./scripts/dev/verify/reliability/stack.sh inspect
./scripts/dev/verify/reliability/stack.sh logs postgres app workspace-init daemon
./scripts/dev/verify/reliability/stack.sh down --volumes
```

Daemon 不发布宿主端口，只经 `ws://app:8080/api/harness/environment-daemon/v1` 连接 App。

容器入口在启动前按与产品相同的布局物化配置与凭证：把注入的注册凭证写为
`/workspace/.kk-studio/daemon.token`（目录 0700、文件 0600），并按 studioUrl `http://app:8080`
生成兄弟 `daemon.json`，最后用唯一的 `--config /workspace/.kk-studio/daemon.json` 启动 Daemon。
凭证文本既不进入 argv（`ps` 不可见），也不残留在 `/proc/<pid>/environ`；Daemon 由配置的
`studioUrl` 自行派生上述 gateway。容器配置不含 `--gateway-uri`、`--data-dir` 或 `--lsp-config`
等第二入口。

[daemon.Dockerfile](../../deploy/reliability/daemon.Dockerfile) 以 JDK 21 builder 构建
[`harness/daemon`](../../harness/daemon) 单文件 shaded JAR，runtime 使用
`eclipse-temurin:21.0.8_9-jdk-jammy`，预装
Node `22.19.0`/npm `11.19.0`、bash、git，创建 `kkdaemon` uid/gid `10001`，入口先物化凭证再
`exec java -jar /opt/kk-studio/daemon.jar`。Daemon 只挂载一个 `/workspace` named volume，
`workspace-init` 完成 owner 初始化（`chown 10001:10001`）后 Daemon 才启动。

`up` 等待 PostgreSQL health、App `/actuator/health` 和公共 `GET /api/harness/environments` 的
`status=READY`。`inspect` fail closed 检查：

- Daemon uid 不是 root；
- mount 只有 `volume -> /workspace` 且可写、没有 bind mount；
- `java`/`javap`、Node/npm/git/bash 可执行，`rg`/`fd` 不存在；
- workspace 可写、Environment `READY`；
- Compose config 不含 provider credential 或 bind mount。

向 Daemon named volume 写入跨仓基线是独立的显式操作，必须给出 clean Git worktree：

```bash
PI_ANCHOR=/path/to/pi \
PI_BASE_ANCHOR=/path/to/pi-base \
  ./scripts/dev/verify/reliability/stack.sh snapshot
```

可靠性栈与 Agent 矩阵的运行入口见
[开发与测试](development-and-testing.md#隔离栈与真实-agent-矩阵)。

## 运行配置与凭据

| Stack/用途 | 责任组 | 典型变量 |
| --- | --- | --- |
| local | host mapping/database/S3/profile | `KK_STUDIO_APP_*`、`KK_STUDIO_PG_*`、`KK_STUDIO_S3_*`、`KK_STUDIO_STORAGE_S3_*`、`KK_STUDIO_SPRING_PROFILES_ACTIVE` |
| local | Harness dispatcher | `KK_STUDIO_HARNESS_DISPATCHER_*` |
| NAS App 节点 | prod 数据面与异步执行 | `KK_STUDIO_DB_*`、`KK_STUDIO_STORAGE_S3_*`、`KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE`、`KK_STUDIO_CANVAS_H3_COMFY_BEARER_TOKEN` |
| 本机 preview | 外部数据面配置文件 | `SHARED_PREVIEW_ENV_FILE` 指向的文件内的同一组 `KK_STUDIO_DB_*` / `KK_STUDIO_STORAGE_S3_*` |
| local/reliability | admission/gateway | `KK_STUDIO_MODEL_MAX_CONCURRENCY`、`KK_STUDIO_TOOL_MAX_CONCURRENCY`、`KK_STUDIO_ENVIRONMENT_GATEWAY_*`（subagent 并发上限不经环境变量配置，只由运行时 SystemSettings 的 `aiRuntime.subagent*` 持有） |
| production with authenticated Plugin | encrypted credential | `KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE`（所有 App 节点挂载同一 owner-only 文件） |
| production with authenticated Plugin | resource staging bounds | `KK_STUDIO_PLUGINS_RESOURCE_CONNECT_TIMEOUT`、`KK_STUDIO_PLUGINS_RESOURCE_REQUEST_TIMEOUT`、`KK_STUDIO_PLUGINS_RESOURCE_UPLOAD_TIMEOUT`、`KK_STUDIO_PLUGINS_RESOURCE_MAX_BYTES`、`KK_STUDIO_PLUGINS_RESOURCE_TEMP_DIRECTORY` |
| test | ports/build/mock | `CANVAS_TEST_*` |
| test | S3/media/fake runtime | `KK_STUDIO_STORAGE_S3_*`、`KK_STUDIO_CANVAS_RESOURCE_*`、`KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED` |
| distributed | node identity/ports | `DISTRIBUTED_*` |
| reliability | stack identity | `RELIABILITY_APP_PORT`、`RELIABILITY_MINIO_PORT`、`RELIABILITY_ENV_NAME`、`RELIABILITY_REGISTRATION_TOKEN` |
| supply-chain | reports/images/cache/network | `SUPPLY_CHAIN_REPORT_ROOT`、`SUPPLY_CHAIN_APP_IMAGE`、`SUPPLY_CHAIN_DAEMON_IMAGE`、`SUPPLY_CHAIN_TRIVY_CACHE_VOLUME`、`SUPPLY_CHAIN_TRIVY_NETWORK`、`TRIVY_SKIP_DB_UPDATE` |
| 显式 `--real` E2E | 仅宿主 credential 同步 | `TEST_GOOGLE_*`、`TEST_OPENAI_*`、`TEST_ANTHROPIC_*`、`TEST_DEEPSEEK_*` |
| reliability Agent 矩阵 | 仅宿主 credential 同步 | `TEST_MINIMAX_BASE_URL`、`TEST_MINIMAX_API_KEY` |
| 显式 Seedance prepare-only | 确认与外部 Hub/workspace | `RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`、`OPENCLI_HUB_BASE_URL`、`SEEDANCE_WORKSPACE_ID`、可选 `OPENCLI_HUB_INSTANCE_ID` / `SEEDANCE_PREPARE_SMOKE_PROMPT`；另需唯一参数 `--confirm-prepare-only`，见 [deploy/test](../../deploy/test/README.md#真实-seedance-prepare-only-边界) |

Dispatcher、Admission 和 gateway frame/queue 上限是启动配置，不由 SystemSettings editor 修改。
Canvas Function runtime 变量和 `KK_STUDIO_CANVAS_H3_COMFY_BEARER_TOKEN` 也只在启动期读取。

凭据边界：

- [.dockerignore](../../.dockerignore) 与 [.gitignore](../../.gitignore) 排除 `.env`、key/cert/
  credential 文件、`credentials*`、service account JSON 和 `secrets/`。
- 本机 preview 的外部数据面配置是仓库之外的 owner-only 文件（模板见
  [scripts/dev/shared-preview.env.example](../../scripts/dev/shared-preview.env.example)）：[scripts/dev/app.sh](../../scripts/dev/app.sh)
  只按 `KEY=VALUE` 字面量解析白名单键，不做 shell 求值、不把值放进命令参数或日志，权限过宽、
  属主不符、符号链接、未知键或值缺失都直接失败。
- local/test/reliability/distributed 的固定 PostgreSQL 与 MinIO 凭据只属于 disposable compose；
  宿主绑定地址一旦改为非 loopback，就必须显式覆盖这些默认值。
- [`scripts/dev/verify/e2e/run.sh`](../../scripts/dev/verify/e2e/run.sh) 只在显式 `--real` 时读取四组完整 credential pair，并经 HTTP 写入各自专用
  database；credential 不进入 Compose environment、Dockerfile、image layer、backend/Daemon
  command 或报告。reliability 栈独立使用 `TEST_MINIMAX_BASE_URL`/`TEST_MINIMAX_API_KEY`。
- 各测试栈的 registration token 是栈内隔离配置；`NVD_API_KEY` 只由供应链脚本写入临时
  mode `600` 的 Maven settings，二者都不进 command line、POM、image 或报告。
- Plugin credential 主密钥是原始 32-byte 随机值（不是 Base64 文本），只通过
  `KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE` 指向的只读 secret file 注入，不能把密钥正文放进
  environment、Compose 文件、数据库或 image。多 App 节点必须使用同一内容；缺失或错误时
  已保存凭据读取与新认证 fail closed，未连接 Plugin 不阻止 App 启动。
- Plugin 资源端口只在需要时接受外部内容：会话资源下载以调用方的 Harness Thread 归属授权，
  远端媒体暂存只允许公网 HTTPS 目标（私网/保留/环回等地址一律拒绝，因此节点必须能对目标
  主机做正向解析）、受默认 `256MiB` 与 `1GiB` 硬上限的字节预算约束，并在成功后立即删除
  临时文件。放宽 `KK_STUDIO_PLUGINS_RESOURCE_MAX_BYTES`、把目标指向内网或让
  `KK_STUDIO_PLUGINS_RESOURCE_TEMP_DIRECTORY` 落到共享卷都会直接扩大该端口的暴露面，
  应作为部署变更评审。

构建代理与运行代理独立。App image build 支持
`KK_STUDIO_BUILD_HTTP_PROXY`、`KK_STUDIO_BUILD_HTTPS_PROXY`、`KK_STUDIO_BUILD_NO_PROXY` 和
`KK_STUDIO_MAVEN_BUILD_OPTS`；[scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 与
[scripts/dev/verify/performance/run.sh](../../scripts/dev/verify/performance/run.sh) 会从宿主 proxy 变量生成 build 参数，
离线 smoke 对可解析的 HTTP(S) proxy 默认使用 host build network，performance 则只自动处理
loopback proxy。这些 build 参数不会自动进入 App/Daemon runtime。

Backend 的运行代理在 **系统设置 → 网络** 保存，全局只配置一个无认证 HTTP 代理地址
`http://host:port`；HTTPS 请求通过 CONNECT。地址留空即强制直连，不继承宿主环境变量。
保存后重启各 Backend 节点生效，无模型、Git 或插件单独覆盖。绕过规则用逗号分隔，
支持主机、域名后缀、IP、可选端口、`*` 与 IPv4/IPv6 CIDR，例如
`localhost,127.*,::1,192.168.0.0/16,minio,*.internal`；CIDR 只按目标 URL 中的数值 IP 逐位匹配，
不为域名解析 DNS，按域名访问的内网服务仍应写域名规则。不支持 SOCKS、代理认证或 TLS 到代理。
代理失败不会自动改为直连。
本机/内网 S3、Hub、ComfyUI 应纳入绕过规则；多节点代理地址必须对每个节点可达，
容器里的 `127.0.0.1` 不是宿主机。公网媒体下载即使走代理也要求 Backend 正向解析
并校验全部地址，CONNECT 固定到已校验 IP，不能用代理绕过公网地址准入。

Daemon 不读取这项 Backend 配置；它遵循本机环境与操作系统代理，
具体来源与服务进程环境见 [Environment Daemon](environment-daemon.md#本机代理)。
浏览器直传/下载以及 Hub、ComfyUI 自身的外部请求由各自的网络配置决定。

## 清理

先使用保留数据的停止命令。带 `-v` / `--volumes` 的命令没有额外确认提示，只能在确认对应栈
全部数据可丢弃后执行；不能用来修复生产认证、Flyway 校验或健康 S3 的连接问题。

```bash
# local：停止并保留数据；第二条额外删除 PostgreSQL 与 MinIO 数据
docker compose -f deploy/local/compose.yaml down
docker compose -f deploy/local/compose.yaml down -v

# test：停止并保留数据；第二条额外删除 PostgreSQL/MinIO volumes
docker compose -f deploy/test/compose.yaml --profile app down --remove-orphans
docker compose -f deploy/test/compose.yaml --profile app down -v --remove-orphans

# distributed：停止并保留数据；第二条删除 PG/MinIO/daemon workspace volumes
./scripts/dev/verify/e2e/distributed.sh down
./scripts/dev/verify/e2e/distributed.sh down --volumes

# reliability：保留或删除 PostgreSQL/MinIO/workspace named volumes
./scripts/dev/verify/reliability/stack.sh down
./scripts/dev/verify/reliability/stack.sh down --volumes

# 确认宿主端口
ss -ltnp | grep -E ':8080|:5432|:9000|:15432|:15433|:18082|:18083|:18088|:18089|:18090|:19000|:19001|:19002|:18091' || true
```

`down` 保留 named volume，`down -v` / `--volumes` 删除它并让下次启动重新执行 Flyway 与
bucket 初始化。离线 smoke、性能入口及 E2E `--distributed` 会自动删除各自测试栈的数据，
执行前也必须确认同名 project 是可丢弃环境。清理命令只作用于对应 Compose project，不影响其它 project 的容器、network 或
volume。任何 app、database、MinIO、mock、Daemon `READY` 或 smoke 失败都应保留诊断并进入失败
路径，而不是把未验证的服务交给上层脚本。

## NAS 运行拓扑

本仓库只发布产物与脚本，NAS 上由外部 Compose 项目运行它，Gateway 负责对外路由；自迭代不需要
第二个 NAS 节点，也不需要容器内源码或工具链：

| 边界 | 职责 |
| --- | --- |
| 本仓库 | 应用镜像 [`deploy/local/Dockerfile`](../../deploy/local/Dockerfile)、[发布工作流](../../.github/workflows/docker-publish.yml)、NAS Compose 引用的变量名与 Daemon 发布物 |
| NAS Compose 仓库 | `vps-kk-studio` 容器、共享 PostgreSQL/S3 连接、私密环境变量注入、反向代理接入 |
| Gateway 仓库 | `studio.kk1.fun` 的 HTTP/WebSocket 路由和访问控制 |

### 镜像与运行职责

App 镜像只有一个：[deploy/local/Dockerfile](../../deploy/local/Dockerfile) 构建的 Fat JAR 镜像
（内嵌前端），运行时不包含源码、Maven、Node 或凭据。`dev` 与 `main` 发布同一个镜像，只有可变
tag 不同：

| 分支 | 可变 tag | 用途 |
| --- | --- | --- |
| `dev` | `<namespace>/kk-studio:dev` | 自迭代：push 后直接发布，不自动执行完整仓库门禁，需调用方另行验证 |
| `main` | `<namespace>/kk-studio:main` | 用户使用的稳定版本：通过完整仓库门禁后发布 |

本拓扑要求外部 NAS Compose 只运行一个 App 容器 `vps-kk-studio`，以 `prod` profile 常驻：
它承担共享数据库唯一的 Flyway owner 与 Harness worker，Thread/Model/Tool 的异步执行都发生在这里。
容器数量与 Gateway 域名由外部部署管理，本仓脚本不校验 NAS 的实际拓扑。迭代手段
是替换镜像 tag 并重启容器，而不是在容器内改源码，因此容器不挂载源码工作区、Maven/npm cache
或 `gh` 配置。

镜像创建了 UID/GID `10001` 可写的 `/app/logs`，日志配置由 Platform 引入的 convention4j
Logback 配置与运行环境共同决定。排查时先看 `docker logs vps-kk-studio` 与实际日志配置；
控制台只有 JVM 或启动横幅不代表没有应用错误。若日志写文件，按运行配置定位文件，再读取并脱敏。
需要跨容器替换保留日志时，由外部 Compose 为日志目录设置持久挂载与 UID/GID `10001` 的写权限，
宿主权限按部署策略收紧；首次挂载不会自动迁移旧容器里的文件，需在替换前另行保存。
共享 PostgreSQL 与 S3/MinIO 已在各自容器持久化；App 的媒体转码与上传暂存目录仍是可清理的临时空间，
不应挂成跨容器重启保留的数据卷。使用加密 Plugin 凭据时另需持久化的主密钥文件，并以只读方式挂入
容器、设置 `KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE`；文件内容必须是恰好 32 bytes 的原始 AES-256
密钥（不是 Base64 文本），由容器用户 UID `10001` 可读，权限不得允许组或其他用户读取。不能用自动创建的空文件或新密钥替代已有密钥。

### 本机 preview 与 NAS 数据面

前端与同步 API 的日常开发在笔记本上进行：[scripts/dev/shared-preview.sh](../../scripts/dev/shared-preview.sh)
用 NAS 上已有的 PostgreSQL/S3 启动已打包的 Backend 与 Vite/HMR。它读一份 owner-only 配置文件
（endpoint 与凭据，模板见
[scripts/dev/shared-preview.env.example](../../scripts/dev/shared-preview.env.example)），强制
`SPRING_PROFILES_ACTIVE=prod`、`SPRING_FLYWAY_ENABLED=false` 和
`KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false`：schema 只由 NAS 上的 Flyway owner 演进，
异步 Harness Work 只在 NAS 上执行，本机进程只是同步 HTTP 面。Backend 默认监听
`127.0.0.1:18080`，Vite/HMR 默认监听 `127.0.0.1:5173`；配置文件的权限要求、键白名单与失败
边界见[开发与测试](development-and-testing.md#本机-preview-的外部数据面)。

Environment Daemon 不属于 NAS App 容器：需要主机能力时，在目标主机通过 Studio 的安装弹窗保存
配置、复制命令并执行，宿主 Daemon 再按配置中的 studioUrl 连接 NAS App 的
`wss://<studio-origin>/api/harness/environment-daemon/v1`。安装机制、安装设置字段、注册 token
文件、更新与卸载见 [Environment Daemon 安装与运行](environment-daemon.md)。

### 发布产物与凭据边界

[`.github/workflows/docker-publish.yml`](../../.github/workflows/docker-publish.yml) 在推送
`main` 时先执行全仓 Java/Frontend/脚本/文档/敏感数据门禁，再构建并发布
`<namespace>/kk-studio:main`；推送 `dev` 时不执行这组全仓门禁，直接构建并
发布同一个 Dockerfile 的 `<namespace>/kk-studio:dev`。需只验收不发布时可显式使用
`workflow_dispatch` 的 `validate_only=true`，操作见[CI 验证](development-and-testing.md#ci-验证与镜像发布)。
两者都附带 commit SHA tag、
`linux/amd64` 平台和 Buildx GHA cache。这个 tag 标识构建所用的源码提交，不是可按提交重现的
镜像摘要：基础镜像按 tag 解析，运行阶段执行 `apt-get upgrade`，因此同一次提交在不同日期构建
可能得到不同镜像。Docker Hub 凭据只来自 Actions secrets，不作为 build arg 或 image layer。

外部 Compose 和 Gateway 配置只引用环境变量名。真实 database、S3、Provider、Gateway、
registration credential 和 Plugin 主密钥不进入本仓库、Docker build context、image layer、日志
或报告；registration token 只经 owner-only 的 `daemon.token` 兄弟文件传递，由 `install` 的
`--config-file` / `--token-file` 读取，不出现在 Daemon argv 或环境变量中。本机 preview 的配置文件同样
留在仓库之外、只有 owner 可读，脚本
只把它当作数据面输入，不打印其中的值。共享数据库维护流程（产品导出配置、重建空库、当前 Schema 初始化与产品导入）见
[开发与测试](development-and-testing.md#共享数据库重建)。

---

上级：[系统设计](../system-design.md)。相关文档：[开发与测试](development-and-testing.md)、
[Environment Daemon 安装与运行](environment-daemon.md)、
[本地一键启动栈](../../deploy/local/README.md)、[Canvas/Storage 隔离测试栈](../../deploy/test/README.md)。
