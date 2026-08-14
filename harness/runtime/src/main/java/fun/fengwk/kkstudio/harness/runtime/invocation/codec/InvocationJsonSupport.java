package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** durable Invocation value codec 共享的 feature-local 严格 JSON 原语。 */
final class InvocationJsonSupport {

  static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private InvocationJsonSupport() {}

  static JsonNode parse(String json, String context) {
    Objects.requireNonNull(json, "json");
    try {
      JsonNode value = MAPPER.readTree(json);
      if (value == null) {
        throw new IllegalArgumentException("malformed " + context + " JSON: empty document");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed " + context + " JSON", error);
    }
  }

  static String write(JsonNode node, String context) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode " + context + " JSON", error);
    }
  }

  static ObjectNode object(JsonNode value, String context) {
    Objects.requireNonNull(value, "value");
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be an object");
    }
    return object;
  }

  static ArrayNode array(JsonNode value, String field) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(field + " must be an array");
    }
    return array;
  }

  static void requireFields(ObjectNode node, String context, String... names) {
    Set<String> expected = Set.of(names);
    if (node.size() != expected.size()) {
      throw new IllegalArgumentException(context + " must declare exactly " + expected);
    }
    for (String name : expected) {
      if (!node.has(name)) {
        throw new IllegalArgumentException(context + " must declare " + name);
      }
    }
  }

  static JsonNode required(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare non-null " + field);
    }
    return value;
  }

  static JsonNode declared(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalArgumentException(context + " must declare " + field);
    }
    return value;
  }

  static String text(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.textValue();
  }

  static String nullableText(ObjectNode node, String field, String context) {
    JsonNode value = declared(node, field, context);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    return value.textValue();
  }

  static boolean bool(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  static int positiveInt(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a positive integer");
    }
    return value.intValue();
  }

  static long nonNegativeLong(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a non-negative long integer");
    }
    return value.longValue();
  }

  static <E extends Enum<E>> E nullableEnum(
      ObjectNode node, String field, Class<E> enumType, String context) {
    String value = nullableText(node, field, context);
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " has unknown value " + value, error);
    }
  }

  static <E extends Enum<E>> E requiredEnum(
      ObjectNode node, String field, Class<E> enumType, String context) {
    String value = text(node, field, context);
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " has unknown value " + value, error);
    }
  }

  static Instant nullableInstant(ObjectNode node, String field, String context) {
    String value = nullableText(node, field, context);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be an ISO-8601 instant", error);
    }
  }

  static UUID nullableUuid(ObjectNode node, String field, String context) {
    String value = nullableText(node, field, context);
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical UUID string", error);
    }
  }

  static UUID requiredUuid(ObjectNode node, String field, String context) {
    UUID value = nullableUuid(node, field, context);
    if (value == null) {
      throw new IllegalArgumentException(context + " must declare non-null " + field);
    }
    return value;
  }

  static EnvironmentName nullableEnvironmentName(ObjectNode node, String field, String context) {
    String value = nullableText(node, field, context);
    return value == null ? null : new EnvironmentName(value);
  }

  /**
   * 读取可空的完整 Environment binding 对象：null 表示未绑定；非 null 必须是恰好 {@code name}/{@code workspacePath}
   * 两字段的严格对象，且两字段分别经 {@link EnvironmentName} 与 workspace path validator 校验。
   */
  static EnvironmentBinding nullableEnvironmentBinding(
      ObjectNode node, String field, String context) {
    JsonNode value = declared(node, field, context);
    if (value.isNull()) {
      return null;
    }
    String bindingContext = context + "." + field;
    ObjectNode binding = object(value, bindingContext);
    requireFields(binding, bindingContext, "name", "workspacePath");
    return new EnvironmentBinding(
        new EnvironmentName(text(binding, "name", bindingContext)),
        text(binding, "workspacePath", bindingContext));
  }

  static String jsonObjectText(ObjectNode node, String field, String context) {
    return requireJsonObject(text(node, field, context), context + "." + field);
  }

  static String requireJsonObject(String value, String context) {
    Objects.requireNonNull(value, "value");
    try {
      JsonNode parsed = MAPPER.readTree(value);
      if (parsed == null || !parsed.isObject()) {
        throw new IllegalArgumentException(context + " must contain a JSON object");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(context + " must contain a JSON object", error);
    }
  }

  static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  static void putNullable(ObjectNode node, String field, Instant value) {
    putNullable(node, field, value == null ? null : value.toString());
  }

  static void putNullable(ObjectNode node, String field, UUID value) {
    putNullable(node, field, value == null ? null : value.toString());
  }

  static void putNullable(ObjectNode node, String field, EnvironmentName value) {
    putNullable(node, field, value == null ? null : value.value());
  }

  /** 编码可空完整 Environment binding：null 输出显式 null；非 null 输出严格 {@code {name, workspacePath}} 对象。 */
  static void putNullable(ObjectNode node, String field, EnvironmentBinding value) {
    if (value == null) {
      node.putNull(field);
      return;
    }
    ObjectNode binding = node.putObject(field);
    binding.put("name", value.environmentName().value());
    binding.put("workspacePath", value.workspacePath());
  }

  static void putNullable(ObjectNode node, String field, Enum<?> value) {
    putNullable(node, field, value == null ? null : value.name());
  }
}
