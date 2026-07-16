package fun.fengwk.kkstudio.core.environment.service.model;

import java.time.LocalDateTime;
import lombok.Data;

/** Global Environment (Daemon) registry row. */
@Data
public class ToolEnvironment {

  private Long id;
  private String name;
  private String description;
  private String capabilitiesJson;
  private LocalDateTime lastSeenAt;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
