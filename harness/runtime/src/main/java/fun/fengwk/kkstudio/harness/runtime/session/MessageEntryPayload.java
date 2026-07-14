package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
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
    if (assistant) {
      boolean hasToolCall =
          message.contents().stream().anyMatch(ToolCallMessageContent.class::isInstance);
      boolean toolStop = assistantMetadata.stopReason() == ProviderStopReason.TOOL_CALLS;
      if (hasToolCall != toolStop) {
        throw new IllegalArgumentException(
            "assistant stop reason and tool call contents are inconsistent");
      }
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.MESSAGE;
  }
}
