package fun.fengwk.kkstudio.core.environment.repo.impl.model;

import java.time.LocalDateTime;
import lombok.Data;

/** tool_environment persistence row. */
@Data
public class ToolEnvironmentDO {

  private Long id;
  private String name;
  private String description;
  private String capabilitiesJson;
  private LocalDateTime lastSeenAt;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
