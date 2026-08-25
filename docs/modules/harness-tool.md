# Harness Tool

## 定位

`harness-tool` 是 Harness 各边界共享的纯 Java Tool contract 模块，提供 route-neutral descriptor、输入 schema、调用/结果值对象、异步执行 SPI、Environment catalog、RemoteTool 代理以及 Daemon wire codec。Descriptor 只描述工具能力，不携带具体 Environment、Session、Agent 或执行连接；持久化、权限、调度和 terminal 状态由 Runtime/Platform 负责。

模块的生产依赖只有 Jackson databind；主源码不依赖 Runtime、Daemon、Platform、Web、Spring、数据库或 Provider SDK。包级边界见 [`package-info.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/package-info.java)。

## Goals / Non-goals

### Goals

- 以同一份 `ToolDescriptor` 和 `ToolParamsSchema` 约束模型声明、参数校验、Daemon 注册和远程调用。
- 让本地 Tool 与远程 Environment Tool 使用同一异步执行/取消/partial 回调形状。
- 让 Resource URI、workspace route、wire envelope 和结果 payload 在构造或 codec 边界完成 canonical 与大小校验。
- 提供固定版本的 Environment tool catalog 与 Daemon v3 / capabilities v4 公共类型。

### Non-goals

- 不实现 Tool permission、ToolInvocation 状态机、Work 调度、Entry 写入或 Resource 的 durable 物化。
- 不解析 Agent/Session 上下文，也不选择 Environment；`EnvironmentBinding` 仅是调用方冻结的 route + workspace 值。
- `http`/`https` Resource URI 的校验不承诺 SSRF 防护；真实路径越界和 symlink 安全由 Daemon 执行边界处理。
- 不把连接、URI 或内存中的 `BinaryToolContent` 当作 durable 目的地。

## 依赖边界

```text
runtime / platform / daemon
          │
          ▼
   harness-tool
   ├─ descriptor + schema
   ├─ execution SPI
   ├─ ResourceRef / EnvironmentBinding
   ├─ RemoteTool port
   └─ Daemon v3 wire + capabilities v4
```

`harness-tool` 的生产依赖是 `jackson-databind`，测试依赖 JUnit；模块 POM 与架构守卫分别见 [`pom.xml`](../../harness/tool/pom.xml) 和 [`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java)。

## 核心模型 / API

### Descriptor、schema 与 execution SPI

`ToolDescriptor` 是不可变 record：

```text
name, version, type, description, rendererKey,
inputSchema, sideEffect, timeout
```

- `name` 必须匹配 `[A-Za-z][A-Za-z0-9_-]*`；`version`、`description`、`rendererKey` 非空；`timeout` 非负。
- `type` 只有 `PLATFORM` 和 `ENVIRONMENT`；`sideEffect` 只有 `READ_ONLY`、`IDEMPOTENT`、`NON_IDEMPOTENT`。
- `ToolVisibility`（`SELECTABLE` / `INTERNAL`）属于目录装配属性，不是 descriptor 的字段。
- `ToolParamsSchema` 是 provider 无关的 JSON Schema 子集，支持 string、integer、number、boolean、enum、array、object。Object schema 明确保存 `properties`、`required` 和 `additionalProperties`。

[`ToolDescriptorJsonCodec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/codec/ToolDescriptorJsonCodec.java) 的 descriptor JSON 字段为 `name`、`version`、`type`、`description`、`rendererKey`、`sideEffect`、`timeoutMillis`、`inputSchema`。Codec 拒绝未知字段、duplicate field、trailing token、错误类型和缺少 object-schema 必需字段；输出时 `properties`、`required` 按字典序，enum 保留输入顺序。

执行 SPI 只有三件事：

```java
ToolDescriptor descriptor();
ToolExecutionHandle execute(
    ToolExecutionRequest request,
    ToolExecutionListener listener);
```

[`ToolExecutionRequest`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/execution/ToolExecutionRequest.java) 构造时执行 `ToolCall.validateFor`：要求参数是 JSON object，按 schema 做静默归一化，再做严格校验；因此执行器只能读取归一化后的 `argumentsJson`。`timeout=Duration.ZERO` 表示采用 descriptor 默认值，非零值是本次请求覆盖值；`workdir` 非空时必须是 absolute path。Listener 的 `onPartial`、`onComplete`、`onError` 中，完成和错误至多出现一次；[`ToolExecutionHandle`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/execution/ToolExecutionHandle.java) 的 `cancel()` 必须幂等。

### ToolCall、ToolResult 与内容

`ToolCall(id, toolName, argumentsJson)` 保留模型调用 ID 和 JSON object 参数。`validateFor(descriptor)` 同时检查工具名、normalization 和 schema。

`ToolResult(toolCallId, contents, error, detailsJson)` 是 partial 或 terminal 结果：

- `contents` 最多 64 项，构造时 defensive copy；
- `detailsJson` 为空时规范化为 `{}`，原始 UTF-8 最多 1 MiB，且必须是 JSON object；
- 内容是 sealed `ToolContent`：`TextToolContent`、`JsonToolContent`、`ResourceToolContent`、`BinaryToolContent`；
- `JsonToolContent` 的原始 JSON UTF-8 最多 1 MiB；
- `BinaryToolContent` 只在内存中保存并在访问时复制 byte array；Daemon 的 COMPLETED wire 必须先将它写成 Resource。

### ResourceRef 与 Environment binding

`ResourceRef(uri, mediaType, name, size, sha256)` 在构造时完成所有校验。允许的 scheme 只有 `data`、`file`、`s3`、`http`、`https`：

| 边界 | 当前约束 |
| --- | --- |
| `uri` | ASCII、UTF-8 最多 131072 bytes；无控制字符和反斜杠；percent escape 使用大写 hex，不能编码 NUL、`/`、`\` 或 unreserved 字符 |
| `data` | decoded bytes 最多 65536；必须携带 `size`/`sha256`；base64 和非 base64 payload 均要求 frozen canonical 表示 |
| `file` | 精确 `file:///...`、空 authority、无 query/fragment、路径无 percent encoding、空段和 dot segment |
| `s3` | bucket 3–63 字符、DNS 风格小写、无连续点/IP 形状；key 非空、无 percent encoding、空段或 dot segment |
| `http` / `https` | 小写非空 host、无 userinfo/query/fragment、不得显式声明默认端口、path 无 dot segment |
| `mediaType` | 小写 `type/subtype`，ASCII 最多 255 bytes，无参数 |
| `name` | 非空时无控制字符，UTF-8 最多 512 bytes |
| `preview` | `ResourceToolContent` preview 的 UTF-8 最多 16384 bytes |

`file`、`s3` 和 `data` 必须同时提供 `size` 与 64 位小写 hex `sha256`；`http`/`https` 只校验 URI 形状，不访问网络。`EnvironmentName` 是唯一持久化 route identity：小写字母/数字段以单个 `-` 分隔，最多 64 字符。`EnvironmentBinding` 由 `EnvironmentName` 和 canonical 相对 `workspacePath` 组成；`"."` 表示 root，路径最多 2048 UTF-16 字符，拒绝反斜杠、绝对路径、Windows drive、空段、`.`/`..` 段和控制字符。对应实现见 [`ResourceRef.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceRef.java)、[`ResourceUriValidator.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceUriValidator.java)、[`EnvironmentName.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/EnvironmentName.java) 和 [`EnvironmentWorkspacePath.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/EnvironmentWorkspacePath.java)。

### Environment tool catalog

[`EnvironmentToolCatalog`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/EnvironmentToolCatalog.java) 是每个 Environment Daemon 的固定目录，版本为 `"2"`。descriptor、schema 和 prompt 全部来自 `harness/tool/src/main/resources/fun/fengwk/kkstudio/harness/tool/environment/prompts/`；资源缺失会在 catalog 初始化时失败。

| Tool | type / version | side effect | descriptor timeout |
| --- | --- | --- | --- |
| `read` | ENVIRONMENT / `1` | READ_ONLY | 1 min |
| `write` | ENVIRONMENT / `1` | IDEMPOTENT | 1 min |
| `edit` | ENVIRONMENT / `1` | NON_IDEMPOTENT | 1 min |
| `bash` | ENVIRONMENT / `1` | NON_IDEMPOTENT | 1 h |
| `grep` | ENVIRONMENT / `1` | READ_ONLY | 1 h |
| `find` | ENVIRONMENT / `1` | READ_ONLY | 1 h |
| `lsp_goto_definition` | ENVIRONMENT / `1` | READ_ONLY | 2 min |
| `lsp_workspace_symbols` | ENVIRONMENT / `1` | READ_ONLY | 2 min |
| `lsp_java_decompile` | ENVIRONMENT / `1` | READ_ONLY | 2 min |
| `mcp_list_tools` | ENVIRONMENT / `1` | READ_ONLY | 30 s |
| `mcp_call_tool` | ENVIRONMENT / `1` | NON_IDEMPOTENT | 5 min |

[`ToolCatalog`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCatalog.java) 接收 Platform descriptor 和 internal name 集合，将 internal tool 从 selectable map 移出，再合并上述 Environment descriptor；Platform 与 Environment 名称冲突、重复 Platform 名称以及非法 internal 名称均在构造期拒绝。

### RemoteTool

[`RemoteTool`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/remote/RemoteTool.java) 实现与本地 Tool 相同的 `Tool` SPI，持有 descriptor、完整 `EnvironmentBinding` 和 [`RemoteToolTransport`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/remote/RemoteToolTransport.java)。它只把 `execute` 委托为远程 `INVOKE`，把 `PARTIAL` 映射为 `onPartial`，`COMPLETED` 映射为 `onComplete`，远程 `FAILED`/`CANCELLED` 映射为 `onError`；`cancel` 映射为远程 `CANCEL`。

Transport 的 admission 结果区分：

- `RemoteToolBusyException`：调用未发出，可重试；
- `RemoteToolUnavailableException`：调用未发出，目标不可用；
- `RemoteToolSendUncertainException`：发送结果不确定，不重放潜在副作用；
- 远程 terminal failure/cancel 通过专用异常交给 listener。

RemoteTool 不拥有 durable `ToolInvocation`，也不依赖 Runtime、Daemon、Spring 或数据库。

### Daemon wire 与 capabilities

[`DaemonProtocol`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonProtocol.java) 当前只支持 wire version `3`。[`DaemonEnvelope`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonEnvelope.java) 的字段是：

```json
{
  "protocolVersion": 3,
  "messageType": "INVOKE",
  "environmentName": "workspace-a",
  "invocationId": "canonical-id",
  "sequence": 12,
  "payload": {}
}
```

Envelope 强制 canonical `environmentName`、非负 `sequence`、JSON object payload、duplicate/trailing 拒绝；`INVOKE`、`CANCEL`、`STARTED`、`PARTIAL`、`COMPLETED`、`FAILED`、`CANCELLED`、`LOAD_SKILL`、`SKILL_LOADED`、`SKILL_LOAD_FAILED` 必须携带 `invocationId`。目录控制面 `LIST_DIRECTORY`、`DIRECTORY_LISTED`、`DIRECTORY_LIST_FAILED` 使用 payload `requestId` 关联且不携带 `invocationId`。消息类型全集见 [`DaemonMessageType.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonMessageType.java)。

[`DaemonCapabilities`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonCapabilities.java) 的 READY capabilities version 为 `4`，payload 包含：

```text
version: 4
environment: operatingSystem / timeZone / note / rootPath
skills: name / description
mcpServers: name / status / error / tools(name / description)
```

READY 只上报短字段、OS/time zone、可信 note、canonical rootPath、Skill 摘要和 MCP server 摘要；完整 `SKILL.md` 由 `LOAD_SKILL` 按需返回，MCP 完整 schema 由固定 `mcp_list_tools` 返回。headers、environment values、command、URL、本地路径和完整 MCP schema 不进入 capabilities。[`DaemonCapabilitiesCodec`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonCapabilitiesCodec.java) 拒绝未知字段、duplicate/trailing、缺失字段、重复 Skill/server 名和非法 server 状态。

`DaemonToolResultCodec` 的 result wire 只允许 text/json/resource：

- `PARTIAL` 只能包含 text/json，任何 resource/binary 在 store 操作前拒绝；
- `COMPLETED` 在读写 Resource 或分配 Base64 前完成内容数、单条资源和聚合资源预算预检；
- 单次 resource 聚合默认最多 8 MiB，最终 payload UTF-8 最多 16 MiB；
- resource 必填 `uri`、`mediaType`、`size`、`sha256`、`contentBase64`，先校验声明和预算，再分配 Base64，解码长度与摘要必须相等；
- `contents` 最多 64 项，Jackson string、nesting、number 和 document 长度均有界。

实现见 [`DaemonToolResultCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonToolResultCodec.java) 和 [`DaemonEnvelopeCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonEnvelopeCodec.java)。

## 执行 / 状态 trace

```text
Model / Platform
  -> ToolExecutionRequest（参数 normalize + schema validate）
  -> local Tool.execute 或 RemoteToolTransport.invoke
  -> PARTIAL* -> COMPLETED / FAILED / CANCELLED
  -> Runtime ToolProcessor 做 permission、durable terminal、Entry apply
```

远程路径为：

```text
RemoteTool
  -> RemoteToolTransport(INVOKE, frozen EnvironmentBinding)
  -> DaemonEnvelope(v3)
  -> Environment Daemon
  -> Tool SPI
  -> PARTIAL / COMPLETED / FAILED / CANCELLED
```

该模块只定义值对象与回调语义；Work lease、retry、approval、resource durable materialization 和 Thread wake 不属于此 trace。

## 不变量、failure / recovery

- 所有 JSON codec 都在边界拒绝未知字段、duplicate field 和 trailing token；schema 参数必须是 object。
- `ToolExecutionRequest` 不能持有非 absolute `workdir`，且执行路径必须使用归一化后的 ToolCall。
- `ResourceRef` 构造失败不会访问网络或存储；data URI 的 `size`/`sha256` 不匹配直接失败。
- `BinaryToolContent` 不进入 PARTIAL；Daemon COMPLETED 编码的所有 store read/write 必须位于全量资源预检之后。
- Daemon envelope 的 route scope 只能是 canonical Environment name；能力版本不匹配、payload 形状错误或序列非法均拒绝 wire 消息。
- Resource 引用只是可验证的值；真正的 root、symlink、进程和网络安全边界由执行侧实现，不能由 descriptor 推导。

## 配置 / 扩展

- 新的 Platform Tool 通过 `ToolDescriptor` + `Tool` 实现加入 Platform catalog；内部工具由 `ToolCatalog` 的 `internalToolNames` 标记。
- Environment Tool contract 要求 descriptor、schema、prompt 资源、
  `EnvironmentToolCatalog` 固定目录和 Daemon 注册保持同一 version。
- RemoteTool 只需提供 `RemoteToolTransport`；WebSocket 或其它连接实现留在边界模块。
- Daemon capabilities 只允许增加当前协议版本内明确定义的安全摘要字段；wire version 与 capabilities version 是独立版本。

## 测试与源码入口

### 源码入口

- [`ToolDescriptor.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolDescriptor.java)、[`ToolCall.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCall.java)、[`ToolResult.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolResult.java)
- [`Tool.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/execution/Tool.java)、[`ToolExecutionRequest.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/execution/ToolExecutionRequest.java)、[`RemoteTool.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/remote/RemoteTool.java)
- [`EnvironmentToolCatalog.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/EnvironmentToolCatalog.java)、[`ToolCatalog.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ToolCatalog.java)
- [`ResourceRef.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceRef.java)、[`ResourceUriValidator.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/ResourceUriValidator.java)
- [`DaemonEnvelopeCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonEnvelopeCodec.java)、[`DaemonCapabilitiesCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonCapabilitiesCodec.java)、[`DaemonToolResultCodec.java`](../../harness/tool/src/main/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonToolResultCodec.java)

### 关键测试守卫

- [`ToolModuleArchitectureTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolModuleArchitectureTest.java)：依赖方向和禁用 Runtime/Daemon/Platform/Provider SDK。
- [`ToolContractTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolContractTest.java)、[`ToolExecutionNormalizationTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ToolExecutionNormalizationTest.java)：descriptor、调用参数归一化和 execution contract。
- [`EnvironmentToolCatalogSchemaTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/EnvironmentToolCatalogSchemaTest.java)：固定 11 项 Environment catalog 与 schema。
- [`ResourceRefTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/ResourceRefTest.java)：scheme、canonical URI、size/sha 和边界。
- [`DaemonEnvelopeCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonEnvelopeCodecTest.java)、[`DaemonCapabilitiesCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonCapabilitiesCodecTest.java)、[`DaemonToolResultCodecTest.java`](../../harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/daemon/DaemonToolResultCodecTest.java)：wire 版本、能力摘要、资源大小和严格 JSON。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、
[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Web](web.md)。
