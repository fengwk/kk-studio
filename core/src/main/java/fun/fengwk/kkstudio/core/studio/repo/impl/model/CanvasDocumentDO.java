package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CanvasDocumentDO {
  private Long id;
  private Long workspaceId;
  private String title;
  private Integer schemaVersion;
  private Long revision;
  private String lifecycle;
  private String homeViewportJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
