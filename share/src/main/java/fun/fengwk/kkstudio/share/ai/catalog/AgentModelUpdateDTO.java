package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT body for {@code /api/models/{id}}; {@link #expectedVersion} is required. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelUpdateDTO extends AgentModelEditablePropertiesDTO {

  /** Required non-negative decimal string; must match the current model version. */
  private String expectedVersion;
}
