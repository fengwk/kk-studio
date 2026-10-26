package fun.fengwk.kkstudio.harness.runtime.execution;

import java.util.List;
import java.util.UUID;

/**
 * 退出确认 write-ahead journal 的最小端口。
 *
 * <p>{@link #write} 必须在数据库确认事务提交之前完成并持久化；{@link #delete} 只在确认与结算提交之后调用。读取失败或记录身份冲突必须
 * fail-closed，不得删除原记录。
 */
public interface ExecutionExitJournal extends AutoCloseable {

  /** 原子写入一条记录并返回已持久化的 canonical 记录。相同执行身份与目标证据复用已存记录（含其观测时间）；不同身份或目标证据冲突，不覆盖。 */
  ExecutionExitJournalRecord write(ExecutionExitJournalRecord record);

  /** 删除一条已完成的记录；不存在时幂等成功。 */
  void delete(UUID executionId);

  /** 读取全部待重放记录；损坏或身份冲突必须抛 {@link ExecutionExitJournalException}。 */
  List<ExecutionExitJournalRecord> loadAll();

  @Override
  void close();
}
