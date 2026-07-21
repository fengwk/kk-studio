package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Complete Agent Model create/PUT body with structured executable {@link #config}. */
@Data
public class AgentModelEditablePropertiesDTO {

  private String name;
  private String description;
  private AgentModelConfigDTO config;
}
