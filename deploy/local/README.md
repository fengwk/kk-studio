# 本地一键启动栈

`kk-studio` 的本地一键启动栈：`app + postgres + redis`。

默认 Compose 由仓库根目录构建：

- `app` —— Spring Boot Fat JAR 镜像。构建时在容器内调用
  `mvn -Pdistribution -pl web -am -DskipTests clean package`，把 React 产物
  嵌入 `BOOT-INF/classes/static`，运行时由 Spring 直接服务 UI / API / SPA
  fallback；不加 Nginx，也不另起前端容器。容器内端口固定为 `8080`。
- `postgres:17-alpine` —— 唯一 durable 数据库，命名为 `kk_studio`，默认用户
  `kk_studio`。空库由 app 在 `dev` profile 通过 Flyway 执行
  [`V1__schema.sql`](../../database/src/main/resources/db/migration/V1__schema.sql) 和
  [`V2__dev_seed.sql`](../../database/src/main/resources/db/seed/dev/V2__dev_seed.sql)；
  已执行版本由 `flyway_schema_history` 记录。
- `redis:7.4-alpine` —— `Harness` 的 lossy realtime projection；
  `--save "" --appendonly no`，纯内存使用，重启即清空。

> Harness Daemon 不在当前栈内。

## 启动

```bash
docker compose -f deploy/local/compose.yaml up -d --build --wait
```

`--wait` 会一直等到三个服务的 healthcheck 全部 `healthy`（PostgreSQL 用
`pg_isready`、Redis 用 `redis-cli ping`、app 用 `curl http://127.0.0.1:8080/actuator/health`）。

查看状态与日志：

```bash
docker compose -f deploy/local/compose.yaml ps
docker compose -f deploy/local/compose.yaml logs -f app
docker compose -f deploy/local/compose.yaml logs -f postgres
```

## 统一地址

| 资源 | 地址 |
| --- | --- |
| Web UI | <http://localhost:8080/> |
| Harness API | <http://localhost:8080/api/ai/runtime/threads> 等 |
| Health | <http://localhost:8080/actuator/health> |
| PostgreSQL | `jdbc:postgresql://localhost:5432/kk_studio`（用户 / 密码：`kk_studio`） |
| Redis | `redis://localhost:6379` |

`localhost` 默认绑定 `127.0.0.1`；通过环境变量 `KK_STUDIO_APP_HOST` /
`KK_STUDIO_PG_HOST` / `KK_STUDIO_REDIS_HOST` 可改为 `0.0.0.0` 等地址。

## 停止与清理

```bash
# 停止：删除容器与宿主端口映射，仅 PostgreSQL 命名卷 kk-studio-postgres 保留。
docker compose -f deploy/local/compose.yaml down

# 彻底清理：额外删除命名卷，回到空数据库；下次 app 启动会重新执行 Flyway migrations。
docker compose -f deploy/local/compose.yaml down -v
```

`down` 会把容器连同 `ports:` 配置产生的宿主映射一并释放，监听立即消失；
`down -v` 在此基础上再删除命名卷，确认无残留的命令见下文「清理宿主网络监听」。

## 端口与并行 smoke

并行跑第二套实例用于烟测时，只覆盖下表中的环境变量即可。容器内端口固定
不变，只能换宿主侧。

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `KK_STUDIO_APP_PORT` | `8080` | app **宿主**端口（映射到容器内 `8080`，不可改容器端口；改值也不会让 Dockerfile HEALTHCHECK 失效） |
| `KK_STUDIO_APP_HOST` | `127.0.0.1` | app 宿主绑定地址 |
| `KK_STUDIO_PG_PORT` | `5432` | PostgreSQL **宿主**端口（映射到容器内 `5432`；`KK_STUDIO_DB_URL` 始终指向容器内 `5432`） |
| `KK_STUDIO_PG_HOST` | `127.0.0.1` | PostgreSQL 宿主绑定地址 |
| `KK_STUDIO_REDIS_PORT` | `6379` | Redis **宿主**端口（映射到容器内 `6379`；`KK_STUDIO_REDIS_URL` 始终指向容器内 `6379`） |
| `KK_STUDIO_REDIS_HOST` | `127.0.0.1` | Redis 宿主绑定地址 |
| `KK_STUDIO_PG_DATABASE` | `kk_studio` | 初始数据库名 |
| `KK_STUDIO_PG_USER` | `kk_studio` | 初始用户名 |
| `KK_STUDIO_PG_PASSWORD` | `kk_studio` | 初始密码 |
| `KK_STUDIO_SPRING_PROFILES_ACTIVE` | `dev` | 传递给 `SPRING_PROFILES_ACTIVE` |
| `KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_MESSAGE_BYTES` | `16777216` | Daemon WebSocket 单帧上限（字节），默认 16MiB |

## 真实 E2E MiniMax 凭证

Compose 和 app 不注入、传递或读取任何真实 Provider 凭证。真实 E2E 必须从宿主执行
`./scripts/e2e.sh --real`；runner 在 backend ready 后调用唯一的
`scripts/e2e/sync_provider_credentials.py`，通过 backend API 更新 E2E seed 中的
MiniMax Provider。

唯一允许的 credential pair 是 `TEST_MINIMAX_BASE_URL` 与 `TEST_MINIMAX_API_KEY`，必须
同时提供；Base URL 会去除尾部斜杠并补为 `/v1`。真实用例和默认 E2E Agent 固定使用
`minimax/MiniMax-M2.7`。同步不会输出密钥，也不应通过 app environment 手工同步。

E2E profile 通过 Flyway 执行
[`V1__schema.sql`](../../database/src/main/resources/db/migration/V1__schema.sql) 和
[`V2__e2e_seed.sql`](../../database/src/main/resources/db/seed/e2e/V2__e2e_seed.sql)。seed 仍保留
7 个 Provider 和 19 个 Pi 模型 catalog，但不包含真实凭证；真实凭证不会写入镜像、SQL
seed 或仓库。

数据库首次初始化的约束：

- `V1__schema.sql` 和 `V2__dev_seed.sql` 是 `dev` profile 的唯一 bootstrap 来源；
  Flyway 仅执行 `flyway_schema_history` 尚未记录的版本。
- `V2__dev_seed.sql` 写入的是 local-only 的 stub provider（`stub-key`），
  不携带任何真实凭证。
- 真实 E2E credential 仅由宿主 runner 的 MiniMax 同步器在运行时注入，绝不写入镜像、
  SQL seed 或仓库。

## 清理宿主网络监听

`down`（不加 `-v`）会删除容器，端口映射随之释放，命名卷保留；`down -v`
进一步删除命名卷。可用以下命令确认无残留：

```bash
docker compose -f deploy/local/compose.yaml ps -a
docker compose -f deploy/local/compose.yaml down -v
ss -ltnp | grep -E ':8080|:5432|:6379' || true
```
