package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Complete Agent Definition create/PUT body; nullable text fields explicitly clear their values.
 */
@Data
public class AgentDefinitionEditablePropertiesDTO {

  private String description;
  private String systemPrompt;
  private String variant;
  private AgentDefinitionConfigDTO config;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Definition field: " + name);
  }
}
