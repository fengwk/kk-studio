package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 有序 command harvest 归并：YOLO 不经过 mailbox，reducer 只归并 branch settings。 */
class CommandHarvestReducerTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final BranchSettings BASE =
      new BranchSettings(
          "coding", new ModelSelection("anthropic", "claude-sonnet", "default"), null);

  /** 测试用确定性 goal id 分配器：每次调用产生新 id，因此断言到的 id 序列同时证明「每条 typed GOAL 设置恰分配一个新 id」。 */
  private static Supplier<UUID> goalIds() {
    AtomicLong sequence = new AtomicLong();
    return () -> id(1_000L + sequence.incrementAndGet());
  }

  private static BranchSettings reduce(
      UUID threadId, BranchSettings base, List<ThreadCommand> commands) {
    return new CommandHarvestReducer().reduce(threadId, base, commands, goalIds());
  }

  @Test
  void appliesCommandsInSequenceWithLastWriteWins() {
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
                    new SetModelCommandPayload(new ModelSelection("openai", "gpt-4.1", "default"))),
                queued(
                    id(5L),
                    5L,
                    new SetModelCommandPayload(
                        new ModelSelection("anthropic", "claude-opus", "thinking"))),
                queued(id(6L), 6L, new SetEnvironmentCommandPayload("local")),
                queued(id(8L), 8L, new CustomMessageCommandPayload(user("instruction")))));

    assertEquals(
        new BranchSettings(
            "agent-b", new ModelSelection("anthropic", "claude-opus", "thinking"), "local"),
        result);
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
                queued(id(3L), 3L, new SetModelCommandPayload(BASE.model())),
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

    // 环境变更不得隐式影响 agent 与 model。
    assertEquals(BASE.agentName(), cleared.agentName());
    assertEquals(BASE.model(), cleared.model());
  }

  @Test
  void modelSelectionIsAtomicAndMessagesDoNotChangeSettings() {
    ModelSelection replacement = new ModelSelection("openai", "gpt-4.1", "default");
    BranchSettings result =
        reduce(
            id(7L),
            BASE,
            List.of(
                queued(id(1L), 1L, new UserMessageCommandPayload(user("hello"))),
                queued(id(2L), 2L, new SetModelCommandPayload(replacement)),
                queued(id(3L), 3L, new CustomMessageCommandPayload(user("custom")))));

    assertEquals("coding", result.agentName());
    assertEquals(replacement, result.model());
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

  /**
   * 测试意图：typed GOAL 命令把新目标 id + 正文写入 TURN_START settings 快照，且与同批 SET_* 互不干扰；它不产生任何设置提醒
   * carrier，模型可见载体是同 turn 的冻结 USER 消息。
   */
  @Test
  void goalCommandWritesSnapshotWithoutTouchingOtherSettings() {
    BranchSettings result =
        reduce(
            id(7L),
            BASE,
            List.of(
                queued(id(1L), 1L, new GoalCommandPayload("ship it")),
                queued(id(2L), 2L, new SetAgentCommandPayload("agent-a"))));

    assertEquals(new GoalSetting(id(1_001L), "ship it"), result.goal());
    assertEquals("agent-a", result.agentName());
    assertEquals(BASE.model(), result.model());
    assertNull(result.environmentName());
  }

  /** 测试意图：相同文本的再次设置仍是新目标（新 id，旧报告因此失效），清除把 Goal 置回 null；两者都只改 settings。 */
  @Test
  void repeatedGoalTextGetsFreshIdAndClearDropsGoal() {
    BranchSettings first =
        reduce(id(7L), BASE, List.of(queued(id(1L), 1L, new GoalCommandPayload("same"))));
    BranchSettings second =
        reduce(
            id(7L),
            first,
            List.of(
                queued(id(1L), 2L, new GoalCommandPayload("same")),
                queued(id(2L), 3L, new GoalCommandPayload(null))));

    assertEquals(new GoalSetting(id(1_001L), "same"), first.goal());
    assertNull(second.goal());
    // Goal 的创改不得影响 agent / model / environment。
    assertEquals(first.agentName(), second.agentName());
    assertEquals(first.model(), second.model());
    assertEquals(first.environmentName(), second.environmentName());
  }

  /** 测试意图：不涉及 GOAL 的命令批次不消耗 goal id，排除「空批也分配 id」这类破坏「设置即新目标」语义的实现。 */
  @Test
  void nonGoalCommandsDoNotAllocateGoalIds() {
    AtomicLong allocations = new AtomicLong();
    BranchSettings result =
        new CommandHarvestReducer()
            .reduce(
                id(7L),
                BASE,
                List.of(
                    queued(id(1L), 1L, new SetAgentCommandPayload("agent-a")),
                    queued(id(2L), 2L, new UserMessageCommandPayload(user("hello")))),
                () -> {
                  allocations.incrementAndGet();
                  return id(9_000L);
                });

    assertEquals("agent-a", result.agentName());
    assertEquals(0L, allocations.get());
  }
}
