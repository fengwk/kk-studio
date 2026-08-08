# Harness 能力装配

Core 通过 Spring `ObjectProvider` 直接收集 ProviderFactory、ToolFactory 与 Tool interceptor，并装配成不可变集合、统一 ToolCatalog 和 gateway。`core` 只做 Catalog/TurnResolver/ModelGateway/ToolGateway 适配，不写 `harness_*` 表；领域状态机全部在 `harness-runtime`。

## 1. 装配图

```text
ModelExecutionConfiguration
  -> ProviderFactory beans
  -> ProviderFactories（按 ProviderType 不可变索引）
  -> CoreModelGateway（serialized FIFO 回调桥）

HarnessToolGatewayConfiguration
  -> ToolFactory beans
  -> ToolFactories（按 (name, version) 索引）
  -> ToolCatalog（PLATFORM / ENVIRONMENT 两类 + 内部 load_skill）
  -> CoreToolGateway（preflight + 两阶段激活 + FIFO 回调桥）

DatabaseTurnResolver
  -> Agent/Provider/Model/Variant Catalog 查询（按名称读最新行）
  -> ToolCatalog + LiveEnvironmentRegistry
  -> ProviderFactory（由当前行 providerType 解析，派生 cache policy）
  -> 冻结 ModelInvocationRequest（Provider/Model 只按名称引用）

CoreModelGateway.start（每次 attempt）
  -> DatabaseProviderResolutionService 按 providerName 读取当前 agent_provider 行
  -> 当前 ProviderFactory.create(当前 credential/config) -> attempt-local adapter
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

- selectable Platform ToolFactory（Agent 可选择）：
  - `create_goal` / `get_goal` / `update_goal`（Goal 工具，`RuntimeToolsConfiguration` 以 `@ConditionalOnBean(GoalStore.class)` 条件装配为 `ToolFactory` bean；`DatabaseGoalStore` 提供 durable `agent_thread_goal` 存储）；
  - 其他 Core 提供的 Platform ToolFactory；
- 固定的十个 `ENVIRONMENT` Tool descriptor：
  `read, write, edit, bash, grep, find, lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile`；
- **`load_skill` 是唯一 internal Platform Tool**（`ToolCatalog(descriptors, Set.of(LoadSkillTool.NAME))`），从 selectable 集合移出，不出现在 Agent 可选择目录中。

`GET /api/ai/catalog/tools` 只返回 Agent 可选择的 Platform/Environment 目录。Agent config 保存可选择 Tool 名称集合，不保存 Tool 实例或 Environment 连接。

`CoreToolGateway` 是 `ToolGateway` 端口适配：`preflight` 同步无副作用（`Allow` / `Ask(reason)` / `Deny(error)`），外部 I/O 前完成权限判定与机械校验；两阶段激活与 Model 同构；`PLATFORM` binding 走本地 registry，`ENVIRONMENT` binding 经 `RemoteToolTransport`（`EnvironmentDaemonGateway`）发往冻结 route。terminal success 在回调桥内经 `ToolResultExternalizer` 做 durable Resource 外部化（reference plan → put → exact ref check），再把已外部化的 ToolResult 交给 ToolProcessor 落库。

## 4. BranchSettings 到冻结请求

每次 turn 的输入是 head Entry 分支的完整 `BranchSettings`（ROOT/TURN_START 固化）：

```java
public record BranchSettings(
    EnvironmentName environmentName, // canonical 路由名称，可 null
    String agentName,
    ModelSelection model,          // providerName/modelName/variant
    String thinkingLevel,
    List<String> activeTools) {}
```

`DatabaseTurnResolver.resolve(threadId, candidatePath, yoloEnabled)` 的解析顺序：

```text
candidate path 最近 TURN_START 的 BranchSettings
  -> AgentDefinition（agentName）
  -> Provider / (providerName, modelName) Model / effective Variant
  -> ToolCatalog + activeTools（必须命中可选择目录）
  -> environmentName 路由 + LiveEnvironmentRegistry（READY + 心跳未过期才可用）
  -> ProviderFactory（按当前行 providerType 派生 cache policy）
  -> 冻结 ModelInvocationRequest（Agent/Model 修改下一 turn 生效）
```

**fail closed（Environment route 规则）**：

- **latest-snapshot-wins**：解析只使用 candidate path 最近一个 ROOT/TURN_START 的**完整** `BranchSettings` 快照（`EntryPath.baseSettings()` 逐项取最新）；快照中的 null/缺失/不可用值（如 `environmentName` 为 null、agent/Environment 已不存在）**绝不触发向更旧 ROOT/TURN_START 快照回退**——更旧快照中的非 null environment 或仍有效的 agent 不再参与解析；
- **ENVIRONMENT 工具规划不拒绝**：一律按最新 `BranchSettings.environmentName()` 绑定（null/缺失/未 READY 都放行）；实际 start 时 null route 或目标不可用（未注册/未 READY/心跳过期）→ `Rejected`（`UNAVAILABLE`），durable `FAILED` ToolResult 对模型可见，turn 收敛；
- **Agent skills 规划要求最新选中 Environment live**：缺失/未 READY/分支无名称都是确定性拒绝（精确 message），绝不回看更旧 settings；
- Agent 配置中的 Tool 名必须命中可选择目录，未知 Tool 拒绝；Platform Tool 总可候选。

**确定性拒绝**：所有 planning rejection 共用稳定 `AssistantError` code **`PLANNING_FAILED`**（`DatabaseTurnResolver.REJECTION_CODE`），message 携带具体原因（缺失 Agent/Provider/Model/Variant、未知 Tool、Environment 不可用等）——不存在按类别区分的独立拒绝码列表。

`Rejected` 由 Processor 写成 `ASSISTANT_ERROR` barrier（+ `FAILED` TURN_END），不产生 ModelInvocation，也不切换到其他 Agent/Provider/Model/Environment/Tool/Skill。Resolver 抛异常表示临时基础设施失败，由 Processor reschedule。

**Skills**：只从 Agent config 读取，必须由选中 READY Environment 精确提供，且 `activeTools` 必须显式包含内部 `load_skill`（`agent has skills but activeTools must include load_skill` 拒绝）；`load_skill` **不是** Resolver 隐式追加，也不在 selectable catalog。Provider 返回冻结 request 中不可见的 Tool 时，Model Invocation 终结失败，Agent Loop 写入 `ASSISTANT_ERROR` 并关闭该 Turn，不物化 ToolInvocation。

## 5. 冻结 request 的不变量

`ModelInvocationRequest(environmentName, providerRequest, toolBindings, skillBindings, yoloEnabled)`：

- `providerRequest.tools` 与 `toolBindings` 数量、顺序、名称一一对应；
- `ToolBinding(descriptor, type, environmentName)`：`PLATFORM` 的 route 为 null，`ENVIRONMENT` 指向具体 route（可为 null）；descriptor 的 type 与 binding type 一致；
- tool/skill binding 名称不重复；每个 environment-bound tool/skill 引用本请求 route；
- `ModelDescriptor` 只含 `providerName`/`modelName`/`tools`/`reasoning`/`pricing` 五个字段；Provider 类型与 cache capability 由 attempt 时当前 `ProviderFactory` 解析；
- retry 重放同一 request；ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择；
- attempt 时 `DatabaseProviderResolutionService` 按 `providerName` 读取当前 `agent_provider` 行构造短生命周期 Provider，并把持久 cache control 按当前 factory capability 规范化：不兼容能力降级为 `none()`，兼容时按当前 capability 重求形态与断点。

## 6. Interceptor chain

`HarnessToolGatewayConfiguration` 通过 `ObjectProvider<BeforeToolCallInterceptor>` / `ObjectProvider<AfterToolCallInterceptor>` 按顺序构造 interceptor chain；唯一的 `PermissionBoundaryInterceptor` 位于 before chain 末端，在 registry lookup、RemoteTool send 和 `Tool.execute` 之前完成 Allow/Ask/Deny。权限结果写入 ToolInvocation approval 事实（durable `ALLOWED`/`DENIED`）；批准继续执行原 binding，拒绝写入失败终态并唤醒 owning Thread。

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
