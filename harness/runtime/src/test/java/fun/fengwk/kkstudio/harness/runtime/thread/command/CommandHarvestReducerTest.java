package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 有序 command harvest 归并：YOLO 不经过 mailbox，reducer 只归并 branch settings。 */
class CommandHarvestReducerTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final BranchSettings BASE =
      new BranchSettings(
          "coding",
          new ExecutionSelection(
              "builtin", "{\"modelName\":\"claude-sonnet\",\"variant\":\"default\"}"),
          null);

  private static BranchSettings reduce(
      UUID threadId, BranchSettings base, List<ThreadCommand> commands) {
    return new CommandHarvestReducer().reduce(threadId, base, commands);
  }

  @Test
  void appliesCommandsInSequenceWithLastWriteWins() {
    ExecutionSelection opus =
        new ExecutionSelection(
            "builtin", "{\"modelName\":\"claude-opus\",\"variant\":\"thinking\"}");
    BranchSettings result =
        reduce(
            id(7L),
            BASE,
            List.of(
                queued(id(1L), 1L, new UserMessageCommandPayload(user("hello"))),
                queued(id(2L), 2L, new SetAgentCommandPayload("agent-a")),
                queued(id(3L), 3L, new SetAgentCommandPayload("agent-b")),
                queued(
                    id(4L),
                    4L,
                    new SetExecutionCommandPayload(
                        new ExecutionSelection(
                            "builtin", "{\"modelName\":\"gpt-4.1\",\"variant\":\"default\"}"))),
                queued(id(5L), 5L, new SetExecutionCommandPayload(opus)),
                queued(id(6L), 6L, new SetEnvironmentCommandPayload("local")),
                queued(id(8L), 8L, new CustomMessageCommandPayload(user("instruction")))));

    assertEquals(new BranchSettings("agent-b", opus, "local"), result);
  }

  /** 测试意图：设置命令按顺序生效；重复设置、消息命令不影响最终快照。 */
  @Test
  void ignoresNoOpSettingsAndMessages() {
    BranchSettings result =
        reduce(
            id(7L),
            BASE,
            List.of(
                // no-op：与 BASE 相同。
                queued(id(1L), 1L, new SetAgentCommandPayload("coding")),
                queued(id(2L), 2L, new SetAgentCommandPayload("reviewer")),
                queued(id(3L), 3L, new SetExecutionCommandPayload(BASE.execution())),
                queued(id(4L), 4L, new SetEnvironmentCommandPayload("local")),
                // 消息不改变设置。
                queued(id(5L), 5L, new CustomMessageCommandPayload(user("custom")))));
    assertEquals(BASE.withAgentName("reviewer").withEnvironmentName("local"), result);
  }

  /** 测试意图：SET_ENVIRONMENT 的显式 null 是「解除环境选择」，必须真实写回 settings 而不是被当作 no-op。 */
  @Test
  void environmentSelectionIsAtomicallySetAndClearedByNull() {
    BranchSettings withEnvironment = BASE.withEnvironmentName("local");
    BranchSettings selected =
        reduce(
            id(7L), BASE, List.of(queued(id(1L), 1L, new SetEnvironmentCommandPayload("shared"))));
    assertEquals("shared", selected.environmentName());

    BranchSettings cleared =
        reduce(
            id(7L),
            withEnvironment,
            List.of(queued(id(1L), 1L, new SetEnvironmentCommandPayload(null))));
    assertNull(cleared.environmentName());

    // 环境变更不得隐式影响 agent 与 execution。
    assertEquals(BASE.agentName(), cleared.agentName());
    assertEquals(BASE.execution(), cleared.execution());
  }

  @Test
  void executionSelectionIsAtomicAndMessagesDoNotChangeSettings() {
    ExecutionSelection replacement =
        new ExecutionSelection("builtin", "{\"modelName\":\"gpt-4.1\",\"variant\":\"default\"}");
    BranchSettings result =
        reduce(
            id(7L),
            BASE,
            List.of(
                queued(id(1L), 1L, new UserMessageCommandPayload(user("hello"))),
                queued(id(2L), 2L, new SetExecutionCommandPayload(replacement)),
                queued(id(3L), 3L, new CustomMessageCommandPayload(user("custom")))));

    assertEquals("coding", result.agentName());
    assertEquals(replacement, result.execution());
  }

  @Test
  void rejectsWrongThreadNonQueuedAndNonMonotonicCommands() {
    assertThrows(NullPointerException.class, () -> reduce(null, BASE, List.of()));

    ThreadCommand foreign = queued(id(1L), 1L, new SetAgentCommandPayload("coding"), id(8L));
    ThreadCommand applied = applied(id(2L), 2L);
    ThreadCommand cancelled =
        new ThreadCommand(
            id(7L),
            3L,
            new SetAgentCommandPayload("coding"),
            id(3L),
            ThreadCommandPayloadJsonCodec.requestHash(new SetAgentCommandPayload("coding")),
            null,
            id(3L),
            CREATED.plusSeconds(1),
            CREATED);
    assertThrows(IllegalArgumentException.class, () -> reduce(id(7L), BASE, List.of(foreign)));
    assertThrows(IllegalArgumentException.class, () -> reduce(id(7L), BASE, List.of(applied)));
    assertThrows(IllegalArgumentException.class, () -> reduce(id(7L), BASE, List.of(cancelled)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reduce(
                id(7L),
                BASE,
                List.of(
                    queued(id(4L), 2L, new SetAgentCommandPayload("coding")),
                    queued(id(5L), 1L, new SetAgentCommandPayload("other")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reduce(
                id(7L),
                BASE,
                List.of(
                    queued(id(6L), 1L, new SetAgentCommandPayload("coding")),
                    queued(id(7L), 1L, new SetAgentCommandPayload("other")))));
  }

  @Test
  void emptyCommandListLeavesBranchSettingsUnchanged() {
    BranchSettings result = reduce(id(7L), BASE, List.of());

    assertEquals(BASE, result);
  }

  private static ThreadCommand queued(UUID id, long sequence, ThreadCommandPayload payload) {
    return queued(id, sequence, payload, id(7L));
  }

  private static ThreadCommand queued(
      UUID id, long sequence, ThreadCommandPayload payload, UUID threadId) {
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        id,
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        CREATED);
  }

  private static ThreadCommand applied(UUID id, long sequence) {
    ThreadCommandPayload payload = new SetAgentCommandPayload("coding");
    return new ThreadCommand(
        id(7L),
        sequence,
        payload,
        id,
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        id(99L),
        null,
        null,
        CREATED);
  }

  private static AgentMessage user(String text) {
    return message(AgentMessageRole.USER, text);
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }
}
