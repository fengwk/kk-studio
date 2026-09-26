# 部署与运行

本文覆盖从源码构建可运行产物、在容器里运行 App、接入外部 PostgreSQL 与 S3、生产反向代理，
以及本仓库提供的隔离栈和 NAS 运行拓扑。跨模块边界的整体模型见
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
sh scripts/dev/lib/extract-convention4j-agent.sh "$JAVA_HOME_21/bin/jar" \
  web/target/kk-studio-web-1.0.0.jar \
  web/target/convention4j-agent/convention4j-agent.jar
"$JAVA_HOME_21/bin/java" \
  -javaagent:web/target/convention4j-agent/convention4j-agent.jar \
  -jar web/target/kk-studio-web-1.0.0.jar
```

`distribution` profile 在 `prepare-package` 阶段由 [web/pom.xml](../../web/pom.xml) 的
`frontend-maven-plugin` 安装 Node `v24.14.0`、npm `11.9.0` 并执行 `npm ci` 与
`npm run build`，把 Vite 产物复制到 `web/target/classes/static`，再由
`spring-boot-maven-plugin` 写入 `BOOT-INF/classes/static`。普通 `mvn test` / `mvn package`
不激活该 profile。

可选 Plugin 是构建期依赖，不从运行目录动态发现。把对应 artifact 以 runtime scope 加入
[`web/pom.xml`](../../web/pom.xml) 后，上面的 `-pl web -am` 会构建并把它写入 Fat JAR；
移除 dependency 并重新构建后，该 Plugin 的管理端口、调度任务和模型工具都不存在。
顶层 `plugins` aggregator 只定义可构建模块，不决定发行物包含哪些 Plugin。
共享数据库且启用 Worker 的 App 节点必须运行同一 Fat JAR 和 Plugin 集合；改变 Plugin
依赖时先停止旧 Worker 领取新 Work、排空在途调用，再整体切换，不能让不同 Tool catalog
长期混跑。

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
| process | `java -javaagent:/app/convention4j-agent/convention4j-agent.jar -jar /app/app.jar`，`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` |
| health | `curl -fsS http://127.0.0.1:8080/actuator/health`，15s interval、5s timeout、60s start period、6 retries |

builder 接受 `KK_STUDIO_BUILD_HTTP_PROXY`、`KK_STUDIO_BUILD_HTTPS_PROXY`、
`KK_STUDIO_BUILD_NO_PROXY` 和 `KK_STUDIO_MAVEN_BUILD_OPTS`；这些值只用于 build，不复制进
runtime image。构建时从 Fat JAR 提取同版本 `convention4j-agent`，保留 manifest
`Boot-Class-Path` 要求的版本化文件名并通过稳定别名启动 JVM；它负责线程池中的 TTL/MDC
上下文透传，不替代跨服务的 Trace 传播。runtime image 不含 Maven、Node 和源码。

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
  [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 建立，migration
  只由唯一 Flyway owner 执行。
- `prod` profile 的数据源只来自 `KK_STUDIO_DB_URL`/`KK_STUDIO_DB_USER`/`KK_STUDIO_DB_PASSWORD`，
  没有默认值，缺失时启动失败而不是回退到开发数据库；`SPRING_PROFILES_ACTIVE` 与全部
  `KK_STUDIO_STORAGE_S3_*` 同样必须显式提供。

### 已执行旧 V1 的数据库

本次 Project/Issue、Harness Goal 和证据重构直接重写了 Flyway V1；**旧库不能直接运行新镜像**。
Flyway 校验失败不是可跳过的升级步骤：不得修改 `flyway_schema_history`、使用 `repair` 伪造
checksum，或直接在有数据的旧库上重放 V1。源码合入 `dev` 也不等于批准生产数据库重建。

上线前由数据所有者批准维护窗口和数据范围，然后按
[共享数据库重建](development-and-testing.md#共享数据库重建)执行：先停止所有旧 App/Worker 和
Daemon，等待在途调用收敛；从**与新部署相同的提交**对旧库只读执行 Catalog 导出 `--dry-run`
和正式导出（仅当三张表列结构仍符合检查器契约时），再对旧库执行 `reset-database.sh --dry-run`。
确认离线备份的存放位置、恢复权限和数据范围后，才在维护窗口执行 reset：脚本会生成完整备份、
冻结原库并创建同名空库；由唯一 Flyway owner 在空库执行新 V1，再回灌三张 Catalog 表，重建
Environment/Daemon 注册，并用新镜像及独立 S3 bucket 验证健康与关键业务操作。旧 Project、
Issue、Chat、Harness、Blob 引用与历史设置**只保存在冻结快照/完整备份，不导入新库**；
存储对象本身不由数据库 reset 删除，不得在确认快照保留策略前清理旧 bucket。

如导出预检不接受旧 Catalog 结构、生产依赖保留旧会话历史、备份不可验证或缺少可恢复的停机窗口，
则**停止部署**，保持旧镜像/旧库运行；不可用不受支持的兼容别名或静默丢弃数据绕过预检。
回退时停止新 App，使用已验证的冻结快照和旧镜像恢复旧入口；新库中写入的事实不会自动合并回
旧库。真正执行重建、清理快照或切换生产服务均需另行明确授权。

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
Resource、fake Function、OpenCLI fake Hub adapter 和离线 Chat smoke：

```bash
./scripts/dev/verify/smoke/offline-chat.sh
./scripts/dev/verify/smoke/offline-chat.sh --with-app
```

完整步骤、mock routes 与真实 Seedance prepare-only 边界见
[deploy/test/README.md](../../deploy/test/README.md)。

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

```text
--gateway-uri ws://app:8080/api/harness/environment-daemon/v1
--note "Isolated Docker reliability environment."
--data-dir /workspace/.kkstudio/daemon
```

注册凭证不经 argv 传递：Compose 只向容器注入 `KK_STUDIO_DAEMON_REGISTRATION_TOKEN`，
[daemon-entrypoint.sh](../../deploy/reliability/daemon-entrypoint.sh) 把它写成
`/home/kkdaemon/.kkstudio/daemon-registration.token`（目录 0700、文件 0600）并从环境中 `unset`，
再用 `--registration-token-file <绝对路径>` 传入。凭证不出现在 `ps` 可见的 argv，也不留在
`/proc/<pid>/environ`。

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
- JDK/`javap`/Node/npm/git/bash 可执行，`rg`/`fd` 不存在；
- workspace 可写、Environment `READY`；
- Compose config 不含 provider credential。

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
| local/reliability | admission/gateway | `KK_STUDIO_MODEL_MAX_CONCURRENCY`、`KK_STUDIO_TOOL_MAX_CONCURRENCY`、`KK_STUDIO_SUBAGENT_MAX_CONCURRENCY`、`KK_STUDIO_ENVIRONMENT_GATEWAY_*` |
| production with authenticated Plugin | encrypted credential | `KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE`（所有 App 节点挂载同一 owner-only 文件） |
| production with authenticated Plugin | resource staging bounds | `KK_STUDIO_PLUGINS_RESOURCE_CONNECT_TIMEOUT`、`KK_STUDIO_PLUGINS_RESOURCE_REQUEST_TIMEOUT`、`KK_STUDIO_PLUGINS_RESOURCE_UPLOAD_TIMEOUT`、`KK_STUDIO_PLUGINS_RESOURCE_MAX_BYTES`、`KK_STUDIO_PLUGINS_RESOURCE_TEMP_DIRECTORY` |
| test | ports/build/mock | `CANVAS_TEST_*` |
| test | S3/media/fake runtime | `KK_STUDIO_STORAGE_S3_*`、`KK_STUDIO_CANVAS_RESOURCE_*`、`KK_STUDIO_CANVAS_FUNCTION_FAKE_ENABLED` |
| distributed | node identity/ports | `DISTRIBUTED_*` |
| reliability | stack identity | `RELIABILITY_APP_PORT`、`RELIABILITY_MINIO_PORT`、`RELIABILITY_ENV_NAME`、`RELIABILITY_REGISTRATION_TOKEN` |
| supply-chain | reports/images/cache | `SUPPLY_CHAIN_REPORT_ROOT`、`SUPPLY_CHAIN_APP_IMAGE`、`SUPPLY_CHAIN_DAEMON_IMAGE`、`SUPPLY_CHAIN_TRIVY_CACHE_VOLUME`、`TRIVY_SKIP_DB_UPDATE` |
| 显式 `--real` E2E | 仅宿主 credential 同步 | `TEST_GOOGLE_*`、`TEST_OPENAI_*`、`TEST_ANTHROPIC_*`、`TEST_DEEPSEEK_*` |
| reliability Agent 矩阵 | 仅宿主 credential 同步 | `TEST_MINIMAX_BASE_URL`、`TEST_MINIMAX_API_KEY` |
| 显式 Seedance prepare-only | 外部 Hub/workspace | `OPENCLI_HUB_BASE_URL`、`SEEDANCE_WORKSPACE_ID`、可选 `OPENCLI_HUB_INSTANCE_ID`，见 [deploy/test](../../deploy/test/README.md#真实-seedance-prepare-only-边界) |

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

Proxy 只作用于 build、npm/Maven dependency fetch 或显式 Trivy network：App image build 支持
`KK_STUDIO_BUILD_HTTP_PROXY`、`KK_STUDIO_BUILD_HTTPS_PROXY`、`KK_STUDIO_BUILD_NO_PROXY` 和
`KK_STUDIO_MAVEN_BUILD_OPTS`；[scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 与
[scripts/dev/verify/performance/run.sh](../../scripts/dev/verify/performance/run.sh) 会从宿主 proxy 变量生成 build 参数，
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
./scripts/dev/verify/e2e/distributed.sh down --volumes

# reliability：保留或删除 PostgreSQL/MinIO/workspace named volumes
./scripts/dev/verify/reliability/stack.sh down
./scripts/dev/verify/reliability/stack.sh down --volumes

# 确认宿主端口
ss -ltnp | grep -E ':8080|:5432|:9000|:15432|:15433|:18082|:18083|:18088|:18089|:18090|:19000|:19001|:19002|:18091' || true
```

`down` 保留 named volume，`down -v` / `--volumes` 删除它并让下次启动重新执行 Flyway 与
bucket 初始化。清理命令只作用于对应 Compose project，不影响其它 project 的容器、network 或
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
| `dev` | `<namespace>/kk-studio:dev` | 自迭代：仓库门禁在提交前完成，push 后直接发布 |
| `main` | `<namespace>/kk-studio:main` | 用户使用的稳定版本：通过完整仓库门禁后发布 |

NAS 上只运行一个 App 容器 `vps-kk-studio`，以 `prod` profile 常驻：它既是共享数据库唯一的
Flyway owner，也是唯一的 Harness worker，Thread/Model/Tool 的异步执行都发生在这里。迭代手段
是替换镜像 tag 并重启容器，而不是在容器内改源码，因此容器不挂载源码工作区、Maven/npm cache
或 `gh` 配置。

`prod` profile 的应用日志写入容器内 `/app/logs/kk-studio-all.log`，不写到 Docker 控制台。
NAS Compose 将 `/app/logs` 绑定到宿主 `${DOCKER_VOLUMNS_DIR_SSD}/vps-kk-studio/logs`；
宿主目录首次由 NAS `.vpsrc` 初始化给镜像用户 UID/GID `10001`，目录权限 `0700`。
首次增加挂载前若要保留旧容器内的日志，应在替换容器之前另行复制；新挂载不会自动迁移旧文件。
`docker logs` 中只有 JVM 或启动横幅不代表没有应用错误。排查异步 Work 时可用
`docker exec vps-kk-studio sh -c 'tail -n 100 /app/logs/kk-studio-all.log'` 查看，并在分享前脱敏。
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

Environment Daemon 不属于 NAS App 容器：需要主机能力时，规范路径是在目标主机 clone 源码并通过平台
对应的安装脚本常驻（Linux/macOS 用 [scripts/daemon/install.sh](../../scripts/daemon/install.sh)，
Windows 用 [scripts/daemon/install.ps1](../../scripts/daemon/install.ps1)），并连接 NAS App 的
gateway `wss://<studio-origin>/api/harness/environment-daemon/v1`。安装机制、注册 token 文件、
升级与卸载见 [Environment Daemon 安装与运行](environment-daemon.md)。

### 发布产物与凭据边界

[`.github/workflows/docker-publish.yml`](../../.github/workflows/docker-publish.yml) 在推送
`main` 时先执行全仓 Java/Frontend/脚本/文档/敏感数据门禁，再构建并发布
`<namespace>/kk-studio:main`；推送 `dev` 时依赖提交前检查，跳过这组重复的全仓门禁，直接构建并
发布同一个 Dockerfile 的 `<namespace>/kk-studio:dev`。两者都附带 immutable commit SHA tag、
`linux/amd64` 平台和 Buildx GHA cache；Docker Hub 凭据只来自 Actions secrets，不作为 build arg
或 image layer。

外部 Compose 和 Gateway 配置只引用环境变量名。真实 database、S3、Provider、Gateway、
registration credential 和 Plugin 主密钥不进入本仓库、Docker build context、image layer、日志
或报告；registration token 经 owner-only 凭证文件传递（`--registration-token-file`），不出现
在 Daemon argv 或环境变量中。本机 preview 的配置文件同样留在仓库之外、只有 owner 可读，脚本
只把它当作数据面输入，不打印其中的值。共享数据库维护流程（导出 Catalog、重建空库、Flyway V1 初始化与回灌）见
[开发与测试](development-and-testing.md#共享数据库重建)。

---

上级：[系统设计](../system-design.md)。相关文档：[开发与测试](development-and-testing.md)、
[Environment Daemon 安装与运行](environment-daemon.md)、
[本地一键启动栈](../../deploy/local/README.md)、[Canvas/Storage 隔离测试栈](../../deploy/test/README.md)。
