package fun.fengwk.kkstudio.harness.runtime.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用两阶段日志：与文件实现相同阶段语义，只保存在内存中，并可观测目录锁保留请求。 */
public final class MemoryExecutionExitJournal implements ExecutionExitJournal {

  private final Map<UUID, ExecutionExitJournalRecord> records = new ConcurrentHashMap<>();
  private final AtomicInteger holds = new AtomicInteger();
  private boolean closed;

  @Override
  public synchronized ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
    ensureOpen();
    ExecutionExitJournalRecord existing = records.get(record.executionId());
    if (existing == null) {
      records.put(record.executionId(), record);
      return record;
    }
    if (!existing.sameIdentity(record)) {
      throw new ExecutionExitJournalConflictException(
          ExecutionExitJournalConflictException.Conflict.IDENTITY,
          "execution exit journal identity conflict for " + record.executionId());
    }
    if (existing.stage() == ExecutionExitJournalStage.EXIT_PROOF) {
      if (record.stage() == ExecutionExitJournalStage.PREPARED) {
        throw new ExecutionExitJournalConflictException(
            ExecutionExitJournalConflictException.Conflict.STAGE,
            "execution exit journal stage cannot regress for " + record.executionId());
      }
      if (existing.targetState() != record.targetState()) {
        throw new ExecutionExitJournalConflictException(
            ExecutionExitJournalConflictException.Conflict.TARGET,
            "execution exit journal target conflict for " + record.executionId());
      }
      return existing;
    }
    if (record.stage() == ExecutionExitJournalStage.PREPARED) {
      return existing;
    }
    records.put(record.executionId(), record);
    return record;
  }

  @Override
  public synchronized void delete(UUID executionId) {
    ensureOpen();
    records.remove(executionId);
  }

  @Override
  public synchronized ExecutionExitJournalSnapshot loadAll() {
    ensureOpen();
    return new ExecutionExitJournalSnapshot(List.copyOf(records.values()), List.of());
  }

  @Override
  public synchronized void holdUntilProcessExit() {
    holds.incrementAndGet();
  }

  @Override
  public synchronized void close() {
    closed = true;
  }

  /** 目录锁保留请求次数；用于断言无法证明退出时不会释放锁。 */
  public int holdRequests() {
    return holds.get();
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("journal is closed");
    }
  }
}
