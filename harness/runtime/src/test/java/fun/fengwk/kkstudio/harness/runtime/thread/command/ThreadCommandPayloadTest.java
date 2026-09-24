package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.List;

/** 类型化 command payload 的 role、字段与 canonical 值不变量。 */
class ThreadCommandPayloadTest {

  private static final ModelSelection MODEL =
      new ModelSelection("anthropic", "claude-sonnet", "default");

  @Test
  void everyPayloadReportsItsCommandType() {
    assertEquals(
        List.of(
            ThreadCommandType.USER_MESSAGE,
            ThreadCommandType.CUSTOM_MESSAGE,
            ThreadCommandType.GOAL,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.SET_ENVIRONMENT),
        List.of(
                new UserMessageCommandPayload(user("hello")),
                new CustomMessageCommandPayload(user("custom")),
                new GoalCommandPayload("finish"),
                new SetAgentCommandPayload("coding"),
                new SetModelCommandPayload(MODEL),
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

  /** SET_* payload 只接受 canonical 取值；model selection 作为整体原子替换；环境名允许 null。 */
  @Test
  void settingPayloadsEnforceCanonicalValuesAndAtomicModel() {
    assertEquals(MODEL, new SetModelCommandPayload(MODEL).model());
    assertEquals("coding", new SetAgentCommandPayload("coding").agentName());
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" coding"));
    assertThrows(IllegalArgumentException.class, () -> new SetAgentCommandPayload(" "));
    assertThrows(NullPointerException.class, () -> new SetModelCommandPayload(null));
    assertEquals("local", new SetEnvironmentCommandPayload("local").environmentName());
    assertNull(new SetEnvironmentCommandPayload(null).environmentName());
    assertThrows(IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload(" "));
    assertThrows(IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload(" a"));
    assertThrows(IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload("a/b"));
    assertThrows(
        IllegalArgumentException.class, () -> new SetEnvironmentCommandPayload("e".repeat(65)));
  }

  /** GOAL 是 user-like 终止输入（贡献冻结 USER 消息），绝不是 SET_* prefix 设置命令。 */
  @Test
  void classifiesMessageAndSettingCommandTypes() {
    assertTrue(ThreadCommandType.USER_MESSAGE.isMessage());
    assertTrue(ThreadCommandType.CUSTOM_MESSAGE.isMessage());
    assertTrue(ThreadCommandType.GOAL.isMessage());
    assertFalse(ThreadCommandType.GOAL.isSetting());
    assertFalse(ThreadCommandType.SET_AGENT.isMessage());
    assertFalse(ThreadCommandType.SET_MODEL.isMessage());
    assertFalse(ThreadCommandType.SET_ENVIRONMENT.isMessage());
    assertFalse(ThreadCommandType.USER_MESSAGE.isSetting());
    assertFalse(ThreadCommandType.CUSTOM_MESSAGE.isSetting());
    assertTrue(ThreadCommandType.SET_AGENT.isSetting());
    assertTrue(ThreadCommandType.SET_MODEL.isSetting());
    assertTrue(ThreadCommandType.SET_ENVIRONMENT.isSetting());
  }

  /** 测试意图：GOAL payload 只接受 canonical 目标正文或 null（清除）；空串不是清除，codec 严格区分「显式 null」与「字段缺失」。 */
  @Test
  void goalPayloadAcceptsCanonicalTextOrExplicitClear() {
    ThreadCommandPayloadJsonCodec codec = new ThreadCommandPayloadJsonCodec();
    assertEquals("finish", new GoalCommandPayload("finish").text());
    assertNull(new GoalCommandPayload(null).text());
    assertThrows(IllegalArgumentException.class, () -> new GoalCommandPayload(""));
    assertThrows(IllegalArgumentException.class, () -> new GoalCommandPayload(" x"));
    assertThrows(IllegalArgumentException.class, () -> new GoalCommandPayload("x".repeat(2_001)));

    String set = codec.encode(new GoalCommandPayload("finish"));
    assertEquals("{\"text\":\"finish\"}", set);
    assertEquals(new GoalCommandPayload("finish"), codec.decode(ThreadCommandType.GOAL, set));
    String clear = codec.encode(new GoalCommandPayload(null));
    assertEquals("{\"text\":null}", clear);
    assertEquals(new GoalCommandPayload(null), codec.decode(ThreadCommandType.GOAL, clear));
    // 字段缺失 / 未知字段 / 非文本一律确定性拒绝。
    assertThrows(IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.GOAL, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(ThreadCommandType.GOAL, "{\"text\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(ThreadCommandType.GOAL, "{\"text\":null,\"extra\":1}"));
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
