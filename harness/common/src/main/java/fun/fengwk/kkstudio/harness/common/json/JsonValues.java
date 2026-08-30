package fun.fengwk.kkstudio.harness.common.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * 严格 JSON 值与对象校验与读取门禁。
 *
 * <p>启用 {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION} 与 {@link
 * DeserializationFeature#FAIL_ON_TRAILING_TOKENS}，在边界严格拒绝重复字段与尾随 token。
 */
public final class JsonValues {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private JsonValues() {}

  /**
   * 确保文本非空白且是有效严格 JSON，返回原始文本。
   *
   * @throws IllegalArgumentException 文本为 null、空白、存在重复字段/尾随 token 或非合法 JSON 时
   */
  public static String requireValidJson(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("json must not be blank");
    }
    readTree(json);
    return json;
  }

  /**
   * 确保文本是有效的顶层 JSON object；null 或空白文本规范化为 {@code "{}"}。
   *
   * @throws IllegalArgumentException 文本非合法 JSON、存在重复字段/尾随 token 或顶层不是 JSON object 时
   */
  public static String requireJsonObject(String json) {
    return requireJsonObject(json, "json");
  }

  /**
   * 确保文本是有效的顶层 JSON object，并使用指定字段名构造异常；null 或空白文本规范化为 {@code "{}"}。
   *
   * @throws IllegalArgumentException 文本非合法 JSON、存在重复字段/尾随 token 或顶层不是 JSON object 时
   */
  public static String requireJsonObject(String json, String name) {
    Objects.requireNonNull(name, "name");
    String normalized = json == null || json.isBlank() ? "{}" : json;
    JsonNode node = readTree(normalized);
    if (!node.isObject()) {
      throw new IllegalArgumentException(name + " must be a JSON object");
    }
    return normalized;
  }

  /**
   * 解析非空严格 JSON 文本为 {@link JsonNode}。
   *
   * @throws IllegalArgumentException 文本为 null、非合法 JSON 或包含重复字段/尾随内容时
   */
  public static JsonNode readTree(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      if (node == null) {
        throw new IllegalArgumentException("json must not be null");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("json must be valid", error);
    }
  }

  /**
   * 将 {@link JsonNode} 序列化为紧凑 JSON 文本。
   *
   * @throws IllegalStateException 无法序列化时
   */
  public static String write(JsonNode node) {
    Objects.requireNonNull(node, "node");
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode JSON node", error);
    }
  }
}
