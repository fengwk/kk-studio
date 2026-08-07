package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 完整的 Agent Model 创建/PUT 请求体，携带结构化可执行 {@link #config}。 */
@Data
public class AgentModelEditablePropertiesDTO {

  /** 可空描述（≤512 字符）；null/空白视为清除。 */
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
