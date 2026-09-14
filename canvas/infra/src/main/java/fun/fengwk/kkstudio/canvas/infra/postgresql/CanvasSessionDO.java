package fun.fengwk.kkstudio.canvas.infra.postgresql;

import lombok.Data;

import java.util.UUID;

/** {@code session_owner} 的 Canvas 归属行映射。 */
@Data
public class CanvasSessionDO {
  private UUID sessionId;
  private UUID canvasId;
}
