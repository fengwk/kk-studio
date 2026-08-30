# Harness Tool

## 定位

`harness-tool` 是 Harness 各边界共享的纯 Java Tool 值契约模块。它只定义稳定工具身份、模型可见描述、参数 schema、调用与结果内容、Resource 引用和 JSON codec；不包含执行 SPI、Environment 路由、Daemon wire、Contributor、权限、调度或持久化。

执行 SPI 与 Contributor 目录位于 [`harness-contributor-api`](harness-contributor-api.md)，Environment Capability 与 Daemon v5 wire 位于 [`harness-environment`](harness-environment.md)，durable 执行状态机位于 [`harness-runtime`](harness-runtime.md)。

## Goals / Non-goals

### Goals

- 提供跨 Runtime、Provider、Contributor 和持久化 codec 共享的 route-neutral Tool 值对象。
- 用 `AgentToolId` 固定 durable/settings 身份，用 `ToolDescriptor.name` 固定模型调用名。
- 对参数 JSON、schema、结果内容、Resource URI 和序列化边界执行一致的 fail-closed 校验。
- 保持模块只依赖 JDK 与 Jackson，不反向依赖任何执行层。

### Non-goals

- 不定义 `Tool.execute`、listener、cancel handle 或 gateway admission。
- 不定义 Environment identity、Capability catalog、Daemon envelope 或 wire message。
- 不决定工具由本地代码还是远端 Environment 执行。
- 不读取 Session、Agent、Store、Spring bean 或系统设置。

## 依赖边界

```text
JDK + Jackson
      │
      ▼
 harness-tool
   ├─ identity / descriptor
   ├─ schema / argument validation
   ├─ call / result / content
   ├─ ResourceRef
   └─ JSON codecs

禁止：harness-environment / harness-runtime / harness-daemon
      contributor-api / platform / web / Spring / JDBC / Provider SDK
```

生产依赖见 [`pom.xml`](../../harness/tool/pom.xml)。[`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java) 扫描主源码 import，守卫该依赖方向。

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
- [`ToolSideEffect`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java) 固定为 `READ_ONLY`、`IDEMPOTENT`、`NON_IDEMPOTENT`。
- timeout 不得为负；具体 admission 和 deadline 解释由执行层负责。

[`AgentToolDefinition`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java) 只组合：

```text
AgentToolId + ToolDescriptor + ToolVisibility
```

[`ToolVisibility`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java) 只有 `SELECTABLE` 与 `INTERNAL`。定义中没有 backend 分类或 Environment mapping。

### 参数 schema 与 ToolCall

schema 位于 [`schema`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/schema/) 包，支持 object、array、string、integer、number、boolean 和 enum。object schema 显式保存 properties、required 与 additional-properties policy。

[`ToolCall`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java) 保存 `id`、`toolName` 与原始 JSON object。执行前必须调用：

```java
ToolCall normalized = call.validateFor(descriptor);
```

该操作先校验 Tool name，再执行约定归一化，最后按 schema 校验；若参数被归一化则返回新的 `ToolCall`。执行路径必须使用返回值，不能继续使用原始 JSON。

### 结果内容

[`ToolResult`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java) 保存：

```text
toolCallId / contents / error / detailsJson
```

[`ToolContent`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolContent.java) 是 sealed hierarchy：

- `TextToolContent`
- `JsonToolContent`
- `BinaryToolContent`
- `ResourceToolContent`

结果最多 64 个 content；`detailsJson` 必须是 JSON object，原始 UTF-8 最多 1 MiB。`ToolResult.error` 生成标准语义错误结果。

执行边界可进一步限制 partial/terminal 类型。例如 Platform 禁止 partial 携带 Binary/Resource，并在成功 terminal 时将非 durable Resource 全量外部化；这些限制不属于本模块。

### ResourceRef

[`ResourceRef`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceRef.java) 是严格、不可变的 Resource URI 引用：

```text
uri / mediaType / name? / size? / sha256?
```

允许 scheme：`data`、`file`、`s3`、`https`、`http`。构造时统一校验 URI canonical form、media type、UTF-8、size、sha256；`data:` 还会有界解码并核对声明事实。主要边界：

```text
URI UTF-8             <= 128 KiB
data decoded bytes    <= 64 KiB
name UTF-8            <= 512 B
preview UTF-8         <= 16 KiB
```

`ResourceRef` 只表达引用，不负责授权读取、对象生命周期、签名 URL、owner 关系或 durable materialization。

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
- Resource 引用在构造和 codec decode 时使用相同严格规则，避免“直接构造合法、反序列化非法”或相反。
- 任何 schema、JSON、Unicode、URI 或大小边界失败均抛出确定性参数异常，不做宽松修复。

## 测试与源码入口

### 源码入口

- [`AgentToolId.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolId.java)、[`AgentToolDefinition.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java)、[`ToolDescriptor.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java)
- [`ToolCall.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java)、[`ToolResult.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java)、[`ToolContent.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolContent.java)
- [`ResourceRef.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceRef.java)、[`ResourceUriValidator.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceUriValidator.java)
- [`schema`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/schema/)、[`codec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/)

### 关键测试守卫

- [`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java)：模块依赖方向。
- [`ToolContractTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolContractTest.java)、[`ToolExecutionNormalizationTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolExecutionNormalizationTest.java)：descriptor、call 与参数归一化。
- [`ResourceRefTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ResourceRefTest.java)、[`ToolResultTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolResultTest.java)：Resource 与结果边界。
- [`AgentToolDefinitionJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/AgentToolDefinitionJsonCodecTest.java)、[`ToolDescriptorJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/ToolDescriptorJsonCodecTest.java)、[`ToolResultJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodecTest.java)：严格 JSON codec。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Contributor API](harness-contributor-api.md)、[Harness Runtime](harness-runtime.md)、[Harness Daemon](harness-daemon.md)。
