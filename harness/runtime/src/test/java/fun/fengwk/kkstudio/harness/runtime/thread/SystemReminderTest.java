package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CommandHarvestResult;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;

import java.util.List;

/**
 * 运行时上下文切换提醒契约：systemInstruction 是唯一的系统指令，因此 SET_* 变更与内部 steering 统一以 durable USER CUSTOM_MESSAGE +
 * 精确 {@code <system-reminder>} 定界符进入历史，且只描述生效后的当前事实。
 */
class SystemReminderTest {

  private static final BranchSettings BASE =
      new BranchSettings(
          "coding", new ModelSelection("anthropic", "claude-sonnet", "default"), null);

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

  /** SET_AGENT 提醒只描述生效后的 agent 事实。 */
  @Test
  void setAgentReminderDescribesTheEffectiveAgent() {
    AgentMessage message =
        SettingsReminder.message(
            new CommandHarvestResult.SettingsChange(
                ThreadCommandType.SET_AGENT, BASE.withAgentName("reviewer")));

    assertEquals(
        SystemReminder.wrap("The agent for this branch is now `reviewer`."), textOf(message));
    assertTrue(SystemReminder.isReminder(message));
  }

  /** SET_MODEL 提醒携带完整的生效 provider/model/variant 选择，而不是增量。 */
  @Test
  void setModelReminderDescribesTheEffectiveSelection() {
    AgentMessage message =
        SettingsReminder.message(
            new CommandHarvestResult.SettingsChange(
                ThreadCommandType.SET_MODEL,
                BASE.withModel(new ModelSelection("openai", "gpt-5", "thinking"))));

    assertEquals(
        SystemReminder.wrap(
            "The model for this branch is now `openai/gpt-5` (variant `thinking`)."),
        textOf(message));
  }

  /** SET_ENVIRONMENT 的 attach 与 detach 两种事实各自有明确的提醒文本。 */
  @Test
  void setEnvironmentReminderCoversAttachAndDetach() {
    AgentMessage attached =
        SettingsReminder.message(
            new CommandHarvestResult.SettingsChange(
                ThreadCommandType.SET_ENVIRONMENT, BASE.withEnvironmentName("local")));
    assertEquals(
        SystemReminder.wrap("This branch is now attached to environment `local`."),
        textOf(attached));

    AgentMessage detached =
        SettingsReminder.message(
            new CommandHarvestResult.SettingsChange(
                ThreadCommandType.SET_ENVIRONMENT, BASE.withEnvironmentName(null)));
    assertEquals(
        SystemReminder.wrap("This branch is now detached from any environment."), textOf(detached));
  }

  /** 非 SET_* 命令类型不得渲染提醒（提醒只由真实 setting 变更产生）。 */
  @Test
  void nonSettingChangeTypesAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SettingsReminder.message(
                new CommandHarvestResult.SettingsChange(ThreadCommandType.USER_MESSAGE, BASE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SettingsReminder.message(
                new CommandHarvestResult.SettingsChange(ThreadCommandType.CUSTOM_MESSAGE, BASE)));
    assertThrows(NullPointerException.class, () -> SettingsReminder.message(null));
  }

  private static String textOf(AgentMessage message) {
    List<AgentMessageContent> contents = message.contents();
    assertEquals(1, contents.size());
    return ((TextMessageContent) contents.get(0)).text();
  }
}
