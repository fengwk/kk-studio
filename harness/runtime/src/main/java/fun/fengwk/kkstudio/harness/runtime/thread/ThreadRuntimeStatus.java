package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Objects;

/**
 * Thread 活动三态（{@link #RUNNING} / {@link #WAITING} / {@link #IDLE}）。
 *
 * <p>与持久执行控制 {@code ThreadExecutionControl}（{@code RUNNABLE} / {@code STOPPED}）正交，STOP 绝不直接覆盖本状态。
 *
 * <ul>
 *   <li>{@link #RUNNING}：本地流程未结束，含 queued / 续作 / 待 apply / model / tool / 审批问卷 / 环境 / owner
 *       compaction。
 *   <li>{@link #WAITING}：本地已让出执行，且直接 NOTIFY_PARENT 子任务尚未返回（不计自己入向 join）。
 *   <li>{@link #IDLE}：无本地义务或直接异步结果。
 * </ul>
 */
public enum ThreadRuntimeStatus {
  /** 本地流程未结束，含 queued / 续作 / 待 apply / model / tool / 审批问卷 / 环境 / owner compaction。 */
  RUNNING,

  /** 本地已让出执行，且直接 NOTIFY_PARENT 子任务尚未返回（不计自己入向 join）。 */
  WAITING,

  /** 当前 Thread 没有活跃调用，没有未完成的直接子任务，也没有未接纳输入或续作义务。 */
  IDLE;

  /**
   * 从已分类的 Thread context 派生本地阶段。
   *
   * <p>仅作本地 context 投影：{@link ThreadContext.IdleOrHistorical} 投影为 {@link
   * #IDLE}，其余本地活跃阶段（ContinuationDue / ModelActive / ModelTerminalPending / ToolActive /
   * ToolTerminalPending）均投影为 {@link #RUNNING}。 WAITING 状态包含直接子委派的异步等待事实，由包含直接子 Join 计数的 {@code
   * ThreadSnapshot} 结合本方法结果共同判定。
   */
  public static ThreadRuntimeStatus from(ThreadContext context) {
    Objects.requireNonNull(context, "context");
    return switch (context) {
      case ThreadContext.IdleOrHistorical ignored -> IDLE;
      case ThreadContext.ContinuationDue ignored -> RUNNING;
      case ThreadContext.ModelActive ignored -> RUNNING;
      case ThreadContext.ModelTerminalPending ignored -> RUNNING;
      case ThreadContext.ToolActive ignored -> RUNNING;
      case ThreadContext.ToolTerminalPending ignored -> RUNNING;
    };
  }
}
