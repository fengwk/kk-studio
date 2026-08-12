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
  -> loadSkillTool / TaskTool（内部 PLATFORM Tool beans）
  -> SubagentConfig（maxDepth/并发/idle/maxTurns/poll）+ SubagentRunRegistry（进程内并发 reservation）

DatabaseTurnResolver
  -> Agent/Provider/Model/Variant Catalog 查询（按名称读最新行）
  -> ToolCatalog + PluginCatalog + LiveEnvironmentRegistry
  -> ContextProjector(candidate BranchView)
  -> ProviderFactory（由当前行 providerType 解析，派生 cache policy）
  -> AgentPromptComposer（Agent 正文 -> current_environment -> available_skills -> available_subagents）
  -> 冻结 ModelInvocationRequest（Provider/Model 只按名称引用；含 subagentBindings）

CoreModelGateway.start（每次 attempt）
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

`CoreModelGateway` 是 `ModelGateway` 端口适配：admission 两阶段激活（`start` → Processor `markRunning` 后 `activate`），回调桥是 serialized FIFO 单 drainer 状态机，terminal-once；`Busy` 重试、`Rejected` 确定性终结、`Indeterminate` 收敛 `UNKNOWN`。

## 3. ToolCatalog

`ToolCatalog` 只使用两类产品级 Tool，并维护 Agent 可选择目录与内部 Platform Tool 目录的分离：

- selectable Platform：
  - 冻结 `PluginCatalog` 的 SELECTABLE contributions；当前包括 Goal 插件 `create_goal` / `get_goal` / `update_goal` v2；
  - Core 提供的其他 Platform `ToolFactory`；
- 固定的十一个 `ENVIRONMENT` Tool descriptor：
  `read, write, edit, bash, grep, find, lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile, mcp_list_tools, mcp_call_tool`；
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

## 4. BranchSettings 到冻结请求

每次 turn 的输入是 head Entry 分支的完整 `BranchSettings`（ROOT/TURN_START 固化）：

```java
public record BranchSettings(
    EnvironmentName environmentName, // canonical 路由名称，可 null
    String agentName,
    ModelSelection model,          // providerName/modelName/variant
    List<String> activeTools) {}
```

`DatabaseTurnResolver.resolve(threadId, candidatePath, yoloEnabled, compactionPreparation)` 分为正常 turn 与 compaction turn。正常解析顺序：

```text
candidate path 最近 TURN_START 的 BranchSettings
  -> AgentDefinition（agentName）
  -> Provider / (providerName, modelName) Model / effective Variant
  -> ToolCatalog + PluginCatalog + activeTools（必须命中可选择目录或内部 load_skill/task）
  -> environmentName 路由 + LiveEnvironmentRegistry（READY + 心跳未过期才可用）
  -> CurrentEnvironmentContext（单一 instant；capabilities metadata 或服务端 Clock zone）
  -> skills（Agent config；最新选中 Environment 精确提供 + 显式 load_skill）
  -> subagents（Agent config allowlist；activeTools 含 task + depth < maxDepth 才绑定）
  -> 插件 ContextProjector(candidate BranchView)
  -> AgentPromptComposer（Agent 正文 -> 始终存在的 current_environment -> available_skills -> available_subagents）
  -> ProviderFactory（按当前行 providerType 派生 cache policy）
  -> 冻结 ModelInvocationRequest（Agent/Model 修改下一 turn 生效；含 subagentBindings）
```

**fail closed（Environment route 规则）**：

- **latest-snapshot-wins**：解析只使用 candidate path 最近一个 ROOT/TURN_START 的**完整** `BranchSettings` 快照（`EntryPath.baseSettings()` 逐项取最新）；快照中的 null/缺失/不可用值（如 `environmentName` 为 null、agent/Environment 已不存在）**绝不触发向更旧 ROOT/TURN_START 快照回退**——更旧快照中的非 null environment 或仍有效的 agent 不再参与解析；
- **ENVIRONMENT 工具规划不拒绝**：一律按最新 `BranchSettings.environmentName()` 绑定（null/缺失/未 READY 都放行）；实际 start 时 null route 或目标不可用（未注册/未 READY/心跳过期）→ `Rejected`（`UNAVAILABLE`），durable `FAILED` ToolResult 对模型可见，turn 收敛；
- **Agent skills 规划要求最新选中 Environment live**：缺失/未 READY/分支无名称都是确定性拒绝（精确 message），绝不回看更旧 settings；
- **current_environment prompt 不改变路由语义**：块始终只含 name/system/date/note；选中条目只要 capabilities 非 null 就使用 READY OS/note/timeZone 计算上下文，无 metadata 时 OS/note 为 `none` 并回退服务端 Clock zone。status、heartbeat、workdir、时间与 timeZone 不进入 Prompt；它只冻结模型上下文，不参与 Tool/Skill 的实时 ready 校验；
- **task/subagent 规划规则**：`task` 只在 activeTools 显式含 task、allowlist 非空且 depth < maxDepth 时绑定；allowlist 名称必须解析到现存 Agent（名称 + 描述冻结为 `subagentBindings`），执行绝不重读父 Agent 配置扩权；
- Agent 配置中的 Tool 名必须命中可选择目录，未知 Tool 拒绝；内部 Platform Tool（load_skill/task）必须显式出现在 activeTools 才能绑定，不是隐式追加。

**确定性拒绝**：所有 planning rejection 共用稳定 `AssistantError` code **`PLANNING_FAILED`**（`DatabaseTurnResolver.REJECTION_CODE`），message 携带具体原因（缺失 Agent/Provider/Model/Variant、未知 Tool、Environment 不可用等）——不存在按类别区分的独立拒绝码列表。

`Rejected` 由 Processor 写成 `ASSISTANT_ERROR` barrier（+ `FAILED` TURN_END），不产生 ModelInvocation，也不切换到其他 Agent/Provider/Model/Environment/Tool/Skill。Resolver 抛异常表示临时基础设施失败，由 Processor reschedule。

**Skills**：只从 Agent config 读取，必须由选中 READY Environment 精确提供，且 `activeTools` 必须显式包含内部 `load_skill`（`agent has skills but activeTools must include load_skill` 拒绝）；`load_skill` **不是** Resolver 隐式追加，也不在 selectable catalog。Provider 返回冻结 request 中不可见的 Tool 时，Model Invocation 终结失败，Agent Loop 写入 `ASSISTANT_ERROR` 并关闭该 Turn，不物化 ToolInvocation。

Compaction resolver 不读取 Agent prompt、plugin projector、Environment live 能力或 prompt cache，不调用 `AgentPromptComposer`，因此不注入 `<current_environment>`，也不绑定 tool/skill/subagent；它只使用 branch 引用的 provider/model/catalog Variant 与 planner 冻结事实，构造一个 summarization SYSTEM + 一个 USER request。Variant 是唯一模型请求预设，其 reasoning effort 与其余请求参数一并生效；contextWindow 沿用触发 invocation 冻结值，输出上限取有效 model/variant max output 与 phase reserve budget 的较小值。

## 5. 冻结 request 的不变量

`ModelInvocationRequest(environmentName, providerRequest, toolBindings, skillBindings, subagentBindings, yoloEnabled, contextWindow, compaction)`：

- `providerRequest.tools` 与 `toolBindings` 数量、顺序、名称一一对应；
- `ToolBinding(descriptor, type, environmentName, plugin)`：`PLATFORM` 的 route 为 null，`ENVIRONMENT` 指向具体 route（可为 null）；descriptor 的 type 与 binding type 一致；普通 Tool 的 plugin 为 null；
- plugin binding 仅允许 `PLATFORM`，冻结 canonical `pluginId`、`contributionLocalName` 与有序唯一 state accesses；retry/重启后仍按该 provenance 恢复，不按工具名猜 owner；
- tool/skill/subagent binding 名称各自不重复；每个 environment-bound tool/skill 引用本请求 route；
- `SubagentBinding(name, description)`：canonical 短名 + 可空描述（≤512 字符）的 allowlist 快照；`task` 的 ToolBinding 冻结在 `toolBindings`，allowlist 冻结在 `subagentBindings`，二者在同一个 request 中配对；
- `contextWindow` 是冻结正 int；`compaction` 非 null 时 provider tools、tool bindings、skill bindings、subagent bindings 必须全空，并冻结 phase/trigger/tokensBefore/firstKept/cut/prefix；
- `ModelDescriptor` 只含 `providerName`/`modelName`/`inputModalities`/`tools`/`reasoning`/`pricing` 六个字段；Provider 类型与 cache capability 由 attempt 时当前 `ProviderFactory` 解析；
- retry 重放同一 request；ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择；
- attempt 时 `DatabaseProviderResolutionService` 按 `providerName` 读取当前 `agent_provider` 行构造短生命周期 Provider，并把持久 cache control 按当前 factory capability 规范化：不兼容能力降级为 `none()`，兼容时按当前 capability 重求形态与断点。

## 6. Interceptor chain

`HarnessToolGatewayConfiguration` 通过 `ObjectProvider<BeforeToolCallInterceptor>` / `ObjectProvider<AfterToolCallInterceptor>` 按顺序构造 interceptor chain；唯一的 `PermissionBoundaryInterceptor` 位于 before chain 末端，在 registry lookup、RemoteTool send 和 `Tool.execute` 之前完成 Allow/Ask/Deny。`CoreToolGateway.preflight` 中 YOLO 在加载权限 settings/evaluator 之前直接返回 Allow（两处都不存在第二套权限实体）；权限结果写入 ToolInvocation approval 事实（durable `ALLOWED`/`DENIED`）；批准继续执行原 binding，拒绝写入失败终态并唤醒 owning Thread。子 Agent Thread 继承父 Thread 的 YOLO 策略，子工具审批复用同一 approval 端点。

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
