package fun.fengwk.kkstudio.core.harness.redis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * ActivationNotifier 的确定性 strict JSON codec。
 *
 * <p>wire envelope 仅包含 {@code {targetKind, targetId}}；{@code targetKind} 是 {@link
 * ExecutionTargetKind#name()}（大小写敏感），{@code targetId} 是 {@link Long#toString(long)}。任何 duplicate /
 * trailing / unknown / missing / wrong-type 字段都会被拒绝；不允许 Jackson polymorphic typing 或 default
 * typing。
 */
public final class ExecutionTargetJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> TARGET_FIELDS = orderedSet("targetKind", "targetId");

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ExecutionTargetJsonCodec() {}

  /** 把 {@link ExecutionTarget} 编码为 canonical JSON 文本。 */
  public String encode(ExecutionTarget target) {
    Objects.requireNonNull(target, "target");
    return write(encodeNode(target));
  }

  /** 把 {@link ExecutionTarget} 编码为 deterministic canonical {@link ObjectNode}。 */
  public ObjectNode encodeNode(ExecutionTarget target) {
    Objects.requireNonNull(target, "target");
    ObjectNode node = NODES.objectNode();
    node.put("targetKind", target.kind().name());
    node.put("targetId", Long.toString(target.id()));
    return node;
  }

  /** 从 canonical JSON 文本解码为 {@link ExecutionTarget}。 */
  public ExecutionTarget decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed execution target JSON", error);
    }
    return decodeNode(root);
  }

  /** 从任意 {@link JsonNode} 解码为 {@link ExecutionTarget}。 */
  public ExecutionTarget decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = requireObject(value, "executionTarget");
    requireExactFields(node, TARGET_FIELDS, "executionTarget");
    String kindName = requiredText(node, "targetKind", "executionTarget");
    ExecutionTargetKind kind =
        readEnum(ExecutionTargetKind.class, kindName, "executionTarget.targetKind");
    String idText = requiredText(node, "targetId", "executionTarget");
    if (idText.isEmpty() || idText.charAt(0) == '+' || idText.charAt(0) == '-') {
      throw new IllegalArgumentException(
          "executionTarget.targetId must be a positive decimal string");
    }
    long id;
    try {
      id = Long.parseLong(idText);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "executionTarget.targetId must be a positive decimal string", error);
    }
    if (id <= 0) {
      throw new IllegalArgumentException(
          "executionTarget.targetId must be a positive decimal string");
    }
    return new ExecutionTarget(kind, id);
  }

  // ---------- Helpers ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode execution target JSON", error);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String context) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return object;
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.textValue();
  }

  private static <E extends Enum<E>> E readEnum(Class<E> kind, String name, String context) {
    try {
      return Enum.valueOf(kind, name);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IllegalArgumentException(
          context
              + " must be one of "
              + kind.getEnumConstants().length
              + " "
              + kind.getSimpleName()
              + " values: "
              + name,
          error);
    }
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new LinkedHashSet<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      actual.add(names.next());
    }
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          context + " unexpected fields: " + actual + " (expected " + expected + ")");
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Set.copyOf(set);
  }
}
