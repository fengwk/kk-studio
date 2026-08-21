package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 完整的 Agent Definition create/PUT 请求体；可空文本字段用于显式清除对应值。 */
@Data
public class AgentDefinitionEditablePropertiesDTO {

  /** 可空描述（≤512 字符）；null/空白视为清除。 */
  private String description;

  /** 可空系统提示词；null/空白视为清除。 */
  private String systemPrompt;

  /** 必填模型引用，序列化形式为 {@code providerName/modelName}；provider 与 model 必须已存在。 */
  private String model;

  /** 可空模型变体 id（≤64 字符）；null/空白表示不覆盖，运行时回退解析 model config 的 defaultVariant。 */
  private String variant;

  /** 必填结构化执行配置（tools/skills/subagents），经严格 codec 校验后持久化为 config JSONB。 */
  private AgentDefinitionConfigDTO config;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Definition field: " + name);
  }
}
