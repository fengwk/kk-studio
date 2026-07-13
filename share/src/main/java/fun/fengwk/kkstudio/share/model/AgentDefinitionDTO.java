package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class AgentDefinitionDTO {

  private String id;
  private String workspaceId;
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
