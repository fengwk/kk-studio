package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.util.Objects;

/**
 * createModelInvocationAndRelease 事务的有限结果。
 *
 * <p>{@link Created} 携带新 ModelInvocation 的 {@link ExecutionTarget}，其 kind 必须为 {@link
 * ExecutionTargetKind#MODEL_INVOCATION}，target 由事务在内部分配。{@link PlanningFailureApplied} 表示事务按最新 live
 * definitions 规划失败并已追加 AssistantError barrier。{@link LostOwnership} 表示 fencing 失败。意外
 * RuntimeException 经 best-effort release 后重新抛出。
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

  /** 最新规划失败，AssistantError barrier 已写入；Thread lease 仍由当前 Reconciler 持有。 */
  record PlanningFailureApplied() implements ModelCreationOutcome {}

  /** fencing 校验失败。 */
  record LostOwnership() implements ModelCreationOutcome {}
}
