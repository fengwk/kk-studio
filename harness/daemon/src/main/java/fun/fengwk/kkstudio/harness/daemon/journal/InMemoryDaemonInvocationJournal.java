package fun.fengwk.kkstudio.harness.daemon.journal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 用于单进程运行和测试的线程安全内存 journal。通过 {@link ConcurrentHashMap} 的原子计算保证状态跃迁的一致性。 */
public final class InMemoryDaemonInvocationJournal implements DaemonInvocationJournal {

  private final ConcurrentHashMap<String, DaemonInvocationJournalEntry> entries =
      new ConcurrentHashMap<>();

  @Override
  public DaemonInvocationJournalStart start(String invocationId) {
    AtomicBoolean created = new AtomicBoolean();
    // computeIfAbsent 保证原子创建：仅首个线程会执行 lambda 并将 created 置为 true，其余并发调用返回既有记录。
    DaemonInvocationJournalEntry entry =
        entries.computeIfAbsent(
            invocationId,
            id -> {
              created.set(true);
              return new DaemonInvocationJournalEntry(id, DaemonInvocationState.RUNNING, null);
            });
    return new DaemonInvocationJournalStart(created.get(), entry);
  }

  @Override
  public Optional<DaemonInvocationJournalEntry> find(String invocationId) {
    return Optional.ofNullable(entries.get(invocationId));
  }

  @Override
  public boolean complete(String invocationId, DaemonTerminalMessage terminalMessage) {
    AtomicBoolean completed = new AtomicBoolean();
    // computeIfPresent 保证原子状态跃迁：仅非终态记录允许转移到终态；已终态记录保持不可变并拒绝二次完成。
    entries.computeIfPresent(
        invocationId,
        (id, entry) -> {
          if (!entry.state().isTerminal()) {
            completed.set(true);
            return new DaemonInvocationJournalEntry(id, toState(terminalMessage), terminalMessage);
          }
          return entry;
        });
    return completed.get();
  }

  private DaemonInvocationState toState(DaemonTerminalMessage terminalMessage) {
    return switch (terminalMessage.messageType()) {
      case COMPLETED -> DaemonInvocationState.COMPLETED;
      case FAILED -> DaemonInvocationState.FAILED;
      case CANCELLED -> DaemonInvocationState.CANCELLED;
      default -> throw new IllegalArgumentException("messageType must be terminal");
    };
  }
}
