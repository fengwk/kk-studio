package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT body for {@code /api/agents/{id}}; {@link #expectedVersion} is required. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDefinitionUpdateDTO extends AgentDefinitionEditablePropertiesDTO {

  /** Required non-negative decimal string; must match the current agent version. */
  private String expectedVersion;
}
