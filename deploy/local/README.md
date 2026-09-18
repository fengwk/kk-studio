# 本地栈：App、PostgreSQL 与 MinIO

一条命令在本机拉起可用的 Studio：[compose.yaml](compose.yaml) 里的 `app` 由
[Dockerfile](Dockerfile) 构建成 Spring Boot Fat JAR 镜像，同时服务 UI、API 和 SPA
fallback；`postgres` 保存全部 durable 数据；`minio` 提供 S3 兼容对象存储，`minio-init`
创建私有 bucket。栈内没有 Nginx、没有独立前端容器，也不包含 Environment Daemon。

需要让 Agent 操作本机文件、命令或检索时，再单独安装
[Environment Daemon](../../docs/operations/environment-daemon.md)。隔离测试栈、分布式
双节点、可靠性栈、生产部署和反向代理见[部署与运行](../../docs/operations/deployment.md)。

## 前置条件

- Docker Engine 与 Docker Compose v2（`docker compose version` 可用）。
- 首次构建需要拉取镜像并下载 Maven 与 npm 依赖；本机不需要 JDK 或 Node。
- 默认宿主端口 `8080`、`5432`、`9000` 未被占用。

## 启动并验证

```bash
docker compose -f deploy/local/compose.yaml up -d --build --wait
```

`--wait` 会等到四个服务 healthcheck 全部 `healthy`：PostgreSQL 用 `pg_isready`，
MinIO 用 `/minio/health/live`，`minio-init` 用 `mc stat`，`app` 用
`curl http://127.0.0.1:8080/actuator/health`。构建在容器内执行
`mvn -Pdistribution -pl web -am -DskipTests clean package`，React 产物嵌入
`BOOT-INF/classes/static`，因此本机不需要安装 JDK、Maven 或 npm。

验证并打开界面：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

浏览器访问 <http://localhost:8080/>。`dev` profile 预置 `stub` Provider、
`acceptance-stub` Model 和 `default-assistant` Agent，作为 Catalog 结构示例；本地栈不含
`stub.local` 模型服务，直接用该 Agent 发消息会连接失败，需要先在
<http://localhost:8080/providers> 接入自己的 Provider。

查看状态与日志：

```bash
docker compose -f deploy/local/compose.yaml ps
docker compose -f deploy/local/compose.yaml logs -f app
docker compose -f deploy/local/compose.yaml logs -f postgres
docker compose -f deploy/local/compose.yaml logs -f minio
```

## 地址与端口

| 资源 | 地址 |
| --- | --- |
| Web UI | <http://localhost:8080/> |
| Harness API | <http://localhost:8080/api/harness/threads> 等 |
| Health | <http://localhost:8080/actuator/health> |
| PostgreSQL | `jdbc:postgresql://localhost:5432/kk_studio`（用户 / 密码 `kk_studio`） |
| MinIO S3 API | <http://localhost:9000> |

容器内端口固定（`app` `8080`、PostgreSQL `5432`、MinIO `9000`），只有宿主绑定和映射可改。

## 配置

改宿主映射只影响浏览器、curl 和本地 PostgreSQL 客户端；`app` 始终经 Compose 网络连接
`postgres:5432` 与 `minio:9000`。

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `KK_STUDIO_APP_HOST` | `127.0.0.1` | app 宿主绑定地址 |
| `KK_STUDIO_APP_PORT` | `8080` | app 宿主端口（映射到容器内 `8080`；改它不会影响镜像 HEALTHCHECK） |
| `KK_STUDIO_PG_HOST` / `KK_STUDIO_PG_PORT` | `127.0.0.1` / `5432` | PostgreSQL 宿主绑定地址与端口 |
| `KK_STUDIO_PG_DATABASE` / `KK_STUDIO_PG_USER` / `KK_STUDIO_PG_PASSWORD` | `kk_studio` | 初始数据库名、用户名与密码；disposable 本地值 |
| `KK_STUDIO_S3_HOST` / `KK_STUDIO_S3_PORT` | `127.0.0.1` / `9000` | MinIO 宿主绑定地址与端口 |
| `KK_STUDIO_S3_PUBLIC_HOST` | `127.0.0.1` | 预签名 URL 发布给浏览器的主机名或 IP |
| `KK_STUDIO_S3_BUCKET` | `kk-studio` | 初始 bucket |
| `KK_STUDIO_S3_ACCESS_KEY` / `KK_STUDIO_S3_SECRET_KEY` | `kk-studio` | MinIO 凭据；disposable 本地值 |
| `KK_STUDIO_S3_REGION` | `us-east-1` | S3 region |
| `KK_STUDIO_SPRING_PROFILES_ACTIVE` | `dev` | 传给 `SPRING_PROFILES_ACTIVE` |

[compose.yaml](compose.yaml) 还接受进程级的容量与调度参数，本地启动通常不需要覆盖：dispatcher、
admission 与 subagent 上限的默认值和语义由
[Platform 配置](../../docs/modules/platform.md#部署级-configurationproperties)持有，
`KK_STUDIO_ENVIRONMENT_GATEWAY_*` 的 Daemon WebSocket 边界由
[Web 配置](../../docs/modules/web.md#生命周期与配置)持有。

把宿主绑定改成 `0.0.0.0` 等非 loopback 地址时，必须同时覆盖上面所有 PostgreSQL 与 MinIO
凭据，并把 `KK_STUDIO_S3_PUBLIC_HOST` 设为浏览器实际可访问的主机名或 IP：预签名 URL 不会
使用不可路由的绑定地址。Studio 当前没有内置登录鉴权，向局域网或公网暴露前必须在外部入口
配置 TLS 和访问控制，见[部署与运行](../../docs/operations/deployment.md)。

## 数据与 Flyway

PostgreSQL 是唯一 durable 数据库。空库由 `app` 在 `dev` profile 下用 Flyway 执行
[V1__schema.sql](../../schema/src/main/resources/db/migration/V1__schema.sql) 和
[R__dev_seed.sql](../../schema/src/main/resources/db/seed/dev/R__dev_seed.sql)，已执行版本
记录在 `flyway_schema_history`。数据保存在命名卷 `kk-studio-postgres`，MinIO 数据保存在
`kk-studio-minio`。`dev`/`e2e`/`canvas-test` 的 profile 与 seed 对应关系见
[Schema 模块](../../docs/modules/schema.md)，生产 profile 见[部署与运行](../../docs/operations/deployment.md)。

## 停止与清理

```bash
# 停止：删除容器与宿主端口映射，保留 PostgreSQL 与 MinIO 命名卷
docker compose -f deploy/local/compose.yaml down

# 彻底清理：额外删除命名卷，回到空数据库与空 bucket；下次启动重新执行 Flyway
docker compose -f deploy/local/compose.yaml down -v
```

`down -v` 不可恢复。确认没有残留监听：

```bash
docker compose -f deploy/local/compose.yaml ps -a
ss -ltnp | grep -E ':8080|:5432|:9000' || true
```

## 常见问题

| 现象 | 处理 |
| --- | --- |
| `--wait` 超时，`app` 一直 unhealthy | 先看 `docker compose -f deploy/local/compose.yaml logs app postgres`；确认 PostgreSQL health 通过后再看 `/actuator/health` |
| 宿主端口被占用 | 用 `KK_STUDIO_APP_PORT` / `KK_STUDIO_PG_PORT` / `KK_STUDIO_S3_PORT` 改映射，避免影响其它栈 |
| 浏览器里图片或附件加载失败 | 检查 `KK_STUDIO_S3_PUBLIC_HOST` 是否可从浏览器访问，预签名 URL 使用该主机名 |
| 改过 `KK_STUDIO_PG_*` 后连接失败 | 命名卷里已有旧数据库；`docker compose -f deploy/local/compose.yaml down -v` 后按新值重新初始化 |
| 需要真实 Provider 的 E2E | 不在本栈执行；从宿主运行 `./scripts/e2e.sh --real`，见[开发与测试](../../docs/operations/development-and-testing.md) |

隔离的 Canvas/Storage 测试栈见 [deploy/test](../test/README.md)。
