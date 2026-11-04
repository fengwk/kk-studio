package fun.fengwk.kkstudio.harness.model.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型模块严格 JSON codec 共用的节点读写原语。
 *
 * <p>本类只承载各 codec 完全相同的校验规则，不替调用方决定字段集合、错误语义或值对象约束；错误文案保留调用方传入的上下文。
 */
public final class JsonCodecSupport {

  private JsonCodecSupport() {}

  /** 要求节点是 object；用于字段上下文已经明确的错误文案。 */
  public static ObjectNode object(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return object;
  }

  /** 要求节点是非 null 的 object；保留 replay/diagnostic codec 的既有文案。 */
  public static ObjectNode objectNode(JsonNode value, String context) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  public static ArrayNode array(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be an array");
    }
    return array;
  }

  public static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.stringValue();
  }

  public static String decodeNullableText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException(field + " must be text or null");
    }
    return value.stringValue();
  }

  public static boolean bool(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be boolean");
    }
    return value.booleanValue();
  }

  public static long nonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    long parsed = value.longValue();
    if (parsed < 0) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    return parsed;
  }

  /** 要求字段集合与 expected 完全一致；用于 request/response/model 的精确契约。 */
  public static void requireFields(ObjectNode node, Set<String> expected, String name) {
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          "unexpected fields for " + name + ": " + actual + " (expected " + expected + ")");
    }
  }

  /** required 必须全部存在，optional 可以缺失；未知字段仍然拒绝。 */
  public static void requireFields(
      ObjectNode node, Set<String> required, Set<String> optional, String name) {
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    Set<String> allowed = new HashSet<>(required);
    allowed.addAll(optional);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
      throw new IllegalArgumentException(
          "unexpected fields for " + name + ": " + actual + " (expected " + required + ")");
    }
  }

  /** replay/diagnostic codec 使用的字段数量与必填字段校验。 */
  public static void requireFieldsWithCount(ObjectNode node, Set<String> expected, String context) {
    if (node.size() != expected.size()) {
      throw new IllegalArgumentException(
          context + " field count mismatch: expected " + expected.size() + " fields");
    }
    for (String name : expected) {
      if (!node.has(name)) {
        throw new IllegalArgumentException(context + " missing required field: " + name);
      }
    }
  }

  public static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return set;
  }

  /** 按给定比较器排序的不可变列表，用于 enum Set 的 deterministic 输出。 */
  public static <T> List<T> sorted(Set<T> values, Comparator<? super T> comparator) {
    return values.stream().sorted(comparator).toList();
  }

  public static String write(ObjectMapper mapper, JsonNode node, String errorMessage) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException(errorMessage, exception);
    }
  }
}
