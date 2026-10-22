package fun.fengwk.kkstudio.platform.plugin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonValues;

import java.nio.charset.StandardCharsets;

/**
 * 单个 Plugin 的不可变普通配置：单调递增的 revision 加一段严格 JSON object 文本。
 *
 * <p>这里只承载普通值；秘密字段由 {@link PluginConfigSchema#secrets()} 声明，绝不进入 valuesJson。文本必须严格 JSON
 * object（拒绝重复字段、 尾随内容与非法类型），且受 UTF-8 64 KiB 上限约束。默认值只来自 schema。
 *
 * @param revision 非负配置修订号；初始默认配置为 0
 * @param valuesJson 严格 JSON object 文本
 */
public record PluginConfig(long revision, String valuesJson) {

  /** 原始与归一化配置文本共用的 UTF-8 字节上限。 */
  public static final int MAX_VALUES_UTF8_BYTES = 64 * 1024;

  public PluginConfig {
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    requireWithinBudget(valuesJson, "valuesJson");
    requireObjectNode(valuesJson, "valuesJson");
  }

  /** 该 Plugin schema 的初始默认配置：revision=0，values 为 schema 声明的默认值。 */
  public static PluginConfig initial(PluginConfigSchema schema) {
    return new PluginConfig(0, schema.defaultsJson());
  }

  /**
   * 非空且 UTF-8 不超过 64 KiB 的文本门禁。
   *
   * <p>先按 UTF-16 字符数快速拒绝（字符数不多于字节数是 UTF-8 的恒成立下界），只有在字符数已受限时才物化字节数组，避免无界分配。
   */
  static String requireWithinBudget(String text, String field) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (text.length() > MAX_VALUES_UTF8_BYTES
        || text.getBytes(StandardCharsets.UTF_8).length > MAX_VALUES_UTF8_BYTES) {
      throw new IllegalArgumentException(
          field + " must not exceed " + MAX_VALUES_UTF8_BYTES + " bytes");
    }
    return text;
  }

  /** 严格 JSON object 解析；失败一律收敛为固定错误，既不回显原始文本也不附带底层 cause。 */
  static ObjectNode requireObjectNode(String text, String field) {
    try {
      JsonNode node = JsonValues.readTree(text);
      if (!node.isObject()) {
        throw new IllegalArgumentException(field + " must be a strict JSON object");
      }
      return (ObjectNode) node;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a strict JSON object");
    }
  }
}
