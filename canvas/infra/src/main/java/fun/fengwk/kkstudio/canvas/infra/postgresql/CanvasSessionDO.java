package fun.fengwk.kkstudio.canvas.infra.postgresql;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_session} 行映射。 */
@Data
public class CanvasSessionDO {
  private UUID sessionId;
  private UUID canvasId;
}
