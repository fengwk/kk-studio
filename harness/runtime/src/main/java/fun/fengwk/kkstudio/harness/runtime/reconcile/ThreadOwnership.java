package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.util.Objects;

/**
 * Reconcile mutation 的 fencing identity。
 *
 * <p>每次写入都必须携带当前 activation 的 {@code threadId + executionEpoch + processorToken}；PostgreSQL adapter
 * 在同一事务中校验三者一致才能提交。Lease 到期时间仍由 {@link
 * fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread#processorLease()} 表达。
 *
 * <p>不可变且不含续租时间或回调；所有权失效应由调用方重新 {@code claim} 而非重用过期 ownership。
 */
public record ThreadOwnership(long threadId, long executionEpoch, String processorToken) {

  public ThreadOwnership {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (executionEpoch < 0) {
      throw new IllegalArgumentException("executionEpoch must not be negative");
    }
    processorToken = requireNonBlank(processorToken, "processorToken");
  }

  /**
   * 对应 {@link ExecutionTargetKind#THREAD} 的 durable owner 引用，可与 ModelInvocation target 组合为 {@code
   * ContinuationRef}。
   */
  public ExecutionTarget threadTarget() {
    return new ExecutionTarget(ExecutionTargetKind.THREAD, threadId);
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
