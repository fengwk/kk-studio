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
    （注册时校验 localName / 模型可见 name / customType ownership）
  -> 冻结时校验 stateAccesses 命中所属 Contributor 已注册的 customType
  -> 各扩展点按 requires 偏序、priority 降序、ContributionId 字典序冻结
```

依赖图的完整性在调用任何 `contribute` 之前就已判定：缺失依赖或环路直接终止装配，不会先执行一半再失败。跨 Contributor 的全局唯一性在这里被强制——模型可见的工具 name 是唯一 Agent 侧身份，全局唯一且冲突在注册当时抛出；`ContributionId` 只在所属 contributor 内唯一。`registerTool` 会立刻读取 `tool.descriptor()` 与 `tool.requirements()`，因此 [`ToolContribution`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java) 构造时还能复查两者与其声明一致。

自定义状态的所有权键是 `(contributorId, customType)`：不同 Contributor 可以各自拥有同名 `customType`，同一 Contributor 内不得重复。冻结阶段还要求每个 `ToolRequirements.stateAccesses` 都能命中本 Contributor 自己注册过的 Custom Entry Type——工具无法声明访问别人的状态，也无法访问一个从未注册的类型。

排序规则是 `requires` 的传递偏序优先，其次 priority 降序，最后 `ContributionId` 字典序。因此 priority 只在彼此无依赖的项之间决定次序，输入集合的原始顺序对结果没有任何影响。冻结完成后 `descriptors`、`tools`、`selectableTools`、`customEntryTypes`、`contextProjectors` 与 `transitiveRequires` 都是不可变集合，查找入口有：

```text
findTool(model-visible name) / findTool(ContributionId)
findDescriptor / findCustomEntryType(contributorId, customType) / findContextProjector
```

启动期装配意味着 Contributor 集合变更需要重启应用重建目录；JAR 发现与类加载属于 [`web`](web.md) 组合根，不在本模块。

## Tool SPI

[`Tool`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java) 是本地计算、分支状态副作用与环境能力调用的统一执行契约：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements();                  // 默认 ToolRequirements.none()
default Duration resolveTimeout(ToolCall call);           // 默认返回 descriptor.defaultTimeout()
default Optional<ToolHistoryRenderer> historyRenderer();  // 默认 absent：Runtime 使用通用回退
ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener);
```

`execute` 必须启动式、快速返回：实现可以在调用线程触发同步回调，但调用状态持久化为 `RUNNING` 之前回调会被门控缓冲，因此执行状态转换始终有序；返回的 [`ToolExecutionHandle`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionHandle.java) 提供幂等 `cancel()` 与 `isCancelled()`。

`resolveTimeout` 是执行前唯一的超时解析点：入参是已归一化并通过 schema 校验的 `ToolCall`，默认实现返回 definition 的 `defaultTimeout()`，只有真正拥有 arguments 级超时契约的工具才覆盖它。返回值必须是原样的最终结果：`Duration.ZERO` 表示没有 execution deadline，下游不得再回落实现默认值或施加上限；非法 arguments 级超时必须抛出 `IllegalArgumentException` 而不是退回默认值，Gateway 会把它收敛为确定性的 `INVALID_REQUEST` 拒绝。

[`ToolExecutionRequest`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java) 携带最终 descriptor、已归一化并通过 schema 校验的 `ToolCall`（构造期调用 `validateFor`）、已解析的 `timeout` 与可选的 `ToolExecutionContext`。`timeout` 就是 `resolveTimeout` 的结果，执行层不得二次解析。请求不携带 workdir：目录只存在于具体工具的 arguments 中，框架既不把它提升为通用执行状态，也不提供隐藏默认目录。

[`ToolExecutionListener`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java) 接收 `onPartial(ToolResult)*` 与互斥的 `onComplete(ToolOutcome)` / `onError(Throwable)`：终态至多一次，重复或迟到的回调由运行时侧适配层过滤，实现无需自己防御。

### 历史语义渲染

[`ToolHistoryRenderer`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolHistoryRenderer.java) 是 Tool 拥有的可选能力：当 Provider 无法再承载原生长 Tool 历史（工具已不在本次请求的绑定中，或调用的 Environment 已被切换）时，Runtime 用渲染出的自然语言动作替代那次调用，使模型仍能理解过去发生过什么。输入是刻意最小的 [`ToolHistoryRenderRequest`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolHistoryRenderRequest.java)：已按 schema 归一化的 `ToolCall` 与调用冻结时的 Environment 名（可空），不含 durable Entry、callIndex 或 Provider 协议结构。

渲染器必须是无副作用的确定性纯函数，并只描述动作本身：保留动作、核心目标与作用域，省略 timeout、limit、offset、分页、`maxTurns`、并发版本（`expected_version`、`observed_*`）等执行控制参数，以及已由结果表达的长正文。渲染结果不得暴露 toolCallId，也不得模拟 Tool 协议文本。Runtime 在成功响应持久化前调用它；返回 absent、blank 或抛异常一律按「未提供映射」处理，回退到逐字保留全部 arguments 的中性描述——缺失渲染器绝不让模型请求失败，也绝不猜测工具语义。冻结、回退与投影由 [`harness-runtime`](harness-runtime.md) 承担；MCP 与其它第三方 Tool 保持默认 absent。

## Environment 能力

[`ToolRequirements`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java) 冻结三项声明：

```text
environmentSupport       NONE / OPTIONAL / REQUIRED
requiredEnvironmentId    精确要求的目标 Environment；非空时 support 必须为 REQUIRED
stateAccesses[]           该工具访问的 branch custom state 集合（同一 customType 不得重复）
```

`NONE` 工具始终在 Platform 执行且不接收 Environment；`OPTIONAL` 工具在无 Environment
时仍可执行，有选择时获得可选 `BoundEnvironment`；`REQUIRED` 工具只有在 Branch 已选择
Environment 时才进入模型工具面。`requiredEnvironmentId` 用于把 `REQUIRED` 工具限定到
指定 Environment。Platform MCP 工具使用 `NONE`，统一 `read` 使用 `OPTIONAL`，宿主
写入、命令、检索与 LSP 工具使用 `REQUIRED`。

需要环境的工具在 [`ToolExecutionContext`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java) 中拿到 `invocationId`、`threadId`、`executedAt`、`branch` 与可选的 [`BoundEnvironment`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)。`BoundEnvironment` 是刻意窄的接口——只有 `environmentId()` 与 `execute(capability, request, listener)`，工具据此执行一个 `EnvironmentCapabilityDescriptor`，而连接注册、路由与协议细节由 Platform 的适配器承担。

环境绑定在持久化侧被冻结：[`ToolBinding`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolBinding.java)
保留 `environmentSupport` 与可空的 `environmentId` / `environmentName`。`NONE` 不得携带
环境，`OPTIONAL` 可携带当前选择，`REQUIRED` 只会在存在选择时被正常规划。Platform 在
构造模型工具列表前过滤无 Environment 的 `REQUIRED` 工具；[`ToolExecutionGateway`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java)
仍用冻结 definition、Contributor provenance、support 与 `requiredEnvironmentId` 逐项
比对当前目录，并对陈旧或伪造的缺环境调用稳定返回 `ENVIRONMENT_NOT_SELECTED`。
`environmentName` 与 `environmentId` 同源，继续作为历史投影判定 native 资格的
durable 事实。

## 分支状态与副作用

[`BranchView`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java) 是只读视图：`customEntries(customType)` 与 `latestCustomEntry(customType)` 读取当前 Contributor 自己的自定义状态，`goal()` 读取该 branch 生效 settings 中的用户 Goal（[`GoalSnapshot`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/GoalSnapshot.java)，nullable）。视图严格限定在当前 Contributor 与当前 Assistant 分支的 root-to-head 路径上：兄弟分支与其他 owner 的状态不可见，底层存储句柄与全量会话历史都不暴露；Platform 用窄查询解析 branch settings，不为只读视图物化完整 EntryPath。

[`StateDeclaration`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateDeclaration.java) 用 `READ` / `WRITE` 声明访问意图，它的用途不只是文档：同一 Assistant 的多个工具共享同一份冻结分支快照，因此同一 `(contributorId, customType)` 一旦已被先前 sibling 声明 WRITE，后续的 READ 或 WRITE 都必然读到陈旧快照，Runtime 在 dispatch 之前就把它确定性拒绝并提示下一轮再调用（[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 的 sibling state conflict 检查）；READ 之后再来 WRITE 则允许并发。

状态变更通过 [`ToolOutcome`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java) 声明，而不是直接写存储：`customEntries` 是有序的 [`AppendCustomEntry`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java) 列表，每项含 `customType`、正数 `schemaVersion` 与非空 `dataJson`，owner 由当前 Tool contribution 自动决定。`ToolOutcome` 构造期强制正确性约束：错误结果不得携带任何 effects。真正的落库在 [`harness-runtime`](harness-runtime.md) 的 [`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolOutcomeAppender.java) 中，与 ToolResult Entry 在同一事务里按声明顺序追加，校验失败即整体回滚，不产生部分条目。

[`ContextProjector`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java) 把 `BranchView` 纯函数式投影为不可变的 `List<ContextFragment>`，只读、不产生副作用。Platform 在规划下一次模型请求时按 Catalog 冻结顺序执行全部投影器，并把片段按空行确定性拼接进本次请求唯一的 `systemInstruction` 中（会话消息中绝不注入系统消息）。

## 扩展一个 Contributor

1. 实现 `HarnessContributor`（多用 `of` 工厂），给出 canonical `ContributorId`、版本与 `requires`。
2. 在 `contribute` 中注册工具：实现 `Tool` 的 `descriptor()` / `requirements()` /
   `execute()`，选择 `ToolVisibility` 与 priority；按工具实际执行面声明 `NONE`、
   `OPTIONAL` 或 `REQUIRED`，需要精确绑定时再给出 `requiredEnvironmentId`；希望历史
   降级时仍保留语义就 override `historyRenderer()`。
3. 要维护分支状态时，先 `registerCustomEntryType(localName, customType, priority)`，再在 `ToolRequirements.stateAccesses` 中声明 READ 或 WRITE；状态载荷与 `AppendCustomEntry` 使用同一个 customType。
4. 要注入模型上下文时注册 `ContextProjector`，只依据 `BranchView` 计算文本。
5. 装配到组合根：组件以 Spring bean 注册，由组合根统一交给 `HarnessCatalog.from`。

Contributor 的能力在两种装配方式下完全相同，但宿主边界不同：

- 构建期 Plugin 由 Spring Boot auto-configuration 创建 `HarnessContributor` bean，可以
  同时使用 Platform 的 credential、Blob 和 management services；Plugin 仍只经本 SPI
  注册 Tool，不得另建执行、历史或审批协议。要访问当前 Session 的 Resource 或把远端媒体写回
  Storage，Plugin 只能调用 `PluginResourceGateway`：`resolveSessionResource(threadId, resourceUri)`
  的唯一身份来源是本次调用的 `ToolExecutionContext.threadId()`，实现据此校验 Session 对 blob 的
  引用后签发短期受控下载地址。因此工具在发送请求前必须自证拿到了 invocation context；取不到
  threadId 时引用会话资源的调用必须确定性失败（如 Mavis 映射为 `MAVIS_RESOURCE_UNAVAILABLE`），
  不允许退化为未鉴权访问或跳过资源解析后发送。
构建期 Plugin 是唯一的扩展方式，因此不需要隔离 classloader、`ServiceLoader` 或运行时
jar 目录扫描：扩展代码要么在编译期依赖里，要么不存在。

两种方式都在 `HarnessCatalog.from` 时一次性冻结，运行中不安装、卸载或刷新代码。Plugin
本身是否存在由 `web` 的 Maven runtime dependency 决定；`EnvironmentSupport.NONE` 的
远端 API 工具不会因未选择 Environment 被过滤。

## 源码与测试

- 冻结与校验：[`HarnessCatalog.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java)、[`HarnessRegistrar.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java)、[`HarnessContributor.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java)、[`Identifiers.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Identifiers.java)
- 执行 SPI：[`Tool.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java)、[`ToolExecutionRequest.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java)、[`ToolExecutionListener.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java)、[`ToolExecutionHandle.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionHandle.java)、[`ToolHistoryRenderer.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolHistoryRenderer.java)
- 环境与状态：[`ToolRequirements.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java)、[`BoundEnvironment.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)、[`BranchView.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java)、[`AppendCustomEntry.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java)、[`ContextProjector.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java)
- 改动冻结逻辑先跑 [`HarnessCatalogTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalogTest.java)，它覆盖 DAG、排序、唯一性、freeze 不可变与 registrar 越界拒绝；[`HarnessContractTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContractTest.java) 覆盖 SPI 契约与请求归一化，[`BranchViewTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/BranchViewTest.java) 覆盖 contributor-scoped 状态可见性。参考实现见 [`harness-builtin`](harness-builtin.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Harness Builtin](harness-builtin.md)。
