package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 与 Harness Session 的一对一归属边。
 *
 * <p>{@code session_owner.session_id} 是主键，因此一个 Session 全局至多属于一个产品 owner。
 */
public record CanvasSession(UUID sessionId, UUID canvasId) {

  public CanvasSession {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(canvasId, "canvasId");
  }
}
