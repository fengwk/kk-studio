package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 与 Harness Session 的一对一归属边。
 *
 * <p>{@code sessionId} 是主键，因此一个 Session 至多被一个 Canvas 持有，并与 {@code chat_session} 在应用创建事务内互斥 （锁定
 * harness_session 后检查另一张归属表）。数据库 FK 只负责 Session 与 owner 的存在性，不引入多态 owner 表。
 */
public record CanvasSession(UUID sessionId, UUID canvasId) {

  public CanvasSession {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(canvasId, "canvasId");
  }
}
