package fun.fengwk.kkstudio.core.ai.catalog.model.service.model;

import lombok.Data;

import java.time.Instant;

/** Global Agent model resource. */
@Data
public class AgentModel {

  private String providerName;
  private String name;
  private String description;
  private String configJson;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
  private Instant deletedAt;
}
