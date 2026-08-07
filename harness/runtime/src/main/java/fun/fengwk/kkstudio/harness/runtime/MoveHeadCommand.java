package fun.fengwk.kkstudio.harness.runtime;

/**
 * 不可变的 MOVE_HEAD 请求：在精确 {@code expectedRevision} 乐观 CAS 下将 Thread cursor 重定位到 {@code
 * targetEntryId}。{@code expectedRevision} 必须非负。
 */
public record MoveHeadCommand(long threadId, long targetEntryId, long expectedRevision) {

  public MoveHeadCommand {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (targetEntryId <= 0) {
      throw new IllegalArgumentException("targetEntryId must be positive");
    }
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
