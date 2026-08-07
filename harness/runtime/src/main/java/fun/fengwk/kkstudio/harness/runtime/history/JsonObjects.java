package fun.fengwk.kkstudio.harness.runtime.history;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * history 包内部的严格 canonical JSON object 校验工具：供 {@link CustomEntryPayload} / {@link
 * CustomMessagePayload} 的 dataJson / detailsJson 使用。
 *
 * <p>canonical 意味着：可解析、单一 JSON object、无 trailing token、无 duplicate field，且用共享严格 mapper 重新
 * 序列化后与输入逐字节相同（输入已经是 canonical 形态）。{@code maxChars} 提供 bounded 上限。
 */
final class JsonObjects {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private JsonObjects() {}

  static void requireCanonicalObjectJson(String raw, String name, int maxChars) {
    if (raw.length() > maxChars) {
      throw new IllegalArgumentException(name + " must not exceed " + maxChars + " chars");
    }
    JsonNode node = parseObject(raw, name);
    String canonical;
    try {
      canonical = MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot canonicalize " + name, error);
    }
    if (!canonical.equals(raw)) {
      throw new IllegalArgumentException(name + " must be canonical JSON");
    }
  }

  /** 解析为单一 JSON object；解析失败 / 非 object 抛 {@link IllegalArgumentException}。 */
  static JsonNode parseObject(String raw, String name) {
    JsonNode node;
    try {
      node = MAPPER.readTree(raw);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(name + " must contain JSON", error);
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(name + " must be a JSON object");
    }
    return node;
  }

  /** 用共享严格 mapper 序列化为 canonical JSON 文本。 */
  static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode JSON", error);
    }
  }
}
