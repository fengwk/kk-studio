# Harness Common

## 定位

`harness-common` 是 Harness 各子域及上层模块共享的基础契约与工具库，专注于无状态值对象、数据校验与通用编解码工具。模块提供：

- classpath prompt 模板解析、缓存与精确变量渲染（`common.prompt`）；
- 严格 JSON 树解析、顶层 object 校验与有界 UTF-8 流式编码（`common.json`）；
- 不可变规范 Resource URI 引用模型与格式校验（`common.resource`）；
- sealed 结果内容模型 `ResultContent`（Text、Json、Binary、Resource）（`common.result`）；
- 输入参数模式 `InputSchema` / `SchemaElement` 体系、参数校验器、别名与类型归一化器及确定性 JSON 编解码器（`common.schema`）。

工具与环境的具体身份模型由 [`harness-tool`](harness-tool.md) 与 [`harness-environment`](harness-environment.md) 定义，执行调度与外部存储由运行时和基础设施模块承接；`harness-common` 保持为纯 Java 底层值契约。

## 职责

### 核心职责

- 提供无状态、不可变且语义确定的基础值对象与严格校验工具。
- 为 `harness-tool` 与 `harness-environment` 等上层模块提供共享的 `ResultContent`、`InputSchema`、`ResourceRef` 与 JSON 边界校验能力。
- 生产依赖限定在 JDK 与 Jackson。
- 在 Unicode 编码、UTF-8 字节上限、JSON 语法、Schema 结构与 URI 格式上执行严格的 fail-closed 校验，遇非法输入立即失败。

### 协作边界

- `AgentToolId`、`AgentToolDefinition`、`ToolCall` 与 `ToolResult` 等工具契约归入 [`harness-tool`](harness-tool.md)。
- `EnvironmentId`、`EnvironmentBinding`、Capability catalog 与 Daemon 通信协议归入 [`harness-environment`](harness-environment.md)。
- 执行 SPI（`Tool` / `EnvironmentCapability`）与网关路由由执行与编排模块提供。
- Spring 容器装配、JDBC 持久化与模型 Provider SDK 均由外部容器与上层应用承接。

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

依赖约束：仅允许依赖 JDK 与 Jackson；框架装配、持久化与 Provider SDK 均置于外层模块。
```

生产依赖见 [`pom.xml`](../../harness/common/pom.xml)。[`CommonModuleArchitectureTest.java`](../../harness/common/src/test/java/fun/fengwk/kkstudio/harness/common/CommonModuleArchitectureTest.java) 守卫主源码 import 与 POM 依赖。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.common.prompt` | 提供严格 classpath prompt 模板解析、缓存与精确变量渲染原语 | 仅支持 `${name}` 占位符且要求变量全集精确匹配；表达式求值与业务编排由调用方承接 |
| `fun.fengwk.kkstudio.harness.common.json` | 提供严格重复键与尾随拦截的 JSON 校验器，以及流式有界 UTF-8 编码器 | 聚焦底层语法与体积边界校验，业务数据建模由各领域模块自行组织；超限时立即中止流式写入 |
| `fun.fengwk.kkstudio.harness.common.resource` | 定义不可变规范 Resource URI 引用 `ResourceRef` 与严格 URI / 载荷校验器 | 限定五类 scheme 与严格字节上限；网络下载、传输协议与持久化存储由外部能力承接 |
| `fun.fengwk.kkstudio.harness.common.result` | 定义 sealed `ResultContent` 内容单元层次（Text、Json、Binary、Resource） | 纯不可变值模型；执行生命周期、权限控制与持久化调度由运行时承接 |
| `fun.fengwk.kkstudio.harness.common.schema` | 定义输入参数 Schema 结构、参数校验器、静默归一化器与确定性 JSON 编解码器 | 采用属性字典序的确定性编解码；执行路由适配与 Provider 转换由上层模块处理 |

## 核心模型 / API

### Prompt 模板与 Loader

[`PromptTemplate`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplate.java) 仅支持 `${name}` 形式的变量占位符，变量名需匹配 `[A-Za-z_][A-Za-z0-9_]*`：

- 构造时扫描变量并校验占位符闭合与变量名合法性；
- `render(Map<String, String> values)` 要求传入的变量集合与模板声明的变量集合精确一致，存在未提供或多余变量时抛出异常；
- 变量值按字面量写入，其中出现的 `${...}` 保持原文。

[`PromptTemplateLoader`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt/PromptTemplateLoader.java) 从 classpath 读取 UTF-8 文本资源并缓存解析后的 `PromptTemplate` 实例，资源缺失或读取失败时抛出确定性异常。

### JSON 边界工具

[`JsonValues`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/JsonValues.java) 开启 `STRICT_DUPLICATE_DETECTION` 与 `FAIL_ON_TRAILING_TOKENS`：

- `requireValidJson(String json)`：校验非空白严格合法 JSON；
- `requireJsonObject(String json)`：校验顶层必须为 JSON object，null 或空白文本规范化为 `"{}"`；
- `readTree(String json)` / `write(JsonNode node)`：提供严格的树解析与紧凑序列化能力。

[`BoundedJsonWriter`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/json/BoundedJsonWriter.java) 将 `JsonNode` 序列化为 UTF-8 JSON 文本：

- 在流式写入过程中累计 UTF-8 字节数，一旦超过 `maxBytes` 立即中止并返回 `null`，避免在内存中物化超限内容；
- 未超限时返回完整 JSON 文本。

### Resource 引用与 URI 校验

[`ResourceRef`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceRef.java) 表示不可变的规范 Resource URI 引用：

```text
uri / mediaType / name? / size? / sha256?
```

- 允许的 scheme：`data`、`file`、`s3`、`https`、`http`；
- [`ResourceUriValidator`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/resource/ResourceUriValidator.java) 在构造时校验 URI 规范性、media type、Unicode 编码、UTF-8 长度、size 与 sha256 校验和；
- 核心字节上限：
  - `MAX_URI_UTF8_BYTES = 131072`（128 KiB）
  - `MAX_DATA_DECODED_BYTES = 65536`（64 KiB）
  - `MAX_MEDIA_TYPE_ASCII_BYTES = 255`
  - `MAX_NAME_UTF8_BYTES = 512`
  - `MAX_PREVIEW_UTF8_BYTES = 16384`（16 KiB）
- 提供 `utf8Length(String, String)` 与非分配式有界扫描 `utf8LengthUpTo(String, String, int)` 工具方法。

### ResultContent 体系

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
- [`InputNormalizer`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/InputNormalizer.java)：在 schema 校验前执行模型输入容错的静默归一化（数字字符串转为 JSON 整数/数字；schema 已声明但未列入 `required` 的属性若显式为 `null`，则等价为缺省）；required null 与未知属性仍保留给严格校验器拒绝；
- [`SchemaJsonCodec`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/schema/SchemaJsonCodec.java)：严格且确定性的 `InputSchema` JSON 编解码器，编码时属性按字典序排序，解码时拒绝未知字段与语法错误。

## 不变量、failure / recovery

- 基础值对象均在构造时进行不变量断言，非法输入立即抛出 `IllegalArgumentException`。
- 所有 JSON 操作启用重复键检查与尾随 token 拦截，遇到语法瑕疵立即抛出解析异常。
- 文本长度使用 Unicode 编码校验与 UTF-8 字节计数，遇到未配对代理项时明确报错。
- `ResultContent` 与 `InputSchema` 保持为纯 Java 数据模型，生命周期与持久化交由运行时和存储层驱动。

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
