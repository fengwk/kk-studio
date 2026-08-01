package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.util.Objects;

/**
 * Durable conversation message.
 *
 * <p>USER messages must carry the originating {@link TurnSettings}; ASSISTANT and TOOL result
 * messages must not. SYSTEM messages belong to {@link CustomMessageEntryPayload}. Assistant
 * metadata is present exactly for ASSISTANT messages, and tool-call content is strictly aligned
 * with {@link ProviderStopReason#TOOL_CALLS}.
 */
public record MessageEntryPayload(
    AgentMessage message, TurnSettings turnSettings, AssistantMessageMetadata assistantMetadata)
    implements RuntimeEntryPayload {

  public MessageEntryPayload {
    Objects.requireNonNull(message, "message");
    if (message.role() == AgentMessageRole.SYSTEM) {
      throw new IllegalArgumentException("MESSAGE payload must not use SYSTEM role");
    }
    boolean user = message.role() == AgentMessageRole.USER;
    boolean assistant = message.role() == AgentMessageRole.ASSISTANT;
    if (user != (turnSettings != null)) {
      throw new IllegalArgumentException("turn settings must be present exactly for user messages");
    }
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
  public EntryType type() {
    return EntryType.MESSAGE;
  }
}
