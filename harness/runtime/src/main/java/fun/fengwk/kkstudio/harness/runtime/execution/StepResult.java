package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;

import java.util.Objects;

/**
 * 协调器统一返回的有限结果类型。
 *
 * <p>调用方通过 switch 消费结果。Suspended 只引用 durable blocker，不保留调用栈或回调。
 */
public sealed interface StepResult {

  /** 协调器成功推进到下一个 durable 事实。 */
  record Progressed() implements StepResult {}

  /** 协调器在 durable blocker 处主动交还执行权；调用方应释放任何持有的 lease 并退出 activation。 */
  record Suspended(ContinuationRef continuation) implements StepResult {

    public Suspended {
      Objects.requireNonNull(continuation, "continuation");
    }
  }

  /** 协调器在重新检查 runnable/mailbox/debt 后认定无可推进事实，事务性 quiesce。 */
  record Quiescent() implements StepResult {}

  /** 协调器在校验 lease/fencing 时失败，当前已不再拥有该 Thread/Invocation。 */
  record LostOwnership() implements StepResult {}

  /** 协调器在处理过程中检测到不可恢复的失败。 */
  record Failed(Failure failure) implements StepResult {

    public Failed {
      Objects.requireNonNull(failure, "failure");
    }
  }
}
