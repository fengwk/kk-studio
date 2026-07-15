package fun.fengwk.kkstudio.harness.daemon.journal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 用于单进程运行和测试的线程安全内存 journal。 */
public final class InMemoryDaemonInvocationJournal implements DaemonInvocationJournal {

  private final ConcurrentHashMap<String, DaemonInvocationJournalEntry> entries =
      new ConcurrentHashMap<>();

  @Override
  public DaemonInvocationJournalStart start(String invocationId) {
    AtomicBoolean created = new AtomicBoolean();
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
