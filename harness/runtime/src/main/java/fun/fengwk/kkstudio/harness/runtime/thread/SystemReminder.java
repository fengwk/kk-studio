package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;
import java.util.Objects;

/**
 * 运行时注入的上下文切换提醒：durable USER 消息，正文精确包在 {@code <system-reminder>} 定界符中。
 *
 * <p>系统指令只由每次请求的 systemInstruction 承载，会话中不存在 SYSTEM 角色。设置变更（SET_AGENT / SET_MODEL /
 * SET_ENVIRONMENT）与内部 steering（如 task max turns）因此统一以该 USER 形态进入持久历史，模型与前端都能看到它是一次注入的
 * 上下文提醒，而不是用户发言。
 */
public final class SystemReminder {

  private static final String OPEN_TAG = "<system-reminder>";
  private static final String CLOSE_TAG = "</system-reminder>";

  private SystemReminder() {}

  /** 用定界符包裹提醒正文。 */
  public static String wrap(String text) {
    Objects.requireNonNull(text, "text");
    if (text.isBlank()) {
      throw new IllegalArgumentException("reminder text must not be blank");
    }
    return OPEN_TAG + "\n" + text + "\n" + CLOSE_TAG;
  }

  /** 构造一条提醒 USER 消息。 */
  public static AgentMessage message(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(wrap(text))));
  }

  /** 判断消息是否为本运行时注入的提醒（USER 角色 + 精确定界符包裹的单条文本）。 */
  public static boolean isReminder(AgentMessage message) {
    if (message == null || message.role() != AgentMessageRole.USER) {
      return false;
    }
    if (message.contents().size() != 1) {
      return false;
    }
    AgentMessageContent content = message.contents().get(0);
    return content instanceof TextMessageContent text && isReminderText(text.text());
  }

  /** 判断一段文本是否以提醒定界符开始（用于识别合成 USER 消息中的前导提醒段）。 */
  public static boolean isReminderText(String text) {
    return text != null && text.startsWith(OPEN_TAG + "\n");
  }
}
