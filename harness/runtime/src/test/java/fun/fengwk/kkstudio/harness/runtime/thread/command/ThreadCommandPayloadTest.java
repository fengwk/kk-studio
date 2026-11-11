package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;

import java.util.List;

/** 类型化 command payload 的 role、字段与 canonical 值不变量。 */
class ThreadCommandPayloadTest {

  private static final ExecutionSelection EXECUTION = new ExecutionSelection("fake", "{}");

  @Test
  void everyPayloadReportsItsCommandType() {
    assertEquals(
        List.of(
            ThreadCommandType.USER_MESSAGE,
            ThreadCommandType.CUSTOM_MESSAGE,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_EXECUTION,
            ThreadCommandType.SET_ENVIRONMENT),
        List.of(
                new UserMessageCommandPayload(user("hello")),
                new CustomMessageCommandPayload(user("custom")),
                new SetAgentCommandPayload("coding"),
                new SetExecutionCommandPayload(EXECUTION),
                new SetEnvironmentCommandPayload(null))
            .stream()
            .map(ThreadCommandPayload::type)
            .toList());
  }

  @Test
  void messagePayloadsEnforceRoles() {
    assertEquals(
        AgentMessageRole.USER, new UserMessageCommandPayload(user("hello")).message().role());
    assertEquals(
        AgentMessageRole.USER,
        new CustomMessageCommandPayload(user("custom-user")).message().role());
    assertThrows(
        IllegalArgumentException.class, () -> new UserMessageCommandPayload(assistant("answer")));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomMessageCommandPayload(assistant("answer")));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomMessageCommandPayload(tool("call-1")));
    assertThrows(NullPointerException.class, () -> new UserMessageCommandPayload(null));
  }

  /** SET_* payload 只接受 canonical 取值；execution selection 作为整体原子替换；环境名允许 null。 */
  @Test
  void settingPayloadsEnforceCanonicalValuesAndAtomicExecution() {
    assertEquals(EXECUTION, new SetExecutionCommandPayload(EXECUTION).execution());
    assertEquals("coding", new SetAgentCommandPayload("coding").agentName());
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" coding"));
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" "));
    assertThrows(NullPointerException.class, () -> new SetExecutionCommandPayload(null));
    assertEquals("local", new SetEnvironmentCommandPayload("local").environmentName());
    assertNull(new SetEnvironmentCommandPayload(null).environmentName());
    assertThrows(IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload(" "));
    assertThrows(IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload(" a"));
    assertThrows(IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload("a/b"));
    assertThrows(
        IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload("e".repeat(65)));
  }

  /** 消息类命令是 user-like 终止输入，绝不是 SET_* prefix 设置命令。 */
  @Test
  void classifiesMessageAndSettingCommandTypes() {
    assertTrue(ThreadCommandType.USER_MESSAGE.isMessage());
    assertTrue(ThreadCommandType.CUSTOM_MESSAGE.isMessage());
    assertFalse(ThreadCommandType.SET_AGENT.isMessage());
    assertFalse(ThreadCommandType.SET_EXECUTION.isMessage());
    assertFalse(ThreadCommandType.SET_ENVIRONMENT.isMessage());
    assertFalse(ThreadCommandType.USER_MESSAGE.isSetting());
    assertFalse(ThreadCommandType.CUSTOM_MESSAGE.isSetting());
    assertTrue(ThreadCommandType.SET_AGENT.isSetting());
    assertTrue(ThreadCommandType.SET_EXECUTION.isSetting());
    assertTrue(ThreadCommandType.SET_ENVIRONMENT.isSetting());
  }

  private static AgentMessage user(String text) {
    return message(AgentMessageRole.USER, text);
  }

  private static AgentMessage assistant(String text) {
    return message(AgentMessageRole.ASSISTANT, text);
  }

  private static AgentMessage tool(String text) {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                "call-1", "test", "test", List.of(new TextMessageContent(text)), false, "{}")));
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }
}
