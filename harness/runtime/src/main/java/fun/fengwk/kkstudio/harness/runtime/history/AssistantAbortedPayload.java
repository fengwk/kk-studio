package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;

import java.util.Objects;

/**
 * 用户主动 stop 时持久化的 assistant turn：仅保留安全 text/thinking 内容，绝不含 tool call。
 *
 * <p>该 Entry 是 Provider 语义上下文中的完整 assistant turn，也是 continuation 的 debt barrier。构造器拒绝非 assistant
 * role、拒绝空 contents、拒绝只含空字符串 text/thinking blocks，并拒绝包含除 text/thinking 之外任何 content。无 safe content
 * 的 stop 路径必须改用 {@link AssistantErrorPayload} 的 cancellation barrier， 绝不把空 aborted payload 写入 Entry
 * Tree。不提供 contents 视图或工厂方法。
 */
public record AssistantAbortedPayload(AgentMessage message) implements EntryPayload {

  public AssistantAbortedPayload {
    Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalArgumentException("assistant aborted entry requires ASSISTANT role");
    }
    if (message.contents().isEmpty()) {
      throw new IllegalArgumentException("assistant aborted entry requires non-empty contents");
    }
    boolean hasMeaningfulContent = false;
    for (AgentMessageContent content : message.contents()) {
      if (!(content instanceof TextMessageContent)
          && !(content instanceof ThinkingMessageContent)) {
        throw new IllegalArgumentException(
            "assistant aborted entry must not carry tool calls or non-text/thinking content: "
                + content.getClass().getName());
      }
      String text =
          content instanceof TextMessageContent
              ? ((TextMessageContent) content).text()
              : ((ThinkingMessageContent) content).text();
      if (!text.isEmpty()) {
        hasMeaningfulContent = true;
      }
    }
    if (!hasMeaningfulContent) {
      // 仅由空 text/thinking block 构造的 aborted entry 在语义上是无操作 assistant turn；/stop 路径必须回退到
      // cancellation barrier。
      throw new IllegalArgumentException(
          "assistant aborted entry requires non-empty text or thinking");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.ASSISTANT_ABORTED;
  }
}
