# 本地一键启动栈

`kk-studio` 的本地一键启动栈：`app + postgres + minio + minio-init`。

默认 Compose 由仓库根目录构建：

- `app` —— Spring Boot Fat JAR 镜像。构建时在容器内调用
  `mvn -Pdistribution -pl web -am -DskipTests clean package`，把 React 产物
  嵌入 `BOOT-INF/classes/static`，运行时由 Spring 直接服务 UI / API / SPA
  fallback；不加 Nginx，也不另起前端容器。容器内端口固定为 `8080`。
  依赖 PostgreSQL、MinIO 与 minio-init healthy 后启动；启动时自动验证 S3 连接属性与 bucket 可用性。
- `postgres:17-alpine` —— 唯一 durable 数据库，命名为 `kk_studio`，默认用户
  `kk_studio`。空库由 app 在 `dev` profile 通过 Flyway 执行
  [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 和
  [`R__dev_seed.sql`](../../schema/src/main/resources/db/seed/dev/R__dev_seed.sql)；
  已执行版本由 `flyway_schema_history` 记录。Harness Work、version 与 realtime 的低延迟
  提示也复用 PostgreSQL `LISTEN/NOTIFY`；通知丢失时由 durable snapshot 与 periodic poll 恢复。
- `minio:RELEASE.2025-04-22T22-12-26Z` —— 基础 S3 兼容对象存储，为 Blob 与 Storage 提供本地持久化存储。
  宿主端口默认绑定 `127.0.0.1:9000`，数据保存于命名卷 `kk-studio-minio`。
- `minio-init:RELEASE.2025-04-16T18-13-26Z` —— 初始化容器，等待 MinIO healthy 后自动使用 `mc`
  创建私有 bucket 并设置安全权限，保持健康守护。

> Harness Daemon 不在当前栈内。

## 启动

```bash
docker compose -f deploy/local/compose.yaml up -d --build --wait
```

`--wait` 会一直等到四个服务的 healthcheck 全部 `healthy`（PostgreSQL 用
`pg_isready`，MinIO 用 `/minio/health/live`，minio-init 用 `mc stat`，app 用
`curl http://127.0.0.1:8080/actuator/health`）。

查看状态与日志：

```bash
docker compose -f deploy/local/compose.yaml ps
docker compose -f deploy/local/compose.yaml logs -f app
docker compose -f deploy/local/compose.yaml logs -f postgres
docker compose -f deploy/local/compose.yaml logs -f minio
```

## 统一地址

| 资源 | 地址 |
| --- | --- |
| Web UI | <http://localhost:8080/> |
| Harness API | <http://localhost:8080/api/harness/threads> 等 |
| Health | <http://localhost:8080/actuator/health> |
| PostgreSQL | `jdbc:postgresql://localhost:5432/kk_studio`（用户 / 密码：`kk_studio`） |
| MinIO API | <http://localhost:9000> |

`localhost` 默认绑定 `127.0.0.1`；通过环境变量 `KK_STUDIO_APP_HOST` /
`KK_STUDIO_PG_HOST` / `KK_STUDIO_S3_HOST` 可改为 `0.0.0.0` 等绑定地址。
此时还需将 `KK_STUDIO_S3_PUBLIC_HOST` 设为浏览器实际可访问的主机名或 IP；
预签名 URL 不会使用不可路由的 bind 地址。

## 停止与清理

```bash
# 停止：删除容器与宿主端口映射，PostgreSQL 与 MinIO 命名卷保留。
docker compose -f deploy/local/compose.yaml down

# 彻底清理：额外删除命名卷，回到空数据库与空对象桶；下次 app 启动会重新初始化。
docker compose -f deploy/local/compose.yaml down -v
```

`down` 会把容器连同 `ports:` 配置产生的宿主映射一并释放，监听立即消失；
`down -v` 在此基础上再删除命名卷，确认无残留的命令见下文「清理宿主网络监听」。

## 端口、容量与并行 smoke

并行跑第二套实例用于烟测时，只需覆盖下表中的宿主映射变量；容器内端口固定不变。
同一张表也列出 Local App 可覆盖的部署级容量与传输边界。

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `KK_STUDIO_APP_PORT` | `8080` | app **宿主**端口（映射到容器内 `8080`，不可改容器端口；改值也不会让 Dockerfile HEALTHCHECK 失效） |
| `KK_STUDIO_APP_HOST` | `127.0.0.1` | app 宿主绑定地址 |
| `KK_STUDIO_PG_PORT` | `5432` | PostgreSQL **宿主**端口（映射到容器内 `5432`；`KK_STUDIO_DB_URL` 始终指向容器内 `5432`） |
| `KK_STUDIO_PG_HOST` | `127.0.0.1` | PostgreSQL 宿主绑定地址 |
| `KK_STUDIO_PG_DATABASE` | `kk_studio` | 初始数据库名 |
| `KK_STUDIO_PG_USER` | `kk_studio` | 初始用户名 |
| `KK_STUDIO_PG_PASSWORD` | `kk_studio` | 初始密码 |
| `KK_STUDIO_S3_PORT` | `9000` | MinIO **宿主**端口（映射到容器内 `9000`） |
| `KK_STUDIO_S3_HOST` | `127.0.0.1` | MinIO 宿主绑定地址 |
| `KK_STUDIO_S3_PUBLIC_HOST` | `127.0.0.1` | 预签名 URL 对浏览器发布的 MinIO 主机名或 IP |
| `KK_STUDIO_S3_BUCKET` | `kk-studio` | 初始 S3 存储桶名 |
| `KK_STUDIO_S3_ACCESS_KEY` | `kk-studio` | MinIO / S3 access key（固定 disposable 本地凭据） |
| `KK_STUDIO_S3_SECRET_KEY` | `kk-studio` | MinIO / S3 secret key（固定 disposable 本地凭据） |
| `KK_STUDIO_S3_REGION` | `us-east-1` | S3 region |
| `KK_STUDIO_SPRING_PROFILES_ACTIVE` | `dev` | 传递给 `SPRING_PROFILES_ACTIVE` |
| `KK_STUDIO_HARNESS_DISPATCHER_MAX_DISPATCH_TASKS` | `64` | 本地 queued/running Processor handoff 总量上限 |
| `KK_STUDIO_HARNESS_DISPATCHER_LEASE_DURATION` | `30s` | Work 初始 claim 租约时长 |
| `KK_STUDIO_HARNESS_DISPATCHER_POLL_INTERVAL` | `1s` | 丢失通知时的兜底轮询间隔 |
| `KK_STUDIO_HARNESS_DISPATCHER_REJECTION_DELAY` | `1s` | worker executor 拒绝 handoff 后的重排延迟 |
| `KK_STUDIO_HARNESS_DISPATCHER_WORKER_CONCURRENCY` | `16` | bounded worker executor 平台线程并发数 |
| `KK_STUDIO_HARNESS_DISPATCHER_WORKER_QUEUE_CAPACITY` | `64` | bounded worker executor 队列容量 |
| `KK_STUDIO_MODEL_MAX_CONCURRENCY` | `16` | 单进程 Model invocation admission 上限；启动配置，不进入 SystemSettings |
| `KK_STUDIO_TOOL_MAX_CONCURRENCY` | `64` | 单进程 Tool invocation admission 上限；启动配置，不进入 SystemSettings |
| `KK_STUDIO_SUBAGENT_MAX_CONCURRENCY` | `10` | Subagent 固定虚拟线程执行器容量；启动配置，不进入 SystemSettings |
| `KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_MESSAGE_BYTES` | `16777216` | Daemon WebSocket 单帧上限（字节），默认 16MiB |
| `KK_STUDIO_ENVIRONMENT_GATEWAY_QUEUE_CAPACITY` | `256` | 每个 Daemon 连接的出站待发送帧数上限（含在途帧） |
| `KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_BYTES` | `16777216` | 每个 Daemon 连接的出站待发送 UTF-8 总字节上限（含在途帧） |
| `KK_STUDIO_ENVIRONMENT_GATEWAY_SEND_TIMEOUT` | `10s` | 单帧 WebSocket 发送超时；超时后关闭连接 |

## 真实 E2E Provider 凭证

Compose 和 app 不注入、传递或读取任何真实 Provider 凭证。真实 E2E 必须从宿主执行
`./scripts/e2e.sh --real`；runner 在 backend ready 后调用唯一的
`scripts/e2e/sync_provider_credentials.py`，通过 backend API 更新 E2E database
中由 seed 创建的 Google、OpenAI Responses、MiniMax Anthropic 和 DeepSeek Provider
row；credential 不进入 seed SQL/resource。

允许且必须完整提供的四组 pair 是：

- `TEST_GOOGLE_BASE_URL` / `TEST_GOOGLE_API_KEY`
- `TEST_OPENAI_BASE_URL` / `TEST_OPENAI_API_KEY`
- `TEST_ANTHROPIC_BASE_URL` / `TEST_ANTHROPIC_API_KEY`
- `TEST_DEEPSEEK_BASE_URL` / `TEST_DEEPSEEK_API_KEY`

OpenAI 与 DeepSeek Base URL 会去除尾部斜杠并补齐 `/v1`；Gemini 与 MiniMax
Anthropic 保留调用方提供的协议 base path。真实矩阵固定使用
`google/gemini-3.8-flash`、`openai/gpt-5.6-luna`、
`minimax-anthropic/MiniMax-M3` 和 `deepseek/deepseek-v4-flash`。同步不会输出
密钥或 endpoint，也不应通过 app environment 手工同步。

E2E profile 通过 Flyway 执行
[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 和
[`R__e2e_seed.sql`](../../schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql)。seed 保留
8 个 Provider 和 21 个 Pi 模型 catalog，但不包含真实凭证；真实凭证不会写入镜像、SQL
seed 或仓库。

数据库首次初始化的约束：

- `V1__schema.sql` 和 `R__dev_seed.sql` 是 `dev` profile 的唯一 bootstrap 来源；
  Flyway 仅执行 `flyway_schema_history` 尚未记录的版本。
- `R__dev_seed.sql` 写入的是 local-only 的 stub provider（`stub-key`），
  不携带任何真实凭证。
- 真实 E2E credential 仅由宿主 runner 的 MiniMax 同步器在运行时注入，绝不写入镜像、
  SQL seed 或仓库。

## 清理宿主网络监听

`down`（不加 `-v`）会删除容器，端口映射随之释放，命名卷保留；`down -v`
进一步删除命名卷。可用以下命令确认无残留：

```bash
docker compose -f deploy/local/compose.yaml ps -a
docker compose -f deploy/local/compose.yaml down -v
ss -ltnp | grep -E ':8080|:5432|:9000' || true
```
