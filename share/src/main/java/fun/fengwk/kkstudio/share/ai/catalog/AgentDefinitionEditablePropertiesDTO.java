package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/**
 * Complete Agent Definition create/PUT body; nullable text fields explicitly clear their values.
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
