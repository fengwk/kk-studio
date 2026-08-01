package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** PUT body for {@code /api/ai/catalog/providers/{name}}; {@link #expectedVersion} is required. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentProviderUpdateDTO extends AgentProviderEditablePropertiesDTO {

  /** Required non-negative decimal string; must match the current provider version. */
  private String expectedVersion;
}
