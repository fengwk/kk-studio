package fun.fengwk.kkstudio.core.agent.model.repo.impl.model;

import java.time.LocalDateTime;
import lombok.Data;

/** Model persistence row. */
@Data
public class AgentModelDO {

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
