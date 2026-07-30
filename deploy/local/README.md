# 本地一键启动栈

`kk-studio` 的本地一键启动栈：`app + postgres + redis`。

默认 Compose 由仓库根目录构建：

- `app` —— Spring Boot Fat JAR 镜像。构建时在容器内调用
  `mvn -Pdistribution -pl web -am -DskipTests clean package`，把 React 产物
  嵌入 `BOOT-INF/classes/static`，运行时由 Spring 直接服务 UI / API / SPA
  fallback；不加 Nginx，也不另起前端容器。容器内端口固定为 `8080`。
- `postgres:17-alpine` —— 唯一 durable 数据库，命名为 `kk_studio`，默认用户
  `kk_studio`。`schema-postgresql.sql` 与 `data-dev-postgresql.sql` 只读挂载到
  `/docker-entrypoint-initdb.d/01-schema.sql` 与 `02-data.sql`；空 volume 首次
  `up` 时执行一次，之后由 `application-dev.yml` 的 `spring.sql.init.mode` 经
  环境变量 `SPRING_SQL_INIT_MODE=never` 跳过，避免重复执行。
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

# 彻底清理：额外删除命名卷，回到空数据库；下次 up 会重新执行 schema/data-dev。
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

## E2E 多模型 Provider 环境注入

E2E seed 的 7 个 Provider 与 19 个 Pi 模型不含任何真实凭证。Compose 会将全部
`TEST_*_BASE_URL` / `TEST_*_API_KEY` 传给 app；仅当 app 运行在 `e2e` profile
且 `KK_STUDIO_E2E_PROVIDER_SYNC_ENABLED=true` 时，启动期同步器才会把**成对的非空**
Base URL 与 API Key 写入对应 Provider。空值或不完整对会被忽略，且不会输出密钥。

| Provider | Base URL | API Key |
| --- | --- | --- |
| MiniMax | `TEST_MINIMAX_BASE_URL` | `TEST_MINIMAX_API_KEY` |
| OpenAI | `TEST_OPENAI_BASE_URL` | `TEST_OPENAI_API_KEY` |
| xAI | `TEST_XAI_BASE_URL` | `TEST_XAI_API_KEY` |
| DeepSeek | `TEST_DEEPSEEK_BASE_URL` | `TEST_DEEPSEEK_API_KEY` |
| Google | `TEST_GOOGLE_BASE_URL` | `TEST_GOOGLE_API_KEY` |
| Anthropic | `TEST_ANTHROPIC_BASE_URL` | `TEST_ANTHROPIC_API_KEY` |
| ZAI | `TEST_ZAI_BASE_URL` | `TEST_ZAI_API_KEY` |

同步使用持久化 Provider 配置，而非让模型调用直接读取环境变量。OpenAI 兼容
Provider 的 Base URL 在缺少末尾 `/v1` 时自动补齐。Compose 仅在容器创建或重建时
读取宿主环境；修改 `TEST_*` 后需重建 app。凭证会存在于 app 容器运行时环境，
因此仅应在受控的本地 E2E 环境使用，并限制 Docker 管理面访问：

```bash
KK_STUDIO_SPRING_PROFILES_ACTIVE=e2e \
KK_STUDIO_PG_DATABASE=kk_studio_e2e \
docker compose -f deploy/local/compose.yaml up -d --build --no-deps --force-recreate app
```

上述命令要求 PostgreSQL 中已有由 `schema-postgresql.sql` 与
`data-e2e-postgresql.sql` 初始化的 `kk_studio_e2e` 数据库。常规 dev 栈不会同步
Provider 凭证；真实凭证不会写入镜像、SQL seed 或仓库。

数据库首次初始化的约束：

- `core/src/main/resources/schema-postgresql.sql` 和
  `data-dev-postgresql.sql` 是唯一事实源，**只在命名卷为空时执行一次**。
- `data-dev-postgresql.sql` 写入的是 local-only 的 stub provider（`stub-key`），
  不携带任何真实凭证。
- 真实 Provider（OpenAI / Google / Anthropic / xAI / MiniMax / DeepSeek / ZAI）
  的 `credential` 必须通过 UI、`PUT /api/ai/catalog/providers/{id}` 或 E2E profile 的环境同步器
  在运行时注入，**绝不**写入镜像、SQL seed 或仓库。

## 清理宿主网络监听

`down`（不加 `-v`）会删除容器，端口映射随之释放，命名卷保留；`down -v`
进一步删除命名卷。可用以下命令确认无残留：

```bash
docker compose -f deploy/local/compose.yaml ps -a
docker compose -f deploy/local/compose.yaml down -v
ss -ltnp | grep -E ':8080|:5432|:6379' || true
```
