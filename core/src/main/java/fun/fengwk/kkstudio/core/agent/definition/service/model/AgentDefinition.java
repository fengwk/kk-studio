package fun.fengwk.kkstudio.core.agent.definition.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Global Agent definition resource. */
@Data
public class AgentDefinition {

  private Long id;
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
