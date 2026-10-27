package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 测试用退出确认器：journal 只保存在内存中，语义与文件实现一致。 */
public final class TestExecutionExitConfirmer {

  private TestExecutionExitConfirmer() {}

  public static ExecutionExitConfirmer create(HarnessStore store, Clock clock) {
    return new ExecutionExitConfirmer(store, new MemoryJournal(), clock);
  }

  public static ExecutionExitJournal memoryJournal() {
    return new MemoryJournal();
  }

  private static final class MemoryJournal implements ExecutionExitJournal {

    private final Map<UUID, ExecutionExitJournalRecord> records = new ConcurrentHashMap<>();
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
      if (existing.targetState() != record.targetState()) {
        throw new ExecutionExitJournalConflictException(
            ExecutionExitJournalConflictException.Conflict.TARGET,
            "execution exit journal target conflict for " + record.executionId());
      }
      return existing;
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
    public synchronized void close() {
      closed = true;
    }

    private void ensureOpen() {
      if (closed) {
        throw new IllegalStateException("journal is closed");
      }
    }
  }
}
