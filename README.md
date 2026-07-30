# kk-studio

`kk-studio` 是全局单实例产品，包含两个并列域：

| 域 | 说明 |
| --- | --- |
| **Harness / AI** | 可恢复 Agent Thread、Tool 与观测 |
| **Studio / Canvas** | 全局单实例画布工作台，持久化 Canvas 文档/节点/连线/幂等命令 |

架构事实源：

- [docs/technical-solution/architecture.md](docs/technical-solution/architecture.md)
- [docs/technical-solution/domain-map.md](docs/technical-solution/domain-map.md)
- [docs/technical-solution/harness-runtime-architecture.md](docs/technical-solution/harness-runtime-architecture.md)

## 能力摘要

- Harness：Session 共享 append-only Entry Tree；HarnessThread 是可复用 durable runtime process（head 重定位 + epoch fencing + ordered mailbox）；PostgreSQL truth + durable activation queue，Redis 仅用于 realtime
- Studio：Canvas 持久化 document / node / link / command-dedup（硬删除节点）；FUNCTION 节点只暴露 `system.generate-text` v1
- 前端：AI 接真实 Thread API；Canvas Library/Create 接真实 API，Editor 仍使用本地交互投影

## 模块

```text
share / studio / core / web
harness-tool / harness-runtime / harness-daemon
frontend
```

## 开发

后端单元 / 集成测试：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn test
```

前端本地开发与校验：

```bash
cd frontend && npm test && npm run lint && npm run build
```

完整应用 Fat JAR（包含 React 静态资源）：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -Pdistribution -pl web -am clean package
$JAVA_HOME_21/bin/java -jar web/target/kk-studio-web-1.0.0.jar
```

## 本地一键启动（app + PostgreSQL + Redis）

仓库根目录构建 Spring Boot Fat JAR（含 React 产物），由 Docker Compose 拉起
`app + postgres + redis` 三个服务。Spring 直接服务 UI / API / SPA fallback，
没有 Nginx，没有独立前端容器。

```bash
# 首次启动：构建镜像、等待 healthcheck 全 healthy
docker compose -f deploy/local/compose.yaml up -d --build --wait

# 状态 / 日志
docker compose -f deploy/local/compose.yaml ps
docker compose -f deploy/local/compose.yaml logs -f app

# 停止（保留 PostgreSQL 数据卷）
docker compose -f deploy/local/compose.yaml down

# 彻底清理（删除数据卷，下次启动重新执行 schema/data-dev）
docker compose -f deploy/local/compose.yaml down -v
```

统一地址：

| 资源 | 地址 |
| --- | --- |
| Web UI | <http://localhost:8080/> |
| Harness API | <http://localhost:8080/api/threads> 等 |
| Health | <http://localhost:8080/actuator/health> |
| PostgreSQL | `jdbc:postgresql://localhost:5432/kk_studio`（用户 / 密码：`kk_studio`） |
| Redis | `redis://localhost:6379` |

数据库首次初始化的约束：

- `core/src/main/resources/schema-postgresql.sql` 与 `data-dev-postgresql.sql`
  由 PostgreSQL 入口脚本在**空 volume**时执行一次；之后重启不重复执行，
  `SPRING_SQL_INIT_MODE=never` 关闭 Spring 的 classpath 兜底 init。
- `data-dev-postgresql.sql` 只写入 local-only 的 stub provider（`stub-key`），
  不携带任何真实凭证。
- 真实 Provider 的 `credential` 必须通过 UI 的 Provider 页面或
  `PUT /api/providers/{id}` 在运行时注入，**绝不**写入镜像或仓库。

端口覆盖与并行 smoke 见 [`deploy/local/README.md`](deploy/local/README.md)。
