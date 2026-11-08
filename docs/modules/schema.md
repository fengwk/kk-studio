# Schema 模块

[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)
是当前 PostgreSQL 表、列、外键、索引与 CHECK 的权威声明，不定义通知触发器。
Web 和集成测试使用同一份 Flyway 资源。模块提供 SQL 与契约测试，
依赖 scope 见 [`schema/pom.xml`](../../schema/pom.xml)。

## 加载 baseline 与 seed

versioned migration 为 V1，repeatable seed 按 profile 加载：

| profile | Flyway 资源 |
| --- | --- |
| prod、dev | `classpath:db/migration` |
| e2e | migration + [e2e seed](../../schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql) |
| canvas-test | migration + [canvas-test seed](../../schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql) |

运行时 profile 不预置 Catalog：新库从空 Provider 开始，由 Studio 引导用户添加 Provider、
模型与 Agent。e2e seed 提供模型目录与受控审批规则，canvas-test seed 提供测试栈设置。
[fixture seed](../../schema/src/main/resources/db/seed/fixture/R__fixture_seed.sql)
只供集成测试显式加载，提供离线 stub Catalog。seed 重复执行保持确定性。
V1 的 `system_setting` seed 行是默认聚合的唯一事实源；测试用 `SystemSettingsTestFixture` 校验 codec 与数据库 seed 一致。

## 数据区域与维护入口

| V1 分段 | 表与事实 | 语义事实源 |
| --- | --- | --- |
| 1 共享基础 | storage_blob、storage_upload、storage_object_cleanup：全局内容寻址、上传契约与对象删除墓碑 | [Storage](platform.md#storageblob-与-resource) |
| 2 平台内核存储 | environment、environment_connection、cluster_node、system_setting；harness_session/entry/thread/thread_command/realtime_subscription/tool_invocation/execution_exit/thread_join/thread_stop_receipt；durable_work；session_blob_ref、session_file | [Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Durable Work](work.md)、[Storage](platform.md#storageblob-与-resource) |
| 3 平台 Driver 存储 | provider、model（MODEL/GROUP 共用名称空间）、model_group_cooldown、agent、skill_package、skill_package_git_source；plugin_configuration、plugin_credential、plugin_auth_attempt；mcp_server、mcp_tool；builtin_model_invocation | [Platform Catalog](platform.md#catalog)、[Plugin 配置存储](platform.md#plugin-配置存储)、[Platform MCP](platform.md#mcp-server-与运行时工具目录) |
| 4 产品 | Canvas：canvas_document/group/node/resource/function_run/function_resource_pin/command_dedup；Chat：chat、chat_session；Project：project、project_issue 与 agent_thread/stage_budget/run/activity/evidence | [Canvas](canvas.md)、[Project](project.md) |

```text
storage_blob
  <-- storage_upload
  <-- session_blob_ref >-- harness_session
  <-- session_file >-- harness_session
  <-- skill_package
  <-- canvas_resource
  <-- project_issue_evidence

environment --< environment_connection
cluster_node
system_setting

harness_session
  +--< harness_entry
  +--< harness_thread --< realtime_subscription
  |       +--< harness_thread_command
  |       +--< harness_thread_join
  |       +--< harness_thread_stop_receipt >-- harness_entry
  |       +--< harness_execution_exit >-- environment
  |       +--< harness_tool_invocation
  |       +--< builtin_model_invocation >-- harness_entry
  +--< session_blob_ref / session_file

canvas_document
  +--< canvas_group
  +--< canvas_node --? canvas_group
  |       +--< canvas_resource >--? storage_blob
  |       +--0..1 canvas_function_run
  |                   +--< canvas_function_resource_pin >-- canvas_resource
  +--< canvas_command_dedup

chat --< chat_session >-- harness_session

project
  +--< project_issue
          +--< project_issue_agent_thread >-- harness_thread
          +--< project_issue_stage_budget
          +--< project_issue_run >-- harness_thread / project_issue_stage_budget / project_issue_agent_thread
          +--< project_issue_activity --? project_issue_run
          +--< project_issue_evidence >-- storage_blob / project_issue_run
```

`durable_work` 通过 `(target_type, target_id)` 表示各模块的调度目标，目标 ID 不设到具体业务表的外键；
可选的 `required_environment_id` 引用 Environment。
Canvas Resource 与 pin 使用同画布外键，Run 保存 requestId，调度与租约保存在 `durable_work`；Project Run 通过
复合外键关联同 Issue 的 Thread 和同阶段预算，Entry 坐标是应用维护的历史引用。
父链区间、权限、workflow 合法性和输出完整性由应用验证。表、列的准确清单与约束名直接查 V1。
约束行为由 schema 模块的 fresh-install 探针在真实 PostgreSQL 上逐条验证。
V1 的 Platform Driver 分段声明 `builtin_model_invocation`；`durable_work.target_type` 只校验
`^[A-Z][A-Z0-9_]{0,15}$` 的格式，不枚举各模块的类型常量。

## 类型与命名

- 主键、外键和业务 ID 使用 `uuid`。通用创建/更新时间列命名为 `created_at` / `updated_at`，连接心跳使用
  `last_seen_at`；业务时点列（如 `expires_at`、`registered_at`、`abandoned_at`）保留语义名。所有时间列均为 `timestamptz(3)`。
- 乐观锁统一 `bigint version`；`plugin_configuration.version` 与 `plugin_auth_attempt.config_version` 是配置 CAS 的同一代次。
- 租约令牌统一 `uuid`：`durable_work.lease_token`、`plugin_credential.refresh_lease_token`、`harness_execution_exit.owner_lease_token`、`storage_upload.cleanup_token`。
- 结构化载荷使用 `jsonb`；需要保留原始文本预算的 Plugin 配置使用严格 JSON object 的 `text`。

## 数据库兜底的不变量

实体 UUID 和业务版本由应用生成。复合外键保持 Entry 父链、Thread head、
Run 区间和节点资源属于同一聚合；CHECK 约束防御形状，业务授权与转换由 service 决定。
Agent、Environment 等目录名称的 canonical 化只在应用写入路径完成，数据库只兜底长度、首尾空白与 `/` 等形状约束。
所有外键为即时校验，删除依赖由应用按明确顺序处理；MCP 工具随所属 server 级联删除。

Plugin 配置每个 pluginId 一行，`version` 为正并以 CAS 推进。`values_json` 是 text：
数据库要求严格 JSON object、对象键唯一及最多 65,536 UTF-8 字节。应用按 Schema 校验并补默认值，
存储规范化的紧凑文本，同时逐值保留大整数、精确小数与超范围指数。秘密只在 `plugin_credential.payload_json` 中，
其每次写入推进 `version`。`plugin_auth_attempt` 只保存 nonce 摘要与 prepare 时的 `config_version`。

Canvas Resource 的 blob/text 恰好一个非空，owner/index 成对且槽位唯一。
Run 以 node_id 保存当前/最后执行，数据库约束状态枚举；状态与错误字段的组合由应用校验，
调度与租约由 `durable_work` 持有；
INPUT/OUTPUT pin 保护执行资源生命周期。UNKNOWN 保留资源并等待核查，具体恢复见 Canvas Infra。
引用连线从 Function args 投影。

Issue 同时保持工作阶段、控制暂停和 Run 执行状态；同 Issue 只允许一个活动主 Run。
阶段预算按 Issue/state，Work 为每 Issue 一个 durable 邮箱。
`harness_entry.entry_seq` 是数据库分配的追加序号（identity），同一 Session 内严格递增且父先于子，只用于创建序读取与 keyset 分页，不是业务身份。Harness 命令按 sequence 与 idempotencyKey 唯一，Thread 持有 `execution_control`（`RUNNABLE`/`STOPPED`）与 `input_through_sequence` 输入水位；`harness_execution_exit` 每个具体 Driver/Tool/Remote 代次一行，以不可变 identity（thread/invocation/attempt/lease/kind/parent）关联，保存 `UNCONFIRMED`、三类真实退出之一或显式 `ABANDONED`，不随 Invocation 删除。`ABANDONED` 只带 `abandoned_at`，真实证据只带 `confirmed_at`；真实证据替换放弃时保留原 `abandoned_at`，两种时间都不得早于 `registered_at`，但彼此没有先后约束。仅 DRIVER/TOOL 可确认 `STUDIO_EXITED`，仅 REMOTE 可确认 `REMOTE_ACKNOWLEDGED`；REMOTE 代次必须携带 `environment_id`，DRIVER/TOOL 该列必须为 NULL。部分索引 `idx_harness_execution_exit_unconfirmed_remote` 支持按环境查询 `UNCONFIRMED` 远端代次。远端代次必须挂同 Thread/invocation/attempt/lease 的 TOOL 或 DRIVER 父代。Join 固定 `terminal_entry_id`/`final_answer_entry_id` 回执与交付坐标；Stop 在 `harness_thread_stop_receipt` 按 `(thread_id, stop_request_id)` 保存逐 Thread 回执；`root_stop_request_id` 归组同一次根停止，取消计数与人工输入由命令表派生。
同一 Session 的执行根（`parent_thread_id IS NULL`）按规范化后的名称精确唯一，部分唯一索引 `uk_harness_thread_root_name` 裁决并发创建与重命名；冲突返回 HTTP 409、reason `THREAD_NAME_CONFLICT`。跨 Session 或子代理同名不受此约束，精确创建重放仍返回已有身份，不再次插入。
`agent.execution` 为可空 JSONB：USER 必填，BUILTIN 可为 null；非空对象恰含
`driver` 和对象类型的 `settings`。具体 settings、注册 Driver 和模型引用由应用校验，
Builtin 模型引用通过按 driver 过滤的索引与同事务行锁保护，不依赖数据库外键。
Work lease 成对，环境亲和性由各模块声明。Fill First 模型组的有序成员保存在 `model.group_config`，成员 Variant、立即降级状态码与
固定冷却秒数由应用 codec 校验；`model_group_cooldown` 只保存 `(组, 成员模型, 成员 Variant)`
的冷却截止时间和写入时的组版本。组配置变更使旧版本冷却不再参与选择。组本身没有价格列，
费用按实际执行的单模型配置读取。Harness 的 Entry `payload` jsonb 保存 assistant 用量（`stopReason` / `usage` / 可选 `decodeDurationMillis`）与压缩摘要等事实，schema 不设金额或价格列；费用由应用在读取时按当前 catalog 现算。
`environment.daemon_config` 必须是 JSON object；数据库只兜底这一形状，字段结构与取值由
应用 codec 在读写时严格校验（`studioUrl` 必填，`bashExecutable`、`lsp`、`proxyUrl` 可空），
非法数据在读取时明确拒绝。卡片备注保存在独立的 `note` 列，最多 512 字符，空值存为 NULL。
`environment.install_code_hash` 与 `install_code_expires_at` 成对出现（CHECK 约束同时为 NULL 或同时非空）：
前者只保存随机一次性安装 code 的 SHA-256 摘要，后者保存数据库时间下的 UTC 失效点（固定五分钟）。
签发按版本 CAS 覆盖旧值且不推进版本；兑换要求摘要匹配、严格未过期且目标 OS 合法，成功后原子清空两列、
轮换注册凭证摘要并推进版本；修改 `daemon_config` 或注册凭证的任何 UPDATE 在同一语句中清空它们，
只修改 `note` 不清空。该摘要不参与配置导出，不保存 code 明文。
`environment_connection.ready_metadata` 保存最近一次被接受的 READY `{daemonVersion, hostInfo}`
（hostInfo 只含 OS、架构、时区、用户名、HOME、临时目录），断线保留。
Environment 的当前运行状态与更新进度不入库：`appliedVersion`、`proxyUrl`、配置错误与更新进度只存在于
Daemon 内存与 Server 当前控制代际的缓存，不持久化更新历史与历史查询。

ACTIVE Blob 必须有正引用，DELETING 必须零引用，ACTIVE 内容以 hash/size 部分唯一。
PENDING upload 的 candidate id 在 complete 创建 Blob 后才绑定真实外键。
对象清理记录与最后数据库事实消失同事务登记、永久保留，后台重复删除使迟到对象写入最终收敛；
存储写入本身的阻断仍属于对象存储能力边界。

通知只作提交后回读/唤醒提示。应用推进 Thread/Canvas 版本与 Project/Issue 事实，并由该写路径在同一事务内对数据库连接执行内建 `pg_notify`（提交后才投递，回滚静默）。
Plugin 配置与凭据写入也在同一事务发出 `plugin_changed`。
执行树与工具交互提示（`harness_thread_tree`、`harness_tool_interaction`）的 payload 定位真实执行根；环境失效提示 `environment_changed` 的 payload 是 Environment ID。它们同样由写入口发布，通知本身不推进、也不伪造任何 Thread version。恢复依赖权威行、poll 和租约，而非通知保存。

## 修改结构

修改 V1 后同步领域校验、mapper、结构测试与新库安装测试。
新环境部署由当前 Schema 初始化空库。
共享环境的 Review、维护窗口、备份保密与恢复步骤以
[共享数据库重建](../operations/development-and-testing.md#共享数据库重建) 为准。
本模块文档描述最终结构，操作指南负责数据库变更流程。
需要重建时，通过产品 YAML 同步保留配置，在空库应用当前 Schema 后导入；
UUID、行版本、连接和执行状态不随配置文件恢复，完整运行数据恢复依赖数据库与对象存储备份。
产品 YAML 不包含 Plugin 认证秘密；秘密保留与恢复需要数据库备份，不能只导出 YAML 后清空数据库。

新增 seed 数据放入对应 repeatable SQL，保持幂等并使用公开占位值。
并发或旁路写入可违反的不变量用 CHECK/unique/FK 兜底，策略校验保留在应用层。

## 测试入口

[`FreshInstallSchemaContractTest`](../../schema/src/test/java/fun/fengwk/kkstudio/schema/FreshInstallSchemaContractTest.java)
在隔离 PostgreSQL 执行 baseline，检查快照与 SQL 探针。
[Platform schema 测试](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql)
覆盖精确表/列/外键/索引、非法事实、Blob 引用、删除语义和 seed 幂等。
命令和数据库测试基座见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Web](web.md)、
[Canvas](canvas.md)、[Project](project.md)。
