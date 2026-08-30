package fun.fengwk.kkstudio.harness.tool.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 严格、deterministic 的 {@link ToolDescriptor} JSON 编解码。
 *
 * <p>Descriptor 只描述模型可见的工具契约，不携带执行路由；input schema 的规范编解码委托唯一权威 {@link SchemaJsonCodec}。
 */
public final class ToolDescriptorJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ToolDescriptorJsonCodec() {}

  /** 把单个 descriptor 编码为 deterministic canonical JSON 文本。 */
  public String encode(ToolDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return write(encodeNode(descriptor));
  }

  /** 把单个 descriptor 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。 */
  public ObjectNode encodeNode(ToolDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    ObjectNode node = NODES.objectNode();
    writeDescriptor(node, descriptor);
    return node;
  }

  /** 解码单个 canonical descriptor JSON 文本；任何非法结构抛 {@link IllegalArgumentException}。 */
  public ToolDescriptor decode(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed tool descriptor JSON", error);
    }
    return decodeNode(root);
  }

  /** 从任意 {@link JsonNode} 解码 descriptor；非对象节点抛 {@link IllegalArgumentException}。 */
  public ToolDescriptor decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    return readDescriptor(value);
  }

  /** 把 {@link InputSchema} 编码为 canonical input schema JSON 文本（委托 {@link SchemaJsonCodec}）。 */
  public String encodeInputSchema(InputSchema schema) {
    return SCHEMA_CODEC.encode(schema);
  }

  /** 从 canonical input schema JSON 文本解码 {@link InputSchema}（委托 {@link SchemaJsonCodec}）。 */
  public InputSchema decodeInputSchema(String json) {
    return SCHEMA_CODEC.decode(json);
  }

  private static void writeDescriptor(ObjectNode target, ToolDescriptor descriptor) {
    target.put("name", descriptor.name());
    target.put("version", descriptor.version());
    target.put("description", descriptor.description());
    target.put("rendererKey", descriptor.rendererKey());
    target.put("sideEffect", descriptor.sideEffect().name());
    target.put("timeoutMillis", descriptor.timeout().toMillis());
    target.set("inputSchema", SCHEMA_CODEC.encodeNode(descriptor.inputSchema()));
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode tool descriptor JSON", error);
    }
  }

  private static ToolDescriptor readDescriptor(JsonNode node) {
    ObjectNode obj = requiredObject(node, "descriptor");
    Set<String> allowed =
        Set.of(
            "name",
            "version",
            "description",
            "rendererKey",
            "sideEffect",
            "timeoutMillis",
            "inputSchema");
    rejectUnknownFields(obj, allowed, "descriptor");
    String name = requiredText(obj, "name", "descriptor");
    String version = requiredText(obj, "version", "descriptor");
    String description = requiredText(obj, "description", "descriptor");
    String rendererKey = requiredText(obj, "rendererKey", "descriptor");
    ToolSideEffect sideEffect = readSideEffect(obj);
    long timeoutMillis = requiredNonNegativeLong(obj, "timeoutMillis", "descriptor");
    JsonNode inputSchemaNode = requiredField(obj, "inputSchema", "descriptor");
    InputSchema inputSchema = SCHEMA_CODEC.decodeNode(inputSchemaNode, "descriptor inputSchema");
    return constructDescriptor(
        name,
        version,
        description,
        rendererKey,
        inputSchema,
        sideEffect,
        Duration.ofMillis(timeoutMillis));
  }

  private static ToolDescriptor constructDescriptor(
      String name,
      String version,
      String description,
      String rendererKey,
      InputSchema inputSchema,
      ToolSideEffect sideEffect,
      Duration timeout) {
    try {
      return new ToolDescriptor(
          name, version, description, rendererKey, inputSchema, sideEffect, timeout);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "descriptor validation failed for " + name + "@" + version + ": " + error.getMessage(),
          error);
    }
  }

  private static ToolSideEffect readSideEffect(ObjectNode obj) {
    String value = requiredText(obj, "sideEffect", "descriptor");
    try {
      return ToolSideEffect.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("descriptor unknown sideEffect: " + value, error);
    }
  }

  private static long requiredNonNegativeLong(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new IllegalArgumentException(
          context + " '" + field + "' must be a non-negative long integer");
    }
    return value.longValue();
  }

  private static void rejectUnknownFields(ObjectNode node, Set<String> allowed, String context) {
    Iterator<String> fields = node.fieldNames();
    if (fields == null) {
      return;
    }
    while (fields.hasNext()) {
      String name = fields.next();
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException(context + " unknown field: '" + name + "'");
      }
    }
  }

  private static JsonNode requiredField(JsonNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare '" + field + "'");
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field, String context) {
    String value = optionalText(node, field, context);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(context + " '" + field + "' must be a non-blank string");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + " '" + field + "' must be a string");
    }
    return value.textValue();
  }

  private static ObjectNode requiredObject(JsonNode node, String context) {
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(
          context
              + " must be a JSON object but was "
              + node.getNodeType().name().toLowerCase(Locale.ROOT));
    }
    return (ObjectNode) node;
  }
}
