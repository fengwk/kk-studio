package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;

/** Stop 网络幂等回执；被取消的 Input 通过 cancelledByStopId 关联。 */
public record ThreadStop(long id, long threadId, String clientRequestId, Instant createdAt) {
  public ThreadStop {
    if (id <= 0 || threadId <= 0) {
      throw new IllegalArgumentException("stop and thread ids must be positive");
    }
    clientRequestId = Objects.requireNonNull(clientRequestId, "clientRequestId");
    if (clientRequestId.isBlank()) {
      throw new IllegalArgumentException("clientRequestId must not be blank");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }
}
