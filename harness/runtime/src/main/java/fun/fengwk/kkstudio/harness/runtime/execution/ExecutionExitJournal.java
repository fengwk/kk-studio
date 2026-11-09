package fun.fengwk.kkstudio.harness.runtime.execution;

import java.util.UUID;

/**
 * 执行日志的最小端口：PREPARED / EXIT_PROOF 两阶段，一个独占目录，一个内核文件锁。
 *
 * <p>{@link #write} 在允许本地 body 之前持久化 PREPARED，在取得退出证据后把同一身份的记录单向推进到 EXIT_PROOF； {@link #delete}
 * 只在确认与结算提交之后调用。读取不得删除或覆盖无法解码、身份不符或类型异常的文件：逐文件失败进入快照，健康记录仍可重放； 目录本身无法枚举或 journal 已关闭时整体失败。
 */
public interface ExecutionExitJournal extends AutoCloseable {

  /**
   * 原子写入并返回已持久化的 canonical 记录。
   *
   * <p>PREPARED 只能首次写入或幂等重放；同一身份的 EXIT_PROOF 把 PREPARED 原子推进到证明阶段。已写入的证明不会退回
   * PREPARED，也不会被另一种证据覆盖：相同证据复用已存记录（含其观测时间），身份、阶段或目标证据冲突时不覆盖。
   */
  ExecutionExitJournalRecord write(ExecutionExitJournalRecord record);

  /** 删除一条已完成的记录；不存在时幂等成功。 */
  void delete(UUID executionId);

  /** 读取健康记录与逐文件失败；目录无法枚举或 journal 已关闭时抛 {@link ExecutionExitJournalException}。 */
  ExecutionExitJournalSnapshot loadAll();

  /**
   * 声明本进程仍有无法证明退出的物理执行：{@link #close()} 不得释放内核目录锁，直到 JVM 退出由操作系统回收。
   *
   * <p>实现必须以进程级强引用保留锁与通道，避免 GC/Cleaner 提前释放；不得注册提前释放锁的 shutdown hook。幂等；journal 已关闭时抛出 {@link
   * ExecutionExitJournalException}，不得假装锁仍被持有。
   */
  void holdUntilProcessExit();

  /** 释放目录锁；已调用 {@link #holdUntilProcessExit()} 时只停止服务并保留锁到 JVM 退出。 */
  @Override
  void close();
}
