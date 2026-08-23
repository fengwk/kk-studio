package fun.fengwk.kkstudio.canvas.infra.postgresql;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_document} 行映射。 */
@Data
public class CanvasDocumentDO {
  private UUID id;
  private String title;
  private Long version;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
