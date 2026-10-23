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
V1 写入单行 SystemSettings 默认聚合；Java 解码与 profile 默认契约由测试校验。

## 数据区域与维护入口

| 区域 | 表与事实 | 语义事实源 |
| --- | --- | --- |
| Catalog | agent_provider、agent_model（MODEL/GROUP 共用名称空间）、agent_model_group_cooldown、agent_definition、skill_package | [Platform Catalog](platform.md#catalog) |
| Plugin | plugin_configuration：每个 Plugin 的当前普通配置与修订；plugin_credential：凭据载荷、状态、refresh lease；plugin_auth_attempt：待完成的交互式认证 | [Plugin 配置存储](platform.md#plugin-配置存储)、[Plugin credential](platform.md#plugin-credential) |
| MCP | mcp_server、mcp_tool：配置与原子发现快照 | [Platform MCP](platform.md#mcp-server-与运行时工具目录) |
| Environment | environment、environment_connection：注册与环境路由租约、保存的安装设置（`install_config`）、宿主/Skill 投影；cluster_node：App 节点内部 IDE 路由 origin 与心跳租约 | [Environment](harness-environment.md) |
| Chat | chat、chat_session：owner 关系 | [Platform](platform.md#命令接受产品归属与派发) |
| Canvas | canvas_document/group/node/resource、function_run、command_dedup、function_resource_pin | [Canvas](canvas.md) |
| Project | project、project_issue 与 agent_thread/stage_budget/run/activity/evidence | [Project](project.md) |
| Harness | harness_session/entry/thread/thread_command/model_invocation/tool_invocation/thread_join/thread_stop_receipt | [Harness Runtime](harness-runtime.md)、[Harness Infra](harness-infra.md) |
| Durable Work | durable_work：全部后台调度（THREAD/MODEL/TOOL/ISSUE/CANVAS_RUN）共用的 mailbox | [Durable Work](work.md) |
| Storage | storage_blob/upload/object_cleanup、session_blob_ref | [Storage](platform.md#storageblob-与-resource) |
| Settings | system_setting，`id = 1` | [配置](platform.md#配置) |

表、列的准确清单与约束名直接查 V1；跨域关系与事务职责见 [Canvas / Project](../canvas-project.md)。
约束行为由 schema 模块的 fresh-install 探针在真实 PostgreSQL 上逐条验证。

## 数据库兜底的不变量

实体 UUID 和业务版本由应用生成。复合外键保持 Entry 父链、Thread head、
Run 区间和节点资源属于同一聚合；CHECK 约束防御形状，业务授权与转换由 service 决定。
所有外键为即时校验，删除依赖由应用按明确顺序处理；MCP 工具随所属 server 级联删除。

Plugin 配置每个 pluginId 一行，`revision` 为正并以 CAS 推进。`values_json` 是 text：
数据库要求严格 JSON object、每层对象键唯一及最多 65,536 UTF-8 字节，
避免 jsonb 数值展开改变普通值的字节预算和恢复形态。秘密只在 `plugin_credential.payload_json` 中，
其每次写入推进 `version`。`plugin_auth_attempt` 只保存 nonce 摘要与 prepare 时的配置修订。

Canvas Resource 的 blob/text 恰好一个非空，owner/index 成对且槽位唯一。
Run 以 node_id 保存当前/最后执行，状态与 lease/available/error 字段组合匹配；
INPUT/OUTPUT pin 保护执行资源生命周期。UNKNOWN 保留资源并等待核查，具体恢复见 Canvas Infra。
引用连线从 Function args 投影。

Issue 同时保持工作阶段、控制暂停和 Run 执行状态；同 Issue 只允许一个活动主 Run。
阶段预算按 Issue/state，Work 为每 Issue 一个 durable 邮箱。
Harness 命令按 sequence 与 idempotencyKey 唯一，Thread 持有 `execution_control`（`RUNNABLE`/`STOPPED`）与 `input_through_sequence` 输入水位；Join 固定 `terminal_entry_id`/`final_answer_entry_id` 回执与交付坐标；Stop 在 `harness_thread_stop_receipt` 按 `(thread_id, stop_request_id)` 保存逐 Thread 回执。
同一 Session 的执行根（`parent_thread_id IS NULL`）按规范化后的名称精确唯一，部分唯一索引 `uk_harness_thread_root_name` 裁决并发创建与重命名；冲突返回 HTTP 409、reason `THREAD_NAME_CONFLICT`。跨 Session 或子代理同名不受此约束，精确创建重放仍返回已有身份，不再次插入。
Work lease 成对，required_environment_id 非空仅适用于 TOOL。Fill First 模型组的有序成员保存在 `agent_model.group_config`，成员 Variant、立即降级状态码与
固定冷却秒数由应用 codec 校验；`agent_model_group_cooldown` 只保存 `(组, 成员模型, 成员 Variant)`
的冷却截止时间和写入时的组版本。组配置变更使旧版本冷却不再参与选择。组本身没有价格列，
费用按实际执行的单模型配置读取。Harness 的 Entry `payload` jsonb 保存 assistant 用量（`stopReason` / `usage` / 可选 `decodeDurationMillis`）与压缩摘要等事实，schema 不设金额或价格列；费用由应用在读取时按当前 catalog 现算。
`environment.install_config` 只允许 NULL 或 JSON object；数据库只兜底这一形状，字段结构与取值由
应用 codec 在读写时校验，非法的历史行会在读取时失败而不是被宽容读出。
`environment_install_code` 以环境 ID 为主键并级联删除，只保存随机一次性安装 code 的 SHA-256、
签发时的注册凭证摘要及有效期；新签发覆盖旧记录，兑换要求凭证代际一致且严格未过期，并原子删除。
该临时表不参与配置导出，不保存 code 明文。

ACTIVE Blob 必须有正引用，DELETING 必须零引用，ACTIVE 内容以 hash/size 部分唯一。
PENDING upload 的 candidate id 在 complete 创建 Blob 后才绑定真实外键。
对象清理记录与最后数据库事实消失同事务登记、永久保留，后台重复删除使迟到对象写入最终收敛；
存储写入本身的阻断仍属于对象存储能力边界。

通知只作提交后回读/唤醒提示。应用推进 Thread/Canvas 版本与 Project/Issue 事实，并由该写路径在同一事务内对数据库连接执行内建 `pg_notify`（提交后才投递，回滚静默）。
Plugin 配置与凭据写入也在同一事务发出 `plugin_changed`。
执行树与工具交互提示（`harness_thread_tree`、`harness_tool_interaction`）的 payload 定位真实执行根；环境失效提示 `environment_changed` 的 payload 是 Environment ID。它们同样由写入口发布，通知本身不推进、也不伪造任何 Thread version。恢复依赖权威行、poll 和租约，而非通知保存。

## 修改结构

修改 V1 后同步领域校验、mapper、结构测试与新库安装测试。
已有数据库记录 Flyway checksum，源码变更须通过批准的数据库维护操作才能应用；
当前 V1 的插件表结构变化需要空库初始化或批准的迁移与备份，不能用 `flyway repair`
代替结构转换。
共享环境的 Review、维护窗口、备份保密与恢复步骤以
[共享数据库重建](../operations/development-and-testing.md#共享数据库重建) 为准。
本模块文档描述最终结构，操作指南负责数据库变更流程。
需要重建时，通过产品 YAML 同步保留配置，在空库应用当前 Schema 后导入；
UUID、行版本、连接和执行状态不随配置文件恢复，完整运行数据恢复仍依赖数据库与对象存储备份。
当前产品 YAML 不包含 Plugin 配置历史与认证秘密；这部分保留与恢复需要数据库备份
或明确批准的转换方案，不能只导出 YAML 后删除数据库。

新增 seed 数据放入对应 repeatable SQL，保持幂等并使用公开占位值。
并发或旁路写入可违反的不变量用 CHECK/unique/FK 兜底，策略校验保留在应用层。

## 测试入口

[`FreshInstallSchemaContractTest`](../../schema/src/test/java/fun/fengwk/kkstudio/schema/FreshInstallSchemaContractTest.java)
在隔离 PostgreSQL 执行 baseline，检查快照与 SQL 探针。
[Platform schema 测试](../../platform/runtime/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql)
覆盖精确表/列/外键/索引、非法事实、Blob 引用、删除语义和 seed 幂等；
[Web bootstrap 测试](../../web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java)
验证依赖 scope 与 profile 入口。命令和数据库测试基座见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Web](web.md)、
[Canvas](canvas.md)、[Harness Infra](harness-infra.md)、[Project](project.md)。
