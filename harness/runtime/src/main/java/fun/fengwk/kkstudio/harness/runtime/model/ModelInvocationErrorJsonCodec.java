package fun.fengwk.kkstudio.harness.runtime.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ModelInvocationError} 的严格、确定性 JSON codec。与 runtime 其他严格 codec 保持一致：显式构建与读取 {@link
 * JsonNode} 树，要求精确字段集合，并以 {@link IllegalArgumentException} 拒绝未知、缺失或类型错误的字段。不使用 Jackson default
 * typing、polymorphic annotation 或反射 POJO 绑定。
 */
public final class ModelInvocationErrorJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> EXPECTED_FIELDS = Set.of("kind", "message");

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ModelInvocationErrorJsonCodec() {}

  /** 将 snapshot 编码为 canonical JSON 字符串。{@code kind} 输出为 enum name；{@code message} 原样输出。 */
  public String encode(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(error));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("cannot encode model invocation error", exception);
    }
  }

  /** 返回 {@link #encode} 与测试共用的 canonical {@link JsonNode} 树。 */
  public JsonNode encodeNode(ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    ObjectNode node = NODES.objectNode();
    node.put("kind", error.kind().name());
    node.put("message", error.message());
    return node;
  }

  public ModelInvocationError decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(OBJECT_MAPPER.readTree(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed model invocation error", exception);
    }
  }

  public ModelInvocationError decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    if (!(value instanceof ObjectNode node)) {
      throw new IllegalArgumentException("model invocation error must be an object");
    }
    requireFields(node);
    ProviderErrorKind kind;
    try {
      kind = ProviderErrorKind.valueOf(text(node, "kind"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown provider error kind", exception);
    }
    return new ModelInvocationError(kind, text(node, "message"));
  }

  private static void requireFields(ObjectNode node) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(EXPECTED_FIELDS)) {
      throw new IllegalArgumentException("unexpected model invocation error fields: " + actual);
    }
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }
}
