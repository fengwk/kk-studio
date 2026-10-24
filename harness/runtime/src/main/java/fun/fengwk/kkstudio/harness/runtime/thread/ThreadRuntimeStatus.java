package fun.fengwk.kkstudio.harness.runtime.thread;

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
  IDLE
}
