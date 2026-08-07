package fun.fengwk.kkstudio.core.ai.catalog.definition.service.model;

import lombok.Data;

import java.time.Instant;

/** Global Agent definition resource. */
@Data
public class AgentDefinition {

  private String name;
  private String description;
  private String systemPrompt;
  private String modelProviderName;
  private String modelName;
  private String variant;
  private String configJson;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
