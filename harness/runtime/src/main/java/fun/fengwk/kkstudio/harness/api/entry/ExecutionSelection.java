package fun.fengwk.kkstudio.harness.api.entry;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;

import java.util.Iterator;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * branch 冻结的不可变执行选择：Driver 标识符 + 规范化的 Driver settings JSON object。
 *
 * <p>{@code driver} 必须符合 {@code ^[a-z][a-z0-9-]{0,31}$}。{@code settingsJson} 在构造时被规范化为
 * 键按字典序递归排序、无空白的 JSON object 文本，因此本对象的 {@code equals} 与 {@code hashCode} 直接等价于语义比较。
 */
public record ExecutionSelection(String driver, String settingsJson) {

  private static final Pattern DRIVER_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final ObjectMapper MAPPER = HarnessJson.STRICT_DECIMAL;

  public ExecutionSelection {
    driver = requireCanonicalDriver(driver, "driver");
    settingsJson = normalizeSettingsJson(settingsJson, "settingsJson");
  }

  /** settingsJson 的简写别名。 */
  public String settings() {
    return settingsJson;
  }

  private static String requireCanonicalDriver(String value, String field) {
    Objects.requireNonNull(value, field);
    if (!DRIVER_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must match ^[a-z][a-z0-9-]{0,31}$: " + value);
    }
    return value;
  }

  private static String normalizeSettingsJson(String raw, String field) {
    Objects.requireNonNull(raw, field);
    JsonNode node;
    try {
      node = MAPPER.readTree(raw);
    } catch (JacksonException error) {
      throw new IllegalArgumentException(field + " must be valid JSON", error);
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    JsonNode normalized = normalizeNode(node);
    try {
      return MAPPER.writeValueAsString(normalized);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot normalize " + field, error);
    }
  }

  private static JsonNode normalizeNode(JsonNode node) {
    if (node.isObject()) {
      ObjectNode source = (ObjectNode) node;
      ObjectNode sorted = NODES.objectNode();
      TreeSet<String> keys = new TreeSet<>();
      Iterator<String> it = source.propertyNames().iterator();
      while (it.hasNext()) {
        keys.add(it.next());
      }
      for (String key : keys) {
        sorted.set(key, normalizeNode(source.get(key)));
      }
      return sorted;
    } else if (node.isArray()) {
      ArrayNode source = (ArrayNode) node;
      ArrayNode normalized = NODES.arrayNode();
      for (JsonNode child : source) {
        normalized.add(normalizeNode(child));
      }
      return normalized;
    } else {
      return node;
    }
  }
}
