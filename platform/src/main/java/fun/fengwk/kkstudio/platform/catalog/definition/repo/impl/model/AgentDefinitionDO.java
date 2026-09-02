package fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class AgentDefinitionDO {
  private String name;
  private String description;
  private String systemPrompt;
  private String modelProviderName;
  private String modelName;
  private String variant;
  private UUID environmentId;
  private String configJson;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
