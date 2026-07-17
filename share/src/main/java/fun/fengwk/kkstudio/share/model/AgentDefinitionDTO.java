package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Public global Agent definition representation. */
@Data
public class AgentDefinitionDTO {

  private String id;
  private String name;
  private String description;
  private String systemPrompt;
  private String modelId;
  private String variant;
  private AgentDefinitionConfigDTO config;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
