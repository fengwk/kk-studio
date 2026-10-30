package fun.fengwk.kkstudio.harness.infra.store;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalConflictException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalSnapshot;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Infra 测试共用的退出确认器；journal 仅保存在内存中。 */
public final class ExecutionExitTestSupport {

  private ExecutionExitTestSupport() {}

  public static ExecutionExitConfirmer confirmer(HarnessStore store, Clock clock) {
    return new ExecutionExitConfirmer(store, new MemoryJournal(), clock);
  }

  private static final class MemoryJournal implements ExecutionExitJournal {

    private final Map<UUID, ExecutionExitJournalRecord> records = new ConcurrentHashMap<>();

    @Override
    public synchronized ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
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
      records.remove(executionId);
    }

    @Override
    public synchronized ExecutionExitJournalSnapshot loadAll() {
      return new ExecutionExitJournalSnapshot(List.copyOf(records.values()), List.of());
    }

    @Override
    public void close() {}
  }
}
