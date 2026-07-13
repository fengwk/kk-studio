package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workspace API representation.
 *
 * @author fengwk
 */
@Data
public class WorkspaceDTO {

  private String id;
  private String name;
  private String settingsJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
