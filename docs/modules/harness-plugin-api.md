# Harness Plugin API

## 定位

`harness-plugin-api` 是受信任 Java plugin 的 startup discovery/catalog freeze
SPI。组合根在启动时收集插件能力并冻结为不可变 `PluginCatalog`，再通过 scoped
`ContributionId`、`BranchView`、`PluginTool`、`AppendCustomEntry` 和
`ContextProjector` 将插件限制在声明式扩展边界内。

插件作者不接触 `HarnessStore`、gateway、transaction、lock 或 processor。插件读取由调用方冻结在 Tool 所属 Assistant Entry 的 `BranchView`，写入只返回 intent，由 Harness Core 在 Tool terminal apply transaction 中验证和追加 CUSTOM Entry。

## Goals / Non-goals

### Goals

- 在启动装配时验证 plugin descriptor 和 requires DAG，再按确定顺序收集 contribution。
- 让同一扩展点中的 requires、priority、ContributionId 顺序稳定且可重现。
- 以 `(pluginId, customType)` 限定 CUSTOM state ownership，避免跨插件状态混淆。
- 为同步纯 PluginTool 提供只读 branch/context 与声明式 branch effects。

### Non-goals

- 不提供插件安装、卸载、热加载、reload、classloader 管理或 HTTP install API。
- 不把插件变成 Store repository、transaction participant、异步 executor 或独立 scheduler。
- 不默认把 CUSTOM Entry 投影到 Provider context；只有显式注册的 ContextProjector 会生成 model-visible messages。
- 不允许 PluginTool 执行 Environment Tool、直接写 Entry/Thread/Invocation/Work 或绕过 Core 的 permission/Resource materialization。

## 依赖边界

```text
trusted plugin implementation
          │
          ▼
   harness-plugin-api
   ├─ harness-runtime value types
   └─ harness-tool descriptor / call / result

禁止：runtime.store / runtime.port / runtime.processor
      harness-infra / harness-daemon / platform / web / Spring / JDBC
```

生产依赖只有 `harness-runtime` 与 `harness-tool`，见 [`pom.xml`](../../harness/plugin-api/pom.xml)。架构守卫 [`PluginApiModuleArchitectureTest.java`](../../harness/plugin-api/src/test/java/fun/fengwk/kkstudio/harness/plugin/api/PluginApiModuleArchitectureTest.java) 扫描主源码，禁止 Store、port、processor、infra、daemon 和 Spring 等依赖。

## 核心模型 / API

### Plugin 与 descriptor

[`HarnessPlugin`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/HarnessPlugin.java) 提供：

```java
PluginDescriptor descriptor();
void contribute(PluginRegistrar registrar);
```

`PluginDescriptor` 包含 `PluginId id`、展示 `name`、`version` 和 `Set<PluginId> requires`。`PluginId` 与 `ContributionId.localName` 是最多 64 字符的小写 dotted/dashed canonical identifier。`ContributionId` 是 `(pluginId, localName)`，由 scoped registrar 构造，插件不能伪造其它 plugin 的 owner。

[`PluginRegistrar`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginRegistrar.java) 只有三个扩展点：

```text
registerTool(localName, agentToolId, PluginTool, visibility, priority)
registerCustomEntryType(localName, customType, priority)
registerContextProjector(localName, ContextProjector, priority)
```

`registerTool` 还必须显式传入跨 Host/Plugin 全局唯一的 `AgentToolId`；`localName` 只在所属 plugin 内跨三种 contribution 类型共享唯一性，不同 plugin 可以使用同名 localName。Tool name 在全局唯一，plugin tool 必须是 `ToolType.PLATFORM`，冻结后的 `ToolContribution` 携带 `PLUGIN` backend 的 `AgentToolDefinition`。Custom entry ownership 是 `(pluginId, customType)`，不同 plugin 可以各自拥有同名 customType。

### PluginCatalog、requires DAG 与冻结顺序

[`PluginCatalog.from`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginCatalog.java) 的构建边界为：

```text
读取每个 descriptor 一次
  -> 检查 duplicate plugin id / missing requires / self requires / cycle
  -> requires DAG + PluginId 字典序拓扑
  -> 按拓扑顺序逐 plugin 调用 contribute 一次
  -> 校验 contribution owner / tool name / custom type / state access
  -> 各扩展点按 requires 传递闭包、priority 降序、ContributionId 字典序冻结
```

requires 图非法时，任何 contributor 都不会被调用。descriptor 输入顺序不影响最终顺序；catalog 完成后 `descriptors()`、`tools()`、`customEntryTypes()` 和 `contextProjectors()` 都是不可变列表。`transitiveRequires(pluginId)` 暴露不可变传递依赖集合，依赖中间 plugin 即使没有某个扩展点 contribution，也不会打破 contribution 的依赖偏序。

重复 plugin id、缺失 dependency、cycle、同 plugin 重复 localName、全局重复 AgentToolId/Tool name、同 plugin 重复 `(customType)`、未注册 state type、重复 state access、非 PLATFORM descriptor 均在冻结阶段失败。

### BranchView 与 state access

[`BranchView`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/BranchView.java) 只持有已校验的 immutable `EntryPath`，提供：

```java
List<CustomEntryPayload> customEntries(PluginId pluginId, String customType);
Optional<CustomEntryPayload> latestCustomEntry(
    PluginId pluginId, String customType);
```

查询同时匹配 pluginId 和 canonical customType，结果按 root-to-head 或 head-to-root 的语义返回；不会扫描其它 Session、其它 Thread 或 Store。

`PluginStateDeclaration(customType, mode)` 的 `mode` 为 `READ` 或 `WRITE`。PluginTool 通过 `stateAccesses()` 静态声明读取/写入的 custom state；Catalog 确认 customType 由同一 plugin 注册。Harness Core 在同一 Assistant sibling batch 内按 ordinal 检查冻结访问：

```text
READ -> READ       allow
READ -> WRITE      allow
WRITE -> READ      SIBLING_STATE_CONFLICT
WRITE -> WRITE     SIBLING_STATE_CONFLICT
```

不同 customType 或不同 pluginId 不冲突。

### PluginTool、intent 与 projector

[`PluginTool`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginTool.java) 是同步纯函数：

```java
ToolDescriptor descriptor();
List<PluginStateDeclaration> stateAccesses();
PluginToolResult execute(PluginToolContext context, ToolCall call);
```

`PluginToolContext` 只包含 `BranchView branch` 和 `Instant executedAt`。[`PluginToolResult`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginToolResult.java) 包含模型可见 `ToolResult` 和有序 `List<AppendCustomEntry> intents`；error ToolResult 不允许携带 intents。

[`AppendCustomEntry`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/AppendCustomEntry.java) 持有最终 `CustomEntryPayload(pluginId, customType, schemaVersion, dataJson)`，payload 自身完成 schemaVersion、canonical identifier、JSON object 和大小校验。Core 还会验证 intent owner、custom type ownership、WRITE 声明、descriptor/provenance 和 effects 上限。

[`ContextProjector`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/ContextProjector.java) 将 `BranchView` 纯投影为不可变 `List<AgentMessage>`。默认 CUSTOM Entry 对 Provider 不可见；只有调用方选中 projector 并把返回消息放入本 turn frozen preamble 时才可见。projector 不产生 state mutation。

## 执行 / 状态 trace

```text
PluginCatalog frozen at startup
  -> Model turn resolver selects PluginTool contribution
  -> ToolBinding freezes ContributionId + descriptor + state accesses
  -> Core builds BranchView at Assistant Entry
  -> PluginTool.execute(context, ToolCall)
  -> PluginToolResult(ToolResult, intents)
  -> Core validates intents / effects / ownership
  -> ToolInvocation SUCCEEDED + ToolEffectBatch
  -> next Thread claim appends CUSTOM entries then TOOL result
```

上下文读取为：

```text
candidate BranchView
  -> selected ContextProjector(s)
  -> frozen AgentMessage preamble
  -> ModelRequestSpec
  -> ProviderRequest
```

PluginTool 不在执行时重新解析 catalog、读取 Store 或重建 branch；Tool retry 使用 Invocation 中冻结的 contribution provenance 和 state accesses。

## 不变量、failure / recovery

- `PluginCatalog.from` 先验证整个 DAG，再调用 contributor；任何 graph failure 都是构建失败，不产生半成品 catalog。
- catalog 构建后 plugin 对象、descriptor、contribution list、state access 和 projector list 均冻结；外部修改不影响 catalog。
- Tool descriptor 必须 PLATFORM，Tool name 全局唯一；descriptor/version/ContributionId 漂移在 binding 恢复时确定性拒绝。
- `AppendCustomEntry` 只能成为同 owner、已注册 customType、声明 WRITE 的 effect；违反时 Core 以 plugin contract violation 失败，effects 为空。
- error ToolResult 与 intents 互斥，Resource externalization 和 durable `SUCCEEDED` 发生在 intent 校验之后；失败不留下部分 Entry。
- branch state 只从当前 Assistant Entry 的 root-to-head path 读取；fork 只看分叉点可见的 CUSTOM snapshot。
- projector 异常、非法 message 或 intent validation failure 不被转换为状态写入；调用方按 Tool failure 关闭当前 outcome。

插件 API 没有“热更新后继续运行”的状态转换。Catalog 只在组合根完成构建时有效，插件集合变化需要重新建立新的运行时装配。

## 配置 / 扩展

- Plugin contribution 通过 `HarnessPlugin.contribute` 和 scoped registrar 注册。
- Branch state contribution 先注册 `customEntryType`，PluginTool 再声明对应
  `READ`/`WRITE` access。
- `ContextProjector` 是插件向 Model context 投影消息的唯一扩展点；投影结果不
  包含完整 transcript 或 Store handle。
- priority 只解决同一扩展点的非依赖排序；requires 传递闭包优先于 priority。
- plugin-api 不定义 plugin discovery、trusted JAR classloader、Spring bean 装配或 runtime reload；这些属于组合根边界。

## 测试与源码入口

### 源码入口

- [`HarnessPlugin.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/HarnessPlugin.java)、[`PluginDescriptor.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginDescriptor.java)、[`PluginCatalog.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginCatalog.java)
- [`PluginRegistrar.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginRegistrar.java)、[`ContributionId.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/ContributionId.java)、[`PluginStateDeclaration.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginStateDeclaration.java)
- [`PluginTool.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginTool.java)、[`PluginToolResult.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/PluginToolResult.java)、[`AppendCustomEntry.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/AppendCustomEntry.java)
- [`BranchView.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/BranchView.java)、[`ContextProjector.java`](../../harness/plugin-api/src/main/java/fun/fengwk/kkstudio/harness/plugin/api/ContextProjector.java)

### 关键测试守卫

- [`PluginApiModuleArchitectureTest.java`](../../harness/plugin-api/src/test/java/fun/fengwk/kkstudio/harness/plugin/api/PluginApiModuleArchitectureTest.java)：依赖方向和 API 不暴露 Store/gateway/processor。
- [`PluginCatalogTest.java`](../../harness/plugin-api/src/test/java/fun/fengwk/kkstudio/harness/plugin/api/PluginCatalogTest.java)：DAG、拓扑顺序、priority、ContributionId、freeze、duplicate 和未调用 contributor。
- [`PluginContractTest.java`](../../harness/plugin-api/src/test/java/fun/fengwk/kkstudio/harness/plugin/api/PluginContractTest.java)：intent、error ToolResult、immutable context 和 state declaration。
- [`BranchViewTest.java`](../../harness/plugin-api/src/test/java/fun/fengwk/kkstudio/harness/plugin/api/BranchViewTest.java)：branch-scoped `(pluginId, customType)` 查询与 latest snapshot。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、
[Harness Tool](harness-tool.md)、[Harness Plugin Goal](harness-plugin-goal.md)、
[Web](web.md)。
