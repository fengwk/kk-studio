package fun.fengwk.kkstudio.harness.tool.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedOutputStream;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 最终与部分 ToolResult 的严格持久化 codec。 */
public final class ToolResultJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          // JSON number 按 BigDecimal 读回，避免 1e309 变成 Infinity、1e-324 下溢成 0 或高精度 decimal 丢精度。
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();
  private static final List<String> RESOURCE_BASE_FIELDS =
      List.of("type", "uri", "mediaType", "name", "size", "sha256");

  private ToolResultJsonCodec() {}

  /** durable ToolInvocation 结果持久化使用的 canonical 对象树。 */
  public static JsonNode encodeNode(ToolResult result) {
    Objects.requireNonNull(result, "result");
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("toolCallId", result.toolCallId());
    ArrayNode contents = node.putArray("contents");
    result.contents().forEach(content -> contents.add(encodeContent(content)));
    node.put("error", result.error());
    node.set("details", readObject(result.detailsJson(), "detailsJson"));
    return node;
  }

  public static String encode(ToolResult result) {
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(result));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("cannot encode tool result", exception);
    }
  }

  /**
   * 判断 {@link #encode} canonical 编码的 UTF-8 字节数是否超过 {@code maxBytes}。
   *
   * <p>按 {@link #encode} 完全相同的字段顺序与语义，把 {@link ToolResult} 逐字段、逐内容直接序列化到 bounded/counting 输出流
   * （toolCallId、contents 数组；text/json/resource 精确字段；error；details），超过 {@code maxBytes} 即在中止点停止，不调用
   * {@link #encodeNode}，也不构造完整 contents 树或完整 JSON String / byte[]。json 内容与 detailsJson 原始文本经严格
   * parser （重复字段 / 尾随内容约束）流式复制单个 JSON 值/对象到同一 generator，不构建中间树；计数按 UTF-8 字节（非字符）精确计算。
   *
   * @throws IllegalArgumentException maxBytes 非正数、内容不受支持（如 Binary）、或 json/details 解析失败时（与 encode
   *     一致）。
   */
  public static boolean exceedsEncodedUtf8Bytes(ToolResult result, int maxBytes) {
    Objects.requireNonNull(result, "result");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    BoundedOutputStream out = new BoundedOutputStream(maxBytes);
    try (JsonGenerator generator = OBJECT_MAPPER.createGenerator(out)) {
      writeResult(generator, result);
    } catch (JacksonException error) {
      if (isOverLimit(error)) {
        return true;
      }
      throw new IllegalArgumentException("cannot encode tool result", error);
    }
    return false;
  }

  /**
   * 仅当 cause 链中出现有界输出流自身的 {@link BoundedOutputStream.LimitExceededException} 时判定为超限中止；其它编码 / IO
   * 失败沿链识别不到该专属异常，不得误判为超限并被静默吞掉。
   */
  private static boolean isOverLimit(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof BoundedOutputStream.LimitExceededException) {
        return true;
      }
    }
    return false;
  }

  private static void writeResult(JsonGenerator generator, ToolResult result) {
    generator.writeStartObject();
    generator.writeStringProperty("toolCallId", result.toolCallId());
    generator.writeArrayPropertyStart("contents");
    for (ResultContent content : result.contents()) {
      writeContent(generator, content);
    }
    generator.writeEndArray();
    generator.writeBooleanProperty("error", result.error());
    generator.writeName("details");
    copyRawJson(generator, result.detailsJson(), "detailsJson", true);
    generator.writeEndObject();
  }

  private static void writeContent(JsonGenerator generator, ResultContent content) {
    generator.writeStartObject();
    if (content instanceof TextResultContent value) {
      generator.writeStringProperty("type", "text");
      generator.writeStringProperty("text", value.text());
    } else if (content instanceof JsonResultContent value) {
      generator.writeStringProperty("type", "json");
      generator.writeName("json");
      copyRawJson(generator, value.json(), "json", false);
    } else if (content instanceof ResourceResultContent value) {
      ResourceRef resource = value.resource();
      generator.writeStringProperty("type", "resource");
      generator.writeStringProperty("uri", resource.uri());
      generator.writeStringProperty("mediaType", resource.mediaType());
      writeNullableText(generator, "name", resource.name());
      writeNullableLong(generator, "size", resource.size());
      writeNullableText(generator, "sha256", resource.sha256());
      if (value.preview() != null) {
        generator.writeStringProperty("preview", value.preview());
      }
      if (value.textMetadata() != null) {
        writeTextMetadata(generator, value.textMetadata());
      }
    } else {
      throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
    }
    generator.writeEndObject();
  }

  /**
   * 用严格 parser（重复字段 / 尾随内容约束）解析单个 JSON 值/对象并流式复制到 {@code generator}：逐 token exact 复制，不构建中间树， 数字按同一
   * BigDecimal profile 原样写出，输出与 {@link #encode} 的树序列化保持相同转义/数字语义；超过输出上限由 bounded 输出流在复制点中止。
   */
  private static void copyRawJson(
      JsonGenerator generator, String rawJson, String name, boolean requireObject) {
    try (JsonParser parser = OBJECT_MAPPER.createParser(rawJson)) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        throw new IllegalArgumentException(name + " is invalid JSON");
      }
      if (requireObject && token != JsonToken.START_OBJECT) {
        throw new IllegalArgumentException(name + " must be an object");
      }
      generator.copyCurrentStructureExact(parser);
      if (parser.nextToken() != null) {
        throw new IllegalArgumentException(name + " has trailing content");
      }
    } catch (JacksonException exception) {
      // 有界输出流超限会被 Jackson 包装成 JacksonException；保留原异常沿 cause 链上抛，不能被误判为“非法 JSON”。
      if (isOverLimit(exception)) {
        throw exception;
      }
      throw new IllegalArgumentException(name + " is invalid JSON", exception);
    }
  }

  private static void writeTextMetadata(JsonGenerator generator, TextArtifactMetadata metadata) {
    generator.writeObjectPropertyStart("textMetadata");
    generator.writeNumberProperty("totalBytes", metadata.totalBytes());
    generator.writeNumberProperty("totalLines", metadata.totalLines());
    generator.writeEndObject();
  }

  private static void writeNullableText(JsonGenerator generator, String name, String value) {
    if (value == null) {
      generator.writeNullProperty(name);
    } else {
      generator.writeStringProperty(name, value);
    }
  }

  private static void writeNullableLong(JsonGenerator generator, String name, Long value) {
    if (value == null) {
      generator.writeNullProperty(name);
    } else {
      generator.writeNumberProperty(name, value.longValue());
    }
  }

  public static ToolResult decode(String resultJson) {
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(resultJson);
      // Jackson 3 对空/纯空白输入返回 MissingNode；两种形态都在解码入口统一拒绝。
      if (parsed == null || parsed.isMissingNode() || !(parsed instanceof ObjectNode node)) {
        throw new IllegalArgumentException("tool result must be an object");
      }
      requireFields(node, "toolCallId", "contents", "error", "details");
      ArrayNode array = requireArray(node.get("contents"), "contents");
      List<ResultContent> contents = new ArrayList<>(array.size());
      for (JsonNode content : array) {
        contents.add(decodeContent(content));
      }
      return new ToolResult(
          text(node, "toolCallId"),
          contents,
          bool(node, "error"),
          OBJECT_MAPPER.writeValueAsString(requireObject(node.get("details"), "details")));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("malformed tool result", exception);
    }
  }

  private static ObjectNode encodeContent(ResultContent content) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    if (content instanceof TextResultContent value) {
      node.put("type", "text");
      node.put("text", value.text());
    } else if (content instanceof JsonResultContent value) {
      node.put("type", "json");
      node.set("json", readObjectOrValue(value.json(), "json"));
    } else if (content instanceof ResourceResultContent value) {
      ResourceRef resource = value.resource();
      node.put("type", "resource");
      node.put("uri", resource.uri());
      node.put("mediaType", resource.mediaType());
      putNullableText(node, "name", resource.name());
      putNullableLong(node, "size", resource.size());
      putNullableText(node, "sha256", resource.sha256());
      if (value.preview() != null) {
        node.put("preview", value.preview());
      }
      if (value.textMetadata() != null) {
        node.set("textMetadata", textMetadataNode(value.textMetadata()));
      }
    } else {
      throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
    }
    return node;
  }

  private static ResultContent decodeContent(JsonNode value) {
    ObjectNode node = requireObject(value, "content");
    String type = text(node, "type");
    return switch (type) {
      case "text" -> {
        requireFields(node, "type", "text");
        yield new TextResultContent(textAllowEmpty(node, "text"));
      }
      case "json" -> {
        requireFields(node, "type", "json");
        try {
          yield new JsonResultContent(OBJECT_MAPPER.writeValueAsString(node.get("json")));
        } catch (JacksonException exception) {
          throw new IllegalArgumentException("cannot decode JSON content", exception);
        }
      }
      case "resource" -> {
        List<String> names = new ArrayList<>(RESOURCE_BASE_FIELDS);
        if (node.has("preview")) {
          names.add("preview");
        }
        if (node.has("textMetadata")) {
          names.add("textMetadata");
        }
        requireFields(node, names.toArray(String[]::new));
        JsonNode size = node.get("size");
        if (!size.isNull() && (!size.isIntegralNumber() || !size.canConvertToLong())) {
          throw new IllegalArgumentException("size must be an integer or null");
        }
        yield new ResourceResultContent(
            new ResourceRef(
                text(node, "uri"),
                text(node, "mediaType"),
                textOrNull(node, "name"),
                size.isNull() ? null : size.longValue(),
                textOrNull(node, "sha256")),
            node.has("preview") ? textOrNull(node, "preview") : null,
            node.has("textMetadata") ? textMetadata(node.get("textMetadata")) : null);
      }
      default -> throw new IllegalArgumentException("unknown tool content type: " + type);
    };
  }

  private static JsonNode readObject(String json, String name) {
    JsonNode node = readObjectOrValue(json, name);
    if (!node.isObject()) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return node;
  }

  private static JsonNode readObjectOrValue(String json, String name) {
    try {
      return OBJECT_MAPPER.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(name + " is invalid JSON", exception);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return object;
  }

  private static ArrayNode requireArray(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be an array");
    }
    return array;
  }

  private static String text(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isString() || value.stringValue().isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank text");
    }
    return value.stringValue();
  }

  private static String textAllowEmpty(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(name + " must be text");
    }
    return value.stringValue();
  }

  private static String textOrNull(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(name + " must be text or null");
    }
    return value.stringValue();
  }

  private static ObjectNode textMetadataNode(TextArtifactMetadata metadata) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("totalBytes", metadata.totalBytes());
    node.put("totalLines", metadata.totalLines());
    return node;
  }

  private static TextArtifactMetadata textMetadata(JsonNode value) {
    ObjectNode node = requireObject(value, "textMetadata");
    requireFields(node, "totalBytes", "totalLines");
    return new TextArtifactMetadata(
        nonNegativeLong(node, "totalBytes"), nonNegativeLong(node, "totalLines"));
  }

  private static long nonNegativeLong(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new IllegalArgumentException(name + " must be a non-negative integer");
    }
    return value.longValue();
  }

  private static void putNullableText(ObjectNode node, String name, String value) {
    if (value == null) {
      node.putNull(name);
    } else {
      node.put(name, value);
    }
  }

  private static void putNullableLong(ObjectNode node, String name, Long value) {
    if (value == null) {
      node.putNull(name);
    } else {
      node.put(name, value.longValue());
    }
  }

  private static boolean bool(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(name + " must be boolean");
    }
    return value.booleanValue();
  }

  private static void requireFields(ObjectNode node, String... names) {
    if (node.size() != names.length) {
      throw new IllegalArgumentException("unexpected tool result fields");
    }
    for (String name : names) {
      if (!node.has(name)) {
        throw new IllegalArgumentException("missing tool result field: " + name);
      }
    }
  }
}
