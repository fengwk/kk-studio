package fun.fengwk.kkstudio.core.agent.definition.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentDefinition {

  private Long id;
  private String name;
  private String description;
  private String systemPrompt;
  private Long defaultProviderId;
  private Long defaultModelId;
  private String defaultVariant;
  private String toolsJson;
  private String subagentsJson;
  private String skillsJson;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
