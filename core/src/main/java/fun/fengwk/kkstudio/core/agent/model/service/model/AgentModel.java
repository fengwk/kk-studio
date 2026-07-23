package fun.fengwk.kkstudio.core.agent.model.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Global Agent model resource. */
@Data
public class AgentModel {

  private Long id;
  private Long providerId;
  private String name;
  private String description;

  /** Persisted structured configuration, encoded into the {@code config} JSONB column. */
  private String configJson;

  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
