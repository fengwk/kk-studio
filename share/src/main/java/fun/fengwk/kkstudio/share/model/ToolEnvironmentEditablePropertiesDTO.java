package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * Editable subset of a {@link ToolEnvironmentDTO}. Capability and last-seen facts are managed
 * exclusively by the daemon-facing application service and intentionally absent here.
 */
@Data
public class ToolEnvironmentEditablePropertiesDTO {

  private String name;
  private String description;
}
