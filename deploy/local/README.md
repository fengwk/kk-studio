# 本地栈：App、PostgreSQL 与 MinIO

一条命令在本机拉起可用的 Studio：[compose.yaml](compose.yaml) 里的 `app` 由
[Dockerfile](Dockerfile) 构建成 Spring Boot Fat JAR 镜像，同时服务 UI、API 和 SPA
fallback；`postgres` 保存全部 durable 数据；`minio` 提供 S3 兼容对象存储，`minio-init`
创建私有 bucket；Environment Daemon 独立安装。

需要让 Agent 操作本机文件、命令或检索时，再单独安装
[Environment Daemon](../../docs/operations/environment-daemon.md)。隔离测试栈、分布式
双节点、可靠性栈、生产部署和反向代理见[部署与运行](../../docs/operations/deployment.md)。

## 前置条件

- Docker Engine 与 Docker Compose v2（`docker compose version` 可用）。
- 首次构建需要拉取镜像并下载 Maven 与 npm 依赖。
- 默认宿主端口 `8080`、`5432`、`9000` 未被占用。

MinIO server 的平台限制、mc 本地构建与源码许可见
[开发栈 MinIO 依赖](../dependencies/minio-client/README.md)；ARM 宿主需要 amd64 模拟能力。

## 启动并验证

以下命令均在仓库根目录执行。

```bash
docker compose -f deploy/local/compose.yaml up -d --build --wait
```

`--wait` 会等到 PostgreSQL 与 MinIO 的 `healthcheck` 变为 `healthy`、一次性的
`minio-init` 成功退出（`app` 以 `service_completed_successfully` 等待它），以及 `app` 的
`curl http://127.0.0.1:8080/actuator/health` 通过。构建在容器内执行
`mvn -U -Pdistribution -pl web -am -DskipTests -B -ntp clean package`，React 产物嵌入
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
| Canvas / Catalog API | <http://localhost:8080/api/canvases>、<http://localhost:8080/api/ai/catalog/providers> 等 |
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

[compose.yaml](compose.yaml) 还接受进程级的容量与调度参数，本地启动通常不需要覆盖：dispatcher
与 admission 上限的默认值和语义由
[Platform 配置](../../docs/modules/platform.md#配置)持有，
`KK_STUDIO_ENVIRONMENT_GATEWAY_*` 的 Daemon WebSocket 边界由
[Web 配置](../../docs/modules/web.md#组合与生命周期)持有；subagent 并发上限不经环境变量配置，
只由运行时 SystemSettings 的 `aiRuntime.subagent*` 持有。

把宿主绑定改成 `0.0.0.0` 等非 loopback 地址时，必须同时覆盖上面所有 PostgreSQL 与 MinIO
凭据，并把 `KK_STUDIO_S3_PUBLIC_HOST` 设为浏览器实际可访问的主机名或 IP：预签名 URL 不会
使用不可路由的绑定地址。Studio 当前没有内置登录鉴权，向局域网或公网暴露前必须在外部入口
配置 TLS 和访问控制，见[部署与运行](../../docs/operations/deployment.md)。

## 数据与 Flyway

PostgreSQL 是唯一 durable 数据库。空库由 `app` 在 `dev` profile 下用 Flyway 执行
[V1__schema.sql](../../schema/src/main/resources/db/migration/V1__schema.sql) 和
[R__dev_seed.sql](../../schema/src/main/resources/db/seed/dev/R__dev_seed.sql)，已执行版本
记录在 `flyway_schema_history`。Compose 卷键为 `kk-studio-postgres` 与 `kk-studio-minio`；
默认 project `kk-studio-local` 下的 Docker 卷名分别为
`kk-studio-local_kk-studio-postgres` 与 `kk-studio-local_kk-studio-minio`。
普通启动与 `down` 都保留已有卷；启动不会重建健康的 PostgreSQL 或清空 S3 对象。
`dev`/`e2e`/`canvas-test` 的 profile 与 seed 对应关系见
[Schema 模块](../../docs/modules/schema.md)，生产 profile 见[部署与运行](../../docs/operations/deployment.md)。

## 更新与数据保护

更新前保存数据库、对象存储和运行配置，确认待运行镜像与 schema 兼容。
使用同一 Compose project 重新构建并启动 App：

```bash
docker compose -f deploy/local/compose.yaml up -d --build --wait
curl -fsS http://127.0.0.1:8080/actuator/health
```

该命令保留命名卷。健康通过后检查界面与预签名上传/下载；Flyway 校验失败时保留数据并排查
schema 差异。需要恢复或建立空库时按[部署维护流程](../../docs/operations/deployment.md#恢复与重建)
执行，不能用删除卷代替备份和恢复。

## 停止与清理

```bash
# 停止：删除容器与宿主端口映射，保留 PostgreSQL 与 MinIO 命名卷
docker compose -f deploy/local/compose.yaml down

# 仅在确认本栈全部数据可丢弃后：删除 PostgreSQL 和 MinIO 两个命名卷
docker compose -f deploy/local/compose.yaml down -v
```

`down -v` 没有额外确认提示，会同时删除数据库与对象存储数据；没有独立备份就无法恢复。
它不是升级或连接排错的默认步骤。下次启动从空数据重新执行 Flyway 与 bucket 初始化。
确认没有残留监听（按当前配置的宿主端口核对，默认 8080/5432/9000）：

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
| 改过 PostgreSQL 配置后连接失败 | 先看 `ps` 与 `logs postgres app`。`KK_STUDIO_PG_HOST/PORT` 仅改宿主映射；`DATABASE/USER/PASSWORD` 是空卷初始化值，不会修改已有数据库。先恢复匹配旧卷的连接配置；确需改库名、角色或密码时，由数据所有者按 PostgreSQL 管理流程处理，不用 `down -v` 排错 |
| 需要真实 Provider 的 E2E | 不在本栈执行；从宿主运行 `./scripts/dev/verify/e2e/run.sh --real`，见[开发与测试](../../docs/operations/development-and-testing.md) |

隔离的 Canvas/Storage 测试栈见 [deploy/test](../test/README.md)。
