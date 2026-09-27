package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * Thread 的持久化递归生命周期状态（Durable Recursive Lifecycle Status）。
 *
 * <p>维护 Thread 自身及其执行子树的递归生命周期。与细粒度、高频流式推进的调用层上下文投影 （{@link ThreadRuntimeStatus}）不同，{@link
 * ThreadLifecycleStatus} 是持久化在存储层 （{@code harness_thread.status}）的权威结构状态，遵循以下核心语义：
 *
 * <ul>
 *   <li><b>递归判定</b>：父 Thread 的空闲性取决于自身及所有永久直接子 Thread 的状态；
 *   <li><b>单调版本演进</b>：生命周期状态变更同事务推进 Thread {@code version}，并作为 join 匹配和通知唤醒的稳定依据；
 *   <li><b>停止级联</b>：停止状态沿执行树向下传播，且停止的父 Thread 不自动唤醒。
 * </ul>
 */
public enum ThreadLifecycleStatus {

  /**
   * 空闲状态。
   *
   * <p>线程及其递归执行子树处于完全静止状态：
   *
   * <ul>
   *   <li>本地无待执行的 QUEUED Command；
   *   <li>本地无适用或处于执行中的模型/工具 Invocation；
   *   <li>本地无未结清的 continuation obligation（如模型续跑或工具继续义务）；
   *   <li>该 Thread 的所有永久直接孩子 Thread（direct children）皆处于 {@link #IDLE} 状态。
   * </ul>
   *
   * <p>空闲状态与是否存在未交付给父 Thread 的 join 结果无关。处于 {@link #IDLE} 状态的 Thread 可以被 等待的 pending join
   * 匹配，并在接受新的输入时重新进入 {@link #ACTIVE}。
   */
  IDLE,

  /**
   * 活跃运行状态。
   *
   * <p>线程自身或其递归子树正在执行或等待执行：
   *
   * <ul>
   *   <li>本地存在未处理的 QUEUED Command；
   *   <li>或存在就绪、分派或运行中的模型/工具 Invocation；
   *   <li>或存在待推进的 continuation 义务；
   *   <li>或至少有一个直接孩子 Thread 处于非空闲状态（{@link #ACTIVE} 或非静止状态）。
   * </ul>
   *
   * <p>运行中的模型与工具细分阶段（如排队、分派、流式生成、审批等待等）由 Invocation 自身投影， 不在此重复物化。从非空闲首次回到 {@link #IDLE}
   * 时，必须在同一事务内完成版本推进与等待 join 匹配。
   */
  ACTIVE,

  /**
   * 显式停止状态。
   *
   * <p>线程已被显式停止（如收到 Stop 指令）：
   *
   * <ul>
   *   <li>停止信号递归向下传播至所有执行后代，无论是否存在未交付的 join；
   *   <li>所有未完成的 Command 与 Invocation 被取消或终止，迟到的模型/工具结果必须被现有 fence 拒绝；
   *   <li>显式停止的父 Thread 不会自动被子 Thread 的完成所唤醒；若子执行完成，结果 receipt 先行保存， 直到父 Thread 显式接受新输入时才原子交付；
   *   <li>处于停止状态的 Thread 必须通过显式的新指令或恢复动作才能重新激活。
   * </ul>
   */
  STOPPED;

  /** 是否处于空闲状态。 */
  public boolean isIdle() {
    return this == IDLE;
  }

  /** 是否处于活跃状态。 */
  public boolean isActive() {
    return this == ACTIVE;
  }

  /** 是否处于显式停止状态。 */
  public boolean isStopped() {
    return this == STOPPED;
  }
}
