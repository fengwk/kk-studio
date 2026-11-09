package fun.fengwk.kkstudio.harness.api.execution;

/**
 * 执行代次的单向退出证据。
 *
 * <p>未确认、Studio body 与本地资源已退出、本地所有者已不可能继续执行、远端正向 ACK、以及确定未启动，五者只向前推进。删除事实行不是退出。
 *
 * <p>{@link #ABANDONED} 是用户显式放弃继续等待确认的独立事实，不冒充任何物理退出；它不阻塞 Join 结算与 Stop 后恢复，但仍可被后续到达的真实退出证据取代。
 */
public enum ExecutionExitState {
  /** 已登记，但还没有正向退出证据。 */
  UNCONFIRMED,
  /** Studio 执行体及其本地资源的 finally 已完成。不能证明远端进程停止。 */
  STUDIO_EXITED,
  /**
   * 该本地执行的所有者进程已经不可能是仍在运行的执行者：由重启后的日志恢复确认。
   *
   * <p>只描述本地执行所有者的物理生命，不表示 finally 成功执行、不表示业务成功，更不表示远端停止。只能用于本地 DRIVER/TOOL。
   */
  OWNER_EXITED,
  /** 远端执行方对这一具体代次给出了正向 ACK。 */
  REMOTE_ACKNOWLEDGED,
  /** 在发送或进入 body 之前已确定不会启动。 */
  NOT_STARTED,
  /** 用户显式放弃确认；只解除收敛阻塞，不代表执行已经退出。 */
  ABANDONED;

  /** 真实退出证据只有 Studio 退出、本地所有者退出、远端 ACK 与确定未启动四种；放弃不是退出。 */
  public boolean confirmed() {
    return this == STUDIO_EXITED
        || this == OWNER_EXITED
        || this == REMOTE_ACKNOWLEDGED
        || this == NOT_STARTED;
  }
}
