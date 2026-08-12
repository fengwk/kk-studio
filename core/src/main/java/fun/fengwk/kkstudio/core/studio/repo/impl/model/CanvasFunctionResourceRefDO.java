package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_function_resource_ref} 行映射。 */
@Data
public class CanvasFunctionResourceRefDO {
  private UUID canvasId;
  private UUID nodeId;
  private UUID requestId;
  private String role;
  private UUID resourceId;
}
