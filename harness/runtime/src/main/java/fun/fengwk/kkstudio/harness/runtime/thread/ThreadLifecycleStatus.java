package fun.fengwk.kkstudio.harness.runtime.thread;

/** 持久化的递归生命周期；显式停止由 head STOPPED 事实判定，而不是第四种状态。 */
public enum ThreadLifecycleStatus {
  /** 本地无工作且所有永久直接孩子 IDLE；未交付 join 不影响判定。 */
  IDLE,
  /** 本地存在命令、适用调用或续接义务；细分运行阶段由 Invocation 投影。 */
  ACTIVE,
  /** 本地已完成，但至少一个永久直接孩子非 IDLE。 */
  WAITING_CHILDREN;

  /** 是否处于空闲状态。 */
  public boolean isIdle() {
    return this == IDLE;
  }

  /** 是否处于活跃状态。 */
  public boolean isActive() {
    return this == ACTIVE;
  }

  /** 是否处于等待子线程状态。 */
  public boolean isWaitingChildren() {
    return this == WAITING_CHILDREN;
  }
}
