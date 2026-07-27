# Harness 能力装配

本文描述 Harness Runtime 当前生效的直接能力装配：core 通过 Spring `ObjectProvider` 收集所有 `ProviderFactory` / `ToolFactory` / `BeforeToolCallInterceptor` / `AfterToolCallInterceptor` bean，并把它们装配到 typed collection 与 interceptor chain。

## 设计目标

- 单一来源：能力由 Spring 容器声明并按类型直接收集。
- 构造期可验证：重复 provider type、重复 `(name, version)`、null descriptor 在集合构造阶段被拒绝。
- 唯一权限边界：`PermissionEvaluator` 是 `ToolInterceptorChain` 中唯一的 `PermissionBoundaryInterceptor`。

## 模块边界

| 模块 | 职责 |
| --- | --- |
| `harness.runtime.model.provider` | `ProviderFactory`、`ProviderFactories`、`ProviderType` 等 Provider 适配契约 |
| `harness.runtime.tool` | `ToolFactory`、`ToolFactories`、`BeforeToolCallInterceptor`、`AfterToolCallInterceptor`、`PermissionBoundaryInterceptor`、`ToolInterceptorChain` |
| `harness.runtime.tool.worker` | `ToolWorker`、`ToolRegistry`、`ToolInvocationTransactions` 等持久执行模型 |

## 装配图

```
ModelExecutionConfiguration
        │
        ├─ ProviderFactory beans (openai/openai_responses/anthropic/google)
        │
        ▼ ObjectProvider<ProviderFactory>
        └─ ProviderFactories ──► DatabaseProviderResolutionService

PlatformToolsConfiguration
        │
        ├─ ToolFactory beans (per platform Tool)
        │
        ▼ ObjectProvider<ToolFactory>
        └─ ToolFactories ──► ToolRegistry（toolFactories::find）
                           └─► AgentDefinitionLiveCapabilityValidator
                           └─► RuntimeConfigSnapshotResolver

BeforeToolCallInterceptor beans (incl. PermissionEvaluator)
AfterToolCallInterceptor beans
        │
        ▼ ObjectProvider<...>
HarnessToolConfiguration ──► ToolInterceptorChain

```

## ProviderFactory

[`ProviderFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderFactory.java) 描述单个 Provider 类型的工厂：

```java
public interface ProviderFactory {
  ProviderType providerType();
  PromptCacheCapability promptCacheCapability();
  ProviderAdapter create(String credential, String configJson);
}
```

`ProviderFactory.of(type, capability, ctor)` 是一个直接构造助手：固定 `ProviderType`、固定 cache capability、并通过 `BiFunction<String, String, ProviderAdapter>` 构造 adapter；每次 `create` 时校验返回的 adapter 报告的 `ProviderType` 与工厂一致。

### Core composition

[`ModelExecutionConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/harness/model/ModelExecutionConfiguration.java) 暴露四个 named `ProviderFactory` bean：

| Bean name | ProviderType | Cache capability |
| --- | --- | --- |
| `openaiProviderFactory` | `OPENAI` | `affinity(SHORT)` |
| `openaiResponsesProviderFactory` | `OPENAI_RESPONSES` | `affinity(SHORT)` |
| `anthropicProviderFactory` | `ANTHROPIC` | `breakpoints(SHORT, {SYSTEM, TOOLS})` |
| `googleProviderFactory` | `GOOGLE` | `automatic()` |

每个 bean 都按 bean name 触发 `@ConditionalOnMissingBean(name=...)`，避免 Spring 按返回类型匹配时把四个同名 `ProviderFactory` bean 互相覆盖。

### `ProviderFactories`

[`ProviderFactories`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderFactories.java) 是不可变的 `ProviderFactory` 集合，按 `ProviderType` 索引。同一 `ProviderType` 上两条注册在构造时以 `IllegalArgumentException` 失败。adapter 的 providerType 一致性只能在 `ProviderFactory#create` 时验证。

`ModelExecutionConfiguration` 通过 `ObjectProvider<ProviderFactory>` 收集 Spring 容器中的所有 `ProviderFactory` bean 并装配到 `ProviderFactories`。

消费方：
- [`DatabaseProviderResolutionService`](../../core/src/main/java/fun/fengwk/kkstudio/core/harness/model/DatabaseProviderResolutionService.java) 用 `ProviderFactories.lookup(ProviderType)` 解析持久化 Provider 行对应的 adapter。

## ToolFactory

[`ToolFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/tool/ToolFactory.java) 按冻结 descriptor 创建工具：

```java
public interface ToolFactory {
  ToolDescriptor descriptor();
  Tool create();

  static ToolFactory singleton(Tool tool) { ... }
}
```

`ToolFactory.singleton(tool)` 把现成 `Tool` 包成 `ToolFactory`，descriptor 在闭包内冻结；`create()` 返回前校验当前 Tool 的 name/version 仍与冻结 descriptor 一致。

### Core composition

[`PlatformToolsConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/harness/tool/PlatformToolsConfiguration.java) 暴露 4 个具体 `Tool` bean（`CreateGoalTool` / `GetGoalTool` / `UpdateGoalTool` / `LoadSkillTool`）以及对应的 `ToolFactory` bean（`createGoalToolFactory` / `getGoalToolFactory` / `updateGoalToolFactory` / `loadSkillToolFactory`）。

### `ToolFactories`

[`ToolFactories`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/tool/ToolFactories.java) 是不可变的 `ToolFactory` 集合，按 `(name, version)` 索引。提供两个对外方法：

- `descriptors()`：注册顺序的冻结 descriptor 列表，供 resolver/validator 校验 tool name。
- `find(name, version)`：`ToolRegistry` 友好的查询；未注册即返回 `Optional.empty()`；descriptor mismatch 抛 `IllegalArgumentException`。

重复 `(name, version)` 在构造时以 `IllegalArgumentException` 失败；重复 tool 名下的多个版本同样在 `find` 时维持各自 key。

`PlatformToolsConfiguration` 通过 `ObjectProvider<ToolFactory>` 装配。

消费方：
- [`AgentDefinitionLiveCapabilityValidator`](../../core/src/main/java/fun/fengwk/kkstudio/core/agent/definition/service/impl/AgentDefinitionLiveCapabilityValidator.java) 通过 `ToolFactories.descriptors()` 列出 platform tool name。
- [`RuntimeConfigSnapshotResolver`](../../core/src/main/java/fun/fengwk/kkstudio/core/harness/thread/command/RuntimeConfigSnapshotResolver.java) 同上。
- [`HarnessToolWorkerConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/harness/tool/worker/HarnessToolWorkerConfiguration.java) 把 `ToolFactories.find(name, version)` 暴露为 `ToolRegistry` bean。

## ToolInterceptorChain

[`ToolInterceptorChain`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/tool/ToolInterceptorChain.java) 按输入顺序执行 before / after interceptor，并把唯一的 `PermissionBoundaryInterceptor` 移到 before 链末尾。

[`HarnessToolConfiguration`](../../core/src/main/java/fun/fengwk/kkstudio/core/harness/tool/service/HarnessToolConfiguration.java) 通过 `ObjectProvider<BeforeToolCallInterceptor>` / `ObjectProvider<AfterToolCallInterceptor>` 直接装配：

```java
@Bean
public ToolInterceptorChain toolInterceptorChain(
    ObjectProvider<BeforeToolCallInterceptor> beforeInterceptors,
    ObjectProvider<AfterToolCallInterceptor> afterInterceptors) {
  return new ToolInterceptorChain(
      beforeInterceptors.orderedStream().toList(), afterInterceptors.orderedStream().toList());
}
```

`PermissionEvaluator` 作为唯一的 `PermissionBoundaryInterceptor` 由同一容器显式提供，保持为唯一权限边界。

## 测试覆盖

- [`ProviderFactoriesTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderFactoriesTest.java)：重复 provider type、null ctor、adapter type mismatch、cache capability 透传、create 一次性调用。
- [`ToolFactoriesTest`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/tool/ToolFactoriesTest.java)：重复 (name, version) 拒绝、descriptor 顺序、find 命中/缺席、descriptor mismatch 与 singleton descriptor 漂移拒绝。
- [`ModelExecutionConfigurationTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/harness/model/ModelExecutionConfigurationTest.java)：四个 named ProviderFactory bean 全部进入 `ProviderFactories`，并创建各自对应的 adapter。
- [`PlatformToolsWiringTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/harness/tool/PlatformToolsWiringTest.java)：四个 platform Tool 全部可经 `ToolFactories.find` 解析。
- [`AgentDefinitionLiveCapabilityValidatorTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/agent/definition/service/impl/AgentDefinitionLiveCapabilityValidatorTest.java) / [`RuntimeConfigSnapshotResolverTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/harness/thread/command/RuntimeConfigSnapshotResolverTest.java) / [`DatabaseModelExecutionResolverTest`](../../core/src/test/java/fun/fengwk/kkstudio/core/harness/model/DatabaseModelExecutionResolverTest.java)：consumer 切换到 typed collection 后行为保持。
