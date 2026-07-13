package fun.fengwk.kkstudio.core.agent.definition.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentDefinitionDO {

  private Long id;
  private Long workspaceId;
  private String name;
  private String description;
  private String systemPrompt;
  private Long modelId;
  private String variant;
  private String configJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
