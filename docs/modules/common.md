# Common

`common` 是全仓最底层的值契约：严格 JSON、规范 Resource 引用、结果内容、输入 schema、SHA-256、带截止时间的响应体与有界调度骨架。Harness、Platform、应用与插件都依赖它，它不依赖任何内部模块，也不保存业务状态。

这些类型在调用边界完成校验，再交给执行模块使用。构造约束通常以 `IllegalArgumentException` 表达，编码阶段的不可恢复失败为 `IllegalStateException`；null 的拒绝或缺省处理以具体入口为准。生产依赖只有 JDK 与 Jackson 3（见 [`pom.xml`](../../common/pom.xml)），模块依赖方向由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 校验。资源打开与关闭由调用方负责，Common 只消费已经提供的值或字符流。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.common.concurrent` | 与业务无关的有界调度骨架：合并 wake 的单 drain、bounded worker handoff、周期 poll 与 stop 生命周期 | 只做同步调度与容量计数；claim / execute / 未接手归还三个回调的语义与 executor 生命周期由调用方注入并持有 |
| `fun.fengwk.kkstudio.common.json` | 严格 JSON 门禁、共享有界输出流与有界 UTF-8 JSON 编码器 | 校验语法与体积，超限中止编码；业务结构由调用方定义 |
| `fun.fengwk.kkstudio.common.hash` | SHA-256 摘要与 64 位小写十六进制编码的唯一入口 | 只依赖 JDK；被哈希的字节与领域分隔由调用方决定 |
| `fun.fengwk.kkstudio.common.io` | send 前冻结预算的响应体 deadline 包装：到期主动关闭底层流并把超时统一为超时失败 | 纯 JDK 流包装，不构造 HTTP 客户端、不持有凭据；协议与凭据分类由调用方适配 |
| `fun.fengwk.kkstudio.common.resource` | 不可变规范 Resource URI 引用与逐 scheme 校验 | 受支持的 scheme 与字节上限在此固定；下载、传输与存储由外部能力承接 |
| `fun.fengwk.kkstudio.common.result` | sealed 结果内容单元 Text / Json / Binary / Resource 与文本工件元数据 | 纯不可变值模型；执行生命周期、权限与持久化调度由运行时承接 |
| `fun.fengwk.kkstudio.common.schema` | 输入参数 schema 结构、严格校验器、容错归一化器与确定性 JSON 编解码器 | 属性字典序的确定性编解码；执行路由与 Provider 转换由上层处理 |

## JSON 边界

[`JsonValues`](../../common/src/main/java/fun/fengwk/kkstudio/common/json/JsonValues.java) 是共享的 JSON 门禁，以不可变 `JsonMapper` 显式开启 `STRICT_DUPLICATE_DETECTION`、`FAIL_ON_TRAILING_TOKENS` 与 `USE_BIG_DECIMAL_FOR_FLOATS`（JSON number 按 `BigDecimal` 读回，`1e-324`、`1e309` 不会在 double 中退化成 0 或 Infinity）：

- `requireValidJson(json)` 要求非空白且严格合法，接受任意类型的单一 JSON 值（`null`、标量、数组与对象都合法，不做 object 归一化），并把原文原样返回；
- `requireJsonObject(json)` / `requireJsonObject(json, name)` 额外要求顶层为 object，并把 `null` 或空白规范化为 `"{}"`，第二个参数用于定制异常里的字段名；
- `readTree` / `write` 提供同一套严格性的树读写；空白文本产生的 MissingNode 被拒绝，JSON 字面量 `null` 仍是合法 NullNode。

[`ToolArguments`](../../common/src/main/java/fun/fengwk/kkstudio/common/json/ToolArguments.java) 是 Tool 历史 action 渲染器的只读 arguments 读取器：`parse` 把 arguments 文本解析为 JSON object（畸形、非 object 或空白返回 `null`），`text` / `flag` 按语义字段名取用并把缺失、类型不符或空白视为「未提供」，`render` 把调用方给出的字段渲染函数应用到指定字段。它对所有畸形输入都返回中性结果而不是抛出，因为渲染器只描述历史、绝不改写 durable 事实，无法形成动作时由 Runtime 回退。

[`BoundedJsonWriter`](../../common/src/main/java/fun/fengwk/kkstudio/common/json/BoundedJsonWriter.java) 在流式写入时累计 UTF-8 字节数，一旦超过 `maxBytes` 立即中止并返回 `null`，不会先在内存里物化超限内容；`fits(node, maxBytes)` 用同一套边界只做判定、不保留字节。这两个方法服务于「先判断能否放下、再决定是否序列化」的场景，例如 Daemon 报文的 16 MiB 预算。上限判定与计数由共享的 `BoundedOutputStream` 承担：构造要求正数 `maxBytes`，默认只计数、不保留任何字节，`retainBytes=true` 时保留已写字节并可用 `toUtf8String()` 还原文本；累计超过上限即原子中止（本次写入不生效、不推进计数）并抛出专属的 `LimitExceededException`。Jackson 3 将输出流异常包装为 `JacksonException`，调用方只沿 cause 链识别专属超限异常；其它编码或 I/O 失败明确抛出，不能误报为超限。[Harness Runtime](harness-runtime.md#tool-契约) 里 Tool 结果的字节预算判定与它复用同一实现。

## Resource 引用与 URI 校验

[`ResourceRef`](../../common/src/main/java/fun/fengwk/kkstudio/common/resource/ResourceRef.java) 是 `uri / mediaType / name? / size? / sha256?` 的不可变记录，允许 `data`、`https`、`http`、`blob-upload`、`kkstudio` 五类 scheme。构造时先做全局约束，再按 scheme 校验；直接构造与 JSON 反序列化走同一入口，因此不会出现两种宽松度：

- URI 必须是不超过 128 KiB 的 canonical ASCII：非 ASCII、控制字符、反斜杠、相对 URI、大写 scheme 一律拒绝，且 `URI.create(uri).toASCIIString()` 必须与原串相等；百分号转义必须是大写 hex，且不得编码 NUL、`/`、`\` 与 unreserved 字符。
- `mediaType` 必须是小写 `type/subtype`、不带参数、不超过 255 ASCII 字节；`name` 不得为空白、不得含控制字符、不超过 512 UTF-8 字节；`sha256` 必须是 64 位小写 hex；`size` 不得为负。
- `data` / `blob-upload` / `kkstudio` 必须携带非空 `size` 与 `sha256`，`http` / `https` 不要求。`data` 的 header 必须精确等于 `<mediaType>,` 或 `<mediaType>;base64,`，解码载荷不超过 64 KiB，且 `size` 必须等于解码长度、`sha256` 必须等于解码摘要；base64 载荷拒绝非规范 pad bits，非 base64 载荷必须使用 frozen 表示（原始 ASCII 仅限 unreserved 与 `/`，其余字节用大写 `%XX`）。
- `http` / `https` 要求小写 host、不携带 userinfo/query/fragment、不显式声明默认端口、不含 dot segment。
- `blob-upload:<uploadId>` 是 Environment Daemon 已把字节直传对象存储后的瞬时引用，不是可解引用地址：`blobUploadUri(UUID)` 构造、`blobUploadId()` 解析，消费方只能通过全局 Blob 上传契约把它转移为会话引用。
- `kkstudio:/resources/<blobId>` 是 Session 已持有的全局 Blob 的 durable 引用：`sessionResourceUri(UUID)` 构造、`sessionBlobId()` 解析，消费方只能按 Session 引用权限复用同一 blob。

[`ResourceUriValidator`](../../common/src/main/java/fun/fengwk/kkstudio/common/resource/ResourceUriValidator.java) 是承载逐 scheme 规则的包私有实现，也是新增 scheme 时唯一需要改动的地方。

## 结果内容模型

[`ResultContent`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/ResultContent.java) 是 sealed 接口，四类内容单元覆盖「可保存或流式传输」的全部形态：

- [`TextResultContent`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/TextResultContent.java)：纯文本，`text` 不得为 `null`；
- [`JsonResultContent`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/JsonResultContent.java)：严格 JSON，且在解析前先按 UTF-8 字节数限制在 1 MiB 内，保证任何树优先解析只面对有界输入；
- [`BinaryResultContent`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/BinaryResultContent.java)：内存中的瞬时字节载荷，构造与读取都做防御性复制，可选 [`TextArtifactMetadata`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/TextArtifactMetadata.java)，其 `totalBytes` 必须与字节长度一致；
- [`ResourceResultContent`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/ResourceResultContent.java)：指向 `ResourceRef` 的引用，可选不超过 16 KiB 的 `preview` 与可选 `TextArtifactMetadata`。

`TextArtifactMetadata(totalBytes, totalLines)` 让大文本工件在不读取正文的前提下就能被展示与分页，两个字段都不得为负。本模块只定义「内容长什么样」，内容何时外部化为 Resource、何时允许出现在 partial 阶段，由执行边界决定。

## 输入 Schema、校验与归一化

[`InputSchema`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/InputSchema.java) 描述顶层参数对象：`description`、`properties`、`required` 与 `additionalProperties`；构造期要求属性名非空白且 `required ⊆ properties`。[`SchemaElement`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/SchemaElement.java) 是 sealed 层次，只允许 String、Integer、Number、Boolean、Enum、Array、Object 七类节点，Enum 取值不得为空或空白。

- [`InputValidator`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/InputValidator.java) 按 schema 递归严格校验类型、必填项与未声明属性（`additionalProperties=false` 时拒绝未知键）。失败时抛出的消息格式固定为 `argumentsJson does not match inputSchema: $.path <reason>`，调用方可以据此把错误定位到具体字段。
- [`InputNormalizer`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/InputNormalizer.java) 在严格校验前做静默容错：`IntegerSchema` 字段接受 `-?\d+` 形式的数字字符串并改写为 JSON 整数，`NumberSchema` 字段接受必须有整数部分、可带小数或指数的十进制文本并按 `BigDecimal` 改写（不受 double 范围限制）；两种改写都在文本超出 long / `BigDecimal` 表示能力时保持原样，其它类型不做转换。归一化递归进入数组与嵌套对象，输出是重新序列化的紧凑 JSON，因此空白与数字字面量可能与原文本不同。显式 `null` 只对 schema 已声明且未列入 `required` 的属性删除，语义等同于缺省；required 属性与未声明属性上的 `null` 原样留给校验器（`additionalProperties=false` 时未声明属性会被拒绝）。
- [`SchemaJsonCodec`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/SchemaJsonCodec.java) 是 schema 的确定性编解码器：编码时 `properties` 与 `required` 按字典序输出，解码时拒绝未知字段、缺失 `type` / `properties` / `required` / `additionalProperties`、`required` 中出现未声明属性；文本解码入口另外拒绝重复键与尾随 token。Provider tool schema 与 harness 内部 schema 都以它为唯一序列化实现。

## 源码与测试

- JSON：[`JsonValues.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/json/JsonValues.java)、[`BoundedJsonWriter.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/json/BoundedJsonWriter.java)、[`ToolArguments.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/json/ToolArguments.java)，字节上限判定复用同包 `BoundedOutputStream`
- Resource：[`ResourceRef.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/resource/ResourceRef.java)、[`ResourceUriValidator.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/resource/ResourceUriValidator.java)
- Result：[`ResultContent.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/ResultContent.java)、[`TextArtifactMetadata.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/result/TextArtifactMetadata.java)
- Schema：[`InputSchema.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/InputSchema.java)、[`InputValidator.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/InputValidator.java)、[`InputNormalizer.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/InputNormalizer.java)、[`SchemaJsonCodec.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/schema/SchemaJsonCodec.java)
- 调度骨架：[`BoundedWorkDispatcher.java`](../../common/src/main/java/fun/fengwk/kkstudio/common/concurrent/BoundedWorkDispatcher.java)；插件响应体截止时间见 [Platform Plugin API](platform-plugin-api.md#公共辅助)。
- [`ResourceRefTest.java`](../../common/src/test/java/fun/fengwk/kkstudio/common/resource/ResourceRefTest.java) 覆盖各类 scheme 与 data URI 解码，[`InputNormalizerTest.java`](../../common/src/test/java/fun/fengwk/kkstudio/common/schema/InputNormalizerTest.java) 锁定容错与拒绝的边界，[`SchemaJsonCodecTest.java`](../../common/src/test/java/fun/fengwk/kkstudio/common/schema/SchemaJsonCodecTest.java) 锁定确定性编码；同包 `BoundedOutputStreamTest` 与 `BoundedJsonWriterTest` 覆盖中文与 emoji 的 UTF-8 字节边界、只计数不保留字节与超限早停。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Environment](harness-environment.md)、[Platform](platform.md)。
