# Schema 模块

## 定位

`schema` 是唯一的 V1 baseline 与 profile seed 纯资源模块。它不包含 Java
源码、Spring bean 或运行时 repository；Flyway 从该模块加载 PostgreSQL DDL
和 profile 数据，应用与集成测试共享同一资源事实源。

```mermaid
flowchart LR
    Schema[schema resources]
    V1[V1__schema.sql<br/>baseline]
    Seeds[db/seed/{dev,e2e,canvas-test}]
    Flyway[Flyway]
    PG[(PostgreSQL)]
    Web[web runtime]
    Tests[platform / harness-infra / canvas-infra tests]

    Schema --> V1
    Schema --> Seeds
    V1 --> Flyway
    Seeds --> Flyway
    Flyway --> PG
    Web --> Flyway
    Tests --> Flyway
```

## Goals

- 以一份 `V1__schema.sql` 定义所有应用 durable 表、约束、索引、触发器和
  默认 `system_setting` 行。
- 以 profile seed 分离 dev、e2e、canvas-test 的可重复数据和运行开关。
- 让生产 Web、Platform/Harness/Canvas 集成测试使用同一 PostgreSQL 形状。
- 由架构测试保证 baseline 唯一、Flyway scope 正确且不存在竞争初始化路径。

## Non-goals

- 不提供 Java domain、DAO、repository、配置读取器或 SQL migration service。
- 不在 schema 中保存 S3 文件字节；Blob 表只保存 hash、媒体事实、引用计数和
  生命周期。
- 不用第二份 SQL mirror、Spring SQL init 或容器 entrypoint 初始化业务表。
- 不把 profile seed 当作业务逻辑；运行时校验和写入由 Web/Platform/Infra 负责。

## 依赖边界

`schema/pom.xml` 无 `<dependencies>`、无 Java source。资源入口固定为：

```text
schema/src/main/resources/db/migration/V1__schema.sql
schema/src/main/resources/db/seed/dev/R__dev_seed.sql
schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql
schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql
```

Web 依赖 `spring-boot-starter-flyway`、`flyway-database-postgresql` 以及 runtime scope 的
`kk-studio-schema`；Platform、Harness Infra、Canvas Infra 在测试中
以 test scope 依赖 schema 与 Flyway。Schema 不反向依赖任何模块。

## 核心模型与 API

### V1 baseline

`V1__schema.sql` 当前按以下边界组织：

| 区域 | durable 事实 |
| --- | --- |
| Catalog / Chat / Canvas | `agent_provider`、`agent_model`、`agent_definition`、`comfyui_workflow_api`、`chat`、`canvas_document`、`canvas_group`、`canvas_node`、`canvas_link`、`canvas_resource`、`canvas_function_run`、`canvas_function_resource_pin`、`canvas_command_dedup` |
| Settings | 单行 `system_setting(id=1)`、JSONB `config`、CAS `version` |
| Harness | `harness_session`、`harness_entry`、`harness_thread`、`harness_thread_command`、`harness_model_invocation`、`harness_tool_invocation`、`harness_work` |
| Owner relation | `chat_session`、`canvas_session` |
| Global Storage | `storage_blob`、`storage_upload`、`session_blob_ref` |

重要约束包括：

- Harness Entry 的 ROOT 唯一、parent shape 和十种 `entry_type` 由数据库 check/
  partial unique index 固定。
- Thread head 必须属于同一 Session；`next_command_sequence >= 1`，
  `version >= 0`。
- `harness_thread_command` 以 `(thread_id, sequence)` 和
  `(thread_id, idempotency_key)` 保证顺序与 exact replay。
- `harness_work` 以 `(target_type, target_id)` 唯一表示 THREAD/MODEL/TOOL 的
  可调度事实，`wake_version` 为正数，lease token 与 lease until 成对存在。
- Canvas ownership foreign key、owner relation、Blob 引用全部使用
  `ON DELETE RESTRICT`；引用释放由应用事务显式完成。
- `canvas_document.version` 是 Canvas graph、Patch、Snapshot 和 version event
  共用的单调坐标；`canvas_function_run` 的状态与 lease 组合由 check constraint
  固定。
- `storage_blob` 的 ACTIVE hash 唯一；`ACTIVE` 必须有正引用，`DELETING` 的
  `ref_count` 必须为零。

### Profile seeds

| profile | 资源 | 内容 |
| --- | --- | --- |
| dev | `db/seed/dev/R__dev_seed.sql` | stub Provider/Model/Agent |
| e2e | `db/seed/e2e/R__e2e_seed.sql` | Provider、Model、default Agent 与权限设置 |
| canvas-test | `db/seed/canvas-test/R__canvas_test_seed.sql` | S3、Canvas Function integration 的测试开关与时间预算 |

baseline 直接插入 `system_setting` 默认聚合；profile seed 只覆盖其 profile
需要的事实，并使用明确的 SQL 条件保证重复执行结果稳定。

## 主流程

```text
Flyway locations
  -> classpath:db/migration
  -> V1__schema.sql 建表、约束、索引、trigger、默认 system_setting
  -> classpath:db/seed/{dev|e2e|canvas-test}
  -> profile seed 写入可选 Provider/Agent/Settings
  -> Web / Platform / Infra 使用同一 PostgreSQL
```

本地 Web 运行加载 baseline 与 dev seed。E2E 入口
`scripts/e2e.sh` 设置 `SPRING_FLYWAY_LOCATIONS`，加载 e2e 与 canvas-test
seed。Testcontainers 测试在每个测试隔离数据库后重新执行 Flyway。

## 不变量与失败恢复

- 任何 SQL schema drift 都必须在 V1 baseline 上显式失败；baseline 不使用
  `IF NOT EXISTS` 隐藏形状错误。
- DDL、seed、业务写入和 trigger 的可见性服从 PostgreSQL transaction。事务
  rollback 不产生可观察的 Work/Canvas Function NOTIFY，也不暴露半成品行。
- `harness_thread_version`、`canvas_version`、`canvas_function_work` 和
  `system_settings_changed` 是提交后的提示，不保存通知记录。消费方分别通过
  Harness/Canvas poll、Snapshot 或 Settings resync 恢复。
- `ON DELETE RESTRICT` 保留跨域引用错误，让应用按 Session Blob ref、Canvas
  pins/Resource、Harness facts 的顺序完成删除。
- `storage_blob` 的字节清理不由 DDL cascade 完成；元数据状态与 S3 object
  操作由 Platform maintenance 以 lease/token 保护。

## 配置与 profile

Flyway 位置由 Web runtime、测试 application 配置和
`scripts/e2e.sh` 选择；业务模块不自行复制 baseline。当前可用资源路径只有
`db/migration`、`db/seed/dev`、`db/seed/e2e`、`db/seed/canvas-test`。

## 测试与源码入口

源码入口：

- `schema/pom.xml`
- `schema/src/main/resources/db/migration/V1__schema.sql`
- `schema/src/main/resources/db/seed/dev/R__dev_seed.sql`
- `schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql`
- `schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql`

架构与集成测试：

- `web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/FlywayAutoConfigurationIntegrationTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlSchemaStructureTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlBusinessSchemaTest.java`
- `canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresCanvasInfraTestSupport.java`

---

上级：[系统设计](../system-design.md)。相关文档：[Share](share.md)、
[Canvas Infra](canvas-infra.md)、[Harness Infra](harness-infra.md)。
