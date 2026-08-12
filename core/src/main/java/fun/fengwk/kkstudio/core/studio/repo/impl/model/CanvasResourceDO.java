package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_resource} 行映射。 */
@Data
public class CanvasResourceDO {
  private UUID id;
  private UUID canvasId;
  private UUID ownerNodeId;
  private Integer resourceIndex;
  private UUID blobId;
  private String name;
  private String textContent;
  private OffsetDateTime createdAt;
}
