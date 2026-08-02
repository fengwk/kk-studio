# Harness 能力装配

Core 通过 Spring `ObjectProvider` 直接收集 ProviderFactory、ToolFactory 与 Tool interceptor，并装配成不可变集合、统一 ToolCatalog 和 interceptor chain。

## 1. 装配图

```text
ModelExecutionConfiguration
  -> ProviderFactory beans
  -> ProviderFactories
  -> DatabaseProviderResolutionService

RuntimeToolsConfiguration
  -> ToolFactory beans
  -> ToolFactories
  -> ToolCatalog
       -> AgentDefinitionConfigValidator
       -> DatabaseTurnExecutionResolver
       -> StudioToolCatalogController

BeforeToolCallInterceptor / AfterToolCallInterceptor
  -> HarnessToolConfiguration
  -> ToolInterceptorChain
```

## 2. Provider

[`ProviderFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderFactory.java) 描述一个 ProviderType 的 adapter 工厂：

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
| `openaiProviderFactory` | `OPENAI` | affinity |
| `openaiResponsesProviderFactory` | `OPENAI_RESPONSES` | affinity |
| `anthropicProviderFactory` | `ANTHROPIC` | breakpoints |
| `googleProviderFactory` | `GOOGLE` | automatic |

[`ProviderFactories`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderFactories.java) 按 ProviderType 建立不可变索引，重复注册在构造阶段失败。`ModelExecutionConfiguration` 通过 `ObjectProvider<ProviderFactory>` 收集全部 ProviderFactory。

Provider 资源本身由 Catalog revision 的 `(name, providerVersion)`、`providerType`、base URL、credential 和 JSON config 描述；ProviderFactory 只负责把冻结 revision 解析成 adapter，不读取或回退到当前 Catalog Provider 行。credential 只在写入 DTO 反序列化与 revision 内部 dispatch 使用，不进入 response 或 invocation JSON。

## 3. ToolCatalog

[`ToolFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/tool/ToolFactory.java) 以冻结 descriptor 创建本地 Tool。`ToolFactories` 按 `(name, version)` 索引，重复 key 或 descriptor 漂移在装配/创建时失败。

[`ToolCatalog`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCatalog.java) 只使用两类产品级 Tool，并维护 Agent 可选择目录与内部 Platform Tool 目录的分离：

- Core 提供的 Agent 可选择 Platform ToolFactory；
- 固定的十个 `ENVIRONMENT` Tool descriptor；
- 内部 `PLATFORM` Tool `load_skill`，不出现在 Agent 可选择目录中。

Agent config 保存可选择 Tool/Skill 名称集合，不保存 Tool 实例或 Environment 连接。`GET /api/ai/catalog/tools` 只返回 Agent 可选择的 Platform/Environment 目录；`load_skill` 随本次 Skill binding 由 Runtime 隐式注入，仍属于 Platform Tool。

当前 Environment Tool 名称为：

```text
read
write
edit
apply_patch
bash
grep
find
lsp_goto_definition
lsp_workspace_symbols
lsp_java_decompile
```

## 4. Per-turn resolver

[`DatabaseTurnExecutionResolver`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/thread/command/DatabaseTurnExecutionResolver.java) 是每次 planning 的 Catalog/Environment 边界。输入是消息的 `TurnSettings` 与 Thread 当前 `environmentName`：

```text
TurnSettings
agentName
yoloEnabled

HarnessThread
environmentName
```

解析顺序为最新 Agent → Provider → `(providerName, modelName)` Model → Variant → ToolCatalog/Thread Environment → Tools → Skills → ProviderFactory。Resolver 同时读取 Model config、Agent config、ToolCatalog 与 live registry，返回本轮含 Provider version 的 `ResolvedTurnExecution`。每次新 ModelInvocation 都重新读取这些最新事实。

真正阻止 planning 的 `PlanningFailure` 为：

```text
MISSING_TURN_SETTINGS
AGENT_NOT_FOUND
PROVIDER_NOT_FOUND
MODEL_NOT_FOUND
VARIANT_NOT_FOUND
TOOL_NOT_FOUND
INVALID_TURN_SETTINGS
```

Agent 配置中的 Tool 名必须命中可选择目录，未知 Tool 返回 `TOOL_NOT_FOUND`。Platform Tool 总可候选；Thread 的 `environmentName` 为 null、stale 或对应 Environment 非 READY/offline 时，只返回零 Environment Tool/Skill。当前 Environment 不提供的 Environment Tool/Skill 只被交集过滤，不产生 Environment 或 Skill 缺失错误。

有 Skill binding 时，Resolver 追加内部 Platform `load_skill` binding；Reconciler 把 planning failure 写成 `ASSISTANT_ERROR`，不创建伪 request，也不切换到其他 Agent、Provider、Model、Environment、Tool 或 Skill。Provider 返回本次 request 不可见的 Tool 时，写入包含请求名称和可用 Tool 名称的可恢复 `ASSISTANT_ERROR`，不物化 ToolInvocation，Reconciler 不会卡住。

## 5. Interceptor chain

`HarnessToolConfiguration` 通过 `ObjectProvider<BeforeToolCallInterceptor>` 与 `ObjectProvider<AfterToolCallInterceptor>` 按顺序构造 `ToolInterceptorChain`。唯一的 `PermissionBoundaryInterceptor` 位于 before chain 末端，在 Tool registry lookup、RemoteTool send 和 `Tool.execute` 之前完成 ALLOW/ASK/DENY。

Permission 结果写入 ToolInvocation。批准继续执行原 binding；拒绝写入失败终态并唤醒 owning Thread。

## 6. 代码与测试

| 目标 | 入口 |
| --- | --- |
| ProviderFactory 装配 | [`ModelExecutionConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/model/ModelExecutionConfiguration.java) |
| ToolFactory 装配 | [`RuntimeToolsConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/tool/RuntimeToolsConfiguration.java) |
| Tool 目录 | [`EnvironmentToolCatalog`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/EnvironmentToolCatalog.java) |
| Agent config 校验 | [`AgentDefinitionConfigValidator`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/catalog/definition/service/impl/AgentDefinitionConfigValidator.java) |
| Planner | [`ModelInvocationPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/plan/ModelInvocationPlanner.java) |
| Provider wiring test | [`ProviderFactoriesTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderFactoriesTest.java) |
| Tool wiring test | [`RuntimeToolsWiringTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/ai/runtime/tool/RuntimeToolsWiringTest.java) |
| Agent config test | [`AgentDefinitionConfigValidatorTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/ai/catalog/definition/service/impl/AgentDefinitionConfigValidatorTest.java) |
| Planner test | [`ModelInvocationPlannerTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/model/plan/ModelInvocationPlannerTest.java) |
