package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** Public global Agent definition representation. */
@Data
public class AgentDefinitionDTO {

  private String name;
  private String description;
  private String systemPrompt;
  private String model;
  private String variant;
  private AgentDefinitionConfigDTO config;

  /** Non-negative decimal string version; clients must echo on every update. */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
