package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 MOVE_HEAD 请求：在精确 {@code expectedRevision} 乐观 CAS 下将 Thread cursor 重定位到 {@code
 * targetEntryId}。{@code expectedRevision} 必须非负。
 */
public record MoveHeadCommand(UUID threadId, UUID targetEntryId, long expectedRevision) {

  public MoveHeadCommand {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(targetEntryId, "targetEntryId");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
