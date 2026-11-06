package fun.fengwk.kkstudio.platform.pluginhost.dto;

import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 权威 catalog 当前 desired 配置的安全投影：普通值、schema 定义与每个已声明秘密字段的录入状态。
 *
 * <p>{@code version} 是该 Plugin 当前条目的配置版本；本节点实际应用的版本由 {@link PluginDTO#getAppliedVersion()} 单独表达。
 *
 * <p>{@code valuesJson} 中的数值逐值保真；存储与返回的是校验后的规范文本，包括补默认值和压缩空白。{@code valuesSchema} 与 {@code
 * defaults} 的结构由 Plugin 自己的 schema 决定，按 JSON 节点传输。{@code secrets} 只有说明与布尔，绝不携带任何秘密值。
 */
@Data
public class PluginConfigurationDTO {

  /** 该投影对应的配置版本（canonical 非负十进制字符串）。 */
  private String version;

  /** 该版本校验后的规范配置文本。 */
  private String valuesJson;

  /** 由配置 schema 派生的 values schema。 */
  private JsonNode valuesSchema;

  /** 由配置 schema 派生的唯一默认值。 */
  private JsonNode defaults;

  /** 每个已声明秘密字段的说明与录入状态；没有声明秘密字段时为空映射。 */
  private Map<String, PluginSecretProjectionDTO> secrets;
}
