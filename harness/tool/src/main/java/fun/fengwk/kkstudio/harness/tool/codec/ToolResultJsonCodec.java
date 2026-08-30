package fun.fengwk.kkstudio.harness.tool.codec;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 最终与部分 ToolResult 的严格持久化 codec。 */
public final class ToolResultJsonCodec {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

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
    } catch (JsonProcessingException exception) {
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
    BoundedUtf8OutputStream out = new BoundedUtf8OutputStream(maxBytes);
    try (JsonGenerator generator = OBJECT_MAPPER.getFactory().createGenerator(out)) {
      writeResult(generator, result);
    } catch (Utf8LimitExceededException error) {
      return true;
    } catch (IOException exception) {
      throw new IllegalArgumentException("cannot encode tool result", exception);
    }
    return false;
  }

  private static void writeResult(JsonGenerator generator, ToolResult result) throws IOException {
    generator.writeStartObject();
    generator.writeStringField("toolCallId", result.toolCallId());
    generator.writeArrayFieldStart("contents");
    for (ResultContent content : result.contents()) {
      writeContent(generator, content);
    }
    generator.writeEndArray();
    generator.writeBooleanField("error", result.error());
    generator.writeFieldName("details");
    copyRawJson(generator, result.detailsJson(), "detailsJson", true);
    generator.writeEndObject();
  }

  private static void writeContent(JsonGenerator generator, ResultContent content)
      throws IOException {
    generator.writeStartObject();
    if (content instanceof TextResultContent value) {
      generator.writeStringField("type", "text");
      generator.writeStringField("text", value.text());
    } else if (content instanceof JsonResultContent value) {
      generator.writeStringField("type", "json");
      generator.writeFieldName("json");
      copyRawJson(generator, value.json(), "json", false);
    } else if (content instanceof ResourceResultContent value) {
      ResourceRef resource = value.resource();
      generator.writeStringField("type", "resource");
      generator.writeStringField("uri", resource.uri());
      generator.writeStringField("mediaType", resource.mediaType());
      writeNullableText(generator, "name", resource.name());
      writeNullableLong(generator, "size", resource.size());
      writeNullableText(generator, "sha256", resource.sha256());
      if (value.preview() != null) {
        generator.writeStringField("preview", value.preview());
      }
    } else {
      throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
    }
    generator.writeEndObject();
  }

  /**
   * 用严格 parser（重复字段 / 尾随内容约束）解析单个 JSON 值/对象并流式复制到 {@code generator}：逐 token 复制，不构建中间树， 输出与 {@link
   * #encode} 的树序列化保持相同转义/数字语义；超过输出上限由 bounded 输出流在复制点中止。
   */
  private static void copyRawJson(
      JsonGenerator generator, String rawJson, String name, boolean requireObject)
      throws IOException {
    try (JsonParser parser = OBJECT_MAPPER.createParser(rawJson)) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        throw new IllegalArgumentException(name + " is invalid JSON");
      }
      if (requireObject && token != JsonToken.START_OBJECT) {
        throw new IllegalArgumentException(name + " must be an object");
      }
      generator.copyCurrentStructure(parser);
      if (parser.nextToken() != null) {
        throw new IllegalArgumentException(name + " has trailing content");
      }
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(name + " is invalid JSON", exception);
    }
  }

  private static void writeNullableText(JsonGenerator generator, String name, String value)
      throws IOException {
    if (value == null) {
      generator.writeNullField(name);
    } else {
      generator.writeStringField(name, value);
    }
  }

  private static void writeNullableLong(JsonGenerator generator, String name, Long value)
      throws IOException {
    if (value == null) {
      generator.writeNullField(name);
    } else {
      generator.writeNumberField(name, value.longValue());
    }
  }

  public static ToolResult decode(String resultJson) {
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(resultJson);
      if (!(parsed instanceof ObjectNode node)) {
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
    } catch (JsonProcessingException exception) {
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
        } catch (JsonProcessingException exception) {
          throw new IllegalArgumentException("cannot decode JSON content", exception);
        }
      }
      case "resource" -> {
        if (node.has("preview")) {
          requireFields(node, "type", "uri", "mediaType", "name", "size", "sha256", "preview");
        } else {
          requireFields(node, "type", "uri", "mediaType", "name", "size", "sha256");
        }
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
            node.has("preview") ? textOrNull(node, "preview") : null);
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
    } catch (JsonProcessingException exception) {
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
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String textAllowEmpty(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(name + " must be text");
    }
    return value.textValue();
  }

  private static String textOrNull(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(name + " must be text or null");
    }
    return value.textValue();
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

  /** 编码超过上限时由 bounded 输出流抛出（IOException 使 Jackson 不做二次包装）。 */
  private static final class Utf8LimitExceededException extends IOException {
    private Utf8LimitExceededException() {}
  }

  /** 有界 UTF-8 输出流：累计超过 maxBytes 即抛出中止异常，不物化完整内容；正常完成时按 UTF-8 还原文本。 */
  private static final class BoundedUtf8OutputStream extends OutputStream {
    private final int maxBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int count;

    private BoundedUtf8OutputStream(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public void write(int b) throws IOException {
      check(1);
      bytes.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      check(length);
      bytes.write(buffer, offset, length);
    }

    private void check(int length) throws IOException {
      if (length > maxBytes - count) {
        throw new Utf8LimitExceededException();
      }
      count += length;
    }
  }
}
