package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 完整的 Agent Model 创建/PUT 请求体，携带结构化可执行 {@link #config}。 */
@Data
public class AgentModelEditablePropertiesDTO {

  /** 必填模型逻辑名：trim 后非空白、无环绕空白、≤128 字符；PUT 更新时作为目标逻辑名（支持重命名）。 */
  private String name;

  /** 必填发往上游 Provider 的真实模型标识：trim 后非空白、无环绕空白、≤256 字符；不要求全局唯一。 */
  private String modelId;

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private String description;

  /**
   * 必填结构化可执行配置（limit/abilities/pricing/defaultVariant/variants），经严格 parser 校验后持久化为 config JSONB。
   */
  private AgentModelConfigDTO config;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Model field: " + name);
  }
}
