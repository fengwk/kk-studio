package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CanvasUploadDO {
  private Long id;
  private Long canvasId;
  private String kind;
  private String filename;
  private String declaredMediaType;
  private Long declaredSize;
  private OffsetDateTime expiresAt;
  private OffsetDateTime createdAt;
}
