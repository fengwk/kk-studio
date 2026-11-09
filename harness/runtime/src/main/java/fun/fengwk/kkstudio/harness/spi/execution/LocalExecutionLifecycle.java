package fun.fengwk.kkstudio.harness.spi.execution;

import java.time.Duration;

/**
 * 本地执行的物理生命周期端口：唯一负责执行开始前的持久登记、物理句柄、取消请求、退出证明、当前进程排空与上一进程日志的恢复。
 *
 * <p>平台只通过本端口编排物理生命周期，不访问 Driver 私有表。Work 准入与 lease 所有权仍归 {@link
 * fun.fengwk.kkstudio.harness.spi.work.ClaimAdmissionGuard}；取消、失权与从准入表移除都不释放物理句柄。
 */
public interface LocalExecutionLifecycle extends AutoCloseable {

  /**
   * 冻结上一进程遗留的日志集合并完成首轮恢复：先重放 EXIT_PROOF，再按 Thread 锁与权威数据库状态对账旧 PREPARED。
   *
   * <p>必须在允许任何新执行登记之前调用且只调用一次；日志目录无法枚举时抛出并拒绝继续启动。
   */
  LocalExecutionRecovery start();

  /** 是否接受新执行登记：首轮恢复已完成且尚未开始排空。 */
  boolean accepting();

  /**
   * 取得物理句柄：先原子写入并 fsync PREPARED，再执行数据库登记。
   *
   * <p>登记回调返回 {@code null}（提交成功但没有登记）或抛出异常（结果不明）时都不返回可执行句柄：{@link
   * LocalExecutionHandle#registered()} 为 {@code false}，调用方必须先释放已取得资源，再以 {@link
   * LocalExecutionHandle#provenExit} {@code NOT_STARTED} 结束句柄。PREPARED 写入失败时不登记、不执行并抛出。
   */
  LocalExecutionHandle begin(
      LocalExecutionIdentity identity, LocalExecutionRegistration registration);

  /**
   * 停止新登记并在 deadline 前等待全部物理句柄结束；超时后请求取消剩余本地 body，并返回仍未结束的执行。
   *
   * <p>超时或存在无法证明的退出时，日志目录锁保留到 JVM 退出。
   */
  LocalExecutionDrain drain(Duration timeout);

  /** 停止新登记；仍有物理执行或未证明退出时保留日志目录锁到 JVM 退出。 */
  @Override
  void close();
}
