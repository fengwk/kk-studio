# Harness Contributor API

## 定位

`harness-contributor-api` 是受信任 Java Contributor 的 startup discovery 与 catalog freeze SPI。组合根在启动时收集 Contributor，并冻结为单一不可变 `HarnessCatalog`；Contributor 只能注册统一 `Tool`、Custom Entry ownership 与 Context Projector。

该模块不向 Contributor 暴露 Runtime Store、gateway、transaction、lock、Spring 或 JDBC。所有 Tool 都走同一个异步执行 SPI；Environment 能力通过 `ToolRequirements.environmentRequired`、执行期 `BoundEnvironment` 和具体 Tool 实现表达，不存在 backend 分类或按 backend 注册的 API。

## Goals / Non-goals

### Goals

- 验证 Contributor descriptor 与 requires DAG，并按确定性顺序收集、冻结 contribution。
- 保证全局唯一的 AgentToolId 与模型 Tool name。
- 用统一 Tool SPI 表达本地逻辑、branch state effect 与 Environment 委托。
- 以 `(contributorId, customType)` 限定 CUSTOM state ownership。
- 提供 scoped `BranchView`、声明式 `AppendCustomEntry` effect 与纯 `ContextProjector`。

### Non-goals

- 不提供运行时安装、卸载、reload、classloader 管理或 HTTP install API。
- 不把 Contributor 变成 Store repository、transaction participant 或持久化锁持有者。
- 不允许 Tool 直接写 Entry、Thread、Invocation 或 Work。
- 不允许 Tool 绕过 Core 的 permission、effect ownership 或 Resource materialization。
- 不把完整 transcript 或底层 storage handle 暴露给 Contributor。

## 依赖边界

```text
trusted contributor implementation
          │
          ▼
 harness-contributor-api
   ├─ harness-tool values
   └─ harness-environment binding/capability values

禁止：harness-runtime store/port/processor
      harness-infra / harness-daemon / platform / web
      Spring / JDBC
```

生产依赖见 [`pom.xml`](../../harness/contributor-api/pom.xml)。[`ContributorApiModuleArchitectureTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorApiModuleArchitectureTest.java) 扫描主源码并守卫该边界。

## 核心模型 / API

### Contributor 与 descriptor

[`HarnessContributor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java) 提供：

```java
ContributorDescriptor descriptor();
void contribute(HarnessRegistrar registrar);
```

[`ContributorDescriptor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java) 包含稳定 `ContributorId`、展示 name、version 与 `requires`。[`ContributionId`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributionId.java) 是 `(contributorId, localName)`；scoped registrar 负责补齐 owner，Contributor 不能伪造其它 owner。

`ContributorId` 和 contribution `localName` 使用 canonical 小写 dotted/dashed identifier。localName 在同一 Contributor 内跨 Tool、Custom Type 与 Projector 共享唯一性。

### HarnessRegistrar

[`HarnessRegistrar`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 只有三个类型化入口：

```text
registerTool(localName, agentToolId, tool, visibility, priority)
registerCustomEntryType(localName, customType, priority)
registerContextProjector(localName, projector, priority)
```

- `registerTool` 在冻结时读取并校验 `tool.descriptor()` 与 `tool.requirements()`。
- AgentToolId 与 model-visible Tool name 在全部 Contributor 中全局唯一。
- 冻结后的 [`ToolContribution`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java) 是单一 record：`ContributionId + AgentToolDefinition + Tool + ToolRequirements + priority`。
- Custom ownership 键是 `(contributorId, customType)`；不同 Contributor 可以拥有同名 customType。

### HarnessCatalog、requires DAG 与冻结顺序

[`HarnessCatalog.from`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java)：

```text
读取每个 descriptor 一次
  -> 检查 duplicate contributor / missing requires / cycle
  -> requires DAG + ContributorId 字典序拓扑排序
  -> 按拓扑顺序逐 contributor 调用 contribute 一次
  -> 校验 localName / Tool name / AgentToolId / custom ownership / state access
  -> 各扩展点按 requires 传递偏序、priority 降序、ContributionId 字典序冻结
```

图非法时不会调用任何 `contribute`。输入集合顺序不影响结果；冻结后的 descriptors、tools、selectableTools、customEntryTypes、contextProjectors 与 transitiveRequires 都不可变。

Catalog 支持按以下身份查找 Tool：

```text
model-visible Tool name
AgentToolId
ContributionId
```

不存在第二套 backend index。

### 统一 Tool SPI

[`Tool`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java)：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements();
ToolExecutionHandle execute(
    ToolExecutionRequest request,
    ToolExecutionListener listener);
```

`execute` 必须是启动式、快速返回的异步 SPI；允许同步 callback，但 Platform 会在 durable `RUNNING` 前关闭 callback gate。返回 handle 支持幂等 cancel。

[`ToolExecutionRequest`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionRequest.java) 持有 descriptor、已归一化/校验的 `ToolCall`、timeout override、可选 execution context 与可选 absolute workdir。timeout 为零时使用 descriptor 默认值。

[`ToolExecutionListener`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionListener.java) 接收：

```text
onPartial(ToolResult)*
-> onComplete(ToolOutcome) | onError(Throwable)
```

完成与失败互斥且至多一次；执行适配层负责过滤 duplicate/late callback。

### ToolRequirements 与 Environment

[`ToolRequirements`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolRequirements.java) 冻结：

```text
environmentRequired
stateAccesses[]
```

当 `environmentRequired=true` 时，Runtime planning 必须冻结 `EnvironmentBinding`，Platform 执行时在 [`ToolExecutionContext`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java) 提供 [`BoundEnvironment`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BoundEnvironment.java)。Tool 通过该窄接口执行一个 `EnvironmentCapabilityDescriptor`，不直接访问连接 registry 或 gateway。

Environment 是统一 Tool 的一项执行要求，不是 Tool definition 的 backend。

### BranchView、state access 与 effects

[`BranchView`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java) 已由 Platform 限定到当前 Contributor，只提供：

```java
List<CustomStateSnapshot> customEntries(String customType);
Optional<CustomStateSnapshot> latestCustomEntry(String customType);
```

查询只读取当前 Assistant branch 的 root-to-head path，不扫描其它 Session、Thread 或 owner。

[`StateDeclaration`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateDeclaration.java) 使用 `READ` / `WRITE` 声明 Tool 对自己 customType 的访问。Catalog 要求每个声明都命中同 owner 已注册的 Custom Entry Type；Runtime 在 sibling batch 中使用冻结声明检查冲突：

```text
READ -> READ       allow
READ -> WRITE      allow
WRITE -> READ      conflict
WRITE -> WRITE     conflict
```

[`ToolOutcome`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java) 组合模型可见 `ToolResult` 与有序 `AppendCustomEntry` effects。[`AppendCustomEntry`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java) 只声明 `customType + schemaVersion + dataJson`；owner 由当前 Tool contribution 冻结。error ToolResult 禁止携带 effects。

Core 在 durable apply 前再次验证 owner、Custom Type 注册、WRITE 声明、数量与 payload 边界；失败不留下部分 Entry。

### ContextProjector

[`ContextProjector`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java) 把 scoped `BranchView` 纯投影为不可变 `List<ContextFragment>`。所有已注册 projector 按 catalog 冻结顺序执行，文本片段依次进入 model preamble。

Projector 只读 branch state，不产生 effects，也不接触 Store。

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

上下文投影：

```text
candidate branch
  -> contributor-scoped BranchView
  -> all ContextProjectors in catalog order
  -> ContextFragment text
  -> frozen model preamble
```

## 不变量、failure / recovery

- Catalog 先验证完整 requires graph，再调用 Contributor；graph failure 不产生半成品目录。
- Catalog 冻结后 ID、descriptor、requirements、priority 与列表不可变；Tool/Projector 实例引用保留。
- Tool definition 没有 backend 字段；路由差异由具体 Tool 实现与 requirements 表达。
- Runtime invocation 冻结 definition、Contributor provenance、environmentRequired、state accesses 与可选 EnvironmentBinding；Platform 发现 catalog drift 时确定性拒绝。
- Tool 只能为自己的已注册 customType 产生已声明 WRITE 的 effect。
- error ToolResult 与 effects 互斥；Resource externalization 或 effect validation 失败时整个 outcome 失败。
- Contributor API 没有热更新状态转换；Contributor 集合变化需要重建应用级 catalog。

## 配置 / 扩展

- Contributor 通过 `HarnessContributor.contribute` 和 scoped registrar 注册。
- 需要 branch state 时，先注册 Custom Entry Type，再在 `ToolRequirements.stateAccesses` 中声明 READ/WRITE。
- 需要 Environment 时，Tool 返回 `ToolRequirements.environment()` 并在执行期使用 `context.environment()`。
- priority 只解决同一扩展点的非依赖排序；requires 传递偏序优先。
- JAR discovery、classloader 与 Spring bean 合并属于 Web composition root，不属于本模块。

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

上级：[系统设计](../system-design.md)。相关文档：[Harness Builtin](harness-builtin.md)、[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Web](web.md)。
