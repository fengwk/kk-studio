package fun.fengwk.kkstudio.core.agent.model.service.model;

import java.time.LocalDateTime;
import lombok.Data;

/** Global Agent model resource. */
@Data
public class AgentModel {

  private Long id;
  private Long providerId;
  private String name;
  private String description;
  private String capabilitiesJson;
  private String configJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
