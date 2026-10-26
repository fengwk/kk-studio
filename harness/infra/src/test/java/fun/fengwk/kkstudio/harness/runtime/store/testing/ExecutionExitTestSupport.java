package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
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
      if (existing.sameIdentity(record) && existing.targetState() == record.targetState()) {
        return existing;
      }
      throw new ExecutionExitJournalException(
          "execution exit journal identity or target conflict for " + record.executionId());
    }

    @Override
    public synchronized void delete(UUID executionId) {
      records.remove(executionId);
    }

    @Override
    public synchronized List<ExecutionExitJournalRecord> loadAll() {
      return List.copyOf(records.values());
    }

    @Override
    public void close() {}
  }
}
