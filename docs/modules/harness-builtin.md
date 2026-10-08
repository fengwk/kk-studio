# Harness Builtin

`harness-builtin` 提供第一方工具集：读写文件、执行命令、检索文件与符号、委派 Subagent、
向用户提问，以及读取用户目标并声明进度。注册入口是
[`BuiltinHarnessContributor`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)
（`ContributorId` 为 `builtin`），通过 [`harness-contributor-api`](harness-contributor-api.md)
的统一 SPI 注册 13 个工具与 `goal.progress` 自定义条目类型；模型可见的工具集合因此由
目录与本轮 Agent 配置共同确定，不依赖容器装配顺序。

模块定义工具行为、参数与领域校验，以及分支状态追加意图。Goal 正文
由用户输入面维护。校验
ownership、WRITE 声明、effects 数量与原子落库由 [`harness-runtime`](harness-runtime.md)
与 Contributor 目录承担；环境能力的网络传输与子进程执行由
[`harness-environment`](harness-environment.md) 与 [`harness-daemon`](harness-daemon.md) 承担。
Skill 由 System Prompt 提供稳定路径，读取统一交给
`read`。生产依赖见 [`pom.xml`](../../harness/builtin/pom.xml)，由
[`BuiltinModuleArchitectureTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinModuleArchitectureTest.java)
守卫。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.builtin` | 第一方内置能力根包：唯一的 `BuiltinHarnessContributor` 与完成态句柄 | 集中注册 13 个工具与 Goal 进度自定义类型；网络传输与持久化调度在外层模块 |
| `fun.fengwk.kkstudio.harness.builtin.environment` | 环境能力工具适配与 prompt 模板加载 | `read` 按地址选择 Platform 或可选 `BoundEnvironment`，其余宿主工具要求 Environment；传输协议与宿主进程管理由 Daemon 承接 |
| `fun.fengwk.kkstudio.harness.builtin.goal` | Goal 读取与进度声明工具（`get_goal`、`update_goal`）、`GoalProgress` 与确定性编解码器 `GoalProgressCodec` | 目标正文由 branch settings 拥有（用户经 typed `GOAL` 命令设置/清除），本包只读取它并声明进度；进度依托通用 `harness_entry` 的 CUSTOM 载荷，通过 `AppendCustomEntry` 由 Runtime 原子追加 |
| `fun.fengwk.kkstudio.harness.builtin.input` | 人工输入工具 `AskUserTool` 及其问卷提示词 | 只声明 `ask_user` 的模型可见契约；等待冻结与答案校验由 [harness-runtime](harness-runtime.md) 的 `runtime.input` 承接 |
| `fun.fengwk.kkstudio.harness.builtin.subagent` | 内部委派工具 `TaskTool`、任务请求 `SubagentTaskRequest`、接受结果 `SubagentTaskAcceptance`、即时回执 `SubagentTaskMessages`、执行端口 `SubagentRunner` 与配置接入 `SubagentConfig`/`SubagentConfigProvider` | 只做参数解析、端口转发与即时回执文本；join 终态匹配、父通知交付与并发额度由 Runtime 与 Platform 负责 |

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
| `runtime.task` | `task` | 无 | INTERNAL | 委派 Subagent 任务，`NON_IDEMPOTENT` |
| `ask-user` | `ask_user` | 无 | SELECTABLE | 向人提出冻结问卷，`READ_ONLY`；Runtime 在 dispatch 前按 contributor `builtin` + name `ask_user` 冻结为 `WAITING_INPUT` |
| `goal.get` | `get_goal` | READ(`goal.progress`) | SELECTABLE | 读取当前用户 Goal 与其进度声明，`READ_ONLY` |
| `goal.update` | `update_goal` | WRITE(`goal.progress`) | SELECTABLE | 声明当前 Goal 的终态进度，`IDEMPOTENT` |

`localName` 是 contributor 内的贡献标识；Agent 配置、权限键与 catalog 使用模型可见 name。[`BuiltinHarnessContributorTest`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributorTest.java) 验证字面值。此外注册 Custom Entry Type `goal.progress-type`（customType 为 `goal.progress`），priority 为 0。Goal 正文保存在用户控制的 branch settings，Agent 经 get_goal 读取。

每次请求的工具面由 Platform 从目录与 Agent 配置解析：subagents allowlist 非空时加入 INTERNAL task；Issue Agent Thread 过滤 get_goal/update_goal，普通分支保留配置的 Goal 工具。未选 Environment 时过滤八个 REQUIRED 宿主工具，OPTIONAL read 保留 Platform 资源读取能力。

八个 Environment-only 工具把 [`EnvironmentCapabilityTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java)
绑定到一个 catalog capability。统一 `read` 则先识别稳定的 `kkstudio:` URI，再把本地路径
委托给执行期可选的 `BoundEnvironment.fs.read`。环境工具 descriptor 的 `inputSchema`
与 `defaultTimeout` 取自 capability descriptor。当前只有 `bash`、`grep`、`find` 的 schema
声明 `timeout_seconds`：归一化 arguments 携带该正数时严格使用该值，缺省时使用 capability 默认值。
共享解析器本身不按工具名过滤参数；是否允许该参数由 schema 决定。
工具说明来自 [`environment/prompts/`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/environment/prompts/)；
资源缺失立即失败，不退化成空描述。

## Goal：用户拥有的目标与 Agent 进度声明

Goal 正文由用户经 typed
`GOAL` 输入命令写入 branch settings（[`BranchSettings.goal`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/BranchSettings.java)，
nullable 不可变 `GoalSetting{id, text}`）。typed `GOAL` 在同一个 `TURN_START` 中冻结新
settings，并追加对应的 USER 消息；它本身是末尾 user-like 输入，不可与普通 `USER_MESSAGE` 同批。
每次设置都分配新 id，即使正文相同；正文必须非空白、无首尾空白且不超过 2000 个 Unicode 码点。
显式 null 表示清除，空字符串校验失败。目标正文保留为用户输入与 branch settings 快照，
其信任级别始终为用户内容。

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
声明通过 goalId 绑定用户目标，表示 Agent 的终态进度报告；Goal 本身与业务验收
分别由用户输入和业务流程维护。

两个工具都先经 [`GoalToolSupport`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalToolSupport.java)
做 schema 校验与强类型解析，并通过限定在 `builtin` 作用域的 `BranchView.goal()` 读取当前
分支的用户 Goal。它们都要求 durable `ToolExecutionContext`，缺失时直接返回错误结果而不是抛出：

- [`GetGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GetGoalTool.java) 无入参、READ_ONLY，只读返回当前目标正文与其 `goalId`，以及绑定该 `goalId` 的进度声明；未设置或已清除时返回明确的提示文本。过期 `goalId` 的声明不会被当成当前目标进度。
- [`UpdateGoalTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/UpdateGoalTool.java) 要求终态 `status`（`complete` | `blocked`）与非空 `reason`，不接受显式 `goalId`：它按执行时当前生效的 `goalId` 绑定声明。没有用户 Goal，或当前 Goal 已有终态声明时直接拒绝，避免迟到/重复报告被当成当前目标的进度。

读取范围仅为当前分支路径，包含 fork 继承的共享祖先；没有读取其他 Session、Thread 或兄弟分支当前 Goal / 进度的参数。同一轮工具共用冻结快照，`update_goal` 声明 WRITE 后再调用 `get_goal` 或 `update_goal` 会被 Runtime 以 `SIBLING_STATE_CONFLICT` 拒绝，应在下一模型轮次读取；先 READ 再 WRITE 则允许。

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
路径委托当前 Daemon，相对本地路径另需绝对 `workdir`。`kkstudio:/resources/<blobId>`
通过本次执行 context 的 `threadId` 解析 Session，并检查该 Session 是否引用 Blob；拿到另一
Session 的 UUID 不授予读取权限，缺少 context 直接失败。`kkstudio:` URI 不接受 `workdir`，
URI 的支持范围由读取器显式校验。Skill 读取使用 read 的路由、权限与超时。

[`TaskTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java) 以 `INTERNAL` 注册，本身不携带环境需求。它把 arguments 解析为 [`SubagentTaskRequest`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskRequest.java) 后交给 [`SubagentRunner`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentRunner.java)：`subagent_type` 与 `prompt` 必填非空白（`subagent_type` 禁止首尾空格），`max_turns` 若给出必须为正整数，`thread_id` 若给出必须是规范 UUID 文本（用于在既有子 Thread 上继续）。缺少 durable context 直接抛 `IllegalArgumentException`；取得 context 后的参数拒绝与 Runner `RuntimeException` 收敛为错误 ToolResult。

[`SubagentRunner`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentRunner.java) 是只有「接受」没有「等待」的端口：实现必须在同一事务内完成子 Thread 的命令接受与 join 凭据写入，然后立即返回子身份，绝不能阻塞到子执行结束。Platform 的实现把请求组装为 [`ThreadJoinRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/join/ThreadJoinRequest.java) 并调用 `acceptCommandsAndJoin`，深度与并发限额也在该事务内由 Runtime 校验。Runner 以 [`SubagentTaskRequest.invocationId`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskRequest.java) 为幂等键：同一次 Tool 调用重试返回同一个子 Thread，不会重复开启执行。

接受成功后 [`TaskTool`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java) 立即返回成功 tool_result JSON `{"thread_id":"...","status":"accepted"}`（[`SubagentTaskMessages.accepted`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskMessages.java)）。执行结果在子 Thread 到达收敛终态边界（源输入已应用、且无未完成直接子 Join / 未送达子回执 / 待处理输入时的最终回答 `TURN_END`、不可继续 `FAILED`，或 Stop 强制收尾）结算 join 后，由 Runtime 作为父 Thread 的 `NOTIFICATION` 命令交付（父 `STOPPED` 时只固化、不唤醒）；完成消息的 XML 编码与转义由 runtime.join 纯函数处理，见 [Harness Runtime](harness-runtime.md)。

[`SubagentConfig`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java) 冻结 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency` 与 `maxTurns`：深度、单父并发和轮数软预算必须为正；全局并发上限允许 0 表示不限。全局额度跨所有执行树按非空闲子 Thread 计数，根 Thread 不计入，同一忙碌子上的多个 join 不重复占额；判定与接受由全局事务准入锁串行化。[`SubagentConfigProvider`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfigProvider.java) 让每个决策点现读配置，Platform 把它映射到 `aiRuntime.subagent*`，因此调整并发与预算不需要重启。

委派的完整契约见 [内置工具与异步委派](builtin-tools-design.md)，逐用例对照见 [Builtin Task 测试映射](../operations/builtin-task-tests.md)。

## 源码与测试

- 注册与身份：[`BuiltinHarnessContributor.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributor.java)
- Goal：[`GoalProgress.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgress.java)、[`GoalProgressCodec.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalProgressCodec.java)、[`GoalToolSupport.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalToolSupport.java)、[`GoalPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalPrompts.java)
- 环境工具：[`EnvironmentCapabilityTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityTool.java)、[`EnvironmentPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentPrompts.java)
- 历史动作：[`BuiltinHistoryRenderers.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderers.java)、[`EnvironmentCapabilityRenderer.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/environment/EnvironmentCapabilityRenderer.java)
- Subagent：[`TaskTool.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskTool.java)、[`SubagentRunner.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentRunner.java)、[`SubagentTaskRequest.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskRequest.java)、[`SubagentTaskAcceptance.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskAcceptance.java)、[`SubagentTaskMessages.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskMessages.java)、[`SubagentConfig.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfig.java)、[`SubagentPrompts.java`](../../harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentPrompts.java)
- Subagent Prompt：[`task.md`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/subagent/prompts/task.md)、[`task-system.md`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/subagent/prompts/task-system.md)、[`task.schema.json`](../../harness/builtin/src/main/resources/fun/fengwk/kkstudio/harness/builtin/subagent/prompts/task.schema.json)；maxTurns 软预算到期的提醒文本不属于本模块，由 Runtime 在已确定继续的边界以 `TASK_BUDGET` 通知物化进历史
- [`BuiltinHarnessContributorTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHarnessContributorTest.java)
  锁定完整的 13 工具清单（12 个 SELECTABLE 与 1 个 INTERNAL `task`）、环境支持级别、capability 映射与 descriptor
  schema/defaultTimeout；[`GoalFeatureTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalFeatureTest.java)
  覆盖 catalog 无创建工具、只读目标读取、过期 `goalId` 进度陈旧、声明绑定当前 `goalId`、
  重复终态声明被拒与缺失上下文安全失败；[`BuiltinHistoryRenderersTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/BuiltinHistoryRenderersTest.java)、
  [`TaskToolTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/subagent/TaskToolTest.java)、
  [`SubagentTaskMessagesTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentTaskMessagesTest.java)
  与 [`SubagentConfigTest.java`](../../harness/builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/subagent/SubagentConfigTest.java)
  分别锁定历史动作渲染、委派参数解析与 `accepted` 即时回执、即时回执文本与预算配置校验；完成消息的 XML 编码与转义契约由 runtime 的
  [`ThreadJoinCompletionRendererTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/join/ThreadJoinCompletionRendererTest.java) 锁定。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Contributor API](harness-contributor-api.md)、[Harness Tool](harness-tool.md)、[Harness Common](harness-common.md)、[Harness Runtime](harness-runtime.md)、[Platform](platform.md)。
