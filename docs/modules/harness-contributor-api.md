# Harness Contributor API

## 定位

`harness-contributor-api` 是受信任 Java 扩展贡献者（Contributor）的启动期发现与不可变目录冻结 SPI。应用组合根在系统启动时发现并收集所有 Contributor，校验依赖图后冻结为全局单一不可变的 `HarnessCatalog`。Contributor 通过类型化注册入口注册统一的 `Tool`、自定义状态条目（Custom Entry）的所有权以及上下文投影器（Context Projector）。

运行时存储、网关路由、事务、并发控制与容器装配由 [`harness-runtime`](harness-runtime.md) 及基础设施模块管理。本地工具与环境工具共用同一个异步执行 SPI；需要访问执行环境的工具声明 `ToolRequirements.environmentRequired`，Platform 在执行时注入 `BoundEnvironment`。

## 职责

### 核心职责

- 校验 Contributor 描述符与 `requires` 依赖有向无环图（DAG），按确定性拓扑顺序与优先级收集并冻结贡献项。
- 保证全局唯一的 `AgentToolId` 以及面向模型可见的工具调用名称（`ToolDescriptor.name`）。
- 通过统一的 `Tool` 异步执行 SPI 表达本地计算、分支状态副作用（branch state effects）与执行环境能力调用。
- 以 `(contributorId, customType)` 严格限定自定义状态条目（CUSTOM Entry）的所有权边界。
- 提供作用域受限的只读 `BranchView`、声明式的 `AppendCustomEntry` 副作用模型以及无副作用的只读 `ContextProjector`。

### 协作边界

- Contributor 采用启动期静态装配机制；动态类加载、运行时插件生命周期与 HTTP 安装接口由应用组合根或外层容器管理。
- 持久化仓储操作、事务参与和并发锁统一由 [`harness-runtime`](harness-runtime.md) 与底层存储负责。
- 会话树节点（Entry）、Thread、Invocation 和 Work 调度状态由运行时核心统一持久化与推进；Tool 仅通过返回 `ToolOutcome` 表达执行结果与自定义状态追加意图。
- 所有工具调用统一经过 Harness Core 的权限判定、副作用所有权校验与资源持久化物化流程。
- Contributor 仅通过受作用域限制的 `BranchView` 访问当前分支状态，隔离底层存储句柄与全量会话历史。

## 依赖边界

```text
trusted contributor implementation
          │
          ▼
 harness-contributor-api
   ├─ harness-tool values
   └─ harness-environment identity/capability values

依赖约束：仅允许依赖 harness-tool 与 harness-environment 的纯值契约；
          运行时存储与处理器、基础设施、守护进程、平台装配、Spring 及 JDBC 均置于外层模块。
```

生产依赖见 [`pom.xml`](../../harness/contributor-api/pom.xml)。[`ContributorApiModuleArchitectureTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorApiModuleArchitectureTest.java) 扫描主源码并守卫该边界。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.contributor.api` | 受信任 Contributor 的启动期发现与不可变目录冻结机制（`HarnessContributor`、`HarnessRegistrar`、`HarnessCatalog`）、统一异步 `Tool` SPI、限定 ownership 的分支自定义状态访问（`BranchView`、`StateDeclaration`、`ToolOutcome`）与纯上下文投影器（`ContextProjector`） | 保持纯 Java 契约；运行时存储、网关路由、事务与锁机制由运行时管理，类加载与插件生命周期由外层组合根负责 |

## 核心模型 / API

### Contributor 与 descriptor

[`HarnessContributor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java) 提供统一的扩展契约：

```java
ContributorDescriptor descriptor();
void contribute(HarnessRegistrar registrar);
```

[`ContributorDescriptor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java) 包含稳定 `ContributorId`、展示 name、version 与依赖前置项 `requires`。[`ContributionId`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributionId.java) 由 `(contributorId, localName)` 二元组构成；Scoped registrar 在注册时自动填充当前 Contributor 的所有者身份，确保贡献项归属严格受限。

`ContributorId` 和贡献项 `localName` 采用规范小写点划线格式（dotted/dashed identifier）。在同一个 Contributor 内部，`localName` 在 Tool、Custom Type 与 Projector 之间共享命名空间并保持唯一。

### HarnessRegistrar

[`HarnessRegistrar`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 提供三个类型化注册入口：

```text
registerTool(localName, agentToolId, tool, visibility, priority)
registerCustomEntryType(localName, customType, priority)
registerContextProjector(localName, projector, priority)
```

- `registerTool` 在目录冻结阶段读取并校验 `tool.descriptor()` 与 `tool.requirements()`。
- `AgentToolId` 与模型可见的调用名称（`ToolDescriptor.name`）在全体 Contributor 之间全局唯一。
- 冻结后的 [`ToolContribution`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java) 为单一记录对象：`ContributionId + AgentToolDefinition + Tool + ToolRequirements + priority`。
- 自定义状态条目的所有权键为 `(contributorId, customType)`；不同 Contributor 可以各自拥有同名 `customType`。

### HarnessCatalog、requires DAG 与冻结顺序

[`HarnessCatalog.from`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java) 执行确定性的构建流程：

```text
读取每个 descriptor 一次
  -> 检查 duplicate contributor / missing requires / cycle
  -> requires DAG + ContributorId 字典序拓扑排序
  -> 按拓扑顺序逐 contributor 调用 contribute 一次
  -> 校验 localName / Tool name / AgentToolId / custom ownership / state access
  -> 各扩展点按 requires 传递偏序、priority 降序、ContributionId 字典序冻结
```

依赖图存在缺失依赖、重复定义或环路时，构建流程在调用 `contribute` 前失败。输入集合的原始顺序不影响冻结结果；冻结完成后的 descriptors、tools、selectableTools、customEntryTypes、contextProjectors 与 transitiveRequires 均为不可变集合。

Catalog 支持按以下三种身份查找 Tool：

```text
model-visible Tool name
AgentToolId
ContributionId
```

### 统一 Tool SPI

[`Tool`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java) 定义异步执行契约：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements();
ToolExecutionHandle execute(
    ToolExecutionRequest request,
    ToolExecutionListener listener);
```

`execute` 是启动式、快速返回的异步执行方法。方法内部允许发起同步回调，Platform 会在调用状态持久化为 `RUNNING` 之前关闭初始回调门禁，确保执行状态有序转换。返回的句柄支持幂等取消（cancel）。

[`ToolExecutionRequest`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java) 持有工具描述符、已归一化与强类型校验的 `ToolCall`、覆盖超时时间以及可选的执行上下文。当请求超时时间未指定或为零时，自动采用描述符声明的默认值。请求不携带 workdir：目录只存在于具体工具 arguments 中，框架不把它提升为通用执行状态，也不提供隐藏默认目录。

[`ToolExecutionListener`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java) 接收增量与终态事件：

```text
onPartial(ToolResult)*
-> onComplete(ToolOutcome) | onError(Throwable)
```

完成与失败回调严格互斥且至多触发一次；执行适配层负责过滤重复或迟到的回调事件。

### ToolRequirements 与 Environment

[`ToolRequirements`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java) 冻结以下声明：

```text
environmentRequired
stateAccesses[]
```

当工具声明 `environmentRequired=true` 时，Runtime planning 阶段在 `ToolBinding` 上冻结 `environmentId`，Platform 在执行期通过 [`ToolExecutionContext`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java) 注入 [`BoundEnvironment`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)。`BoundEnvironment` 只暴露冻结的 `EnvironmentId` 与 `execute(capability, request, listener)`，Tool 通过该窄接口执行 `EnvironmentCapabilityDescriptor` 即可调用环境能力；连接注册中心与环境网关路由由 Platform 统一管理。环境能力作为工具声明的执行依赖存在，与工具的定义模型解耦。

### BranchView、state access 与 effects

[`BranchView`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java) 经由 Platform 限定至当前 Contributor 的所有权边界，仅提供只读查询契约：

```java
List<CustomStateSnapshot> customEntries(String customType);
Optional<CustomStateSnapshot> latestCustomEntry(String customType);
```

视图只包含当前 Assistant 分支 root-to-head 路径上、属于当前 Contributor 的 CUSTOM 状态；兄弟分支及其他所有者的状态不可见。

[`StateDeclaration`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateDeclaration.java) 使用 `READ` / `WRITE` 声明 Tool 对自身 customType 的状态访问意图。Catalog 要求每个声明均命中同所有者已注册的 Custom Entry Type；Runtime 在同批次兄弟工具（sibling batch）并发执行时，依据冻结的声明判定访问冲突：

```text
READ -> READ       allow
READ -> WRITE      allow
WRITE -> READ      conflict
WRITE -> WRITE     conflict
```

[`ToolOutcome`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java) 组合模型可见的 `ToolResult` 与有序的 `AppendCustomEntry` 副作用列表。[`AppendCustomEntry`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java) 声明 `customType + schemaVersion + dataJson`，所有者由当前 Tool contribution 自动冻结。ToolResult 处于错误状态时严禁携带副作用。

Core 在持久化追加前，对所有者合法性、Custom Type 注册状态、WRITE 访问声明、副作用数量以及载荷边界执行严格校验；任一校验未通过均原子回滚，保证不产生局部条目写入。

### ContextProjector

[`ContextProjector`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java) 将作用域受限的 `BranchView` 纯函数式投影为不可变的 `List<ContextFragment>`。所有已注册的 Projector 按照 Catalog 冻结的顺序依次执行，生成的文本片段有序注入模型前置提示词（model preamble）。

Projector 只接收 `BranchView` 并返回上下文片段，整个投影过程是纯内存只读计算。

## 执行 / 状态 trace

```text
HarnessCatalog frozen at startup
  -> TurnResolver selects ToolContribution
  -> Runtime freezes AgentToolDefinition + Contributor provenance + requirements
  -> Platform reloads contribution and verifies no drift
  -> Platform builds scoped BranchView + optional BoundEnvironment
  -> Tool.execute(request, listener)
  -> partial* -> ToolOutcome | error
  -> Platform validates callback/result/resources/effects
  -> Runtime persists terminal
  -> Thread apply appends CUSTOM effects then TOOL result
```

上下文投影流程：

```text
candidate branch
  -> contributor-scoped BranchView
  -> all ContextProjectors in catalog order
  -> ContextFragment text
  -> frozen model preamble
```

## 不变量、failure / recovery

- **依赖图原子校验**：Catalog 在调用 Contributor 前先行验证依赖有向图的完整性与无环性；一旦检测到缺失依赖或环路，立即终止装配，保证目录构建的原子性。
- **不可变冻结保障**：Catalog 冻结完成后，贡献者标识、描述符、依赖要求、优先级以及检索列表完全不可变，工具与投影器实例引用保持稳定。
- **统一模型表达**：本地执行与环境委托共用 Tool SPI，具体实现与 `ToolRequirements` 共同表达执行位置。
- **调用状态一致性**：Runtime Invocation 持久化冻结工具定义、贡献者归属、环境要求、状态访问声明与冻结的 `environmentId`；Platform 执行期比对当前目录，检测到漂移（drift）时确定性拒绝执行。
- **所有权隔离与副作用约束**：Tool 仅允许对其所属 Contributor 已注册、且已显式声明 WRITE 访问的 customType 产生状态追加副作用。
- **执行终态与副作用互斥**：执行错误与追加副作用严格互斥；当资源外部化或副作用校验未通过时，整个执行结果视为失败并原子回滚。
- **启动期装配生命周期**：扩展配置在应用启动期确定；若 Contributor 集合发生变更，通过重新启动应用重建全局目录。

## 配置 / 扩展

- Contributor 通过实现 `HarnessContributor.contribute` 并在 scoped registrar 中声明扩展项完成注册。
- 当需要管理分支自定义状态时，首先注册 Custom Entry Type，随后在 `ToolRequirements.stateAccesses` 中声明相应的 READ 或 WRITE 权限。
- 当工具需要访问执行环境时，在 `ToolRequirements.environment()` 中声明依赖，并在执行期通过 `context.environment()` 调用环境能力。
- 优先级 `priority` 用于决定同一扩展点中无依赖关系项之间的相对次序；存在依赖关系时优先遵循 `requires` 传递偏序。
- JAR 包发现、类加载管理以及 Spring Bean 装配均属于 Web 层的应用组合根职责。

## 测试与源码入口

### 源码入口

- [`HarnessContributor.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java)、[`ContributorDescriptor.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java)、[`HarnessCatalog.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java)
- [`HarnessRegistrar.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java)、[`ToolContribution.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java)
- [`Tool.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java)、[`ToolRequirements.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java)、[`ToolExecutionRequest.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java)、[`ToolExecutionListener.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java)
- [`BranchView.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java)、[`AppendCustomEntry.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java)、[`ContextProjector.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java)
- [`BoundEnvironment.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)、[`ToolExecutionContext.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java)

### 关键测试守卫

- [`ContributorApiModuleArchitectureTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorApiModuleArchitectureTest.java)：依赖方向与 API 边界。
- [`HarnessCatalogTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalogTest.java)：DAG、排序、唯一性、freeze 与 lookup。
- [`HarnessContractTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContractTest.java)：统一 Tool、requirements、outcome 与 execution context。
- [`BranchViewTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/BranchViewTest.java)：Contributor-scoped branch state。
- [`ContributorIdTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorIdTest.java)：canonical identifier。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Builtin](harness-builtin.md)、[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Web](web.md)。
