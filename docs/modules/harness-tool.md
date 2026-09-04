# Harness Tool

## 定位

`harness-tool` 是 Harness 各边界共享的纯 Java Tool 值契约模块。它基于 [`harness-common`](harness-common.md) 的 `InputSchema`、`ResultContent` 与 `ResourceRef`，定义与执行位置解耦的稳定工具身份、模型可见描述、调用与结果数据模型，以及严格确定性的 JSON 编解码器。

分层职责划分明确：基础 schema、结果内容与 Resource 引用由 [`harness-common`](harness-common.md) 提供；执行 SPI 与 Contributor 目录由 [`harness-contributor-api`](harness-contributor-api.md) 承接；Environment 能力与 Daemon 通信协议归入 [`harness-environment`](harness-environment.md)；持久化执行状态机由 [`harness-runtime`](harness-runtime.md) 维护。

## 职责

### 核心职责

- 提供跨 Runtime、Provider、Contributor 和持久化编解码器共享的 route-neutral Tool 值对象。
- 使用 `AgentToolId` 标识持久化与系统配置中的稳定身份，使用 `ToolDescriptor.name` 标识模型可见的调用名称。
- 组合 `harness-common` 的 `ResultContent` 与 `InputSchema`，对调用参数、结果内容和序列化边界执行严格的 fail-closed 校验。
- 生产依赖保持精简，仅依赖 JDK、Jackson 与 `harness-common`。

### 协作边界

- 基础输入模式 `InputSchema`、结果内容 `ResultContent` 与资源引用 `ResourceRef` 直接引用 [`harness-common`](harness-common.md)。
- 执行接口 `Tool.execute`、监听器、取消句柄与网关准入逻辑归入 [`harness-contributor-api`](harness-contributor-api.md) 与运行时。
- 环境标识 `EnvironmentId`、能力目录与 Daemon 通信协议归入 [`harness-environment`](harness-environment.md)。
- 本地与远端环境的执行调度决策由 Contributor 与执行层驱动。
- 会话上下文、存储仓储、Spring 容器与系统配置的读取均由上层运行时与业务编排层处理。

## 依赖边界

```text
harness-common + Jackson
         │
         ▼
    harness-tool
      ├─ identity / descriptor (AgentToolId, AgentToolDefinition, ToolDescriptor, ToolVisibility, ToolSideEffect)
      ├─ call / result (ToolCall, ToolResult)
      └─ JSON codecs (AgentToolDefinitionJsonCodec, ToolDescriptorJsonCodec, ToolResultJsonCodec)

依赖约束：仅依赖 harness-common 与 Jackson；执行层、基础设施与 Spring 集成均置于外层。
```

生产依赖见 [`pom.xml`](../../harness/tool/pom.xml)。[`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java) 扫描主源码 import，守卫该依赖方向。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.tool` | 提供 route-neutral 的 Tool 身份（`AgentToolId`）、模型可见描述（`ToolDescriptor`）、顶层定义（`AgentToolDefinition`）、调用（`ToolCall`）与结果（`ToolResult`）纯值契约 | 纯值模型；执行接口、权限拦截与调度状态机由 Contributor 及 Runtime 承接，系统配置由外部调用方传入 |
| `fun.fengwk.kkstudio.harness.tool.codec` | 提供 Tool 定义、ToolDescriptor 与 ToolResult 的严格、确定性 JSON 编解码器 | 遇到未知字段或语法错误时立即抛出异常；属性按字典序确定性排序，列表外部排序由调用方显式组织 |

## 核心模型 / API

### 稳定身份与模型定义

[`AgentToolId`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolId.java) 是最长 128 字符的规范小写身份：

```text
[a-z0-9]+(?:[.-][a-z0-9]+)*
```

它用于 catalog、settings、permission、日志和 durable binding，独立于模型输出的 Tool name。

[`ToolDescriptor`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java) 声明工具的外部契约：

```text
name / version / description / rendererKey
inputSchema / sideEffect / timeout
```

- `name` 是模型可见调用名，以字母开头，仅包含字母、数字、`_`、`-`。
- `inputSchema` 采用 `harness-common` 的 `InputSchema`。
- [`ToolSideEffect`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java) 固定为 `READ_ONLY`、`IDEMPOTENT`、`NON_IDEMPOTENT`。
- timeout 必须为非负值，超时拦截与截止时间计算交由执行层处理。

[`AgentToolDefinition`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java) 组合身份、描述与可见性：

```text
AgentToolId + ToolDescriptor + ToolVisibility
```

[`ToolVisibility`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java) 包含 `SELECTABLE` 与 `INTERNAL`。Contributor 通过具体实现与 requirements 表达执行环境和后端能力映射。

### 参数校验与 ToolCall

[`ToolCall`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java) 保存 `id`、`toolName` 与原始 JSON object。执行前通过校验入口处理：

```java
ToolCall normalized = call.validateFor(descriptor);
```

该操作依次校验 Tool name，经 `InputNormalizer` 执行约定归一化，再按 `InputValidator` 校验参数；若参数被归一化则返回新的 `ToolCall`。后续执行路径直接使用返回的归一化对象，避免沿用原始参数。

### 结果组合与 ToolResult

[`ToolResult`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java) 保存执行产物：

```text
toolCallId / contents / error / detailsJson
```

- `contents` 是 `harness-common` 的 `List<ResultContent>`（最多 64 项），支持 Text、Json、Binary、Resource 内容；
- `detailsJson` 必须是有效 JSON object，原始 UTF-8 最多 1 MiB；
- `ToolResult.error` 生成标准语义错误结果。

平台层可在执行流中施加更严格的边界策略，例如限制流式阶段（partial）仅传输文本或 JSON，并在终态将临时资源外部化持久化；本模块专注于基础数据容器契约。

### JSON 编解码器

[`codec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/) 包提供：

- `AgentToolDefinitionJsonCodec`
- `ToolDescriptorJsonCodec`
- `ToolResultJsonCodec`

编解码器针对当前值模型工作，输入中出现未知字段、重复键或尾随字符时直接拒绝，对非法类型与领域不变量严格执行 fail-closed 策略。

## 不变量、failure / recovery

- `AgentToolId` 是系统内部的稳定标识，`ToolDescriptor.name` 是模型调用的协议名称，两者分别承担不同职责。
- `AgentToolDefinition` 保持与执行后端解耦，具体的 `Tool` 实例与环境需求由 Contributor 目录解析并冻结。
- `ToolCall` 构造时保证参数为 JSON object，完整 schema 校验由 `validateFor` 显式执行。
- `ToolResult` 通过 `toolCallId` 关联调用请求，调用生命周期由外部运行时维护。
- 数据校验严格执行 fail-closed 策略：凡遇到格式异常、编码非法、URI 超限或体积越界，立即抛出明确异常并中止流程。

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
