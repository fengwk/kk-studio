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

[`ToolCatalog`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCatalog.java) 合并：

- Core 本地 ToolFactory；
- 固定的十个 Environment Tool descriptor；
- runtime-managed `load_skill`。

Agent config 保存 Tool/Skill 名称集合，不保存 Tool 实例或 Environment 连接。`GET /api/ai/catalog/tools` 只返回可由 Agent 选择的目录；`load_skill` 由 Runtime 根据 selected Skill binding 注入。

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

[`DatabaseTurnExecutionResolver`](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/thread/command/DatabaseTurnExecutionResolver.java) 是每次 planning 的 Catalog/Environment 边界。输入只有 `TurnSettings`：

```text
agentName
environmentName
yoloEnabled
```

解析顺序为 active Agent → active Provider → `(providerName, modelName)` Model → Variant → READY Environment → Tools → Skills → ProviderFactory。Resolver 同时读取 Model config、Agent config、ToolCatalog 与 live registry，返回本轮含 Provider version 的 `ResolvedTurnExecution`。

缺失或不可用资源返回明确 `PlanningFailure`：

```text
AGENT_NOT_FOUND
PROVIDER_NOT_FOUND
MODEL_NOT_FOUND
VARIANT_NOT_FOUND
ENVIRONMENT_NOT_FOUND
TOOL_NOT_FOUND
SKILL_NOT_FOUND
INVALID_TURN_SETTINGS
```

Reconciler 把该结果写成 `ASSISTANT_ERROR`，不创建伪 request，也不切换到其他 Agent、Provider、Model、Environment、Tool 或 Skill。

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
