package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** Complete Agent Model create/PUT body with structured executable {@link #config}. */
@Data
public class AgentModelEditablePropertiesDTO {

  private String description;
  private AgentModelConfigDTO config;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Model field: " + name);
  }
}
