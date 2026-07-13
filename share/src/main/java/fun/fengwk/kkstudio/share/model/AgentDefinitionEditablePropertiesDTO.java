package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentDefinitionEditablePropertiesDTO {

  private String name;
  private String description;
  private String systemPrompt;
  private String modelId;
  private String variant;
  private AgentDefinitionConfigDTO config;
}
