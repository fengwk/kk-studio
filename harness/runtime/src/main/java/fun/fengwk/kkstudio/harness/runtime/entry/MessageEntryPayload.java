package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.Objects;

/**
 * 对话消息 Entry。{@code assistantMetadata} 只对 ASSISTANT 角色出现且必须出现；tool-call 内容与 {@link
 * ProviderStopReason#TOOL_CALLS} 严格一致。
 */
public record MessageEntryPayload(AgentMessage message, AssistantMessageMetadata assistantMetadata)
    implements RuntimeEntryPayload {

  public MessageEntryPayload(AgentMessage message) {
    this(message, null);
  }

  public MessageEntryPayload {
    Objects.requireNonNull(message, "message");
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
  public EntryType type() {
    return EntryType.MESSAGE;
  }
}
