# Harness Common

`harness-common` 为 Runtime、Tool、MCP、Daemon 与 Platform 提供共享的基础契约：prompt 模板、严格 JSON、规范 Resource 引用、结果内容、输入 schema、文本窗口与 HTTP 代理选择。值对象与校验工具不保存业务状态；[`PromptTemplateLoader`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateLoader.java) 只缓存 classpath 模板。

这些类型在调用边界完成校验，再交给执行模块使用。构造约束通常以 `IllegalArgumentException` 表达，编码阶段的不可恢复失败为 `IllegalStateException`；null 的拒绝或缺省处理以具体入口为准。生产依赖为 JDK 与 Jackson（见 [`pom.xml`](../../harness/common/pom.xml)），[`CommonModuleArchitectureTest`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/CommonModuleArchitectureTest.java) 校验依赖方向。资源打开与关闭由调用方负责，Common 消费已经提供的值或字符流。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.common.prompt` | classpath prompt 模板的严格解析、缓存与精确变量渲染 | 只支持 `${name}`，要求变量集合精确相等；表达式求值与业务编排由调用方承接 |
| `fun.fengwk.kkstudio.harness.common.json` | 严格 JSON 门禁、共享有界输出流与有界 UTF-8 JSON 编码器 | 校验语法与体积，超限中止编码；业务结构由调用方定义 |
| `fun.fengwk.kkstudio.harness.common.network` | 固定与环境变量 HTTP 代理选择、主机绕过规则 | 只构造不可变选择器；配置来源、JVM 全局安装和客户端生命周期由调用方负责 |
| `fun.fengwk.kkstudio.harness.common.resource` | 不可变规范 Resource URI 引用与逐 scheme 校验 | 六类 scheme 与字节上限在此固定；下载、传输与存储由外部能力承接 |
| `fun.fengwk.kkstudio.harness.common.result` | sealed 结果内容单元 Text / Json / Binary / Resource 与文本工件元数据 | 纯不可变值模型；执行生命周期、权限与持久化调度由运行时承接 |
| `fun.fengwk.kkstudio.harness.common.schema` | 输入参数 schema 结构、严格校验器、容错归一化器与确定性 JSON 编解码器 | 属性字典序的确定性编解码；执行路由与 Provider 转换由上层处理 |
| `fun.fengwk.kkstudio.harness.common.skill` | Skill 名称、package name/version、描述与正文的 canonical 文本规则 | 只定义跨层共享的纯文本约束；Catalog、Git I/O 与 Daemon 安装由外层承接 |
| `fun.fengwk.kkstudio.harness.common.text` | 字符流文本窗口核心：分页投影、正文码点预算、行/列 `long` 计数与截断元数据 | 只做 JDK 字符流的窗口投影与 BOM 邻接策略；编码嗅探、输入流打开、超时与异常映射由调用方适配 |
| `fun.fengwk.kkstudio.harness.common.tool` | 模型可见工具错误的三段式英文文案与执行事实枚举 | 只提供固定措辞与拼接；具体错误的语义、校验与执行状态由各执行层判定 |

## Prompt 模板

[`PromptTemplate`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplate.java) 只识别 `${name}`，变量名必须匹配 `[A-Za-z_][A-Za-z0-9_]*`；构造时拒绝未闭合的 `${` 与非法变量名。`render(Map)` 要求传入集合与模板声明的变量集合精确一致：缺一个报 unresolved，多一个报 unexpected，值为 `null` 等同于未提供；变量值按字面量写入，值里出现的 `${...}` 不会被二次解析。

[`PromptTemplateLoader`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateLoader.java) 按 classpath 资源路径读取 UTF-8 文本，并在 `ConcurrentHashMap` 中缓存解析结果；资源缺失或读取失败抛确定性的 `IllegalArgumentException`（空白路径同样拒绝，`null` 入参是 NPE）。因此 prompt 装配错误在启动或首次使用时立刻暴露，而不是退化成空模板。

## JSON 边界

[`JsonValues`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/JsonValues.java) 是全仓共享的 JSON 门禁，使用的 `ObjectMapper` 开启 `STRICT_DUPLICATE_DETECTION`、`FAIL_ON_TRAILING_TOKENS` 与 `USE_BIG_DECIMAL_FOR_FLOATS`（JSON number 按 `BigDecimal` 读回，`1e-324`、`1e309` 不会在 double 中退化成 0 或 Infinity）：

- `requireValidJson(json)` 要求非空白且严格合法，接受任意类型的单一 JSON 值（`null`、标量、数组与对象都合法，不做 object 归一化），并把原文原样返回；
- `requireJsonObject(json)` / `requireJsonObject(json, name)` 额外要求顶层为 object，并把 `null` 或空白规范化为 `"{}"`，第二个参数用于定制异常里的字段名；
- `readTree` / `write` 提供同一套严格性的树读写。

[`ToolArguments`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/ToolArguments.java) 是 Tool 历史 action 渲染器的只读 arguments 读取器：`parse` 把 arguments 文本解析为 JSON object（畸形、非 object 或空白返回 `null`），`text` / `flag` 按语义字段名取用并把缺失、类型不符或空白视为「未提供」，`render` 把调用方给出的字段渲染函数应用到指定字段。它对所有畸形输入都返回中性结果而不是抛出，因为渲染器只描述历史、绝不改写 durable 事实，无法形成动作时由 Runtime 回退。

[`BoundedJsonWriter`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/BoundedJsonWriter.java) 在流式写入时累计 UTF-8 字节数，一旦超过 `maxBytes` 立即中止并返回 `null`，不会先在内存里物化超限内容；`fits(node, maxBytes)` 用同一套边界只做判定、不保留字节。这两个方法服务于「先判断能否放下、再决定是否序列化」的场景，例如 Daemon 报文 8 MiB 的整包预算。上限判定与计数由共享的 `BoundedOutputStream` 承担：构造要求正数 `maxBytes`，默认只计数、不保留任何字节，`retainBytes=true` 时保留已写字节并可用 `toUtf8String()` 还原文本；累计超过上限即原子中止（本次写入不生效、不推进计数）并抛出专属的 `LimitExceededException`。它刻意继承 `IOException`：Jackson 生成器会把写入期异常原样上抛而不做二次包装，调用方据此区分「超限中止」与「编码失败」。[`harness-tool`](harness-tool.md) 里 Tool 结果的字节预算判定与它复用同一实现。

## Resource 引用与 URI 校验

[`ResourceRef`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRef.java) 是 `uri / mediaType / name? / size? / sha256?` 的不可变记录，允许 `data`、`file`、`s3`、`https`、`http`、`blob-upload` 六类 scheme。构造时先做全局约束，再按 scheme 校验；直接构造与 JSON 反序列化走同一入口，因此不会出现两种宽松度：

- URI 必须是不超过 128 KiB 的 canonical ASCII：非 ASCII、控制字符、反斜杠、相对 URI、大写 scheme 一律拒绝，且 `URI.create(uri).toASCIIString()` 必须与原串相等；百分号转义必须是大写 hex，且不得编码 NUL、`/`、`\` 与 unreserved 字符。
- `mediaType` 必须是小写 `type/subtype`、不带参数、不超过 255 ASCII 字节；`name` 不得为空白、不得含控制字符、不超过 512 UTF-8 字节；`sha256` 必须是 64 位小写 hex；`size` 不得为负。
- `data` / `file` / `s3` / `blob-upload` 必须携带非空 `size` 与 `sha256`，`http` / `https` 不要求。`data` 的 header 必须精确等于 `<mediaType>,` 或 `<mediaType>;base64,`，解码载荷不超过 64 KiB，且 `size` 必须等于解码长度、`sha256` 必须等于解码摘要；base64 载荷拒绝非规范 pad bits，非 base64 载荷必须使用 frozen 表示（原始 ASCII 仅限 unreserved 与 `/`，其余字节用大写 `%XX`）。
- `file` 必须精确为 `file:///…` 空 authority、单前导斜杠、无 dot 或空 segment，且路径不得使用百分号编码；`s3` bucket 为 3–63 字符小写字母、数字、`-` 与 `.`，首尾必须是字母或数字、无连续点、不得形如 IP，key 不得为空、不得使用百分号编码、不得含 `.` 或 `..` segment；`http` / `https` 要求小写 host、不携带 userinfo/query/fragment、不显式声明默认端口、不含 dot segment。
- `blob-upload:<uploadId>` 是 Environment Daemon 已把字节直传对象存储后的瞬时引用，不是可解引用地址：`blobUploadUri(UUID)` 构造、`blobUploadId()` 解析，消费方只能通过全局 Blob 上传契约把它转移为会话引用。

[`ResourceUriValidator`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceUriValidator.java) 是承载逐 scheme 规则的包私有实现，也是新增 scheme 时唯一需要改动的地方。

## 结果内容模型

[`ResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResultContent.java) 是 sealed 接口，四类内容单元覆盖「可保存或流式传输」的全部形态：

- [`TextResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/TextResultContent.java)：纯文本，`text` 不得为 `null`；
- [`JsonResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/JsonResultContent.java)：严格 JSON，且在解析前先按 UTF-8 字节数限制在 1 MiB 内，保证任何树优先解析只面对有界输入；
- [`BinaryResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/BinaryResultContent.java)：内存中的瞬时字节载荷，构造与读取都做防御性复制，可选 [`TextArtifactMetadata`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/TextArtifactMetadata.java)，其 `totalBytes` 必须与字节长度一致；
- [`ResourceResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResourceResultContent.java)：指向 `ResourceRef` 的引用，可选不超过 16 KiB 的 `preview` 与可选 `TextArtifactMetadata`。

`TextArtifactMetadata(totalBytes, totalLines)` 让大文本工件在不读取正文的前提下就能被展示与分页，两个字段都不得为负。本模块只定义「内容长什么样」，内容何时外部化为 Resource、何时允许出现在 partial 阶段，由执行边界决定。

## 输入 Schema、校验与归一化

[`InputSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputSchema.java) 描述顶层参数对象：`description`、`properties`、`required` 与 `additionalProperties`；构造期要求属性名非空白且 `required ⊆ properties`。[`SchemaElement`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaElement.java) 是 sealed 层次，只允许 String、Integer、Number、Boolean、Enum、Array、Object 七类节点，Enum 取值不得为空或空白。

- [`InputValidator`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputValidator.java) 按 schema 递归严格校验类型、必填项与未声明属性（`additionalProperties=false` 时拒绝未知键）。失败时抛出的消息格式固定为 `argumentsJson does not match inputSchema: $.path <reason>`，调用方可以据此把错误定位到具体字段。
- [`InputNormalizer`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizer.java) 在严格校验前做静默容错：`IntegerSchema` 字段接受 `-?\d+` 形式的数字字符串并改写为 JSON 整数，`NumberSchema` 字段接受必须有整数部分、可带小数或指数的十进制文本并按 `BigDecimal` 改写（不受 double 范围限制）；两种改写都在文本超出 long / `BigDecimal` 表示能力时保持原样，其它类型不做转换。归一化递归进入数组与嵌套对象，输出是重新序列化的紧凑 JSON，因此空白与数字字面量可能与原文本不同。显式 `null` 只对 schema 已声明且未列入 `required` 的属性删除，语义等同于缺省；required 属性与未声明属性上的 `null` 原样留给校验器（`additionalProperties=false` 时未声明属性会被拒绝）。
- [`SchemaJsonCodec`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodec.java) 是 schema 的确定性编解码器：编码时 `properties` 与 `required` 按字典序输出，解码时拒绝未知字段、缺失 `type` / `properties` / `required` / `additionalProperties`、`required` 中出现未声明属性；文本解码入口另外拒绝重复键与尾随 token。Provider tool schema 与 harness 内部 schema 都以它为唯一序列化实现。

## 字符流窗口

[`TextReadWindow`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindow.java) 接收已经严格解码的 `Reader`，本地文件与受管 Blob 共用这一份窗口状态机。`offset` 与 `column_offset` 从 1 起，`limit` 默认且最大 2000 行，正文最多 60000 个 Unicode 码点，不计行号、元数据和行分隔符；列偏移只作用于起始行。窗口预算不拆代理对，CRLF、CR、LF 都是行边界。截断输出 `next` 坐标，恰好到 EOF 不算截断；空文件或行起点越过 EOF 返回 `range: empty`，有效行上的越界列则拒绝。

它扫描到 EOF 才能给出文件级 `ends_with_newline` 与准确截断结论，但只保留窗口正文，内存不随全文长度增长。调用方负责参数校验、编码嗅探、开关流、BOM 与检查点异常映射；核心在每块读取前后执行 checkpoint，使适配器能中止超时或取消的扫描。NUL、畸形代理对和解码错误按“看似二进制”拒绝，其他读取故障保留 `IOException`。`withoutLeadingBom` 只剥首字符；已在字节层剥离 BOM 的适配器不能再包装一次，以免吞掉正文紧邻的 `U+FEFF`。行列内部按 `long` 计数，输出位置超出协议 `int` 范围时明确失败。

## 源码与测试

- Prompt：[`PromptTemplate.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplate.java)、[`PromptTemplateLoader.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateLoader.java)
- JSON：[`JsonValues.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/JsonValues.java)、[`BoundedJsonWriter.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/BoundedJsonWriter.java)、[`ToolArguments.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/ToolArguments.java)，字节上限判定复用同包 `BoundedOutputStream`
- Resource：[`ResourceRef.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRef.java)、[`ResourceUriValidator.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceUriValidator.java)
- Result：[`ResultContent.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResultContent.java)、[`TextArtifactMetadata.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/TextArtifactMetadata.java)
- Schema：[`InputSchema.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputSchema.java)、[`InputValidator.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputValidator.java)、[`InputNormalizer.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizer.java)、[`SchemaJsonCodec.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodec.java)
- Skill：[`SkillNames.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/skill/SkillNames.java)
- 文本窗口：[`TextReadWindowTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindowTest.java) 覆盖分页、码点边界、续读坐标、BOM 与读取检查点。
- 工具错误文案：[`ToolErrorGuidance.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/tool/ToolErrorGuidance.java)，[`ToolErrorGuidanceTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/tool/ToolErrorGuidanceTest.java) 锁定执行事实句子与重复包装判定。
- 改动前先跑 [`CommonModuleArchitectureTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/CommonModuleArchitectureTest.java) 确认依赖方向未破；[`ResourceRefTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRefTest.java) 覆盖六类 scheme 与 data URI 解码，[`InputNormalizerTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizerTest.java) 锁定容错与拒绝的边界，[`SchemaJsonCodecTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodecTest.java) 锁定确定性编码，[`PromptTemplateTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateTest.java) 锁定精确变量匹配；同包 `BoundedOutputStreamTest` 与 `BoundedJsonWriterTest` 覆盖中文与 emoji 的 UTF-8 字节边界、只计数不保留字节与超限早停。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Harness Builtin](harness-builtin.md)。
