package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 完整的 Agent create/PUT 请求体；可空文本字段用于显式清除对应值。 */
@Data
public class AgentEditablePropertiesDTO {

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private @Nullable String description;

  /** 可空系统提示词；null/空白视为清除。 */
  private @Nullable String systemPrompt;

  /** 必填执行选择。 */
  private @Nullable HarnessExecutionSelectionDTO execution;

  /** 必填结构化执行配置（tools/skills/subagents），经严格 codec 校验后持久化为 config JSONB。 */
  private AgentConfigDTO config;
}
