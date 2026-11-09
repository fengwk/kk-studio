package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;

import java.util.Objects;

/**
 * 普通委派收敛静止状态的最小共享事实评估器。
 *
 * <p>对外四态运行状态（{@code ThreadRuntimeStatus}）的纯值投影由公共 ThreadSnapshot 的 {@code evaluateRuntimeStatus}
 * 统一给出；本类只回答「本地是否已收敛为可向父结算的静止状态」，与快照状态共用同一批客观事实。本地活跃执行与 open compaction 均属在途工作；待启动输入需求仅在 RUNNABLE
 * 下阻挡收敛；未完成直接子 Join 与未确认执行同样阻挡结算。
 */
public final class ThreadActivityEvaluator {

  private ThreadActivityEvaluator() {}

  /**
   * 判断 Thread 是否处于普通终止的收敛静止状态（可向父结算 Join）。
   *
   * <p>与快照状态共用控制敏感判据：本地必须空闲，且没有未确认执行或未完成直接子 Join。RUNNABLE 的待启动输入仍阻挡结算；STOPPED
   * 保留通知但不自启，因此不把这些通知当成继续执行。
   */
  public static boolean isQuiescent(
      ThreadExecutionControl control,
      boolean hasLiveContext,
      boolean hasCompactionObligation,
      boolean hasInputDemand,
      int incompleteChildJoins,
      boolean hasUnconfirmedExecution) {
    Objects.requireNonNull(control, "control");
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
    boolean blockedInput = hasInputDemand && control == ThreadExecutionControl.RUNNABLE;
    return !hasLiveContext
        && !hasCompactionObligation
        && !blockedInput
        && incompleteChildJoins == 0
        && !hasUnconfirmedExecution;
  }
}
