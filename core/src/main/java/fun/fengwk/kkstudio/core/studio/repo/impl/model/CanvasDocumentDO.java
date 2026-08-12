package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_document} 行映射。 */
@Data
public class CanvasDocumentDO {
  private UUID id;
  private String title;
  private Long version;
  private UUID threadId;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
