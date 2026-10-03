# Schema 模块

[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)
是当前 PostgreSQL 表、列、外键、索引、CHECK 与通知 trigger 的权威声明。
Web 和集成测试使用同一份 Flyway 资源。模块提供 SQL 与契约测试，
依赖 scope 见 [`schema/pom.xml`](../../schema/pom.xml)。

## 加载 baseline 与 seed

versioned migration 为 V1，三个 repeatable seed 按 profile 加载：

| profile | Flyway 资源 |
| --- | --- |
| prod | `classpath:db/migration` |
| dev | migration + [dev seed](../../schema/src/main/resources/db/seed/dev/R__dev_seed.sql) |
| e2e | migration + [e2e seed](../../schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql) |
| canvas-test | migration + dev seed + [canvas-test seed](../../schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql) |

dev seed 提供离线 stub Catalog，e2e seed 提供模型目录与受控审批规则，
canvas-test seed 提供测试栈设置。seed 重复执行保持确定性，凭据从环境注入。
V1 写入单行 SystemSettings 默认聚合；Java 解码与 profile 默认契约由测试校验。

## 数据区域与维护入口

| 区域 | 表与事实 | 语义事实源 |
| --- | --- | --- |
| Catalog | agent_provider/model/definition、skill_package | [Platform Catalog](platform.md#catalog) |
| Plugin | plugin_credential：认证密文、状态、refresh lease | [Plugin credential](platform.md#plugin-credential) |
| MCP | mcp_server、mcp_tool：配置与原子发现快照 | [Platform MCP](platform.md#mcp-server-与运行时工具目录) |
| Environment | environment、environment_connection：注册与路由租约、宿主/Skill 投影 | [Environment](harness-environment.md) |
| Chat | chat、chat_session：owner 关系 | [Platform](platform.md#命令接受产品归属与派发) |
| Canvas | canvas_document/group/node/resource、function_run、command_dedup、function_resource_pin | [Canvas Core](canvas-core.md)、[Canvas Infra](canvas-infra.md) |
| Project | project、project_issue 与 agent_thread/stage_budget/run/activity/work/evidence | [Project](project.md) |
| Harness | harness_session/entry/thread/thread_command/model_invocation/tool_invocation/work/thread_join | [Harness Runtime](harness-runtime.md)、[Harness Infra](harness-infra.md) |
| Storage | storage_blob/upload/object_cleanup、session_blob_ref | [Storage](platform.md#storageblob-与-resource) |
| Settings | system_setting，`id = 1` | [配置](platform.md#配置) |

表、列的准确清单与约束名直接查 V1；跨域关系与事务职责见 [Canvas / Project](../canvas-project.md)。
结构测试分别核对业务表与 Flyway 元数据表，计数口径以测试的 expected set 为准。

## 数据库兜底的不变量

实体 UUID 和业务版本由应用生成。复合外键保持 Entry 父链、Thread head、
Run 区间和节点资源属于同一聚合；CHECK 约束防御形状，业务授权与转换由 service 决定。
所有外键为即时校验，删除依赖由应用按明确顺序处理；MCP 工具随所属 server 级联删除。

Canvas Resource 的 blob/text 恰好一个非空，owner/index 成对且槽位唯一。
Run 以 node_id 保存当前/最后执行，状态与 lease/available/error 字段组合匹配；
INPUT/OUTPUT pin 保护执行资源生命周期。UNKNOWN 保留资源并等待核查，具体恢复见 Canvas Infra。
引用连线从 Function args 投影。

Issue 同时保持工作阶段、控制暂停和 Run 执行状态；同 Issue 只允许一个活动主 Run。
阶段预算按 Issue/state，Work 为每 Issue 一个 durable 邮箱。
Harness 命令按 sequence 与 idempotencyKey 唯一，Join 固定匹配回执与交付坐标。
Work lease 成对，required_environment_id 非空仅适用于 TOOL。

ACTIVE Blob 必须有正引用，DELETING 必须零引用，ACTIVE 内容以 hash/size 部分唯一。
PENDING upload 的 candidate id 在 complete 创建 Blob 后才绑定真实外键。
对象清理记录与最后数据库事实消失同事务登记、永久保留，后台重复删除使迟到对象写入最终收敛；
存储写入本身的阻断仍属于对象存储能力边界。

通知只作提交后回读/唤醒提示。Thread/Canvas 版本由应用推进，数据库 trigger 根据已变化事实发提示；
Project 写库通知由 repository 发送。恢复依赖权威行、poll 和租约，而非通知保存。

## 修改结构

修改 V1 后同步领域校验、mapper、结构测试与新库安装测试。
已有数据库记录 Flyway checksum，源码变更须通过批准的数据库维护操作才能应用；
共享环境的 Review、维护窗口、备份保密与恢复步骤以
[共享数据库重建](../operations/development-and-testing.md#共享数据库重建) 为准。
本模块文档描述最终结构，操作指南负责数据库变更流程。

新增 seed 数据放入对应 repeatable SQL，保持幂等并使用公开占位值。
并发或旁路写入可违反的不变量用 CHECK/unique/FK 兜底，策略校验保留在应用层。

## 测试入口

[`FreshInstallSchemaContractTest`](../../schema/src/test/java/fun/fengwk/kkstudio/schema/FreshInstallSchemaContractTest.java)
在隔离 PostgreSQL 执行 baseline，检查快照与 SQL 探针。
[Platform schema 测试](../../platform/src/test/java/fun/fengwk/kkstudio/platform/harness/persistence/postgresql)
覆盖精确表/列/外键/trigger、非法事实、Blob 引用、删除语义和 seed 幂等；
[Web bootstrap 测试](../../web/src/test/java/fun/fengwk/kkstudio/web/FlywayBootstrapArchitectureTest.java)
验证依赖 scope 与 profile 入口。命令和数据库测试基座见 [开发与测试](../operations/development-and-testing.md)。

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Web](web.md)、
[Canvas Infra](canvas-infra.md)、[Harness Infra](harness-infra.md)、[Project](project.md)。
