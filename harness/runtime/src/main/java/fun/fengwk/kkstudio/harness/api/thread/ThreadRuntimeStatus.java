package fun.fengwk.kkstudio.harness.api.thread;

/**
 * Thread 活动四态（{@link #RUNNING} / {@link #EXIT_PENDING} / {@link #WAITING} / {@link #IDLE}）。
 *
 * <p>与持久执行控制 {@code ThreadExecutionControl}（{@code RUNNABLE} / {@code STOPPED}）正交，STOP 绝不直接覆盖本状态。
 *
 * <ul>
 *   <li>{@link #RUNNING}：本地流程未结束，含活跃 Driver/Tool 与终态物化、queued / 续作 / 审批问卷 / 环境 / owner compaction。
 *   <li>{@link #EXIT_PENDING}：没有上述活跃流程，但仍有已登记且未确认退出的执行代次在阻塞收敛或待启动动作。
 *   <li>{@link #WAITING}：本地已让出执行，且直接 NOTIFY_PARENT 子任务尚未返回（不计自己入向 join）。
 *   <li>{@link #IDLE}：无本地义务或直接异步结果。
 * </ul>
 *
 * <p>{@link #EXIT_PENDING} 不因 {@code RUNNABLE} / {@code STOPPED} 改变，不等同 {@link #IDLE}，也不放宽 Join
 * 或输入门禁。
 */
public enum ThreadRuntimeStatus {
  /** 本地流程未结束，含活跃 Driver/Tool、终态物化、queued / 续作 / 审批问卷 / 环境 / owner compaction。 */
  RUNNING,

  /** 没有活跃流程，但未确认退出的执行代次仍在阻塞收敛或待启动动作；与 executionControl 正交。 */
  EXIT_PENDING,

  /** 本地已让出执行，且直接 NOTIFY_PARENT 子任务尚未返回（不计自己入向 join）。 */
  WAITING,

  /** 当前 Thread 没有活跃调用，没有未确认退出，没有未完成的直接子任务，也没有未接纳输入或续作义务。 */
  IDLE
}
