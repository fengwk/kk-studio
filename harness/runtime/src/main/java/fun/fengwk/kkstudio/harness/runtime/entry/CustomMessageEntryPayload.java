package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.util.Objects;

/** Extension message that explicitly starts or continues a response-producing turn. */
public record CustomMessageEntryPayload(AgentMessage message, TurnSettings turnSettings)
    implements RuntimeEntryPayload {

  public CustomMessageEntryPayload {
    Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.SYSTEM && message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be SYSTEM or USER");
    }
    Objects.requireNonNull(turnSettings, "turnSettings");
  }

  @Override
  public EntryType type() {
    return EntryType.CUSTOM_MESSAGE;
  }
}
