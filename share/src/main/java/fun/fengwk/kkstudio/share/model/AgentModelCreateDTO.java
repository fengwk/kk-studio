package fun.fengwk.kkstudio.share.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author fengwk
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {

  private String providerId;
}
