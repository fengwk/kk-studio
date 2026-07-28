package fun.fengwk.kkstudio.harness.runtime.entry;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户主动 stop 时持久化的 assistant turn：仅保留安全 text/thinking 内容，绝不含 tool call，绝不消耗空 SafeStreamSnapshot。
 *
 * <p>该 Entry 是 Provider 语义上下文中的完整 assistant turn，也是 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlanner} 的 debt barrier。构造器拒绝非
 * assistant role、拒绝空 contents、拒绝只含空字符串 text/thinking blocks（与 AgentMessage 构造器一致 —— AgentMessage
 * 已经要求 contents 非空但允许空字符串；这里进一步要求 text/thinking 都实际承载内容），并 拒绝包含除 text/thinking 之外任何 content。无 safe
 * content 的 stop 路径必须改用 {@link AssistantErrorEntryPayload} 的 cancellation barrier，绝不允许把空 aborted
 * payload 写入 Entry Tree，否则 Planner 会把空 assistant turn 投给 Provider 破坏 partial continue 语义。
 */
public record AssistantAbortedEntryPayload(AgentMessage message) implements RuntimeEntryPayload {

  public AssistantAbortedEntryPayload {
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
      // An aborted entry built only from empty text/thinking blocks is semantically a no-op
      // assistant turn; /stop paths must fall back to the cancellation barrier instead.
      throw new IllegalArgumentException(
          "assistant aborted entry requires non-empty text or thinking");
    }
  }

  /** 安全 contents 视图：返回不可变拷贝防止内部状态被修改。 */
  public List<AgentMessageContent> contents() {
    return List.copyOf(message.contents());
  }

  /**
   * 把已 durable 累积的 text/thinking 字符串构造成 abort payload；二者的 {@code isEmpty} 都为空（null 视为空串）时拒绝——{@code
   * /stop} 在没有安全内容时必须改用 {@link AssistantErrorEntryPayload} 的 {@code CANCELLED} 屏障，绝不物化空 aborted
   * turn。
   */
  public static AssistantAbortedEntryPayload ofTextAndThinking(String text, String thinking) {
    String safeText = text == null ? "" : text;
    String safeThinking = thinking == null ? "" : thinking;
    if (safeText.isEmpty() && safeThinking.isEmpty()) {
      throw new IllegalArgumentException("aborted entry requires non-empty text or thinking");
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!safeText.isEmpty()) {
      contents.add(new TextMessageContent(safeText));
    }
    if (!safeThinking.isEmpty()) {
      contents.add(new ThinkingMessageContent(safeThinking));
    }
    return new AssistantAbortedEntryPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }

  @Override
  public EntryType type() {
    return EntryType.ASSISTANT_ABORTED;
  }
}
