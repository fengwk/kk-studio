package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.execution.Failure;

import java.util.Objects;

/**
 * createModelInvocationAndRelease 事务的有限结果。
 *
 * <p>{@link Created} 携带新 ModelInvocation 的 {@link ExecutionTarget}，其 kind 必须为 {@link
 * ExecutionTargetKind#MODEL_INVOCATION}，target 由事务在内部分配。{@link LostOwnership} 表示 fencing 失败。{@link
 * Failed} 表示 typed 事务失败（如 plan 校验未通过），调用方经 best-effort release 后返回 {@link
 * fun.fengwk.kkstudio.harness.kernel.execution.StepResult.Failed}。意外 RuntimeException 经 best-effort
 * release 后重新抛出。
 */
public sealed interface ModelCreationOutcome {

  /** ModelInvocation 创建成功；{@code target} 即新 invocation 的 ExecutionTarget，lease 已在事务内释放。 */
  record Created(ExecutionTarget target) implements ModelCreationOutcome {
    public Created {
      Objects.requireNonNull(target, "target");
      if (target.kind() != ExecutionTargetKind.MODEL_INVOCATION) {
        throw new IllegalArgumentException(
            "created target must be MODEL_INVOCATION, got " + target.kind());
      }
    }
  }

  /** fencing 校验失败。 */
  record LostOwnership() implements ModelCreationOutcome {}

  /** typed 事务失败。 */
  record Failed(Failure failure) implements ModelCreationOutcome {
    public Failed {
      Objects.requireNonNull(failure, "failure");
    }
  }
}
