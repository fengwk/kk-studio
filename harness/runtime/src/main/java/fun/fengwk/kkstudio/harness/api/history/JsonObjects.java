package fun.fengwk.kkstudio.harness.api.history;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;

/**
 * history 的严格 canonical JSON object 工具：{@link CustomEntryPayload} / {@link CustomMessagePayload}
 * 构造时用它校验 dataJson / detailsJson，history codec 编解码时用它搬运同一份自由 JSON 子树。
 *
 * <p>canonical 意味着：可解析、单一 JSON object、无 trailing token、无 duplicate field，且用共享严格 mapper 重新
 * 序列化后与输入逐字节相同（输入已经是 canonical 形态）。{@code maxChars} 提供 bounded 上限。
 */
public final class JsonObjects {

  private static final ObjectMapper MAPPER = HarnessJson.STRICT_DECIMAL;

  private JsonObjects() {}

  public static void requireCanonicalObjectJson(String raw, String name, int maxChars) {
    if (raw.length() > maxChars) {
      throw new IllegalArgumentException(name + " must not exceed " + maxChars + " chars");
    }
    JsonNode node = parseObject(raw, name);
    String canonical = JsonCodecSupport.write(MAPPER, node, "cannot canonicalize " + name);
    if (!canonical.equals(raw)) {
      throw new IllegalArgumentException(name + " must be canonical JSON");
    }
  }

  /** 解析为单一 JSON object；解析失败 / 非 object 抛 {@link IllegalArgumentException}。 */
  public static JsonNode parseObject(String raw, String name) {
    JsonNode node;
    try {
      node = MAPPER.readTree(raw);
    } catch (JacksonException error) {
      throw new IllegalArgumentException(name + " must contain JSON", error);
    }
    return JsonCodecSupport.object(node, name);
  }

  /** 用共享严格 mapper 序列化为 canonical JSON 文本。 */
  public static String write(JsonNode node) {
    return JsonCodecSupport.write(MAPPER, node, "cannot encode JSON");
  }
}
