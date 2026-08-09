package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CanvasDocumentDO {
  private Long id;
  private String title;
  private Long graphRevision;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
