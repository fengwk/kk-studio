package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_session} 行映射。 */
@Data
public class CanvasSessionDO {
  private UUID sessionId;
  private UUID canvasId;
}
