package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;

/** 内部 steering 使用精确 {@code <system-reminder>} 定界符进入历史。 */
class SystemReminderTest {

  /** 定界符必须精确包裹正文：open tag + 换行 + 正文 + 换行 + close tag。 */
  @Test
  void wrapUsesTheExactDelimiters() {
    assertEquals("<system-reminder>\nhello\n</system-reminder>", SystemReminder.wrap("hello"));
    assertThrows(IllegalArgumentException.class, () -> SystemReminder.wrap(" "));
    assertThrows(IllegalArgumentException.class, () -> SystemReminder.wrap(""));
    assertThrows(NullPointerException.class, () -> SystemReminder.wrap(null));
  }

  /** message() 产出单条 USER 文本消息；isReminder 只认这种精确形态。 */
  @Test
  void messageIsASingleWrappedUserText() {
    AgentMessage reminder = SystemReminder.message("steer");

    assertEquals(AgentMessageRole.USER, reminder.role());
    assertEquals(1, reminder.contents().size());
    assertEquals(
        SystemReminder.wrap("steer"), ((TextMessageContent) reminder.contents().get(0)).text());
    assertTrue(SystemReminder.isReminder(reminder));
  }

  /** 非提醒形态必须被识别为非提醒：非 USER、多内容、无定界符、仅有前缀但无换行。 */
  @Test
  void isReminderRejectsEveryNonReminderShape() {
    assertFalse(SystemReminder.isReminder(null));
    assertFalse(SystemReminder.isReminder(AgentMessage.user("plain text")));
    assertFalse(
        SystemReminder.isReminder(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("assistant")))));
    assertFalse(
        SystemReminder.isReminder(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(
                    new TextMessageContent(SystemReminder.wrap("a")),
                    new TextMessageContent("b")))));
    // 前缀必须带换行，否则不是本运行时注入的提醒。
    assertFalse(
        SystemReminder.isReminder(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(new TextMessageContent("<system-reminder>inline</system-reminder>")))));
    assertTrue(SystemReminder.isReminderText("<system-reminder>\nbody"));
    assertFalse(SystemReminder.isReminderText("<system-reminder>inline"));
    assertFalse(SystemReminder.isReminderText(null));
  }
}
