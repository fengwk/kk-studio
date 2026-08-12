package fun.fengwk.kkstudio.harness.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 Stop 请求。
 *
 * <p>{@code stopRequestId} 是客户端生成的幂等键。Runtime 在将其持久化到 TURN_END 之前会按 operation 与 Thread
 * 限定其作用域，因此同一外部 id 在两个 Thread 上不会发生别名冲突。
 */
public record StopCommand(UUID threadId, UUID stopRequestId, long expectedRevision) {

  public StopCommand {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
