package fun.fengwk.kkstudio.core.agent.model.service.model;

import lombok.Data;

import java.time.Instant;

/** Global Agent model resource. */
@Data
public class AgentModel {

  private Long id;
  private Long providerId;
  private String name;
  private String description;
  private String configJson;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
