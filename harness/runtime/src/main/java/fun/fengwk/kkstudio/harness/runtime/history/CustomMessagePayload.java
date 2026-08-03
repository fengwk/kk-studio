package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.util.Objects;

/** Extension message that explicitly starts or continues a response-producing turn. */
public record CustomMessagePayload(AgentMessage message) implements EntryPayload {

  public CustomMessagePayload {
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.SYSTEM && message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be SYSTEM or USER");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.CUSTOM_MESSAGE;
  }
}
