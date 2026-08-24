# kk-studio

`kk-studio` 是全局单实例产品，包含两个并列域：

| 域 | 说明 |
| --- | --- |
| **Harness / AI** | 可恢复 Agent Thread、Tool 与实时投影 |
| **Studio / Canvas** | 全局单实例画布工作台，持久化 ResourceNode/Resource/Function/Group/Link 与 typed commands |

架构事实源：

- [docs/technical-solution/architecture.md](docs/technical-solution/architecture.md)
- [docs/technical-solution/domain-map.md](docs/technical-solution/domain-map.md)
- [docs/technical-solution/harness-runtime-architecture.md](docs/technical-solution/harness-runtime-architecture.md)
- [docs/technical-solution/harness-agent-loop.md](docs/technical-solution/harness-agent-loop.md)
- [docs/technical-solution/prompt-to-resource.md](docs/technical-solution/prompt-to-resource.md)
- [docs/technical-solution/canvas-resource-function-v1.md](docs/technical-solution/canvas-resource-function-v1.md)

## 能力摘要

- Harness：Session 共享 append-only Entry Tree（TURN_START/MESSAGE/TOOL/TURN_END 语义）；Thread 以非空 head + 命令 mailbox + version CAS 控制执行；7 张 durable 表 + 唯一 `harness_work` 调度 mailbox；PostgreSQL `LISTEN/NOTIFY` 提供低延迟提示，snapshot 与 periodic poll 负责恢复
- Studio：Canvas 八表持久化 document/group/node/link/resource/function-run/function-resource-ref/command-dedup；typed command batch 使用 document version CAS 与 request hash 幂等
- 前端：AI 与 Canvas 都接真实 snapshot/command API 与应用事件 WebSocket 通道；Canvas Editor 通过实体 Patch 更新，gap 时回退权威 Snapshot

## 模块

```text
share / schema / canvas/core / platform / web
harness/tool / harness/runtime / harness/plugin-api / harness/infra
harness/daemon / harness/plugins/goal
frontend
```

`harness-runtime` 是纯 Java 领域状态机；`harness-infra` 只做 PostgreSQL Store、Work、realtime notification 与 Resource 适配。

## 开发

后端单元 / 集成测试：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn test
```

前端本地开发与校验：

```bash
cd frontend && npm test && npm run lint && npm run build
```

供应链质量门禁（显式在线扫描，报告见
[技术方案](docs/technical-solution/supply-chain-quality-gate.md)）：

```bash
./scripts/supply-chain.sh all
```

完整应用 Fat JAR（包含 React 静态资源）：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -Pdistribution -pl web -am clean package
$JAVA_HOME_21/bin/java -jar web/target/kk-studio-web-1.0.0.jar
```

## 本地一键启动（app + PostgreSQL）

仓库根目录构建 Spring Boot Fat JAR（含 React 产物），由 Docker Compose 拉起
`app + postgres` 两个服务。Spring 直接服务 UI / API / SPA fallback，
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
| Harness API | <http://localhost:8080/api/ai/runtime/threads/{threadId}/snapshot> 等 |
| Health | <http://localhost:8080/actuator/health> |
| PostgreSQL | `jdbc:postgresql://localhost:5432/kk_studio`（用户 / 密码：`kk_studio`） |

数据库首次初始化的约束：

- 应用通过 Flyway 执行
  [`V1__schema.sql`](schema/src/main/resources/db/migration/V1__schema.sql) 与
  [`V2__dev_seed.sql`](schema/src/main/resources/db/seed/dev/V2__dev_seed.sql)；
  `flyway_schema_history` 确保重启不会重复迁移。
- `V2__dev_seed.sql` 只写入 local-only 的 stub provider（`stub-key`），
  不携带任何真实凭证。
- 真实 Provider 的 `credential` 必须通过 UI 的 Provider 页面或
  `PUT /api/ai/catalog/providers/{name}` 在运行时注入，**绝不**写入镜像或仓库。

端口覆盖与并行 smoke 见 [`deploy/local/README.md`](deploy/local/README.md)。
