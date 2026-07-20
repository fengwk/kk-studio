package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * Editable portion of an Agent model. Only the structured {@link #config} (and a free-form {@code
 * description} / {@code name}) are accepted; the legacy {@code capabilitiesJson} / {@code
 * configJson} strings are no longer part of the public contract.
 */
@Data
public class AgentModelEditablePropertiesDTO {

  private String name;
  private String description;
  private AgentModelConfigDTO config;
}
