package fun.fengwk.kkstudio.core.workspace.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class WorkspaceDO {

  private Long id;
  private String name;
  private String settingsJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
