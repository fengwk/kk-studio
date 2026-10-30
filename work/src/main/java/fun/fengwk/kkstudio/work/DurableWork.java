package fun.fengwk.kkstudio.work;

import fun.fengwk.kkstudio.common.work.WorkState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code durable_work} 一行的只读快照：target 身份加共享调度状态。 */
public record DurableWork(UUID targetId, WorkState state) {

  public DurableWork {
    targetId = Objects.requireNonNull(targetId, "targetId");
    state = Objects.requireNonNull(state, "state");
  }

  public Instant availableAt() {
    return state.availableAt();
  }

  public long wakeVersion() {
    return state.wakeVersion();
  }

  public String leaseToken() {
    return state.leaseToken();
  }

  public Instant leaseUntil() {
    return state.leaseUntil();
  }

  public UUID requiredEnvironmentId() {
    return state.requiredEnvironmentId();
  }
}
