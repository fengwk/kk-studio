package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

public record MessageEntryPayload(AgentMessage message, AssistantMessageMetadata assistantMetadata)
    implements SessionEntryPayload {

  public MessageEntryPayload(AgentMessage message) {
    this(message, null);
  }

  public MessageEntryPayload {
    message = Objects.requireNonNull(message, "message");
    boolean assistant = message.role() == AgentMessageRole.ASSISTANT;
    if (assistant != (assistantMetadata != null)) {
      throw new IllegalArgumentException(
          "assistant metadata must be present exactly for assistant messages");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.MESSAGE;
  }
}
