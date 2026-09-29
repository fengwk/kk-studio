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

  /** 判断消息是否为本运行时注入的提醒（USER 角色 + 恰好完整定界符包裹的单条文本）。 */
  public static boolean isReminder(AgentMessage message) {
    if (message == null || message.role() != AgentMessageRole.USER) {
      return false;
    }
    if (message.contents().size() != 1) {
      return false;
    }
    AgentMessageContent content = message.contents().get(0);
    return content instanceof TextMessageContent text && isWrapped(text.text());
  }

  /**
   * 判断一段文本是否以提醒定界符开始。
   *
   * <p>这是前缀判定：拼接型内容（例如 one-shot 把可信 system 文本包成前导提醒段再附加调用方内容）只有前导段是提醒。判定 provenance 时不要用它，改用 {@link
   * #isReminder}（要求完整开闭标签）。
   */
  public static boolean isReminderText(String text) {
    return text != null && text.startsWith(OPEN_TAG + "\n");
  }

  /**
   * 判断文本是否恰好等于 {@link #wrap} 的完整形态：开标签 + 换行 + 非空正文 + 换行 + 闭标签，且闭标签之后没有尾随内容。
   *
   * <p>普通消息只要以开标签开头就会被前缀判定命中；这里必须同时验证闭标签与无尾随文本，否则调用方（例如 admission 的 steering 分类）会把伪造前缀的用户消息当成运行时注入。
   */
  private static boolean isWrapped(String text) {
    return isReminderText(text)
        && text.endsWith("\n" + CLOSE_TAG)
        && text.length() > OPEN_TAG.length() + CLOSE_TAG.length() + 2;
  }
}
