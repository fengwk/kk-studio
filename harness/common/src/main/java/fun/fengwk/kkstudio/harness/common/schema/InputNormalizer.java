package fun.fengwk.kkstudio.harness.common.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 输入参数 JSON 的静默归一化：在 schema 校验前把兼容别名与数字字符串改写为正式字段。
 *
 * <p>{@code filePath} 别名规则：仅当 schema 声明了 {@code path}、JSON 含 {@code filePath} 且不含 {@code path} 时改写为
 * {@code path} 并删除原字段；两者同时存在时不改写，仍由校验器以 additionalProperties 拒绝。归一化是 schema 驱动的，别名不会出现在 schema 或
 * prompt 中。
 *
 * <p>{@link IntegerSchema} 字段接受匹配 {@code -?\d+} 的十进制数字字符串并改写为 JSON integer； {@link NumberSchema}
 * 字段接受十进制数字字符串（含小数与指数）并改写为 JSON number。非数字文本、 超出 long / double
 * 可表示范围的值不改写，仍由校验器拒绝。boolean/string/enum 字段不转换。
 *
 * <p>递归遍历对象与数组元素；归一化输出为紧凑 JSON 文本。
 */
public final class InputNormalizer {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Pattern INTEGER_TEXT = Pattern.compile("-?\\d+");
  private static final Pattern NUMBER_TEXT = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

  private InputNormalizer() {}

  /** 按输入参数 schema 静默归一化 JSON 文本；顶层必须是 JSON object（空白归一为 {@code {}}）。 */
  public static String normalize(String argumentsJson, InputSchema schema) {
    Objects.requireNonNull(schema, "schema");
    JsonNode root =
        JsonValues.readTree(JsonValues.requireJsonObject(argumentsJson, "argumentsJson"));
    normalizeObject((ObjectNode) root, schema.properties());
    return JsonValues.write(root);
  }

  private static void normalizeObject(ObjectNode node, Map<String, SchemaElement> properties) {
    if (properties.containsKey("path")
        && !properties.containsKey("filePath")
        && node.has("filePath")
        && !node.has("path")) {
      node.set("path", node.get("filePath"));
      node.remove("filePath");
    }
    for (Map.Entry<String, SchemaElement> entry : properties.entrySet()) {
      JsonNode value = node.get(entry.getKey());
      if (value != null) {
        node.set(entry.getKey(), normalized(value, entry.getValue()));
      }
    }
  }

  /** 返回按 schema 归一化后的节点；无需改写时返回原节点。 */
  private static JsonNode normalized(JsonNode node, SchemaElement schema) {
    if (schema instanceof IntegerSchema) {
      Long value = parseInteger(node);
      if (value != null) {
        return NODES.numberNode(value);
      }
    } else if (schema instanceof NumberSchema) {
      Double value = parseNumber(node);
      if (value != null) {
        return NODES.numberNode(value);
      }
    } else if (schema instanceof ArraySchema arraySchema) {
      if (node.isArray()) {
        ArrayNode array = (ArrayNode) node;
        for (int index = 0; index < array.size(); index++) {
          array.set(index, normalized(array.get(index), arraySchema.items()));
        }
      }
    } else if (schema instanceof ObjectSchema objectSchema) {
      if (node.isObject()) {
        normalizeObject((ObjectNode) node, objectSchema.properties());
      }
    }
    return node;
  }

  private static Long parseInteger(JsonNode node) {
    if (!node.isTextual()) {
      return null;
    }
    String text = node.asText();
    if (!INTEGER_TEXT.matcher(text).matches()) {
      return null;
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException overflow) {
      return null;
    }
  }

  private static Double parseNumber(JsonNode node) {
    if (!node.isTextual()) {
      return null;
    }
    String text = node.asText();
    if (!NUMBER_TEXT.matcher(text).matches()) {
      return null;
    }
    double value = Double.parseDouble(text);
    return Double.isFinite(value) ? value : null;
  }
}
