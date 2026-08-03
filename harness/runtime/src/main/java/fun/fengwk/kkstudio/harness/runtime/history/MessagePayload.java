package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Durable conversation message.
 *
 * <p>SYSTEM messages belong to {@link CustomMessagePayload}. USER messages carry no metadata;
 * ASSISTANT messages must carry {@link AssistantMessageMetadata} and no tool result metadata, and
 * {@code stopReason == TOOL_CALLS} must match the presence of {@link ToolCallMessageContent};
 * assistant tool call ids must be unique so ordinal/prefix validation stays deterministic. TOOL
 * messages must carry {@link ToolResultMetadata} and no assistant metadata, and the unique {@link
 * ToolResultMessageContent#toolCallId()} must match the metadata.
 */
public record MessagePayload(
    AgentMessage message,
    AssistantMessageMetadata assistantMetadata,
    ToolResultMetadata toolResultMetadata)
    implements EntryPayload {

  public MessagePayload {
    message = Objects.requireNonNull(message, "message");
    switch (message.role()) {
      case SYSTEM -> throw new IllegalArgumentException("MESSAGE payload must not use SYSTEM role");
      case USER -> {
        if (assistantMetadata != null || toolResultMetadata != null) {
          throw new IllegalArgumentException(
              "user messages must not carry assistant or tool result metadata");
        }
      }
      case ASSISTANT -> {
        if (assistantMetadata == null || toolResultMetadata != null) {
          throw new IllegalArgumentException(
              "assistant messages require assistantMetadata and must not carry tool result"
                  + " metadata");
        }
        boolean hasToolCall =
            message.contents().stream().anyMatch(ToolCallMessageContent.class::isInstance);
        boolean toolStop = assistantMetadata.stopReason() == ProviderStopReason.TOOL_CALLS;
        if (hasToolCall != toolStop) {
          throw new IllegalArgumentException(
              "assistant stop reason and tool call contents are inconsistent");
        }
        Set<String> toolCallIds = new HashSet<>();
        for (AgentMessageContent content : message.contents()) {
          if (content instanceof ToolCallMessageContent call
              && !toolCallIds.add(call.toolCallId())) {
            throw new IllegalArgumentException("assistant tool call ids must be unique");
          }
        }
      }
      case TOOL -> {
        if (toolResultMetadata == null || assistantMetadata != null) {
          throw new IllegalArgumentException(
              "tool messages require toolResultMetadata and must not carry assistant metadata");
        }
        ToolResultMessageContent result = (ToolResultMessageContent) message.contents().get(0);
        if (!result.toolCallId().equals(toolResultMetadata.toolCallId())) {
          throw new IllegalArgumentException(
              "tool result toolCallId must match toolResultMetadata.toolCallId");
        }
      }
    }
  }

  @Override
  public EntryType type() {
    return EntryType.MESSAGE;
  }
}
