package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 完整的 Agent Model 创建/PUT 请求体。
 *
 * <p>MODEL 必须提供 providerName/modelId/config 且不得携带 group；GROUP 必须提供 group 且不得携带
 * providerName/modelId/config。
 */
@Data
public class AgentModelEditablePropertiesDTO {

  /** 必填全局逻辑名：trim 后非空白、无环绕空白、≤128 字符；PUT 更新时作为目标逻辑名（支持重命名）。 */
  private String name;

  /** 必填资源类型；编辑时不得改变既有类型。 */
  private AgentModelKind kind;

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private String description;

  /** MODEL 必填 Provider 名；GROUP 必须省略或 null。 */
  private String providerName;

  /** MODEL 必填发往上游 Provider 的真实模型标识；GROUP 必须省略或 null。 */
  private String modelId;

  /** MODEL 必填结构化配置；GROUP 必须省略或 null。 */
  private AgentModelConfigDTO config;

  /** GROUP 必填组配置；MODEL 必须省略或 null。 */
  private AgentModelGroupDTO group;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Model field: " + name);
  }
}
