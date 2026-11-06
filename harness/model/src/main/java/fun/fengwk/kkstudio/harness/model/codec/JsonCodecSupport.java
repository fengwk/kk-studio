package fun.fengwk.kkstudio.harness.model.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 严格 JSON codec 共用的节点读写与字段集合校验原语。
 *
 * <p>本类是“节点必须是 object”与“字段集合精确/必填加可选”的唯一实现，harness、platform、web 与产品的 codec 都调用这里；各 codec
 * 只决定字段集合与上下文名称，不复制校验逻辑。值类型约束（文本、布尔、整数等）仍由调用方按业务语义校验。
 *
 * <p>字段集合有两个错误消息契约：{@link #requireFields} 回显实际与期望集合，供内部契约诊断； {@link #requireFieldsWithoutEcho}
 * 只回报数量与缺失的期望字段名，供异常会进入日志、字段名可能来自上游 opaque payload 的 replay / diagnostic codec。
 */
public final class JsonCodecSupport {

  private JsonCodecSupport() {}

  /** 要求节点是非 null 的 object；null 与非 object 统一按“必须是 JSON object”拒绝。 */
  public static ObjectNode object(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be a JSON object");
    }
    return object;
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
    requireFields(node, expected, Set.of(), name);
  }

  /** required 必须全部存在，optional 可以缺失；未知字段仍然拒绝。 */
  public static void requireFields(
      ObjectNode node, Set<String> required, Set<String> optional, String name) {
    Set<String> actual = new LinkedHashSet<>();
    node.propertyNames().forEach(actual::add);
    Set<String> allowed = new LinkedHashSet<>(required);
    allowed.addAll(optional);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
      throw new IllegalArgumentException(
          optional.isEmpty()
              ? name + " unexpected fields: " + actual + " (expected " + required + ")"
              : name
                  + " unexpected fields: "
                  + actual
                  + " (expected "
                  + required
                  + ", optional "
                  + optional
                  + ")");
    }
  }

  /**
   * 字段集合必须与 expected 完全一致，但异常只回报期望字段数量与缺失的期望字段名。
   *
   * <p>replay / diagnostic codec 的异常会进入诊断与日志，字段名可能来自上游 opaque payload（例如把密钥写进字段名），因此本变体与 {@link
   * #requireFields} 的唯一差别是绝不回显实际字段集合。
   */
  public static void requireFieldsWithoutEcho(
      ObjectNode node, Set<String> expected, String context) {
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

  /**
   * 以调用方 mapper 序列化节点，失败统一包装为 {@link IllegalStateException}。
   *
   * <p>各 codec 的 mapper 不同（普通树、十进制、canonical 选项），因此这里只统一失败语义而不持有 mapper；固定使用某个共享 mapper 的 {@code
   * JsonValues.write} 与 {@code JsonObjects.write} 各自封装自己的 mapper。
   */
  public static String write(ObjectMapper mapper, JsonNode node, String errorMessage) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException(errorMessage, exception);
    }
  }
}
