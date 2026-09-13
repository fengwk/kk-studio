# Schema 模块

## 定位

`schema` 是唯一的 V1 baseline、增量 migration 与 profile seed 纯资源模块。它不包含 Java
源码、Spring bean 或运行时 repository；Flyway 从该模块加载 PostgreSQL DDL
和 profile 数据，应用与集成测试共享同一资源事实源。

```mermaid
flowchart LR
    Schema[schema resources]
    V1[V1__schema.sql<br/>baseline]
    Migrations[V2+ incremental migrations]
    Seeds[db/seed/{dev,e2e,canvas-test}]
    Flyway[Flyway]
    PG[(PostgreSQL)]
    Web[web runtime]
    Tests[platform / harness-infra / canvas-infra tests]

    Schema --> V1
    Schema --> Migrations
    Schema --> Seeds
    V1 --> Flyway
    Migrations --> Flyway
    Seeds --> Flyway
    Flyway --> PG
    Web --> Flyway
    Tests --> Flyway
```

## Goals

- 以冻结的 `V1__schema.sql` 建立基础 durable 结构，并只通过有序增量 migration
  推进当前 schema。
- 以 profile seed 分离 dev、e2e、canvas-test 的可重复数据和运行开关。
- 让生产 Web、Platform/Harness/Canvas 集成测试使用同一 PostgreSQL 形状。
- 由架构测试保证 V1 冻结、增量 migration 命名规范、profile seed 清单受限、
  Flyway scope 正确且不存在竞争初始化路径。

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
schema/src/main/resources/db/migration/V2__model_identity_and_variant.sql
schema/src/main/resources/db/migration/V3__remove_workspace.sql
schema/src/main/resources/db/migration/V4__skill_sources_and_operations.sql
schema/src/main/resources/db/migration/V5__mcp_json_and_local.sql
schema/src/main/resources/db/migration/V6__cloud_file_system.sql
schema/src/main/resources/db/migration/V7__project_issue.sql
schema/src/main/resources/db/migration/V8__project_change_notifications.sql
schema/src/main/resources/db/seed/dev/R__dev_seed.sql
schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql
schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql
```

已经应用到共享 database 的 V1 保持冻结：架构测试以 SHA-256 固定它的内容，任何
schema 变更只能新增名字规范（`V<version>__<description>.sql`）、版本唯一且不早于
V2 的增量 migration。profile seed 只允许上表三份 repeatable 资源。

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
- Thread head 必须属于同一 Session；`name` 经应用写路径规范化（非空、单行、至多 256 个
  Unicode 码点），数据库 check 只防御最粗的空白串（`btrim(name) <> ''`）；
  `next_command_sequence >= 1`，`version >= 0`。
- `harness_session.name` 同样只做 `btrim(name) <> ''` 的粗防线（完整规范化在应用写路径）；Session 无 version 行，显示名称重命名经应用锁内 `updateSession` 原语完成。
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

### Incremental migrations

- V2 分离模型逻辑身份与 Provider wire `modelId`，并把 Variant 收敛为推理强度选择。
- V3 删除产品 Workspace 状态、目录查询信箱和 `SET_ENVIRONMENT`，把 Harness
  durable JSON 中的环境绑定收敛为直接 `EnvironmentId`。迁移在存在活动
  Invocation 或旧 JSON 形状损坏时 fail-fast，并依赖 Flyway 事务整体回滚。
- V4 把 Skill 事实从“Daemon READY 的瞬时上报”推进为 Platform 的持久权威状态，并引入面向通用资源目标的管理操作表：
  `environment_inventory` 每个 Environment 一行，保存期望来源集合代际
  (`source_set_version`) 与最近一次被围栏接受的 READY 报告；
  `environment_skill_source` 是 Platform 唯一的来源配置，`version` 同时是行级
  CAS 令牌与 Daemon `sourceVersion`；`environment_skill` 只保存每个来源最新一次
  成功扫描的 inventory（正文永不入库）；`environment_operation` 是跨节点管理
  信箱与不可变历史，使用 `resource_type`（`SKILL_SOURCE` 或 `MCP_SERVER`）、`resource_id`
  与 `resource_version` 作为通用目标，通过配对约束与唯一索引 `uk_environment_operation_active`
  保证同一 `(environment_id, resource_type, resource_id)` 目标同时至多一个 PENDING/RUNNING 操作，
  `resource_id` 故意不建 FK 以保留来源删除后的审计历史。
  迁移在任何 mutation 之前 fail-fast：存在活动 Invocation、`agent_definition.config`
  缺失/畸形/非空 `skills`、或冻结 `request_spec.skillBindings` 缺失/畸形/非空时
  整体回滚。回填对每个既有 Environment 生成一行 inventory 与一个 id 由
  `md5(environment_id::text || ':default-skill-source')::uuid` 派生的缺省 PATH
  来源（`~/.agents/skills`、`version = 0`、`UNAPPLIED`），不依赖扩展、随机数或序列。
- V5 重构 `mcp_server` 配置并引入 Local MCP 支持：移除旧的扁平 `url` 与 `bearer_token`，将连接参数收敛为
  `connection_type`（`REMOTE` 或 `LOCAL`）、`environment_id`（`LOCAL` 必填且受外键 restrict 保护，`REMOTE` 必须为 null）、
  `connection_config`（JSONB 传输配置，Remote 含 `url/headers`，Local 含 `command/cwd/env`）、
  `enabled`（公共启用开关）、`discovery_status`（`UNVERIFIED`、`AVAILABLE`、`FAILED`）、
  `discovered_version`（成功发现的配置代际，`<= version`）；为 `mcp_tool` 增加 `schema_revision`（模式代际，非负，
  模式变更或重新上线时递增）与 `available`（可用性标志，远端工具下线时作为 tombstone 置为 false，保留稳定 UUID 与历史引用）。
- V6 引入全局 Cloud File System：`cloud_node` 以虚拟 root 和
  `(parent_id, name) NULLS NOT DISTINCT` 唯一约束表达 `DIRECTORY`、`TEXT`、`BLOB`
  层级；复合外键保证 parent 必为目录，BLOB 以 `ON DELETE RESTRICT` 引用
  `storage_blob`。`cloud_text_revision` 保存最大 1 MiB 的不可变 UTF-8 历史，
  每个 TEXT 节点至多一条 current revision。迁移预建 `/knowledge`、`/uploads`、
  `/.artifacts`、`/.artifacts/tool-results`，并由 `cloud_files_changed_notify()`
  在 `cloud_node` 提交变更后向 `cloud_files_changed` 发送失效提示。
- V7 引入 Project/Issue 编排事实：`project`、`project_session`、`issue`、
  `issue_dependency`、`issue_input`、`issue_run`、`issue_run_session` 与
  `issue_controller_work`。Issue 使用六态生命周期、项目内单调编号、行
  `version`、`spec_revision` 与 `input_sequence`；依赖边通过复合外键限制在
  同一 Project，应用层负责 DAG 环检测。Run 表以单活跃 partial unique index、
  actor/role/lifecycle checks、终态 action 唯一键和 continuation/deadline 围栏
  固定执行事实；`issue_controller_work_due` 只提示 dispatcher 回读。
  `harness_session_owner_guard` 与四组触发器让 Chat、Canvas、Project 和 IssueRun
  的 Session 归属全局互斥，已有 Chat/Canvas 归属在 migration 内回填。
- V8 为浏览器 Project Snapshot 失效增加 `project_issue_changed_notify()`：
  Project、Project Session、Issue、依赖、输入、Run、Run Session 与其 Harness
  Thread 的提交变更统一归一为 `projectId`，通过 `project_issue_changed` 通道提示
  回读；payload 不是事件日志。

### Profile seeds

| profile | 资源 | 内容 |
| --- | --- | --- |
| dev | `db/seed/dev/R__dev_seed.sql` | stub Provider/Model/Agent |
| e2e | `db/seed/e2e/R__e2e_seed.sql` | Provider、Model、default Agent、权限设置与 e2e Environment 的 inventory/缺省来源 |
| canvas-test | `db/seed/canvas-test/R__canvas_test_seed.sql` | S3、Canvas Function integration 的测试开关与时间预算 |

baseline 直接插入 `system_setting` 默认聚合；profile seed 只覆盖其 profile
需要的事实，并使用明确的 SQL 条件保证重复执行结果稳定。e2e Environment 在 V4
之后才被 seed 插入，因此它们不会由 V4 回填获得 inventory/缺省来源；`R__e2e_seed.sql`
按与 V4 完全相同的确定性规则补齐，且只建立缺失行：同一确定性 ID 的已有来源行
绝不被覆盖，已经存在其它缺省来源时也不插入第二个。

## 主流程

```text
Flyway locations
  -> classpath:db/migration
  -> V1__schema.sql 建表、约束、索引、trigger、默认 system_setting
  -> V2+ 增量 migration（如有）
  -> classpath:db/seed/{dev|e2e|canvas-test}
  -> profile seed 写入可选 Provider/Agent/Settings
  -> Web / Platform / Infra 使用同一 PostgreSQL
```

本地 Web 运行加载 baseline 与 dev seed。E2E 入口
`scripts/e2e.sh` 设置 `SPRING_FLYWAY_LOCATIONS`，加载 e2e 与 canvas-test
seed。NAS `prod` profile 只加载 `classpath:db/migration`：Main 节点是共享 schema
的唯一 Flyway owner，Dev 节点以 `SPRING_FLYWAY_ENABLED=false` 复用同一 profile。
Testcontainers 测试在每个测试隔离数据库后重新执行 Flyway。

## 不变量与失败恢复

- 任何 SQL schema drift 都必须在 V1 baseline 上显式失败；baseline 不使用
  `IF NOT EXISTS` 隐藏形状错误。
- DDL、seed、业务写入和 trigger 的可见性服从 PostgreSQL transaction。事务
  rollback 不产生可观察的 Work/Canvas Function NOTIFY，也不暴露半成品行。
- `harness_thread_version`、`canvas_version`、`canvas_function_work`、
  `issue_controller_work_due`、`cloud_files_changed`、`project_issue_changed` 和
  `system_settings_changed` 都只是提交后的提示，不保存通知记录。消费方分别通过
  dispatcher、Harness/Canvas/Project/Cloud Files Snapshot 或 Settings resync 恢复。
- `ON DELETE RESTRICT` 保留跨域引用错误，让应用按 Session Blob ref、Canvas
  pins/Resource、Harness facts 的顺序完成删除。
- `storage_blob` 的字节清理不由 DDL cascade 完成；元数据状态与 S3 object
  操作由 Platform maintenance 以 lease/token 保护。

## 配置与 profile

Flyway 位置由 Web runtime、测试 application 配置和
`scripts/e2e.sh` 选择；业务模块不自行复制 baseline。当前可用资源路径只有
`db/migration`、`db/seed/dev`、`db/seed/e2e`、`db/seed/canvas-test`，其中
`application-prod.yml` 只启用 `classpath:db/migration`，因此生产启动不会加载任何
profile seed，也没有生产凭据默认值。

## 测试与源码入口

源码入口：

- `schema/pom.xml`
- `schema/src/main/resources/db/migration/V1__schema.sql`
- `schema/src/main/resources/db/migration/V2__model_identity_and_variant.sql`
- `schema/src/main/resources/db/migration/V3__remove_workspace.sql`
- `schema/src/main/resources/db/migration/V4__skill_sources_and_operations.sql`
- `schema/src/main/resources/db/migration/V5__mcp_json_and_local.sql`
- `schema/src/main/resources/db/migration/V6__cloud_file_system.sql`
- `schema/src/main/resources/db/migration/V7__project_issue.sql`
- `schema/src/main/resources/db/migration/V8__project_change_notifications.sql`
- `schema/src/main/resources/db/seed/dev/R__dev_seed.sql`
- `schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql`
- `schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql`

架构与集成测试：

- `web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java`
- `web/src/test/java/fun/fengwk/kkstudio/web/FlywayAutoConfigurationIntegrationTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlSchemaStructureTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlWorkspaceRemovalMigrationTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlSkillSourceMigrationTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlMcpMigrationTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/PostgresqlBusinessSchemaTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/cloudfs/repository/CloudFileSystemSchemaIntegrationTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/project/ProjectSchemaPostgresTest.java`
- `platform/src/test/java/fun/fengwk/kkstudio/platform/project/ProjectChangeNotificationIntegrationTest.java`
- `canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresCanvasInfraTestSupport.java`

---

上级：[系统设计](../system-design.md)。相关文档：[Share](share.md)、
[Canvas Infra](canvas-infra.md)、[Harness Infra](harness-infra.md)。
