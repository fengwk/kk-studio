package fun.fengwk.kkstudio.harness.kernel.execution;

import java.util.Objects;

/**
 * 不可变的执行主体引用：{@code (kind, id)} 对。
 *
 * <p>用于 ActivationNotifier 等端口以及 ContinuationRef 中的 owner/blocker 引用；不持有任何线程、 Future 或回调语义。
 */
public record ExecutionTarget(ExecutionTargetKind kind, long id) {

  public ExecutionTarget {
    kind = Objects.requireNonNull(kind, "kind");
    if (id <= 0) {
      throw new IllegalArgumentException("execution target id must be positive");
    }
  }
}
