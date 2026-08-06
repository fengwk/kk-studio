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
  -> Agent/Provider/Model/Variant Catalog 查询
  -> ToolCatalog + LiveEnvironmentRegistry
  -> ProviderFactory 解析 adapter
  -> 冻结 ModelInvocationRequest
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

`ProviderFactories` 按 ProviderType 建立不可变索引，重复注册在构造阶段失败。Provider 资源由 Catalog revision 描述；ProviderFactory 只负责把冻结 revision 解析成 adapter，不读取或回退到当前 Provider 行。credential 只在写入 DTO 反序列化与 revision 内部 dispatch 使用，不进入 response 或 invocation JSON。

`CoreModelGateway` 是 `ModelGateway` 端口适配：admission 两阶段激活（`start` → Processor `markRunning` 后 `activate`），回调桥是 serialized FIFO 单 drainer 状态机，terminal-once；`Busy` 重试、`Rejected` 确定性终结、`Indeterminate` 收敛 `UNKNOWN`。

## 3. ToolCatalog

`ToolCatalog` 只使用两类产品级 Tool，并维护 Agent 可选择目录与内部 Platform Tool 目录的分离：

- selectable Platform ToolFactory（Agent 可选择）：
  - `create_goal` / `get_goal` / `update_goal`（Goal 工具，`RuntimeToolsConfiguration` 以 `@ConditionalOnBean(GoalStore.class)` 条件装配为 `ToolFactory` bean；`DatabaseGoalStore` 提供 durable `agent_thread_goal` 存储）；
  - 其他 Core 提供的 Platform ToolFactory；
- 固定的十个 `ENVIRONMENT` Tool descriptor：
  `read, write, edit, apply_patch, bash, grep, find, lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile`；
- **`load_skill` 是唯一 internal Platform Tool**（`ToolCatalog(descriptors, Set.of(LoadSkillTool.NAME))`），从 selectable 集合移出，不出现在 Agent 可选择目录中。

`GET /api/ai/catalog/tools` 只返回 Agent 可选择的 Platform/Environment 目录。Agent config 保存可选择 Tool 名称集合，不保存 Tool 实例或 Environment 连接。

`CoreToolGateway` 是 `ToolGateway` 端口适配：`preflight` 同步无副作用（`Allow` / `Ask(reason)` / `Deny(error)`），外部 I/O 前完成权限判定与机械校验；两阶段激活与 Model 同构；`PLATFORM` binding 走本地 registry，`ENVIRONMENT` binding 经 `RemoteToolTransport`（`EnvironmentDaemonGateway`）发往冻结 route。terminal success 在回调桥内经 `ToolResultExternalizer` 做 durable Resource 外部化（reference plan → put → exact ref check），再把已外部化的 ToolResult 交给 ToolProcessor 落库。

## 4. BranchSettings 到冻结请求

每次 turn 的输入是 head Entry 分支的完整 `BranchSettings`（ROOT/TURN_START 固化）：

```java
public record BranchSettings(
    EnvironmentId environmentId,   // canonical UUID route，可 null
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
  -> environmentId 路由 + LiveEnvironmentRegistry（READY 才可用）
  -> ProviderFactory（冻结 provider revision）
  -> 冻结 ModelInvocationRequest
```

**fail closed（Environment route 规则）**：

- **非 null `environmentId` 一律必须存在且 READY**（`environment not found` / `environment is not ready` 拒绝），**即使本 turn 仅 Platform Tool**——route 解析先于 tool 绑定，选中了 Environment 就必须可用；
- **null `environmentId` 只允许 platform-only 且无 skills 的 turn**：任何 ENVIRONMENT tool（`environment tool requires a selected environment: <name>`）或 Agent skill（`agent skills require a selected environment`）都是确定性拒绝，绝不静默省略；
- Agent 配置中的 Tool 名必须命中可选择目录，未知 Tool 拒绝；Platform Tool 总可候选。

**确定性拒绝**：所有 planning rejection 共用稳定 `AssistantError` code **`PLANNING_FAILED`**（`DatabaseTurnResolver.REJECTION_CODE`），message 携带具体原因（缺失 Agent/Provider/Model/Variant、未知 Tool、Environment 不可用等）——不存在按类别区分的独立拒绝码列表。

`Rejected` 由 Processor 写成 `ASSISTANT_ERROR` barrier（+ `FAILED` TURN_END），不产生 ModelInvocation，也不切换到其他 Agent/Provider/Model/Environment/Tool/Skill。Resolver 抛异常表示临时基础设施失败，由 Processor reschedule。

**Skills**：只从 Agent config 读取，必须由选中 READY Environment 精确提供，且 `activeTools` 必须显式包含内部 `load_skill`（`agent has skills but activeTools must include load_skill` 拒绝）；`load_skill` **不是** Resolver 隐式追加，也不在 selectable catalog。Provider 返回冻结 request 中不可见的 Tool 时，Model Invocation 终结失败，Agent Loop 写入 `ASSISTANT_ERROR` 并关闭该 Turn，不物化 ToolInvocation。

## 5. 冻结 request 的不变量

`ModelInvocationRequest(environmentId, providerRequest, toolBindings, skillBindings, yoloEnabled)`：

- `providerRequest.tools` 与 `toolBindings` 数量、顺序、名称一一对应；
- `ToolBinding(descriptor, type, environmentId)`：`PLATFORM` 的 route 为 null，`ENVIRONMENT` 指向具体 route；descriptor 的 type 与 binding type 一致；
- tool/skill binding 名称不重复；每个 environment-bound tool/skill 引用本请求 route；
- retry 重放同一 request；ToolInvocation 执行同一 binding，不从最新 Agent/Environment 重新选择。

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
