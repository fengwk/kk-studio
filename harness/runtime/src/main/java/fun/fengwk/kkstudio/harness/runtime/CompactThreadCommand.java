package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/** 受 Thread version 守护的手动压缩控制请求。 */
public record CompactThreadCommand(UUID threadId, long expectedVersion) {

  public CompactThreadCommand {
    threadId = Objects.requireNonNull(threadId, "threadId");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
