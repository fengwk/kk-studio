package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/** 受 Thread revision 守护的手动压缩控制请求。 */
public record CompactThreadCommand(UUID threadId, long expectedRevision) {

  public CompactThreadCommand {
    threadId = Objects.requireNonNull(threadId, "threadId");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
