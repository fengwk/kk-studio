package fun.fengwk.kkstudio.harness.runtime.execution;

/** 执行日志的明确阶段。日志只在这两个阶段之间单向推进，不记录业务结果。 */
public enum ExecutionExitJournalStage {
  /**
   * 本进程已持久声明持有该执行，但数据库登记可能尚未提交，body 尚未开始。
   *
   * <p>只允许在允许本地 DRIVER/TOOL 启动前写入；重启后该记录代表上一进程可能已在此窗口崩溃。
   */
  PREPARED,
  /** 已取得确切退出证据，数据库确认或日志删除尚未完成。 */
  EXIT_PROOF
}
