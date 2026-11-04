package fun.fengwk.kkstudio.canvas.infra.postgresql;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_function_resource_pin} 行映射。 */
@Data
public class CanvasFunctionResourcePinDO {
  private UUID canvasId;
  private UUID nodeId;
  private UUID requestId;
  private String role;
  private UUID resourceId;
}
