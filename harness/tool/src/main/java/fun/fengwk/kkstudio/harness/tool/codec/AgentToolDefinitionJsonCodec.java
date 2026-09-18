package fun.fengwk.kkstudio.harness.tool.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.Objects;
import java.util.Set;

/**
 * 严格、deterministic 的 {@link AgentToolDefinition} JSON 编解码。
 *
 * <p>wire 形状固定为 {@code {descriptor, visibility}}。descriptor 委派 {@link ToolDescriptorJsonCodec}
 * 并携带唯一身份 name，枚举只接受精确的 Java enum name。解析器拒绝 duplicate field、trailing token、unknown/missing/null
 * field 和错误类型。
 */
public final class AgentToolDefinitionJsonCodec {

  private static final String CONTEXT = "agentToolDefinition";
  private static final Set<String> FIELDS = Set.of("descriptor", "visibility");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final ToolDescriptorJsonCodec DESCRIPTOR_CODEC = new ToolDescriptorJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 把完整 Agent tool definition 编码为 deterministic canonical JSON 文本。 */
  public String encode(AgentToolDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    return write(encodeNode(definition));
  }

  /** 把完整 Agent tool definition 编码为 canonical object；String API 委派此方法。 */
  public ObjectNode encodeNode(AgentToolDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    ObjectNode node = NODES.objectNode();
    node.set("descriptor", DESCRIPTOR_CODEC.encodeNode(definition.descriptor()));
    node.put("visibility", definition.visibility().name());
    return node;
  }

  /** 解码完整 Agent tool definition；任何非法 JSON 结构抛 {@link IllegalArgumentException}。 */
  public AgentToolDefinition decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed " + CONTEXT + " JSON", error);
    }
    if (root == null) {
      throw new IllegalArgumentException("malformed " + CONTEXT + " JSON: empty document");
    }
    return decodeNode(root);
  }

  /** 从 JsonNode 解码完整 Agent tool definition。 */
  public AgentToolDefinition decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    if (!value.isObject()) {
      throw new IllegalArgumentException(CONTEXT + " must be an object");
    }
    ObjectNode node = (ObjectNode) value;
    requireFields(node);
    ToolVisibility visibility = enumValue(node, "visibility", ToolVisibility.class);
    return new AgentToolDefinition(
        DESCRIPTOR_CODEC.decodeNode(required(node, "descriptor")), visibility);
  }

  private static void requireFields(ObjectNode node) {
    if (node.size() != FIELDS.size()) {
      throw new IllegalArgumentException(CONTEXT + " must declare exactly " + FIELDS);
    }
    for (String field : FIELDS) {
      if (!node.has(field)) {
        throw new IllegalArgumentException(CONTEXT + " must declare " + field);
      }
    }
  }

  private static JsonNode required(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(CONTEXT + " must declare non-null " + field);
    }
    return value;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(CONTEXT + "." + field + " must be text");
    }
    return value.textValue();
  }

  private static <E extends Enum<E>> E enumValue(ObjectNode node, String field, Class<E> enumType) {
    String value = text(node, field);
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          CONTEXT + "." + field + " has unknown value " + value, error);
    }
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode " + CONTEXT + " JSON", error);
    }
  }
}
