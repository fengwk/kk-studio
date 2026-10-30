package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * durable Invocation value codec 共享的严格 JSON 原语：重复键、尾随内容、字段集合与标量类型都在这里统一拒绝。
 *
 * <p>依赖 Harness Environment 的 canonical id 校验，因此留在 runtime 而非 common；公开给 Driver 的 codec 复用同一套解析与
 * 校验语义。
 */
public final class InvocationJsonSupport {

  public static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          // 树搬运的自由 JSON（如 variant.protocolOptions）按 BigDecimal 读回，1e309/1e-324 与高精度小数不得退化为 Infinity
          // / 0。
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private InvocationJsonSupport() {}

  public static JsonNode parse(String json, String context) {
    Objects.requireNonNull(json, "json");
    try {
      JsonNode value = MAPPER.readTree(json);
      // Jackson 3 对空白输入返回 MissingNode（Jackson 2 返回 null），两者都必须判为 empty document。
      if (value == null || value.isMissingNode()) {
        throw new IllegalArgumentException("malformed " + context + " JSON: empty document");
      }
      return value;
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed " + context + " JSON", error);
    }
  }

  public static String write(JsonNode node, String context) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode " + context + " JSON", error);
    }
  }

  public static ObjectNode object(JsonNode value, String context) {
    Objects.requireNonNull(value, "value");
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be an object");
    }
    return object;
  }

  public static ArrayNode array(JsonNode value, String field) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(field + " must be an array");
    }
    return array;
  }

  public static void requireFields(ObjectNode node, String context, String... names) {
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

  public static JsonNode required(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare non-null " + field);
    }
    return value;
  }

  public static JsonNode declared(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalArgumentException(context + " must declare " + field);
    }
    return value;
  }

  public static String text(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.stringValue();
  }

  public static String nullableText(ObjectNode node, String field, String context) {
    JsonNode value = declared(node, field, context);
    if (value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    return value.stringValue();
  }

  public static boolean bool(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  public static int positiveInt(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a positive integer");
    }
    return value.intValue();
  }

  public static int nonNegativeInt(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-negative integer");
    }
    return value.intValue();
  }

  public static long nonNegativeLong(ObjectNode node, String field, String context) {
    JsonNode value = required(node, field, context);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a non-negative long integer");
    }
    return value.longValue();
  }

  public static <E extends Enum<E>> E nullableEnum(
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

  public static <E extends Enum<E>> E requiredEnum(
      ObjectNode node, String field, Class<E> enumType, String context) {
    String value = text(node, field, context);
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " has unknown value " + value, error);
    }
  }

  public static Instant nullableInstant(ObjectNode node, String field, String context) {
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

  public static UUID nullableUuid(ObjectNode node, String field, String context) {
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

  /** 读取 canonical EnvironmentId 文本字段；非 canonical UUID 文本拒绝。 */
  public static EnvironmentId environmentId(ObjectNode node, String field, String context) {
    String value = nullableText(node, field, context);
    if (value == null) {
      throw new IllegalArgumentException(context + " must declare non-null " + field);
    }
    try {
      return EnvironmentId.parse(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical UUID string: " + value, error);
    }
  }

  /** 读取可空 canonical Environment id：null 表示未绑定，非 null 必须通过 {@link EnvironmentId} 校验。 */
  public static EnvironmentId nullableEnvironmentId(ObjectNode node, String field, String context) {
    JsonNode value = declared(node, field, context);
    if (value.isNull()) {
      return null;
    }
    return environmentId(node, field, context);
  }

  public static UUID requiredUuid(ObjectNode node, String field, String context) {
    UUID value = nullableUuid(node, field, context);
    if (value == null) {
      throw new IllegalArgumentException(context + " must declare non-null " + field);
    }
    return value;
  }

  public static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  public static void putNullable(ObjectNode node, String field, Instant value) {
    putNullable(node, field, value == null ? null : value.toString());
  }

  public static void putNullable(ObjectNode node, String field, UUID value) {
    putNullable(node, field, value == null ? null : value.toString());
  }

  /** 编码可空 Environment id：null 输出显式 null；非 null 输出 canonical UUID 文本。 */
  public static void putNullable(ObjectNode node, String field, EnvironmentId value) {
    putNullable(node, field, value == null ? null : value.toString());
  }

  public static void putNullable(ObjectNode node, String field, Enum<?> value) {
    putNullable(node, field, value == null ? null : value.name());
  }
}
