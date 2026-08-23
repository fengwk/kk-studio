package fun.fengwk.kkstudio.platform.studio.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code canvas_command_dedup} 行映射。 */
@Data
public class CanvasCommandDedupDO {
  private UUID canvasId;
  private UUID commandId;
  private String requestHash;
}
