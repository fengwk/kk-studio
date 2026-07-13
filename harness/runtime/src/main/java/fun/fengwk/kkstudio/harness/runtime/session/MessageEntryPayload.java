package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

public record MessageEntryPayload(AgentMessage message) implements SessionEntryPayload {
  public MessageEntryPayload {
    message = Objects.requireNonNull(message, "message");
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.MESSAGE;
  }
}
