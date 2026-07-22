package fun.fengwk.kkstudio.harness.kernel.continuation;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;

import java.util.Objects;

/**
 * 不可变的 durable continuation 引用：标识 owner 与 blocker。
 *
 * <p>仅用于在 {@link fun.fengwk.kkstudio.harness.kernel.execution.StepResult.Suspended} 中表达 durable
 * blocker 的归属；不保留任何 Future、回调或栈状态。
 */
public record ContinuationRef(ExecutionTarget owner, ExecutionTarget blocker) {

  public ContinuationRef {
    owner = Objects.requireNonNull(owner, "owner");
    blocker = Objects.requireNonNull(blocker, "blocker");
  }
}
