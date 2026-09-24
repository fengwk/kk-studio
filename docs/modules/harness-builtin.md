# Harness Builtin

Harness 需要一个可直接使用的工具集：读文件、执行命令、查符号、委派 Subagent、读取用户
目标并声明进度。这些工具如果各自散落在不同模块里注册，就会出现身份不统一、副作用标注不一致、
模型可见列表随装配方式漂移的问题。本模块把第一方能力收拢到唯一入口
[`BuiltinHarnessContributor`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)
（`ContributorId` 为 `builtin`），通过 [`harness-contributor-api`](harness-contributor-api.md)
的统一 SPI 注册 12 个工具与 `goal.progress` 自定义条目类型；模型可见的工具集合因此由
代码确定，而不是由容器装配顺序决定。

模块只负责「这些工具做什么」：实现委托、参数与领域校验、以及要追加什么分支状态。目标
正文的写入权限在用户输入面，不在本模块。校验
ownership、WRITE 声明、effects 数量与原子落库由 [`harness-runtime`](harness-runtime.md)
与 Contributor 目录承担；环境能力的网络传输与子进程执行由
[`harness-environment`](harness-environment.md) 与 [`harness-daemon`](harness-daemon.md)
承担。Skill 不再拥有专用加载工具，而是由 System Prompt 提供稳定路径并统一交给
`read`。生产依赖见 [`pom.xml`](../../harness/builtin/pom.xml)，由
[`BuiltinModuleArchitectureTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinModuleArchitectureTest.java)
守卫。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.builtin` | 第一方内置能力根包：唯一的 `BuiltinHarnessContributor` 与完成态句柄 | 集中注册 12 个工具与 Goal 进度自定义类型；网络传输与持久化调度在外层模块 |
| `fun.fengwk.kkstudio.harness.builtin.environment` | 环境能力工具适配与 prompt 模板加载 | `read` 按地址选择 Platform 或可选 `BoundEnvironment`，其余宿主工具要求 Environment；传输协议与宿主进程管理由 Daemon 承接 |
| `fun.fengwk.kkstudio.harness.builtin.goal` | 只读 Goal 工具（`get_goal`、`update_goal`）、进度声明 `GoalProgress` 与确定性编解码器 `GoalProgressCodec` | 目标正文由 branch settings 拥有（用户经 typed `GOAL` 命令设置/清除），本包只读取它并声明进度；进度依托通用 `harness_entry` 的 CUSTOM 载荷，通过 `AppendCustomEntry` 由 Runtime 原子追加 |
| `fun.fengwk.kkstudio.harness.builtin.skill` | Skill 稳定地址读取所需的窄端口和值契约 | 不注册专用模型工具；Git cache、Package Catalog 与本地安装由 Platform/Daemon 承接 |
| `fun.fengwk.kkstudio.harness.builtin.subagent` | 内部委派工具 `TaskTool`、任务请求 `SubagentTaskRequest`、执行端口 `SubagentRunner` 与配置接入 | 只做参数解析与转发；多轮调度、并发上限与持久化状态机由运行时负责 |

## 注册清单

`contribute` 的注册顺序和内容就是内置能力的定义。`task` 由 Platform 装配注入，其余
工具都在这里直接实例化：

| localName | 模型可见 name | 依赖 | 可见性 | 说明 |
| --- | --- | --- | --- | --- |
| `read` | `read` | Platform + Environment | SELECTABLE | `kkstudio:` URI 或 `fs.read`，READ_ONLY |
| `environment.write` | `write` | Environment | SELECTABLE | `fs.write`，IDEMPOTENT |
| `environment.edit` | `edit` | Environment | SELECTABLE | `fs.edit`，NON_IDEMPOTENT |
| `environment.bash` | `bash` | Environment | SELECTABLE | `process.exec`，NON_IDEMPOTENT |
| `environment.grep` | `grep` | Environment | SELECTABLE | `fs.grep`，READ_ONLY |
| `environment.find` | `find` | Environment | SELECTABLE | `fs.find`，READ_ONLY |
| `environment.lsp-goto-definition` | `lsp_goto_definition` | Environment | SELECTABLE | `lsp.goto-definition`，READ_ONLY |
| `environment.lsp-workspace-symbols` | `lsp_workspace_symbols` | Environment | SELECTABLE | `lsp.workspace-symbols`，READ_ONLY |
| `environment.lsp-java-decompile` | `lsp_java_decompile` | Environment | SELECTABLE | `lsp.java-decompile`，READ_ONLY |
| `runtime.task` | `task` | 无 | INTERNAL | 委派 Subagent 任务 |
| `goal.get` | `get_goal` | READ(`goal.progress`) | SELECTABLE | 读取当前用户 Goal 与其进度声明 |
| `goal.update` | `update_goal` | WRITE(`goal.progress`) | SELECTABLE | 声明当前 Goal 的终态进度 |

`localName` 是 contributor 内的 scoped 贡献标识；Agent 侧的唯一身份是模型可见 name，配置、权限键与 catalog 条目都只用它。name 的字面值由 [`BuiltinHarnessContributorTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributorTest.java) 锁定。此外注册 Custom Entry Type `goal.progress-type`（customType 为 `goal.progress`），priority 为 0。Goal 没有创建工具、也没有任何 Context Projector：目标正文由用户维护，绝不注入 `systemInstruction`。

八个 Environment-only 工具把 [`EnvironmentCapabilityTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java)
绑定到一个 catalog capability。统一 `read` 则先识别稳定的 `kkstudio:` URI，再把本地路径
委托给执行期可选的 `BoundEnvironment.fs.read`。环境工具 descriptor 的 `inputSchema`
与 `defaultTimeout` 取自 capability descriptor；拥有 arguments 级超时的工具在归一化
arguments 携带正数 `timeout_seconds` 时严格使用该值，缺省时使用 capability 默认值。
工具说明来自 [`environment/prompts/`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/environment/prompts/)；
资源缺失立即失败，不退化成空描述。

## Goal：用户拥有的目标与 Agent 进度声明

Goal 不建独立表，也**不是**模型工具可以创建或改写的状态：目标正文由用户经 typed
`GOAL` 输入命令写入 branch settings（[`BranchSettings.goal`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/BranchSettings.java)，
nullable 不可变 `GoalSetting{id, text}`），与 `USER_MESSAGE` 在同一个 Turn 原子生效；
显式 `null` 表示清除。目标正文因此属于用户，分支历史中的目标快照不可被 Agent 篡改，
也绝不提升为 `systemInstruction`。

Agent 只能做两件事：读取当前目标，以及对自己正在处理的目标声明终态进度。

[`GoalProgress`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgress.java)
是 `CUSTOM goal.progress` 载荷（`schemaVersion = 1`），持久化在
[`harness_entry`](../../schema/src/main/resources/db/migration/V1__schema.sql) 里：

```text
goalId:     producing 声明时生效的用户 Goal id（必填）
status:     complete | blocked
reason:     non-blank
reportedAt: 毫秒截断
```

[`GoalProgressCodec`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgressCodec.java)
要求精确字段集合：未知字段、缺失字段、重复键、尾随 token、非终态枚举与时间戳格式错误一律拒绝。
声明只绑定 `goalId`，不复制目标正文、不删除 Goal、也不改变任何业务状态——它是「Agent
报告」，不是系统验收。

两个工具都先经 [`GoalToolSupport`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalToolSupport.java)
做 schema 校验与强类型解析，并通过限定在 `builtin` 作用域的 `BranchView.goal()` 读取当前
分支的用户 Goal。它们都要求 durable `ToolExecutionContext`，缺失时直接返回错误结果而不是抛出：

- [`GetGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GetGoalTool.java) 无入参、READ_ONLY，只读返回当前目标正文与其 `goalId`，以及绑定该 `goalId` 的进度声明；未设置或已清除时返回明确的提示文本。旧 `goalId` 的声明不会被当成当前目标进度。
- [`UpdateGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/UpdateGoalTool.java) 要求终态 `status`（`complete` | `blocked`）与非空 `reason`，不接受显式 `goalId`：它按执行时当前生效的 `goalId` 绑定声明。没有用户 Goal，或当前 Goal 已有终态声明时直接拒绝，避免迟到/重复报告被当成当前目标的进度。

声明成功后携带且仅携带一个 `AppendCustomEntry("goal.progress", 1, …)`，查询与所有错误结果
不带任何 effects。真正把条目写进会话树并保证「要么全成功、要么整体回滚」的是 Runtime 的
[`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolOutcomeAppender.java)：
本模块只声明意图，不触碰存储。

## 历史语义渲染

内建工具通过 `Tool.historyRenderer()` 暴露历史动作映射：
[`BuiltinHistoryRenderers`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderers.java)
覆盖 `task` 与 2 个 Goal 工具，[`EnvironmentCapabilityRenderer`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityRenderer.java)
按能力语义生成「动作 + 目标 + workdir 作用域 + 环境名」短语。渲染省略 timeout、
limit、offset 与列分页等执行控制参数，保留会改变动作解释的检索、编辑标志。它们都是
确定性纯函数，无法形成有意义动作时返回 absent，由 Runtime 回退到逐字 arguments 的
中性描述。

## Skill 路径与 Subagent 桥接

当 Agent 配置至少一个 Skill 时，Platform 自动保证 `read` 位于本次模型工具面，并在
System Prompt 中给出每个 Skill 的 name、description 与稳定 path。`read` 对
`kkstudio:/skills/<package>/<skill>/...` 读取 Platform 当前发布 commit；对绝对本地
路径委托当前 Daemon。Skill 没有专用 Tool、运行时 binding 或独立超时。

[`TaskTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java) 同样以 `INTERNAL` 注册，本身不携带环境需求。它把 arguments 解析为 [`SubagentTaskRequest`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskRequest.java) 后交给 [`SubagentRunner`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentRunner.java)：`subagent_type` 与 `prompt` 必填非空白，`maxTurns` 若给出必须为正整数，`session_id` 若给出必须是规范 UUID 文本（大小写与格式都必须与原值逐字一致，用于恢复既有 Session）。参数被拒或 Runner 抛异常都收敛为错误结果，返回的句柄原样承接取消。多轮调度、深度与并发限制、会话与 Thread 的创建或恢复都在 Runner 实现侧。

[`SubagentConfig`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java) 冻结 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency`、`idleTimeout` 与 `maxTurns`：`maxDepth`、`maxConcurrency` 与 `maxTurns` 必须为正，`maxTotalConcurrency` 允许 0 表示不限，`idleTimeout` 允许 0 表示关闭、非零值必须是整毫秒。[`SubagentConfigProvider`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfigProvider.java) 让每个决策点现读配置，Platform 把它映射到 `aiRuntime.subagent*`，因此调整并发与预算不需要重启。

## 源码与测试

- 注册与身份：[`BuiltinHarnessContributor.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)
- Goal：[`GoalProgress.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgress.java)、[`GoalProgressCodec.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgressCodec.java)、[`GoalToolSupport.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalToolSupport.java)、[`GoalPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalPrompts.java)
- 环境工具：[`EnvironmentCapabilityTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java)、[`EnvironmentPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentPrompts.java)
- 历史动作：[`BuiltinHistoryRenderers.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderers.java)、[`EnvironmentCapabilityRenderer.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityRenderer.java)
- Subagent：[`TaskTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java)、[`SubagentConfig.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java)
- [`BuiltinHarnessContributorTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributorTest.java)
  锁定完整的 12 工具清单、环境支持级别、capability 映射与 descriptor
  schema/defaultTimeout；[`GoalFeatureTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalFeatureTest.java)
  覆盖 catalog 无创建工具、只读目标读取、旧 `goalId` 进度陈旧、声明绑定当前 `goalId`、
  重复终态声明被拒与缺失上下文安全失败；[`BuiltinHistoryRenderersTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderersTest.java)
  与 [`TaskToolTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskToolTest.java)
  分别锁定历史动作渲染与委派参数校验。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Contributor API](harness-contributor-api.md)、[Harness Tool](harness-tool.md)、[Harness Common](harness-common.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
