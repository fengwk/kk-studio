# Harness Common

## 定位

`harness-common` 是 Harness 各子域及上层消费方共享的通用基础契约与工具模块。它集中提供：

- 纯粹、严格的 classpath prompt 模板解析与加载（`common.prompt`）；
- 严格 JSON 树解析、顶层 object 校验与有界 UTF-8 编码器（`common.json`）；
- 不可变、严格校验的规范 Resource URI 引用与 URI 校验器（`common.resource`）；
- sealed 结果内容层次 `ResultContent`（Text、Json、Binary、Resource）（`common.result`）；
- 参数模式 `InputSchema` / `SchemaElement` 体系、参数校验器、静默归一化器与严格 JSON codec（`common.schema`）。

该模块是底层纯值契约，不包含 Tool/Environment 身份或 envelope、不包含执行 SPI、路由、状态机、Spring/JDBC 或 Provider SDK。

## Goals / Non-goals

### Goals

- 提供无状态、不可变、零歧义的基础值对象与严格校验工具。
- 为 `harness-tool` 与 `harness-environment` 提供统一的 `ResultContent`、`InputSchema`、`ResourceRef` 与 JSON 边界校验，消除重复类型。
- 保持生产依赖严格为 JDK 与 Jackson，不依赖任何其它 Harness 模块。
- 在 Unicode、UTF-8 字节上限、JSON 语法、Schema 结构与 URI 格式上执行严格的 fail-closed 策略。

### Non-goals

- 不定义 `AgentToolId`、`AgentToolDefinition`、`ToolCall` 或 `ToolResult`（位于 [`harness-tool`](harness-tool.md)）。
- 不定义 `EnvironmentName`、`EnvironmentBinding`、Capability catalog、Daemon protocol（位于 [`harness-environment`](harness-environment.md)）。
- 不定义执行 SPI（`Tool` / `EnvironmentCapability`）或网关路由。
- 不引入 Spring、JDBC、数据库事务或模型 Provider SDK。

## 依赖边界

```text
JDK + Jackson
      │
      ▼
harness-common
  ├─ common.prompt    (PromptTemplate / PromptTemplateLoader)
  ├─ common.json      (JsonValues / BoundedJsonWriter)
  ├─ common.resource  (ResourceRef / ResourceUriValidator)
  ├─ common.result    (ResultContent / Text / Json / Binary / Resource)
  └─ common.schema    (InputSchema / SchemaElement / InputValidator / InputNormalizer / SchemaJsonCodec)

禁止：任何其它 Harness 模块 / platform / web / Spring / JDBC / Provider SDK
```

生产依赖见 [`pom.xml`](../../harness/common/pom.xml)。[`CommonModuleArchitectureTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/CommonModuleArchitectureTest.java) 守卫主源码 import 与 POM 依赖。

## 核心模型 / API

### Prompt 模板与 Loader

[`PromptTemplate`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplate.java) 仅支持 `${name}` 形式的变量占位符，变量名必须匹配 `[A-Za-z_][A-Za-z0-9_]*`：

- 构造时扫描变量并校验占位符闭合与变量名合法性；
- `render(Map<String, String> values)` 要求提供的变量集合与模板声明的变量集合精确一致，缺失（unresolved）或多余（unexpected）均抛出异常；
- 变量替换按原样填入，不做二次解析。

[`PromptTemplateLoader`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateLoader.java) 从 classpath 读取 UTF-8 文本资源并缓存解析后的 `PromptTemplate`，资源缺失或读取失败时抛出确定性异常。

### JSON 边界工具

[`JsonValues`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/JsonValues.java) 启用 `STRICT_DUPLICATE_DETECTION` 与 `FAIL_ON_TRAILING_TOKENS`：

- `requireValidJson(String json)`：校验非空白严格合法 JSON；
- `requireJsonObject(String json)`：校验顶层必须为 JSON object，null 或空白文本规范化为 `"{}"`；
- `readTree(String json)` / `write(JsonNode node)`：严格的树解析与紧凑序列化。

[`BoundedJsonWriter`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/BoundedJsonWriter.java) 将 `JsonNode` 序列化为 UTF-8 JSON 文本：

- 在流式写入时累计 UTF-8 字节数，一旦超过 `maxBytes` 立即中止并返回 `null`，不物化超限内容；
- 未超限时返回完整 JSON 文本。

### Resource 引用与 URI 校验

[`ResourceRef`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRef.java) 是不可变的规范 Resource URI 引用：

```text
uri / mediaType / name? / size? / sha256?
```

- 允许 scheme：`data`、`file`、`s3`、`https`、`http`；
- [`ResourceUriValidator`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceUriValidator.java) 在构造时统一校验 URI 规范性、media type、Unicode、UTF-8 长度、size 与 sha256；
- 核心字节上限：
  - `MAX_URI_UTF8_BYTES = 131072`（128 KiB）
  - `MAX_DATA_DECODED_BYTES = 65536`（64 KiB）
  - `MAX_MEDIA_TYPE_ASCII_BYTES = 255`
  - `MAX_NAME_UTF8_BYTES = 512`
  - `MAX_PREVIEW_UTF8_BYTES = 16384`（16 KiB）
- 提供 `utf8Length(String, String)` 与非分配式有界扫描 `utf8LengthUpTo(String, String, int)` 工具方法。

### 统一 ResultContent 体系

[`ResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResultContent.java) 是 sealed 接口，作为结果中可保存或流式传输的通用内容单元：

- [`TextResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/TextResultContent.java)：纯文本内容，要求 `text != null`；
- [`JsonResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/JsonResultContent.java)：严格 JSON 文本，原始 UTF-8 字节数不超过 `MAX_JSON_UTF8_BYTES = 1048576`（1 MiB）；
- [`BinaryResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/BinaryResultContent.java)：内存中的二进制内容单元，包含 `mediaType` 与防御性复制的 `byte[] content`；
- [`ResourceResultContent`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResourceResultContent.java)：指向规范 `ResourceRef` 的引用内容，支持可选的有界 `preview` 文本。

### InputSchema 与校验/归一化

[`InputSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputSchema.java) 描述顶层参数对象 schema，组合 `description`、`properties`、`required` 与 `additionalProperties` 策略。

[`SchemaElement`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaElement.java) 包含以下元素类型：

- [`ObjectSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/ObjectSchema.java)：嵌套对象 schema；
- [`ArraySchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/ArraySchema.java)：数组 schema 与元素 schema；
- [`StringSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/StringSchema.java)：字符串描述与类型；
- [`IntegerSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/IntegerSchema.java)：整数类型；
- [`NumberSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/NumberSchema.java)：浮点/数字类型；
- [`BooleanSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/BooleanSchema.java)：布尔类型；
- [`EnumSchema`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/EnumSchema.java)：字符串枚举取值列表。

配套工具：

- [`InputValidator`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputValidator.java)：严格校验顶层 JSON object 参数是否满足 `InputSchema`；
- [`InputNormalizer`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizer.java)：在 schema 校验前执行静默归一化（如 `filePath -> path` 别名改写、数字字符串转为 JSON 整数/数字）；
- [`SchemaJsonCodec`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodec.java)：严格且确定性的 `InputSchema` JSON 编解码器，编码时属性按字典序排序，解码时拒绝未知字段与语法错误。

## 不变量、failure / recovery

- 基础值对象均在构造时进行不变量断言，非法输入立即抛出 `IllegalArgumentException`。
- 所有 JSON 操作启用重复键检查与尾随 token 拦截，杜绝隐式解析宽容。
- 文本长度使用 Unicode 编码校验与 UTF-8 字节计数，拒绝未配对代理项。
- `ResultContent` 与 `InputSchema` 均为纯 Java 数据模型，不含任何执行态或持久化副作用。

## 测试与源码入口

### 源码入口

- [`PromptTemplate.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplate.java)、[`PromptTemplateLoader.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateLoader.java)
- [`JsonValues.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/JsonValues.java)、[`BoundedJsonWriter.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/BoundedJsonWriter.java)
- [`ResourceRef.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRef.java)、[`ResourceUriValidator.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceUriValidator.java)
- [`ResultContent.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResultContent.java)、[`TextResultContent.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/TextResultContent.java)、[`JsonResultContent.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/JsonResultContent.java)、[`BinaryResultContent.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/BinaryResultContent.java)、[`ResourceResultContent.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/result/ResourceResultContent.java)
- [`InputSchema.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputSchema.java)、[`InputValidator.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputValidator.java)、[`InputNormalizer.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizer.java)、[`SchemaJsonCodec.java`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodec.java)

### 关键测试守卫

- [`CommonModuleArchitectureTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/CommonModuleArchitectureTest.java)：模块仅依赖 JDK 与 Jackson 生产依赖。
- [`PromptTemplateTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateTest.java)：模板解析、占位符校验与严格渲染。
- [`BoundedJsonWriterTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/json/BoundedJsonWriterTest.java)、[`JsonValuesTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/json/JsonValuesTest.java)：JSON 边界与截断校验。
- [`ResourceRefTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRefTest.java)：URI scheme、data URI 解码与 size/sha 校验。
- [`ResultContentTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/result/ResultContentTest.java)：各 ResultContent 变体的不可变性与约束。
- [`InputValidatorTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/schema/InputValidatorTest.java)、[`InputNormalizerTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizerTest.java)、[`SchemaJsonCodecTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodecTest.java)：模式编解码、归一化与校验。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Harness Runtime](harness-runtime.md)、[Harness Builtin](harness-builtin.md)。
