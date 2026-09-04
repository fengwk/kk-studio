# Harness Tool

## 定位

`harness-tool` 是 Harness 各边界共享的纯 Java Tool 值契约模块。它基于 [`harness-common`](harness-common.md) 的 `InputSchema`、`ResultContent` 与 `ResourceRef`，定义稳定工具身份、模型可见描述、调用与结果内容组合以及严格 JSON codec；不包含执行 SPI、Environment 路由、Daemon wire、Contributor、权限、调度或持久化。

基础 schema、统一结果内容与 Resource 引用位于 [`harness-common`](harness-common.md)，执行 SPI 与 Contributor 目录位于 [`harness-contributor-api`](harness-contributor-api.md)，Environment Capability 与 Daemon v6 wire 位于 [`harness-environment`](harness-environment.md)，durable 执行状态机位于 [`harness-runtime`](harness-runtime.md)。

## Goals / Non-goals

### Goals

- 提供跨 Runtime、Provider、Contributor 和持久化 codec 共享的 route-neutral Tool 值对象。
- 用 `AgentToolId` 固定 durable/settings 身份，用 `ToolDescriptor.name` 固定模型调用名。
- 组合 `harness-common` 的 `ResultContent` 与 `InputSchema`，对调用参数、结果内容和序列化边界执行一致的 fail-closed 校验。
- 保持模块只依赖 JDK、Jackson 与 `harness-common`，不反向依赖任何执行层。

### Non-goals

- 不重复定义 `InputSchema`、`ResultContent` 或 `ResourceRef`（由 [`harness-common`](harness-common.md) 提供）。
- 不定义 `Tool.execute`、listener、cancel handle 或 gateway admission。
- 不定义 Environment identity、Capability catalog、Daemon envelope 或 wire message。
- 不决定工具由本地代码还是远端 Environment 执行。
- 不读取 Session、Agent、Store、Spring bean 或系统设置。

## 依赖边界

```text
harness-common + Jackson
         │
         ▼
    harness-tool
      ├─ identity / descriptor (AgentToolId, AgentToolDefinition, ToolDescriptor, ToolVisibility, ToolSideEffect)
      ├─ call / result (ToolCall, ToolResult)
      └─ JSON codecs (AgentToolDefinitionJsonCodec, ToolDescriptorJsonCodec, ToolResultJsonCodec)

禁止：harness-environment / harness-runtime / harness-daemon
      contributor-api / platform / web / Spring / JDBC / Provider SDK
```

生产依赖见 [`pom.xml`](../../harness/tool/pom.xml)。[`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java) 扫描主源码 import，守卫该依赖方向。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.tool` | 提供 route-neutral 的 Tool 身份（`AgentToolId`）、模型可见描述（`ToolDescriptor`）、顶层定义（`AgentToolDefinition`）、调用（`ToolCall`）与结果（`ToolResult`）纯值契约 | 纯值模型；不定义执行 SPI、listener、权限或持久化调度状态机，不读取系统配置 |
| `fun.fengwk.kkstudio.harness.tool.codec` | 提供 Tool 定义、ToolDescriptor 与 ToolResult 的严格、确定性 JSON 编解码器 | 拒绝未知字段与语法错误，排序保证确定性；不维护历史 backend 属性或外部排序 |

## 核心模型 / API

### 稳定身份与模型定义

[`AgentToolId`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolId.java) 是最长 128 字符的 canonical 小写身份：

```text
[a-z0-9]+(?:[.-][a-z0-9]+)*
```

它用于 catalog、settings、permission、日志和 durable binding，不等同于模型输出的 Tool name。

[`ToolDescriptor`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java) 冻结：

```text
name / version / description / rendererKey
inputSchema / sideEffect / timeout
```

- `name` 是模型可见调用名，必须以字母开头，只含字母、数字、`_`、`-`。
- `inputSchema` 使用 `harness-common` 的 `InputSchema`。
- [`ToolSideEffect`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java) 固定为 `READ_ONLY`、`IDEMPOTENT`、`NON_IDEMPOTENT`。
- timeout 不得为负；具体 admission 和 deadline 解释由执行层负责。

[`AgentToolDefinition`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java) 只组合：

```text
AgentToolId + ToolDescriptor + ToolVisibility
```

[`ToolVisibility`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java) 只有 `SELECTABLE` 与 `INTERNAL`。定义中没有 backend 分类或 Environment mapping。

### 参数校验与 ToolCall

[`ToolCall`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java) 保存 `id`、`toolName` 与原始 JSON object。执行前必须调用：

```java
ToolCall normalized = call.validateFor(descriptor);
```

该操作先校验 Tool name，再经 `InputNormalizer` 执行约定归一化，最后按 `InputValidator` 校验参数；若参数被归一化则返回新的 `ToolCall`。执行路径必须使用返回值，不能继续使用原始 JSON。

### 结果组合与 ToolResult

[`ToolResult`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java) 保存：

```text
toolCallId / contents / error / detailsJson
```

- `contents` 是 `harness-common` 的 `List<ResultContent>`（最多 64 项），支持 Text、Json、Binary、Resource 内容；
- `detailsJson` 必须是有效 JSON object，原始 UTF-8 最多 1 MiB；
- `ToolResult.error` 生成标准语义错误结果。

执行边界可进一步限制 partial/terminal 类型。例如 Platform 禁止 partial 携带 Binary/Resource，并在成功 terminal 时将非 durable Resource 全量外部化；这些限制不属于本模块。

### JSON codecs

[`codec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/) 包提供：

- `AgentToolDefinitionJsonCodec`
- `ToolDescriptorJsonCodec`
- `ToolResultJsonCodec`

codec 对未知字段、duplicate/trailing token、非法类型和领域不变量 fail closed。它们只编码当前值模型，不接受历史 backend 字段。

## 不变量、failure / recovery

- `AgentToolId` 是稳定系统身份；`ToolDescriptor.name` 是模型协议名，两者不能互相替代。
- `AgentToolDefinition` 不携带执行 backend；具体 `Tool` 实例和 requirements 由 Contributor catalog 冻结。
- `ToolCall` 构造只保证参数是 JSON object；完整 schema 校验发生在 `validateFor`。
- Tool result 的 `toolCallId` 必须由执行边界与请求精确关联；本模块不持有 invocation 生命周期。
- 任何 JSON、Unicode、URI 或大小边界失败均抛出确定性参数异常，不做宽松修复。

## 测试与源码入口

### 源码入口

- [`AgentToolId.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolId.java)、[`AgentToolDefinition.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java)、[`ToolDescriptor.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java)
- [`ToolVisibility.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java)、[`ToolSideEffect.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java)
- [`ToolCall.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java)、[`ToolResult.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java)
- [`AgentToolDefinitionJsonCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/AgentToolDefinitionJsonCodec.java)、[`ToolDescriptorJsonCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolDescriptorJsonCodec.java)、[`ToolResultJsonCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodec.java)

### 关键测试守卫

- [`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java)：模块依赖方向。
- [`AgentToolDefinitionTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinitionTest.java)、[`AgentToolIdTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/AgentToolIdTest.java)：身份与定义约束。
- [`ToolContractTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolContractTest.java)、[`ToolExecutionNormalizationTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolExecutionNormalizationTest.java)：descriptor、call 与参数归一化。
- [`ToolResultTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolResultTest.java)：结果内容组合与 detailsJson 边界。
- [`AgentToolDefinitionJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/AgentToolDefinitionJsonCodecTest.java)、[`ToolDescriptorJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/ToolDescriptorJsonCodecTest.java)、[`ToolResultJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodecTest.java)：严格 JSON codec。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Environment](harness-environment.md)、[Harness Contributor API](harness-contributor-api.md)、[Harness Runtime](harness-runtime.md)、[Harness Daemon](harness-daemon.md)。
