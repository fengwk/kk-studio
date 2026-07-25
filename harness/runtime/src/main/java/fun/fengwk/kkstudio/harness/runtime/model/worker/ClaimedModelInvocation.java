package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;

import java.util.Objects;

/**
 * 一次已持有 durable worker lease 的 ModelInvocation。
 *
 * <p>{@code recoveredLease} 表示 claim 接管了一个已过期的 RUNNING lease。由于 Provider terminal 无法被确认，ModelWorker
 * 必须保守地将这种调用落为 UNKNOWN，而不是再次发起外部请求。
 */
public record ClaimedModelInvocation(ModelInvocation invocation, boolean recoveredLease) {

  public ClaimedModelInvocation {
    invocation = Objects.requireNonNull(invocation, "invocation");
    if (invocation.status() != InvocationStatus.RUNNING || invocation.workerLease() == null) {
      throw new IllegalArgumentException("claimed invocation must be RUNNING with a worker lease");
    }
  }
}
