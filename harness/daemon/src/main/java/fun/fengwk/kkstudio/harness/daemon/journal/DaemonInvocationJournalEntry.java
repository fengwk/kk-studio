package fun.fengwk.kkstudio.harness.daemon.journal;

import java.util.Objects;

/** 一条 invocation 的可替换持久化 journal 表示。 */
public record DaemonInvocationJournalEntry(
    String invocationId, DaemonInvocationState state, DaemonTerminalMessage terminalMessage) {

  public DaemonInvocationJournalEntry {
    if (invocationId == null || invocationId.isBlank()) {
      throw new IllegalArgumentException("invocationId must not be blank");
    }
    state = Objects.requireNonNull(state, "state");
    if (state.isTerminal() != (terminalMessage != null)) {
      throw new IllegalArgumentException("terminalMessage must match invocation state");
    }
  }
}
