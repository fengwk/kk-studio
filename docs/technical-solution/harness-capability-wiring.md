# Harness 能力装配

Core 通过 Spring `ObjectProvider` 直接收集 ProviderFactory、ToolFactory、Tool interceptor 与受信任 `HarnessPlugin`，并在启动时冻结为不可变集合、`PluginCatalog`、统一 `ToolCatalog` 和 gateway。`core` 只做 Catalog/TurnResolver/ModelGateway/ToolGateway/插件 host 适配，不写 `harness_*` 表；领域状态机全部在 `harness-runtime`。

## 1. 装配图

```text
ModelExecutionConfiguration
  -> ProviderFactory beans
  -> ProviderFactories（按 ProviderType 不可变索引）
  -> CoreModelGateway（serialized FIFO 回调桥）

HarnessToolGatewayConfiguration
  -> ToolFactory beans
  -> ToolFactories（按 (name, version) 索引）
  -> PluginCatalog（受信任 build-time contributions，启动时冻结）
  -> ToolCatalog（ToolFactory + plugin tools + ENVIRONMENT 两类 + 内部 load_skill/task）
  -> CoreToolGateway（普通 Tool / plugin Tool + preflight + 两阶段激活 + FIFO 回调桥）

RuntimeToolsConfiguration
  -> loadSkillTool / TaskTool（内部 PLATFORM Tool beans；TaskTool 依赖 core port HarnessThreadChangeSource，由 web 组合根适配 ThreadRevisionEventSource 提供）
  -> SubagentConfig（maxDepth/并发/idle/maxTurns，无轮询间隔）+ SubagentRunRegistry（进程内并发 reservation + descendant 订阅）

DatabaseTurnResolver
  -> Agent/Provider/Model/Variant Catalog 查询（按名称读最新行）
  -> 最新 Agent tools/skills/subagents 派生本 turn 工具集合
  -> ToolCatalog + PluginCatalog + LiveEnvironmentRegistry
  -> ContextProjector(candidate BranchView)
  -> ProviderFactory（由当前行 providerType 解析，派生 cache policy）
  -> AgentPromptComposer（Agent 正文 -> current_environment -> available_skills -> available_subagents）
  -> 冻结 compact ModelRequestSpec（含 subagentBindings；无 messages/YOLO/contextWindow）

CoreModelGateway.start（每次 attempt）
  -> ModelRequestMaterializer 在有效 claim 内从 basisHeadEntryId + spec 重建内存 ProviderRequest
  -> DatabaseProviderResolutionService 按 providerName 读取当前 agent_provider 行
  -> 当前 ProviderFactory.create(当前 credential/config) -> attempt-local adapter
  -> ProviderResourceMaterializer 按 storage_blob 事实生成 attempt-only media URL/文本回退
  -> 持久 cache control 按当前 capability 规范化
```

## 2. Provider

`ProviderFactory` 描述一个 ProviderType 的 adapter 工厂：

```java
public interface ProviderFactory {
  ProviderType providerType();
  PromptCacheCapability promptCacheCapability();
  ProviderAdapter create(String credential, String configJson);
}
```

当前 Core 装配：

| Bean | ProviderType | cache capability |
| --- | --- | --- |
| `openaiProviderFactory` | `OPENAI` | AFFINITY |
| `openaiResponsesProviderFactory` | `OPENAI_RESPONSES` | AFFINITY |
| `anthropicProviderFactory` | `ANTHROPIC` | BREAKPOINTS |
| `googleProviderFactory` | `GOOGLE` | AUTOMATIC |

`ProviderFactories` 按 ProviderType 建立不可变索引，重复注册在构造阶段失败。Provider 资源就是当前 `agent_provider` 行：每次 Model attempt 由 `DatabaseProviderResolutionService` 按 `providerName` 读取当前行，以当前 providerType/baseUrl/credential/config 选择 `ProviderFactory` 并构造短生命周期 attempt-local adapter；Provider 更新后下一 attempt 立即使用新值，当前行缺失时确定性 not found，同名重建后解析到新行。credential 只在写入 DTO 反序列化与 attempt 时 adapter 构造使用，不进入 response 或 invocation JSON。

`CoreModelGateway` 是 `ModelGateway` 端口适配：admission 两阶段激活（`start` → Processor `markRunning` 后 `activate`），回调桥是 serialized FIFO 单 drainer 状态机，terminal-once；`Busy` 重试、`Rejected` 确定性终结、`Indeterminate` 收敛 `UNKNOWN`。已启动 attempt 的 retryable `TRANSIENT` 失败由 Runtime 保存完整 partial/error/retryAt 后重放冻结 `ModelRequestSpec`（每次 attempt 从相同 `basisHeadEntryId + spec` 重新 materialize 内存 ProviderRequest），不由 Gateway 拼接历史输出。

## 3. ToolCatalog

`ToolCatalog` 只使用两类产品级 Tool，并维护 Agent 可选择目录与内部 Platform Tool 目录的分离：

- selectable Platform：
  - 冻结 `PluginCatalog` 的 SELECTABLE contributions；当前包括 Goal 插件 `create_goal` / `get_goal` / `update_goal` v2；
  - Core 提供的其他 Platform `ToolFactory`；
- 固定的十一个 `ENVIRONMENT` Tool descriptor：9 个 pi-base coding 能力的本地 Java 实现
  `read, write, edit, bash, grep, find, lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile`，
  加上 2 个固定 MCP 桥接工具 `mcp_list_tools, mcp_call_tool`；它们与 `PLATFORM`/动态 MCP 工具互不进入对方目录；
- **`load_skill` 与 `task` 是两个 internal Platform Tool**（`ToolCatalog(descriptors, internalNames)` 的 internal 集合 = `{load_skill, task}` + 插件 INTERNAL 贡献），从 selectable 集合移出，不出现在 Agent 可选择目录中。`task` 由 `RuntimeToolsConfiguration` 装配（`TaskTool` + `SubagentConfig` + `SubagentRunRegistry`），被 `activeTools` 显式选择后由 Resolver 绑定。

`GET /api/ai/catalog/tools` 只返回 Agent 可选择的 Platform/Environment 目录。Agent config 保存可选择 Tool 名称集合，不保存 Tool 实例或 Environment 连接。

`CoreToolGateway` 是 `ToolGateway` 端口适配：`preflight` 同步无副作用（`Allow` / `Ask(reason)` / `Deny(error)`），外部 I/O 前完成权限判定与机械校验；两阶段激活与 Model 同构；普通 `PLATFORM` binding 走本地 registry，`ENVIRONMENT` binding 经 `RemoteToolTransport`（`EnvironmentDaemonGateway`）发往冻结 route；带 plugin binding 的 `PLATFORM` Tool 按冻结 contribution 精确恢复并同步执行。terminal success 在回调桥内先校验插件 intents，再经 `ToolResultExternalizer` 做瞬时 Resource 外部化（reference plan → put → exact ref check），最后把 `ToolSuccess(result, effects)` 交给 ToolProcessor 原子落 terminal 事实。Tool outcome Entry 写入前，`ToolResultHistoryMaterializer` 再在同一 Store 事务把 Resource 摄入全局 Blob 并转换为 `resource(blobId,name,preview)`。

### Trusted plugin

- 插件只从应用 classpath 的 `HarnessPlugin` beans 收集；`PluginCatalog.from(...)` 在启动时执行一次注册与 freeze。不存在动态 JAR、远程脚本、安装表、依赖解析、热加载或卸载。
- `PluginTool` 是同步纯函数：输入冻结到 Assistant Entry 的 `BranchView`、执行时间与 `ToolCall`，输出 `ToolResult + List<AppendCustomEntry>`；插件不能访问 `HarnessStore`，也不能推进 Thread/Invocation/Work。
- `ToolContribution` 冻结 `(pluginId, contributionLocalName)`、descriptor、visibility 与声明的 `(customType, READ|WRITE)`；注册阶段校验该 customType 已由同一插件注册，重复或漂移 fail closed。
- `CoreToolGateway` 执行前按 binding 中的 contribution id 恢复贡献，descriptor/state accesses 漂移分别确定性拒绝；当前 intent 只接受同 owner、已注册且声明 WRITE 的 `AppendCustomEntry`。
- 合法 intents 映射为有序 `ToolEffectBatch`。校验必须发生在 Resource externalize 与 durable `SUCCEEDED` 之前，违规以 `PLUGIN_CONTRACT_VIOLATION` 失败且 effects 为空。

当前 `goal` 插件注册 `goal/state@schemaVersion=1`、三个 SELECTABLE Platform Tool（均为 v2）和一个 context projector：

- `create_goal`（WRITE）创建或完整替换 active snapshot；替换保留原 `createdAt`，更新 `updatedAt`；
- `get_goal`（READ）读取当前 branch 最近 snapshot，不写 intent；
- `update_goal`（WRITE）只允许 active → complete/blocked，必须携带 reason；
- state 字段固定为 `objective/tokenBudget/status/reason/createdAt/updatedAt`，status 只允许 `active/complete/blocked`，不存在 `budget_limited`；
- 只有 active snapshot 投影到 Provider context，terminal 或缺失 Goal 不注入消息。

## 4. BranchSettings 到冻结 spec

每次 turn 的输入是 head Entry 分支的完整 `BranchSettings`（ROOT/TURN_START 固化）：

```java
public record BranchSettings(
    EnvironmentBinding environment, // 完整 binding{name, workspacePath}，null 表示未选择
    String agentName,
    ModelSelection model,          // providerName/modelName/variant
    List<String> activeTools) {}    // 历史投影；新 turn 从最新 Agent config 派生能力
```

`DatabaseTurnResolver.resolve(threadId, candidatePath, compactionPreparation)` 分为正常 turn 与 compaction turn（YOLO 不参与 Resolver）。正常解析顺序：

```text
candidate path 最近 TURN_START 的 BranchSettings
  -> AgentDefinition（agentName）
  -> Provider / (providerName, modelName) Model / effective Variant
  -> ToolCatalog + PluginCatalog + 最新 Agent config.tools
  -> EnvironmentBinding 路由（binding.environmentName）+ LiveEnvironmentRegistry（READY + 心跳未过期才可用）
  -> CurrentEnvironmentContext（单一 instant；capabilities metadata 或服务端 Clock zone）
  -> skills（最新 Agent config；非空时派生 load_skill，最新选中 Environment 精确提供）
  -> subagents（最新 Agent config allowlist；非空且 depth < maxDepth 时派生 task）
  -> AgentPromptComposer（Agent 正文 -> 始终存在的 current_environment -> available_skills -> available_subagents）
  -> 插件 ContextProjector(candidate BranchView)
  -> compaction-aware 白名单历史投影（MESSAGE / CUSTOM_MESSAGE / ASSISTANT_ABORTED；失败 attempt/error 不投影）
  -> ProviderFactory（按当前行 providerType 派生 cache policy）
  -> 冻结 compact ModelRequestSpec（Agent/Model 修改下一 turn 生效；含 subagentBindings；无 messages/YOLO/contextWindow）
```

**fail closed（Environment route 规则）**：

- **latest-snapshot-wins**：解析只使用 candidate path 最近一个 ROOT/TURN_START 的**完整** `BranchSettings` 快照（`EntryPath.baseSettings()` 逐项取最新）；快照中的 null/缺失/不可用值（如 `environment` binding 为 null、agent/Environment 已不存在）**绝不触发向更旧 ROOT/TURN_START 快照回退**——更旧快照中的非 null environment 或仍有效的 agent 不再参与解析；
- **ENVIRONMENT 工具规划不拒绝**：一律按最新 `BranchSettings.environment()` 完整 binding 绑定（null/缺失/未 READY 都放行）；实际 start 时 null binding 或目标不可用（未注册/未 READY/心跳过期）→ `Rejected`（`UNAVAILABLE`），durable `FAILED` ToolResult 对模型可见，turn 收敛；
- **Agent skills 规划要求最新选中 Environment live**：缺失/未 READY/分支无名称都是确定性拒绝（精确 message），绝不回看更旧 settings；
- **current_environment prompt 不改变路由语义**：块只输出有值的 name/workspace/system/date/note（`none` 整行省略）；选中条目只要 capabilities 非 null 就使用 READY OS/note/timeZone 计算上下文，无 metadata 时省略 OS/note 并回退服务端 Clock zone。status、heartbeat、workdir、时间与 timeZone 不进入 Prompt；它只冻结模型上下文，不参与 Tool/Skill 的实时 ready 校验；
- **最新 Agent 能力规则**：每个新 turn 按最新 `config.tools` 顺序绑定直接工具；skills 非空时派生内部 `load_skill`；subagents allowlist 非空且 depth < maxDepth 时派生内部 `task`。历史 branch activeTools 不限制也不扩张本次能力；
- **task/subagent 规划规则**：allowlist 名称必须解析到现存 Agent（名称 + 描述冻结为 `subagentBindings`），执行绝不重读父 Agent 配置扩权；达到最大 depth 时不暴露 `task`；
- Agent 配置中的 Tool 名必须命中可选择目录，未知 Tool 拒绝；内部 Platform Tool（load_skill/task）不在 selectable catalog，由 Resolver 按最新 skills/subagents 自动派生。

**确定性拒绝**：所有 planning rejection 共用稳定 `AssistantError` code **`PLANNING_FAILED`**（`DatabaseTurnResolver.REJECTION_CODE`），message 携带具体原因（缺失 Agent/Provider/Model/Variant、未知 Tool、Environment 不可用等）——不存在按类别区分的独立拒绝码列表。

`Rejected` 由 Processor 写成 `ASSISTANT_ERROR` barrier（+ `FAILED` TURN_END），不产生 ModelInvocation，也不切换到其他 Agent/Provider/Model/Environment/Tool/Skill。Resolver 抛异常表示临时基础设施失败，由 Processor reschedule。

**Skills**：只从最新 Agent config 读取，必须由选中 READY Environment 精确提供；skills 非空时 Resolver 自动派生内部 `load_skill`，该工具不在 selectable catalog。Provider 返回冻结 spec 中不可见的 Tool 时（unknown tool），Model Invocation 终结失败并物化为带 null binding 的 FAILED ToolInvocation 槽位，Agent Loop 关闭该 Turn。

Compaction resolver 不读取 Agent prompt、plugin projector、Environment live 能力或 prompt cache，不调用 `AgentPromptComposer`，因此不注入 `<current_environment>`，也不绑定 tool/skill/subagent；它只使用 branch 引用的 provider/model/catalog Variant 与 planner 冻结事实，构造一个 summarization SYSTEM + 一个 USER request。Variant 是唯一模型请求预设，其 reasoning effort 与其余请求参数一并生效；contextWindow 沿用触发 turn 冻结在 `TurnStartPayload` 的值，输出上限取有效 model/variant max output 与 phase reserve budget 的较小值。

## 5. 冻结 spec 的不变量

`ModelRequestSpec(providerType, model, variant, preambleMessages, toolBindings, skillBindings, subagentBindings, cacheControl, compaction)`（无 YOLO/contextWindow/history messages/Provider tools 副本）：

- `ProviderType` 随 spec 冻结：每次 attempt 仍读取当前 Provider 行的 credential/base URL/timeout，但当前行 type 必须等于 spec 的 `providerType`，不一致时本次 attempt 确定性失败（禁止在同一 invocation 中切换协议）；
- Provider tool definitions 每次由 `toolBindings` 派生，不保存第二份 `providerRequest.tools`；
- `ToolBinding(descriptor, type, environment, plugin)`：`PLATFORM` 的 binding 为 null，`ENVIRONMENT` 指向具体 binding（可为 null）；descriptor 的 type 与 binding type 一致；普通 Tool 的 plugin 为 null；
- plugin binding 仅允许 `PLATFORM`，冻结 canonical `pluginId`、`contributionLocalName` 与有序唯一 state accesses；retry/重启后仍按该 provenance 恢复，不按工具名猜 owner；
- tool/skill/subagent binding 名称各自不重复；每个 environment-bound tool/skill 引用同一 route；
- `SubagentBinding(name, description)`：canonical 短名 + 可空描述（≤512 字符）的 allowlist 快照；`task` 的 ToolBinding 冻结在 `toolBindings`，allowlist 冻结在 `subagentBindings`，二者在同一个 spec 中配对；
- `compaction` 非 null 时 tool bindings、skill bindings、subagent bindings 必须全空，并冻结 phase/trigger/tokensBefore/firstKept/cut/prefix；
- `ModelDescriptor` 只含 `providerName`/`modelName`/`inputModalities`/`tools`/`reasoning`/`pricing` 六个字段；Provider 类型与 cache capability 由 attempt 时当前 `ProviderFactory` 解析；
- retry 重放同一 spec：每次 attempt 由 `ModelRequestMaterializer` 从相同 `basisHeadEntryId + spec` 重建内存 ProviderRequest；当前失败 attempt 的 partial/thinking/error 不修改 spec，后续 turn 的白名单历史投影同样排除 `MODEL_ATTEMPT_FAILURE` / `ASSISTANT_ERROR`；ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择；
- attempt 时 `DatabaseProviderResolutionService` 按 `providerName` 读取当前 `agent_provider` 行构造短生命周期 Provider，并把持久 cache control 按当前 factory capability 规范化：不兼容能力降级为 `none()`，兼容时按当前 capability 重求形态与断点。

## 6. 权限预检与审批

权限链路固定为 `ToolProcessor（READY 边界读取当前 Thread YOLO 短路）-> ToolGateway.preflight -> CoreToolGateway -> PermissionEvaluator -> Allow/Ask/Deny`。`ToolProcessor` 只在 ToolInvocation 为 `READY` 且尚无 approval 事实时、在当前已锁定的 Thread 上读取 YOLO：为 true 时直接 `Allow`，不调用 permission evaluator；否则把 READY/binding 非空临时构造的 executable request 交给 `ToolGateway.preflight`（`preflight` 不接收 YOLO，也不查询 HarnessStore）。重试、重新调度以及已经进入 `WAITING_APPROVAL` 的 invocation 都不会重新评估。`CoreToolGateway` 通过 `ToolSettingsProvider` 现读数据库 `system_setting` 的 `SystemSettings.Tool.permission` 并评估。V1 默认规则为 `write/edit/bash: ask`，read 不受限，未匹配工具保持 Allow。

Allow 写入 `READY + approval.required=false` 后进入实际 Tool dispatch；Ask 写入 `WAITING_APPROVAL + approval.decision=null`；Deny 写入 `FAILED + PERMISSION_DENIED`。审批 API 把 Allow 决策写成 durable `ALLOWED` 并恢复原 binding 执行，把 Deny 决策写成 durable `DENIED` 与失败终态；`decisionId` 保证幂等，相同 ID 的不同 payload 返回 409。子 Agent Thread 继承父 Thread 的 YOLO，子工具审批复用同一端点并以实际子 ThreadId 寻址。

带 `path` 参数的权限目标只按该次调用的 effective workdir 解析为单一相对 POSIX 路径；raw、Environment root 相对和绝对路径不再作为并列 alias。pattern 使用 JGit `FastIgnoreRule` 的 gitignore 语义（包括 basename、`*`、`**`、根锚定和目录规则），规则仍按全局后工具级、last-match-wins；preflight 不做文件系统 I/O，只有 raw path 末尾显式 `/` 或 `\` 时才把目标本身视为目录，目录规则仍可覆盖其 descendant。由于 action 已表达 Allow/Ask/Deny，path pattern 的 `!` negation 以及空、comment-only、无效 pattern 均拒绝。Bash/普通 command 不使用 gitignore matcher：Bash 继续按顶层静态 surface 分段，动态 wrapper、command/process substitution 与无法可靠解析的复合语法保守进入 Ask（完整命令显式 Deny 除外）。

## 7. 代码与测试

| 目标 | 入口 |
| --- | --- |
| ProviderFactory 装配 | [`ModelExecutionConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/model/ModelExecutionConfiguration.java) |
| ToolFactory 装配 | [`HarnessToolGatewayConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/tool/gateway/HarnessToolGatewayConfiguration.java) |
| Tool 目录 | [`EnvironmentToolCatalog`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/EnvironmentToolCatalog.java) |
| Resolver | [`DatabaseTurnResolver`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/thread/command/DatabaseTurnResolver.java) |
| TurnResolver 端口 | [`TurnResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/TurnResolver.java) |
| ModelGateway 端口 | [`ModelGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ModelGateway.java) |
| ToolGateway 端口 | [`ToolGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolGateway.java) |
