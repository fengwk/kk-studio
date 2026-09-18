# Harness Contributor API

Harness 的工具、分支自定义状态和模型上下文都来自受信任的 Java 代码——内置工具是其中一个 Contributor，团队自己的 JAR 也可以是。这类扩展的危险不在于写得慢，而在于启动后才发现两个 Contributor 抢了同一个工具名、依赖成环、工具偷偷写了别人的状态、或者模型看到的两份定义其实并不一致。本模块把扩展约束全部前移到启动装配期：Contributor 只声明自己能贡献什么，`HarnessCatalog.from` 收集、校验依赖图并按确定性顺序调用一次，冻结出一份全局唯一、不可变的目录；此后运行期只读这份目录。

模块是纯 Java SPI，不含 store、gateway、transaction 或锁（见 [`HarnessRegistrar`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 的接口注释）。生产依赖只有 [`harness-tool`](harness-tool.md) 与 [`harness-environment`](harness-environment.md) 的值契约（见 [`pom.xml`](../../harness/contributor-api/pom.xml)），由 [`ContributorApiModuleArchitectureTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorApiModuleArchitectureTest.java) 守卫；持久化、事务、网关路由、类加载与 Spring 装配分别在 [`harness-runtime`](harness-runtime.md)、[`platform`](platform.md) 与 [`web`](web.md) 侧。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.contributor.api` | Contributor 发现与目录冻结（`HarnessContributor`、`HarnessRegistrar`、`HarnessCatalog`）、统一异步 `Tool` SPI、限定 ownership 的分支自定义状态访问（`BranchView`、`StateDeclaration`、`ToolOutcome`）与纯上下文投影器（`ContextProjector`） | 只声明契约；存储、网关、事务与锁由运行时管理，类加载与插件生命周期由组合根负责 |

## 一个 Contributor 声明什么

[`HarnessContributor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java) 只有两个方法：`descriptor()` 给出静态元数据，`contribute(registrar)` 在同一个 catalog 构建中被调用一次。单次装配的常见写法是用便捷工厂 `HarnessContributor.of(descriptor, registrar -> …)`。

[`ContributorDescriptor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java) 由 canonical `ContributorId`、展示名、版本与 `requires` 前置集合构成；构造期拒绝自依赖并按 id 稳定排序。`ContributorId`、贡献项 `localName`、`customType` 共用同一套 canonical 规则（小写点划线标识符，最长 64 字符），实现在 [`Identifiers`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Identifiers.java)。

[`HarnessRegistrar`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 有三个类型化入口，每个都带 priority 默认 0 的重载：

```text
registerTool(localName, agentToolId, tool, visibility, priority)
registerCustomEntryType(localName, customType, priority)
registerContextProjector(localName, projector, priority)
```

`localName` 只在本 Contributor 内唯一，registrar 在注册时自动补上 owner 形成完整 [`ContributionId`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributionId.java)；在 `contribute` 之外调用会立刻失败，因此贡献项不可能归属到错误的 Contributor。

## 目录如何冻结

[`HarnessCatalog.from`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java) 是唯一构建入口，流程是确定性的：

```text
读取每个 descriptor 一次
  -> 拒绝重复 contributor id、缺失 requires、requires 成环
  -> requires 拓扑 + ContributorId 字典序 排序
  -> 按该顺序逐个调用 contribute 一次
    （注册时校验 localName / 模型可见 name / AgentToolId / customType ownership）
  -> 冻结时校验 stateAccesses 命中所属 Contributor 已注册的 customType
  -> 各扩展点按 requires 偏序、priority 降序、ContributionId 字典序冻结
```

依赖图的完整性在调用任何 `contribute` 之前就已判定：缺失依赖或环路直接终止装配，不会先执行一半再失败。跨 Contributor 的全局唯一性在这里被强制——模型可见的工具调用名与 `AgentToolId` 各自全局唯一，且冲突在注册当时抛出。`registerTool` 会立刻读取 `tool.descriptor()` 与 `tool.requirements()`，因此 [`ToolContribution`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java) 构造时还能复查两者与其声明一致。

自定义状态的所有权键是 `(contributorId, customType)`：不同 Contributor 可以各自拥有同名 `customType`，同一 Contributor 内不得重复。冻结阶段还要求每个 `ToolRequirements.stateAccesses` 都能命中本 Contributor 自己注册过的 Custom Entry Type——工具无法声明访问别人的状态，也无法访问一个从未注册的类型。

排序规则是 `requires` 的传递偏序优先，其次 priority 降序，最后 `ContributionId` 字典序。因此 priority 只在彼此无依赖的项之间决定次序，输入集合的原始顺序对结果没有任何影响。冻结完成后 `descriptors`、`tools`、`selectableTools`、`customEntryTypes`、`contextProjectors` 与 `transitiveRequires` 都是不可变集合，查找入口有：

```text
findTool(model-visible name) / findTool(AgentToolId) / findTool(ContributionId)
findDescriptor / findCustomEntryType(contributorId, customType) / findContextProjector
```

启动期装配意味着 Contributor 集合变更需要重启应用重建目录；JAR 发现与类加载属于 [`web`](web.md) 组合根，不在本模块。

## Tool SPI

[`Tool`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java) 是本地计算、分支状态副作用与环境能力调用的统一执行契约：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements();      // 默认 ToolRequirements.none()
ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener);
```

`execute` 必须启动式、快速返回：实现可以在调用线程触发同步回调，但调用状态持久化为 `RUNNING` 之前回调会被门控缓冲，因此执行状态转换始终有序；返回的 [`ToolExecutionHandle`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionHandle.java) 提供幂等 `cancel()` 与 `isCancelled()`。

[`ToolExecutionRequest`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java) 携带最终 descriptor、已归一化并通过 schema 校验的 `ToolCall`（构造期调用 `validateFor`）、覆盖超时与可选的 `ToolExecutionContext`。超时为零时用 `effectiveTimeout()` 回落到 descriptor 声明的默认值。请求不携带 workdir：目录只存在于具体工具的 arguments 中，框架既不把它提升为通用执行状态，也不提供隐藏默认目录。

[`ToolExecutionListener`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java) 接收 `onPartial(ToolResult)*` 与互斥的 `onComplete(ToolOutcome)` / `onError(Throwable)`：终态至多一次，重复或迟到的回调由运行时侧适配层过滤，实现无需自己防御。

## Environment 能力

[`ToolRequirements`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java) 冻结三项声明：

```text
environmentRequired      是否需要绑定执行环境
requiredEnvironmentId    精确要求的目标 Environment；非空时 environmentRequired 必须为 true
stateAccesses[]          该工具访问的 branch custom state 集合（同一 customType 不得重复）
```

工厂方法 `none()` / `environment()` / `environment(EnvironmentId)` 覆盖三种常见形态，其中第三项用于绑定到指定 Environment 的工具（如本地 MCP 工具）。

需要环境的工具在 [`ToolExecutionContext`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java) 中拿到 `invocationId`、`threadId`、`executedAt`、`branch` 与可选的 [`BoundEnvironment`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)。`BoundEnvironment` 是刻意窄的接口——只有 `environmentId()` 与 `execute(capability, request, listener)`，工具据此执行一个 `EnvironmentCapabilityDescriptor`，而连接注册、路由与协议细节由 Platform 的适配器承担。

环境绑定在持久化侧被冻结：[`ToolBinding`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolBinding.java) 强制 `environmentRequired` 与 `environmentId` 同真同假；Platform 的 [`ToolExecutionGateway`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java) 在执行前用冻结的 definition、Contributor provenance 与 `requiredEnvironmentId` 逐项比对当前目录，任何一项不一致都确定性拒绝执行，绝不按当前配置静默重解释。

## 分支状态与副作用

[`BranchView`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java) 是只读视图，只有 `customEntries(customType)` 与 `latestCustomEntry(customType)` 两个查询。视图严格限定在当前 Contributor 与当前 Assistant 分支的 root-to-head 路径上：兄弟分支与其他 owner 的状态不可见，底层存储句柄与全量会话历史都不暴露。

[`StateDeclaration`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateDeclaration.java) 用 `READ` / `WRITE` 声明访问意图，它的用途不只是文档：同一 Assistant 的多个工具共享同一份冻结分支快照，因此同一 `(contributorId, customType)` 一旦已被先前 sibling 声明 WRITE，后续的 READ 或 WRITE 都必然读到陈旧快照，Runtime 在 dispatch 之前就把它确定性拒绝并提示下一轮再调用（[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 的 sibling state conflict 检查）；READ 之后再来 WRITE 则允许并发。

状态变更通过 [`ToolOutcome`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java) 声明，而不是直接写存储：`customEntries` 是有序的 [`AppendCustomEntry`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java) 列表，每项含 `customType`、正数 `schemaVersion` 与非空 `dataJson`，owner 由当前 Tool contribution 自动决定。`ToolOutcome` 构造期强制正确性约束：错误结果不得携带任何 effects。真正的落库在 [`harness-runtime`](harness-runtime.md) 的 [`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolOutcomeAppender.java) 中，与 ToolResult Entry 在同一事务里按声明顺序追加，校验失败即整体回滚，不产生部分条目。

[`ContextProjector`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java) 把 `BranchView` 纯函数式投影为不可变的 `List<ContextFragment>`，只读、不产生副作用。Platform 在规划下一次模型请求时按 Catalog 冻结顺序执行全部投影器，并把片段按 `system` 消息注入 preamble。

## 扩展一个 Contributor

1. 实现 `HarnessContributor`（多用 `of` 工厂），给出 canonical `ContributorId`、版本与 `requires`。
2. 在 `contribute` 中注册工具：实现 `Tool` 的 `descriptor()` / `requirements()` / `execute()`，选择 `ToolVisibility` 与 priority；需要环境能力就声明 `environment()`，需要精确绑定就声明 `environment(EnvironmentId)`。
3. 要维护分支状态时，先 `registerCustomEntryType(localName, customType, priority)`，再在 `ToolRequirements.stateAccesses` 中声明 READ 或 WRITE；状态载荷与 `AppendCustomEntry` 使用同一个 customType。
4. 要注入模型上下文时注册 `ContextProjector`，只依据 `BranchView` 计算文本。
5. 装配到组合根：组件注册进 Spring 容器或受信任 JAR，由组合根统一交给 `HarnessCatalog.from`。

## 源码与测试

- 冻结与校验：[`HarnessCatalog.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java)、[`HarnessRegistrar.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java)、[`HarnessContributor.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java)、[`Identifiers.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Identifiers.java)
- 执行 SPI：[`Tool.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java)、[`ToolExecutionRequest.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java)、[`ToolExecutionListener.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java)、[`ToolExecutionHandle.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionHandle.java)
- 环境与状态：[`ToolRequirements.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java)、[`BoundEnvironment.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)、[`BranchView.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java)、[`AppendCustomEntry.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java)、[`ContextProjector.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java)
- 改动冻结逻辑先跑 [`HarnessCatalogTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalogTest.java)，它覆盖 DAG、排序、唯一性、freeze 不可变与 registrar 越界拒绝；[`HarnessContractTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContractTest.java) 覆盖 SPI 契约与请求归一化，[`BranchViewTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/BranchViewTest.java) 覆盖 contributor-scoped 状态可见性。参考实现见 [`harness-builtin`](harness-builtin.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Harness Builtin](harness-builtin.md)。
