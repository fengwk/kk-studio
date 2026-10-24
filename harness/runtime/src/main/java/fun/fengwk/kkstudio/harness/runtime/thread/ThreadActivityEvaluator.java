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
   * <p>与快照状态共用判定事实：必须处于 IdleOrHistorical、无 open compaction、无未消费输入需求、且无未完成直接子 Join。
   */
  public static boolean isQuiescent(
      ThreadContext context,
      boolean hasOpenCompactionTurn,
      boolean hasInputDemand,
      int incompleteChildJoins,
      boolean hasUnconfirmedExecution) {
    Objects.requireNonNull(context, "context");
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
    return (context instanceof ThreadContext.IdleOrHistorical)
        && !hasOpenCompactionTurn
        && !hasInputDemand
        && incompleteChildJoins == 0
        && !hasUnconfirmedExecution;
  }
}
