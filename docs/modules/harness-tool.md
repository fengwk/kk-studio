# Harness Tool

模型说「调用 read」，Runtime 要用同一句话定位到持久化配置里的工具、做权限判定、把参数交给实现、把结果写回会话树，而 Provider 只认识工具名与 JSON Schema。这些环节必须共享同一份工具身份与数据契约，否则模型可见的名称、持久化配置与 durable 记录会各说各话。本模块就是这份契约：与执行位置无关的工具身份、定义、调用与结果值对象，以及它们的严格 JSON 编解码器。

模块是纯 Java 值契约，不依赖 Environment、Contributor、Session、Runtime、Spring 或持久化框架；生产依赖只有 [`harness-common`](harness-common.md)、Jackson 与 JDK（见 [`pom.xml`](../../harness/tool/pom.xml)），[`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java) 扫描主源码 import 守卫该方向。工具执行 SPI、权限、调度与 durable 状态由 [`harness-contributor-api`](harness-contributor-api.md) 与 [`harness-runtime`](harness-runtime.md) 承担。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.tool` | 工具身份与模型可见描述 `ToolDescriptor`、顶层定义 `AgentToolDefinition`、调用 `ToolCall` 与结果 `ToolResult` | 纯值模型；执行接口、权限拦截与调度状态机由 Contributor 与 Runtime 承接，系统配置由调用方传入 |
| `fun.fengwk.kkstudio.harness.tool.codec` | `AgentToolDefinition`、`ToolDescriptor` 与 `ToolResult` 的严格确定性 JSON 编解码 | 未知字段、重复键、尾随 token 直接拒绝；属性按字典序确定性排序，列表外部顺序由调用方组织 |

## 唯一身份：模型可见 name

[`ToolDescriptor`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java) 的 `name` 是工具的唯一 Agent 侧身份：模型调用、Agent 配置、权限规则键、catalog 条目与 durable binding 全部使用同一个名字。`ToolDescriptor.isValidName` 要求它字母开头、只含字母数字与 `_`、`-`，且不超过 64 字符（`NAME_MAX_LENGTH`）；该规则在所有持久化与配置边界共享，因此不存在第二套内部 ID 或工具版本。`description`、`rendererKey` 不得空白，`inputSchema` 复用 `harness-common` 的 `InputSchema`，`defaultTimeout` 不得为负，`0` 表示该工具没有执行 deadline。[`ToolSideEffect`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolSideEffect.java) 只有 `READ_ONLY`、`IDEMPOTENT`、`NON_IDEMPOTENT` 三档，供重试与未知结果处理判定；超时拦截与截止时间计算在执行层，本模块只承载声明。

[`AgentToolDefinition`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java) 把 `ToolDescriptor` 与 [`ToolVisibility`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolVisibility.java)（`SELECTABLE` / `INTERNAL`）组合为模型契约，但不含任何环境或实现字段：具体 `Tool` 实例与环境需求由 Contributor 目录解析并在冻结时绑定。Contributor 侧的 scoped `ContributionId`（`(contributorId, localName)`）只用于定位贡献归属，不是 Agent 可见身份。

## 从模型输出到可执行参数

[`ToolCall`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java) 保存 `id`、`toolName` 与参数 JSON。构造时只要求它是合法 JSON object（空白规范化为 `{}`），不改写内容——模型给什么就存什么；所有转换都放在显式的 `validateFor(descriptor)`：

```java
ToolCall normalized = call.validateFor(descriptor);
```

该方法先要求工具名与 descriptor 一致，再经 `InputNormalizer` 按 `inputSchema` 做数字字符串与可缺省 `null` 的通用容错改写，最后由 `InputValidator` 严格校验。参数名及其 required、类型、附加属性规则只由 descriptor schema 决定，不因 `read`、`write` 等工具名获得隐式别名或字段搬运；schema 声明 `file` 时 `file` 就是有效字段，schema 只声明 `path` 时 `file`、`filePath`、`file_path` 都不会替代它。已有 canonical 参数不会被重命名。参数被改动时返回新的 `ToolCall`，执行路径只能使用这个返回值，不得回头读原始 JSON。

同一入口在三个边界各调用一次，因为三者看到的参数必须一致：Planner 校验 Provider wire 上那条 raw call 可按 schema 归一化，durable `ToolInvocation` 入库时校验一次，transient `ToolInvocationRequest` 在权限 preflight、审批预览与执行之前固化归一化副本。原始 function call 仍原样保存在模型结果与 assistant history 中，供审计与 wire replay。

## 结果容器

[`ToolResult`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java) 是一次调用的完整或部分结果：

- `toolCallId` 非空白，把结果关联回请求；
- `contents` 是 `harness-common` 的 `List<ResultContent>`，最多 64 项；
- `detailsJson` 必须是有效 JSON object，且在树解析前先按 UTF-8 字节数限制在 1 MiB 内；
- `ToolResult.error(callId, message)` 生成携带单条文本内容的标准错误结果，使失败也能作为语义结果传递。

值模型允许 `BinaryResultContent`，但可持久化的 [`ToolResultJsonCodec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodec.java) 在编码与解码两个方向都拒绝 Binary：内存中的二进制必须在执行边界先外部化为 managed Resource。字节预算同样不在本模块决定——[`harness-runtime`](harness-runtime.md) 的 [`ToolResultSizeLimits`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolResultSizeLimits.java) 以 canonical JSON 字节数计，终态 1 MiB、partial 256 KiB；本模块只提供做这件事所需的精确编码器。

## JSON 编解码器

[`codec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/) 包是这三类值模型与 wire 之间唯一的转换层，三者都启用 `STRICT_DUPLICATE_DETECTION` 与 `FAIL_ON_TRAILING_TOKENS`，遇到未知字段、类型错误或领域不变量违反即抛异常：

- [`AgentToolDefinitionJsonCodec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/AgentToolDefinitionJsonCodec.java) 处理 `descriptor` / `visibility` 两个顶层字段，用于 durable 工具定义；任何旧 wire 的 `id` 字段都按未知字段拒绝，没有兼容读取路径；
- [`ToolDescriptorJsonCodec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolDescriptorJsonCodec.java) 按固定顺序输出 `name`、`description`、`rendererKey`、`sideEffect`、`defaultTimeoutMillis`、`inputSchema`，其中 schema 字段委派给 `harness-common` 的 `SchemaJsonCodec`，保证 Provider tool schema 与内部 schema 只有一份序列化实现；
- `ToolResultJsonCodec` 提供静态的 `encode` / `decode` 与 `exceedsEncodedUtf8Bytes(result, maxBytes)`，后者按与 `encode` 完全相同的字段顺序流式计数，超限即停，不物化完整 JSON，因此执行层可以在真正序列化之前判定 partial 与终态的字节预算。

## 源码与测试

- 身份与定义：[`ToolDescriptor.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java)、[`AgentToolDefinition.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/AgentToolDefinition.java)
- 调用与归一化：[`ToolCall.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java)，通用参数归一化由 [`InputNormalizer.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizer.java) 提供
- 结果容器：[`ToolResult.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java)
- 编解码：[`codec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/)
- [`ToolExecutionNormalizationTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolExecutionNormalizationTest.java) 锁定 schema 决定参数名、数字文本、可选 `null` 与不可变性边界；[`ToolContractTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolContractTest.java) 用反射固定 descriptor 的组件顺序与类型；[`ToolResultJsonCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/codec/ToolResultJsonCodecTest.java) 逐字节比对 bounded 编码与 `encode`，并断言 Binary 被拒。`ToolCall` 与 [`AgentToolDefinitionJsonCodec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/AgentToolDefinitionJsonCodec.java) 均有 JaCoCo 行覆盖率不低于 90% 的门禁，通用 `InputNormalizer` 的同等门禁位于 `harness-common`。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Environment](harness-environment.md)、[Harness Contributor API](harness-contributor-api.md)、[Harness Runtime](harness-runtime.md)。
