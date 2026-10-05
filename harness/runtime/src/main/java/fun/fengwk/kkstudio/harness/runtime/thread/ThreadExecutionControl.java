package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * Thread 的持久执行控制：只区分可执行与已停止，运行阶段（排队、调用中、等待审批等）由本地工作事实投影。
 *
 * <p>Stop 会把目标 Thread 与完整后代子树置为 {@link #STOPPED}；{@link #STOPPED} 是持久事实，进程重启不会解除。显式的新用户输入或明确
 * 新任务可以把目标 Thread 恢复为 {@link #RUNNABLE}，但不自动重启全部后代。
 */
public enum ThreadExecutionControl {
  /** 可接受的输入会驱动模型 Loop；运行阶段由本地上下文投影。 */
  RUNNABLE,
  /** 已显式停止：不再启动模型执行；迟到的系统结果只固化到历史。 */
  STOPPED;

  /** 是否处于可执行状态。 */
  public boolean isRunnable() {
    return this == RUNNABLE;
  }

  /** 是否处于停止状态。 */
  public boolean isStopped() {
    return this == STOPPED;
  }
}
