# Harness Contributor API

## 定位

`harness-contributor-api` 是受信任 Java Contributor 的 startup discovery 与 catalog freeze SPI 模块。组合根在启动时收集 Contributor 声明并冻结为单一不可变 `HarnessCatalog`，通过类型化的 `HOST`、`DECLARATIVE` 与 `ENVIRONMENT_CAPABILITY` 工具注册、`ContributorId` / `ContributionId`、`BranchView`、`DeclarativeTool`、`AppendCustomEntry` 和 `ContextProjector` 将 Contributor 限制在确定的扩展边界内。

`harness-contributor-api` 与 `HarnessRegistrar` 绝不向 Contributor 暴露 `HarnessStore`、gateway、transaction 或 lock。其中 DeclarativeTool 限制在 `BranchView` + intent 声明式边界内；HOST 工具使用标准的 Tool SPI（`ToolExecutionRequest` / `ToolExecutionListener`）；ENVIRONMENT_CAPABILITY 工具则仅声明底层 Capability mapping。

## Goals / Non-goals

### Goals

- 在启动装配时验证 Contributor descriptor 和 requires DAG，按确定性拓扑顺序收集 contribution。
- 让同一扩展点中的 requires 传递偏序、priority 降序和 ContributionId 字典序稳定且可重现。
- 以 `(contributorId, customType)` 限定 CUSTOM state ownership，避免跨 Contributor 状态混淆。
- 为同步纯 DeclarativeTool 提供只读 branch 上下文与声明式 branch effects。
- 统一 `HOST`、`DECLARATIVE`、`ENVIRONMENT_CAPABILITY` 三种后端的工具注册，并在目录冻结期保证全局唯一的 Tool name 与 AgentToolId。

### Non-goals

- 不提供 Contributor 的运行时安装、卸载、热加载、reload、classloader 管理或 HTTP install API。
- 不把 Contributor 变成 Store repository、transaction participant 或持久化锁持有者。
- 不默认把 CUSTOM Entry 投影到 Provider 上下文；只有显式注册的 ContextProjector 会生成 model-visible messages。
- 不允许 DeclarativeTool 直接执行底层 capability、直接写 Entry/Thread/Invocation/Work 或绕过 Core 的 permission/Resource materialization。

## 依赖边界

```text
trusted contributor implementation
          │
          ▼
   harness-contributor-api
   ├─ harness-runtime value types
   └─ harness-tool descriptor / call / result

禁止：runtime.store / runtime.port / runtime.processor
      harness-infra / harness-daemon / platform / web / Spring / JDBC
```

生产依赖只有 `harness-runtime` 与 `harness-tool`，见 [`pom.xml`](../../harness/contributor-api/pom.xml)。架构守卫 [`ContributorApiModuleArchitectureTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorApiModuleArchitectureTest.java) 扫描主源码，禁止依赖 Store、port、processor、infra、daemon 和 Spring。

## 核心模型 / API

### Contributor 与 descriptor

[`HarnessContributor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java) 提供：

```java
ContributorDescriptor descriptor();
void contribute(HarnessRegistrar registrar);
```

[`ContributorDescriptor`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java) 包含 `ContributorId id`、展示 `name`、`version` 和 `Set<ContributorId> requires`。[`ContributorId`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorId.java) 与 [`ContributionId`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributionId.java) 的 `localName` 是最多 64 字符的小写 dotted/dashed canonical identifier。`ContributionId` 是 `(contributorId, localName)`，由 scoped registrar 构造，Contributor 不能伪造其它 Contributor 的 owner。

### HarnessRegistrar

[`HarnessRegistrar`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java) 提供五个类型化注册入口：

```text
registerHostTool(localName, agentToolId, tool, visibility, priority)
registerDeclarativeTool(localName, agentToolId, declarativeTool, visibility, priority)
registerEnvironmentCapabilityTool(localName, agentToolId, descriptor, capability, visibility, priority)
registerCustomEntryType(localName, customType, priority)
registerContextProjector(localName, projector, priority)
```

- 工具注册必须显式提供跨全部后端全局唯一的 `AgentToolId`，model-visible 的 Tool name 也在全局唯一。
- `localName` 只在所属 Contributor 内部跨全部贡献类型共享唯一性，不同 Contributor 可以使用同名 localName。
- 冻结后的 [`ToolContribution`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java) 是 sealed interface，分为 `HostToolContribution`、`DeclarativeToolContribution` 和 `EnvironmentCapabilityToolContribution`。
- Custom entry ownership 键是 `(contributorId, customType)`，不同 Contributor 可以各自拥有同名 customType。

### HarnessCatalog、requires DAG 与冻结顺序

[`HarnessCatalog.from`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java) 的构建流程为：

```text
读取每个 descriptor 一次
  -> 检查 duplicate contributor id / missing requires / self requires / cycle
  -> requires DAG + ContributorId 字典序拓扑排序
  -> 按拓扑顺序逐 contributor 调用 contribute 一次
  -> 校验 contribution owner / tool name / AgentToolId / custom type / state access
  -> 各扩展点按 requires 传递闭包、priority 降序、ContributionId 字典序冻结
```

requires 图非法时，任何 contributor 的 `contribute` 都不会被调用。descriptor 输入顺序不影响最终排序；Catalog 构建完成后 `descriptors()`、`tools()`、`selectableTools()`、`customEntryTypes()` 和 `contextProjectors()` 均为不可变列表。`transitiveRequires(contributorId)` 暴露不可变传递依赖集合，依赖中间 Contributor 即使没有某个扩展点贡献，也不会打破贡献的依赖偏序。

重复 contributor id、缺失 dependency、cycle、同 contributor 重复 localName、全局重复 AgentToolId/Tool name、同 contributor 重复 customType、未注册 state type、重复 state access 均在冻结阶段立即失败。

### BranchView 与 state access

[`BranchView`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java) 对扩展隐藏内部 `EntryPath`、对话 transcript 与 Store 细节，只持有已校验的不可变路径，提供按 `(contributorId, customType)` 的状态回查：

```java
List<CustomEntryPayload> customEntries(ContributorId contributorId, String customType);
Optional<CustomEntryPayload> latestCustomEntry(
    ContributorId contributorId, String customType);
```

查询同时匹配 contributorId 和 canonical customType，结果按 root-to-head 语义返回，只遍历当前 branch path，不扫描其它 Session、Thread 或 Store。

[`StateDeclaration`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateDeclaration.java) 的 `mode` 为 [`StateMode`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateMode.java) `READ` 或 `WRITE`。DeclarativeTool 通过 `stateAccesses()` 静态声明读取或写入的 custom state；Catalog 确认 customType 已由同一 Contributor 注册。Harness Core 在同一 Assistant sibling batch 内按 ordinal 检查冻结访问：

```text
READ -> READ       allow
READ -> WRITE      allow
WRITE -> READ      SIBLING_STATE_CONFLICT
WRITE -> WRITE     SIBLING_STATE_CONFLICT
```

不同 customType 或不同 contributorId 不冲突。

### Tool、ToolOutcome 与 projector

[`Tool`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java) 是统一的工具执行 SPI：

```java
ToolDescriptor descriptor();
default ToolRequirements requirements() { return ToolRequirements.none(); }
ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener);
```

[`ToolExecutionContext`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java) 包含 `invocationId`、`threadId`、`executedAt`、`BranchView branch` 与 `Optional<BoundEnvironment> environment`。[`ToolOutcome`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java) 包含模型可见 `ToolResult` 和有序 `List<AppendCustomEntry> customEntries`；error ToolResult 严禁携带 effects。

[`AppendCustomEntry`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java) 持有最终 `CustomEntryPayload(contributorId, customType, schemaVersion, dataJson)`，payload 自身完成 schemaVersion、canonical identifier、JSON object 和大小校验。Core 还会验证 intent owner、custom type ownership、WRITE 声明、descriptor/provenance 和 effects 上限。

[`ContextProjector`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java) 将 `BranchView` 纯投影为不可变 `List<AgentMessage>`。目录中所有已注册的 ContextProjector 在每个 live turn 均按冻结目录拓扑/优先级/ID 顺序全部执行并依次拼接进 preamble；API 不提供动态选择或过滤。projector 不产生 state mutation。

## 执行 / 状态 trace

```text
HarnessCatalog frozen at startup
  -> Model turn resolver selects Tool contribution from catalog
  -> ToolBinding freezes AgentToolDefinition + contributor provenance/state accesses
  -> Core builds BranchView at Assistant Entry
  -> DeclarativeTool.execute(context, ToolCall)
  -> DeclarativeToolResult(ToolResult, intents)
  -> Core validates intents / effects / ownership
  -> ToolInvocation SUCCEEDED + ToolEffectBatch
  -> next Thread claim appends CUSTOM entries then TOOL result
```

上下文读取流程为：

```text
candidate BranchView
  -> all registered ContextProjector(s) in catalog order
  -> frozen AgentMessage preamble
  -> ModelRequestSpec
  -> ProviderRequest
```

DeclarativeTool 不在执行时重新解析 catalog、读取 Store 或重建 branch；Tool retry 使用 Invocation 中冻结的 contribution provenance 和 state accesses。

## 不变量、failure / recovery

- `HarnessCatalog.from` 先验证整个 DAG，再调用 contributor；任何 graph failure 都会导致构建失败，不产生半成品 catalog。
- catalog 构建后不可变的 descriptor、ID、列表与 state declaration 均冻结；可执行的 Tool / DeclarativeTool / ContextProjector 实例对象引用保留在 Catalog 中。Platform 在每次执行前重新校验冻结的 AgentToolDefinition、provenance 与 descriptor，防止运行时定义漂移。
- Tool descriptor 只包含模型契约；Tool name 全局唯一，backend 与 contribution provenance 共同确定执行路由。descriptor/version/ContributionId 漂移在 binding 恢复时确定性拒绝。
- `AppendCustomEntry` 只能成为同 owner、已注册 customType、声明 WRITE 的 effect；违反时 Core 以 contributor contract violation 失败，effects 为空。
- error ToolResult 与 intents 互斥，Resource externalization 和 durable `SUCCEEDED` 发生在 intent 校验之后；失败不留下部分 Entry。
- branch state 只从当前 Assistant Entry 的 root-to-head path 读取；fork 只看分叉点可见的 CUSTOM snapshot。
- projector 异常、非法 message 或 intent validation failure 不被转换为状态写入；调用方按 Tool failure 关闭当前 outcome。

Contributor API 没有“热更新后继续运行”的状态转换。Catalog 只在组合根完成构建时有效，Contributor 集合变化需要重新建立新的运行时装配。

## 配置 / 扩展

- Contributor 通过 `HarnessContributor.contribute` 和 scoped registrar 注册。
- Branch state 贡献先注册 `customEntryType`，DeclarativeTool 再声明对应 `READ`/`WRITE` access。
- `ContextProjector` 是 Contributor 向 Model context 投影消息的唯一扩展点；投影结果不包含完整 transcript 或 Store handle。
- priority 只解决同一扩展点的非依赖排序；requires 传递闭包优先于 priority。
- contributor-api 不定义 contributor discovery、trusted JAR classloader、Spring bean 装配或 runtime reload；这些属于组合根边界。

## 测试与源码入口

### 源码入口

- [`HarnessContributor.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContributor.java)、[`ContributorDescriptor.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorDescriptor.java)、[`HarnessCatalog.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalog.java)
- [`HarnessRegistrar.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessRegistrar.java)、[`ContributorId.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorId.java)、[`ContributionId.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContributionId.java)
- [`ToolContribution.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolContribution.java)、[`Tool.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/Tool.java)、[`ToolOutcome.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolOutcome.java)、[`ToolExecutionContext.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ToolExecutionContext.java)
- [`AppendCustomEntry.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/AppendCustomEntry.java)、[`StateDeclaration.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateDeclaration.java)、[`StateMode.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/StateMode.java)
- [`BranchView.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/BranchView.java)、[`ContextProjector.java`](../../harness/contributor-api/src/main/java/fun/fengwk/kkstudio/harness/contributor/api/ContextProjector.java)

### 关键测试守卫

- [`ContributorApiModuleArchitectureTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorApiModuleArchitectureTest.java)：依赖方向和 API 不暴露 Store/gateway/processor。
- [`HarnessCatalogTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessCatalogTest.java)：DAG、拓扑顺序、priority、ContributionId、freeze、duplicate 和未调用 contributor。
- [`HarnessContractTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/HarnessContractTest.java)：intent、error ToolResult、immutable context 和 state declaration。
- [`BranchViewTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/BranchViewTest.java)：branch-scoped `(contributorId, customType)` 查询与 latest snapshot。
- [`ContributorIdTest.java`](../../harness/contributor-api/src/test/java/fun/fengwk/kkstudio/harness/contributor/api/ContributorIdTest.java)：canonical identifier 校验与格式边界。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Builtin](harness-builtin.md)、[Harness Runtime](harness-runtime.md)、[Harness Tool](harness-tool.md)、[Web](web.md)。
