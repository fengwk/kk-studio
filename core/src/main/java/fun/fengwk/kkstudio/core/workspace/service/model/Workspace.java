package fun.fengwk.kkstudio.core.workspace.service.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workspace configuration boundary.
 *
 * @author fengwk
 */
@Data
public class Workspace {

  private Long id;
  private String name;
  private String settingsJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
