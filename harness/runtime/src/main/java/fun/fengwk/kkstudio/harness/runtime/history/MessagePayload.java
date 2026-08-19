package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * durable 会话消息。
 *
 * <p>SYSTEM 消息属于 {@link CustomMessagePayload}。USER 消息不携带 metadata；ASSISTANT 消息必须携带 {@link
 * AssistantMessageMetadata} 且不能携带 tool result metadata；assistant tool call id 必须唯一，以保证
 * ordinal/prefix 校验的确定性。TOOL 消息必须携带 {@link ToolResultMetadata} 且不能携带 assistant metadata，且唯一的 {@link
 * ToolResultMessageContent#toolCallId()} 必须与 metadata 匹配。生成 stop reason 与 tool call 存在性 正交（{@code
 * COMPLETE}/{@code LENGTH} 均可有 calls），不在此处施加等价约束。
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
