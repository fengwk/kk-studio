package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;

/**
 * 运行时状态与委派结算收敛的最小共享事实评估器。
 *
 * <p>评估运行时三态（{@link ThreadRuntimeStatus}）与普通委派收敛静止状态（{@link #isQuiescent}）。 本地活跃执行与 open compaction
 * 均属在途工作；待启动输入需求仅在 RUNNABLE 下表达为 RUNNING； 本地让出后若存在未完成直接子 Join 则判定为 WAITING；无在途与待启动工作且无未完成子任务时为空闲
 * IDLE。
 */
public final class ThreadActivityEvaluator {

  private ThreadActivityEvaluator() {}

  /** 评估 Thread 当前运行状态（RUNNING / WAITING / IDLE）。 */
  public static ThreadRuntimeStatus evaluate(
      ThreadExecutionControl control,
      ThreadContext context,
      boolean hasOpenCompactionTurn,
      boolean hasInputDemand,
      int incompleteChildJoins,
      boolean hasUnconfirmedExecution) {
    Objects.requireNonNull(control, "control");
    Objects.requireNonNull(context, "context");
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }

    // 本地在途活跃执行，以及已登记但尚未确认退出的具体代次，都不因 STOPPED 虚构外部退出事实。
    if (!(context instanceof ThreadContext.IdleOrHistorical)
        || hasOpenCompactionTurn
        || hasUnconfirmedExecution) {
      return ThreadRuntimeStatus.RUNNING;
    }

    // RUNNABLE 下输入需求表达为待启动 RUNNING；STOPPED 保留通知但不自启
    if (hasInputDemand && control == ThreadExecutionControl.RUNNABLE) {
      return ThreadRuntimeStatus.RUNNING;
    }

    // 本地已让出执行但仍有未完成的直接子 Join
    if (incompleteChildJoins > 0) {
      return ThreadRuntimeStatus.WAITING;
    }

    return ThreadRuntimeStatus.IDLE;
  }

  /**
   * 判断 Thread 是否处于普通终止的收敛静止状态（可向父结算 Join）。
   *
   * <p>与快照状态共用控制敏感判据：本地必须空闲，且没有未确认执行或未完成直接子 Join。RUNNABLE 的待启动输入仍阻挡结算；STOPPED
   * 保留通知但不自启，因此不把这些通知当成继续执行。
   */
  public static boolean isQuiescent(
      ThreadExecutionControl control,
      ThreadContext context,
      boolean hasOpenCompactionTurn,
      boolean hasInputDemand,
      int incompleteChildJoins,
      boolean hasUnconfirmedExecution) {
    Objects.requireNonNull(control, "control");
    Objects.requireNonNull(context, "context");
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
    boolean blockedInput = hasInputDemand && control == ThreadExecutionControl.RUNNABLE;
    return (context instanceof ThreadContext.IdleOrHistorical)
        && !hasOpenCompactionTurn
        && !blockedInput
        && incompleteChildJoins == 0
        && !hasUnconfirmedExecution;
  }
}
