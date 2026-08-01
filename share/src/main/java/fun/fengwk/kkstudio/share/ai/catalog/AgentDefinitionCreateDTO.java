package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author fengwk
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDefinitionCreateDTO extends AgentDefinitionEditablePropertiesDTO {

  private String name;
  private String model;
}
