# Harness Builtin

Harness 需要一个可直接使用的工具集：读文件、执行命令、查符号、加载 Skill、委派 Subagent、维护当前目标。这些工具如果各自散落在不同模块里注册，就会出现身份不统一、副作用标注不一致、模型可见列表随装配方式漂移的问题。本模块把第一方能力收拢到唯一入口 [`BuiltinHarnessContributor`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)（`ContributorId` 为 `builtin`），通过 [`harness-contributor-api`](harness-contributor-api.md) 的统一 SPI 一次性注册 14 个工具、`goal.state` 自定义条目类型与一个上下文投影器；模型可见的工具集合因此由代码确定，而不是由容器的装配顺序决定。

模块只负责「这些工具做什么」：实现委托、参数与领域校验、以及要追加什么分支状态。校验 ownership、WRITE 声明、effects 数量与原子落库由 [`harness-runtime`](harness-runtime.md) 与 Contributor 目录承担；环境能力的网络传输与子进程执行由 [`harness-environment`](harness-environment.md) 与 [`harness-daemon`](harness-daemon.md) 承担，Skill 正文由 Platform 全局目录直接加载。生产依赖见 [`pom.xml`](../../harness/builtin/pom.xml)，由 [`BuiltinModuleArchitectureTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinModuleArchitectureTest.java) 守卫。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.builtin` | 第一方内置能力根包：唯一的 `BuiltinHarnessContributor` 与完成态句柄 | 集中注册 14 个工具、Goal 自定义类型与投影器；网络传输与持久化调度在外层模块 |
| `fun.fengwk.kkstudio.harness.builtin.environment` | 环境能力工具实现 `EnvironmentCapabilityTool` 与 prompt 模板加载 | 委托执行期注入的 `BoundEnvironment`；传输协议与宿主进程管理由 Daemon 承接 |
| `fun.fengwk.kkstudio.harness.builtin.goal` | Goal 工具（`create_goal`、`get_goal`、`update_goal`）、投影器 `GoalContextProjector`、快照模型 `GoalState` 与确定性编解码器 `GoalStateCodec` | 状态依托通用 `harness_entry` 的 CUSTOM 载荷，通过 `AppendCustomEntry` 由 Runtime 原子追加 |
| `fun.fengwk.kkstudio.harness.builtin.skill` | 内部 Skill 加载工具 `LoadSkillTool`、选中 Skill 元数据 `SelectedSkill`、正文端口 `SkillContentLoader` 与查找契约 `ThreadSelectedSkillLookup` | 只加载当前 Thread Agent 已冻结的 Platform 全局 Skill 三元组，不依赖 Environment |
| `fun.fengwk.kkstudio.harness.builtin.subagent` | 内部委派工具 `TaskTool`、任务请求 `SubagentTaskRequest`、执行端口 `SubagentRunner` 与配置接入 | 只做参数解析与转发；多轮调度、并发上限与持久化状态机由运行时负责 |

## 注册清单

`contribute` 的注册顺序和内容就是内置能力的定义。除 `load_skill` 与 `task` 由 Platform 装配注入（构造期校验 descriptor name 必须是 `load_skill` 与 `task`，防止传反）之外，其余工具都在这里直接实例化：

| localName | 模型可见 name | 依赖 | 可见性 | 说明 |
| --- | --- | --- | --- | --- |
| `environment.read` | `read` | Environment | SELECTABLE | `fs.read`，READ_ONLY |
| `environment.write` | `write` | Environment | SELECTABLE | `fs.write`，IDEMPOTENT |
| `environment.edit` | `edit` | Environment | SELECTABLE | `fs.edit`，NON_IDEMPOTENT |
| `environment.bash` | `bash` | Environment | SELECTABLE | `process.exec`，NON_IDEMPOTENT |
| `environment.grep` | `grep` | Environment | SELECTABLE | `fs.grep`，READ_ONLY |
| `environment.find` | `find` | Environment | SELECTABLE | `fs.find`，READ_ONLY |
| `environment.lsp-goto-definition` | `lsp_goto_definition` | Environment | SELECTABLE | `lsp.goto-definition`，READ_ONLY |
| `environment.lsp-workspace-symbols` | `lsp_workspace_symbols` | Environment | SELECTABLE | `lsp.workspace-symbols`，READ_ONLY |
| `environment.lsp-java-decompile` | `lsp_java_decompile` | Environment | SELECTABLE | `lsp.java-decompile`，READ_ONLY |
| `runtime.load-skill` | `load_skill` | Platform Skill catalog | INTERNAL | 按冻结 package/version/name 加载 Skill 正文 |
| `runtime.task` | `task` | 无 | INTERNAL | 委派 Subagent 任务 |
| `goal.create` | `create_goal` | WRITE(`goal.state`) | SELECTABLE | 创建 Goal 快照 |
| `goal.get` | `get_goal` | READ(`goal.state`) | SELECTABLE | 读取 Goal 快照 |
| `goal.update` | `update_goal` | WRITE(`goal.state`) | SELECTABLE | 结束 Goal |

`localName` 是 contributor 内的 scoped 贡献标识；Agent 侧的唯一身份是模型可见 name，配置、权限键与 catalog 条目都只用它。name 的字面值由 [`BuiltinHarnessContributorTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributorTest.java) 锁定。此外注册 Custom Entry Type `goal.state-type`（customType 为 `goal.state`）与 Context Projector `goal.context`，priority 均为 0。

九个环境工具不由本模块实现能力，而是把 [`EnvironmentCapabilityTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java) 绑定到一个 catalog capability：descriptor 的 `inputSchema` 与 `defaultTimeout` 取自 capability descriptor，name 取模型可见工具名，构造期还会复查 schema 与 defaultTimeout 完全一致；它是唯一覆盖 `resolveTimeout` 的工具族——归一化 arguments 携带正数 `timeout_seconds` 时严格使用该值（可比默认值更短或更长，没有上限），缺省时使用 capability 默认超时，非正数既不是覆盖也不表示无 deadline，而是确定性拒绝的非法请求。这样 capability 契约变化会立刻反映到模型可见的工具定义上，不会出现两处手写描述不同步。九份工具说明来自 [`environment/prompts/`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/environment/prompts/)，入口是 [`EnvironmentPrompts`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentPrompts.java)；资源缺失即 `IllegalStateException`，不会退化成空描述。

## Goal：快照、工具与投影

Goal 不建独立表，它的全部事实是分支作用域的全量替换快照 [`GoalState`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalState.java)，持久化在 [`harness_entry`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 CUSTOM 载荷里：

```text
objective:   non-blank
tokenBudget: positive long or null
status:      active | complete | blocked
reason:      active 必须为 null，终态必须非空
createdAt / updatedAt: 毫秒截断，updatedAt >= createdAt
```

[`GoalStateCodec`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalStateCodec.java) 要求 `schemaVersion = 1`，`dataJson` 精确包含且仅包含这六个字段，未知字段、缺失字段、重复键、尾随 token、非法状态枚举与时间戳格式错误一律拒绝。

三个工具都先经 [`GoalToolSupport`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalToolSupport.java) 做 schema 校验与强类型解析，再通过限定在 `builtin` 作用域的 `BranchView.latestCustomEntry("goal.state")` 读取当前分支快照。它们都要求 durable `ToolExecutionContext`，缺失时直接返回错误结果而不是抛出：

- [`CreateGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/CreateGoalTool.java) 要求非空 `objective` 与可选正整数 `tokenBudget`，写入一条 `ACTIVE` 快照。分支上已有 Goal 时保留原 `createdAt`，因此「重新表述目标」不会伪造新的开始时间。
- [`GetGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GetGoalTool.java) 无入参，只读返回最新快照；不存在时返回明确的提示文本，不产生任何追加意图。
- [`UpdateGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/UpdateGoalTool.java) 要求终态 `status` 与非空 `reason`，并且只在当前快照处于 `ACTIVE` 时生效：目标不存在或已终结都直接拒绝，保持 `objective`、`tokenBudget`、`createdAt`，只更新 `status`、`reason` 与 `updatedAt`。

创建与更新成功后携带且仅携带一个 `AppendCustomEntry("goal.state", 1, …)`，查询与所有错误结果不带任何 effects。真正把条目写进会话树并保证「要么全成功、要么整体回滚」的是 Runtime 的 [`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolOutcomeAppender.java)：本模块只声明意图，不触碰存储。

[`GoalContextProjector`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalContextProjector.java) 只在最新快照为 `ACTIVE` 时，用 `active-goal-context.md` 模板与规范 `goal` 包裹对象生成一条文本片段；快照不存在或已终结则返回空列表。片段由 Platform 在规划下一次模型请求时拼接进单条 `systemInstruction`，因此已完成的目标不会继续占用上下文。

## 历史语义渲染

6 个非 MCP 内建工具都通过 `Tool.historyRenderer()` 暴露历史动作映射：[`BuiltinHistoryRenderers`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderers.java) 覆盖 `load_skill`（skill 名）、`task`（子代理类型，省略 `maxTurns` / `session_id` / 完整 prompt）、3 个 Goal 工具（目标文本或终态与原因），[`EnvironmentCapabilityRenderer`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityRenderer.java) 按 capability 语义生成「动作 + 目标 + workdir 作用域 + 环境名」短语（省略 `timeout_seconds`、`limit`、`offset`、`column_offset` 等执行控制参数；`grep.include`、`edit.replace_all` 与 `grep` 的大小写/字面量/多行标志会改变解释，因此保留）。它们都是确定性纯函数，只读取已归一化的 arguments，无法形成有意义动作时返回 absent，由 Runtime 回退到逐字 arguments 的中性描述。

## Skill 与 Subagent 桥接

[`LoadSkillTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/skill/LoadSkillTool.java) 以 `INTERNAL` 注册，不能被 Agent 手工选择；当 Agent 配置了 Skill 时，Platform 自动把它加入该次模型工具面。它声明 `ToolRequirements.none()`，执行时由 [`ThreadSelectedSkillLookup`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/skill/ThreadSelectedSkillLookup.java) 解析当前 Model invocation 冻结的 [`SelectedSkill`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/skill/SelectedSkill.java)，再经 [`SkillContentLoader`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/skill/SkillContentLoader.java) 按 `(packageName, packageVersion, name)` 精确读取正文。加载不依赖 Environment、workdir 或独立超时，也不会按名称回退到当前新版本。

[`TaskTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java) 同样以 `INTERNAL` 注册，本身不携带环境需求。它把 arguments 解析为 [`SubagentTaskRequest`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskRequest.java) 后交给 [`SubagentRunner`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentRunner.java)：`subagent_type` 与 `prompt` 必填非空白，`maxTurns` 若给出必须为正整数，`session_id` 若给出必须是规范 UUID 文本（大小写与格式都必须与原值逐字一致，用于恢复既有 Session）。参数被拒或 Runner 抛异常都收敛为错误结果，返回的句柄原样承接取消。多轮调度、深度与并发限制、会话与 Thread 的创建或恢复都在 Runner 实现侧。

[`SubagentConfig`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java) 冻结 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency`、`idleTimeout` 与 `maxTurns`：`maxDepth`、`maxConcurrency` 与 `maxTurns` 必须为正，`maxTotalConcurrency` 允许 0 表示不限，`idleTimeout` 允许 0 表示关闭、非零值必须是整毫秒。[`SubagentConfigProvider`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfigProvider.java) 让每个决策点现读配置，Platform 把它映射到 `aiRuntime.subagent*`，因此调整并发与预算不需要重启。

## 源码与测试

- 注册与身份：[`BuiltinHarnessContributor.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)
- Goal：[`GoalState.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalState.java)、[`GoalStateCodec.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalStateCodec.java)、[`GoalToolSupport.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalToolSupport.java)、[`GoalContextProjector.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalContextProjector.java)、[`GoalPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalPrompts.java)
- 环境工具：[`EnvironmentCapabilityTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java)、[`EnvironmentPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentPrompts.java)
- 历史动作：[`BuiltinHistoryRenderers.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderers.java)、[`EnvironmentCapabilityRenderer.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityRenderer.java)
- Skill 与 Subagent：[`LoadSkillTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/skill/LoadSkillTool.java)、[`TaskTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java)、[`SubagentConfig.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java)
- [`BuiltinHarnessContributorTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributorTest.java) 锁定完整的 14 工具清单、capability 映射与 descriptor schema/defaultTimeout 随 capability 同步，并断言 MCP 类能力不会被自动注册成模型工具；[`GoalFeatureTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalFeatureTest.java) 覆盖分支最新快照、fork/sibling 隔离、替换保留 `createdAt`、终态不可再更新与投影器静默；[`GoalStateCodecTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalStateCodecTest.java)、[`LoadSkillToolTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/skill/LoadSkillToolTest.java)、[`TaskToolTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskToolTest.java) 分别锁定严格 codec、冻结三元组精确加载与委派参数校验。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Contributor API](harness-contributor-api.md)、[Harness Tool](harness-tool.md)、[Harness Common](harness-common.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
