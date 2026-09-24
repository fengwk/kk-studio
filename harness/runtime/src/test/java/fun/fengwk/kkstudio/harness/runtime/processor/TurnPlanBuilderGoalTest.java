package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * typed GOAL 用户输入的 planner 契约：Goal 设置/清除与新 settings 快照、冻结 USER 消息在同一个 TURN_START 内原子生效；
 * CONTINUATION 绝不提前消费 GOAL。id 由 runtime 在 speculative planning 时分配。
 */
class TurnPlanBuilderGoalTest {

  private final TurnPlanBuilder builder = new TurnPlanBuilder();

  /** 确定性 id 分配器：便于断言「每次设置都是新 id」与快照/消息一致。 */
  private static Supplier<UUID> ids() {
    AtomicLong sequence = new AtomicLong();
    return () -> TestIds.id(9_000L + sequence.incrementAndGet());
  }

  /** 测试意图：一条 GOAL 命令必须同时产生 settings.goal 快照与含用户原文及当次行动要求的冻结 USER 消息，且两者属于同一个 TURN_START（原子生效）。 */
  @Test
  void goalInputWritesSettingsSnapshotAndFrozenUserMessageInOneTurn() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    UUID key = seedCommand(store, baseline.threadId(), new GoalCommandPayload("finish migration"));
    ThreadCommand goal = command(store, baseline.threadId(), key);

    TurnPlan plan =
        builder.build(
            baseline.threadId(),
            path(store, baseline.threadId()),
            TurnStartReason.INPUT,
            List.of(goal),
            ids(),
            NOW,
            null);

    assertEquals(List.of(goal), plan.consumedCommands());
    // 原子：turn 内只有 TURN_START + 一条冻结 USER 消息。
    assertEquals(2, plan.candidateEntries().size());
    TurnStartPayload start = (TurnStartPayload) plan.candidateEntries().get(0).payload();
    assertEquals(TurnStartReason.INPUT, start.reason());
    GoalSetting setting = start.settings().goal();
    assertNotNull(setting);
    assertEquals("finish migration", setting.text());
    MessagePayload message = (MessagePayload) plan.candidateEntries().get(1).payload();
    assertEquals(AgentMessageRole.USER, message.message().role());
    assertTrue(textOf(message).contains("finish migration"));
    // 快照与消息同属本 turn：候选 path 的 baseSettings 已经携带该 Goal。
    assertEquals(setting, plan.candidatePath().baseSettings().goal());
  }

  /**
   * 测试意图：相同文本的再次设置也必须得到新的 goal id（旧进度声明因此失效），清除则回到无 Goal 并产生明确的取消 USER 消息； 后继 turn 的 settings 从候选
   * path 自然继承。
   */
  @Test
  void repeatedGoalSetGetsFreshIdAndClearDropsGoalAtomically() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath base = path(store, baseline.threadId());
    UUID key = seedCommand(store, baseline.threadId(), new GoalCommandPayload("same text"));
    ThreadCommand goal = command(store, baseline.threadId(), key);
    Supplier<UUID> ids = ids();

    TurnPlan first =
        builder.build(
            baseline.threadId(), base, TurnStartReason.INPUT, List.of(goal), ids, NOW, null);
    GoalSetting firstSetting = first.candidatePath().baseSettings().goal();
    ThreadCommand repeated =
        new ThreadCommand(
            baseline.threadId(),
            2L,
            new GoalCommandPayload("same text"),
            TestIds.id(11L),
            goal.requestHash(),
            null,
            null,
            null,
            NOW);
    TurnPlan second =
        builder.build(
            baseline.threadId(),
            first.candidatePath(),
            TurnStartReason.INPUT,
            List.of(repeated),
            ids,
            NOW,
            null);
    GoalSetting secondSetting = second.candidatePath().baseSettings().goal();
    assertEquals("same text", secondSetting.text());
    assertFalse(secondSetting.id().equals(firstSetting.id()));

    ThreadCommand clear =
        new ThreadCommand(
            baseline.threadId(),
            3L,
            new GoalCommandPayload(null),
            TestIds.id(12L),
            goal.requestHash(),
            null,
            null,
            null,
            NOW);
    TurnPlan cleared =
        builder.build(
            baseline.threadId(),
            second.candidatePath(),
            TurnStartReason.INPUT,
            List.of(clear),
            ids,
            NOW,
            null);
    assertNull(cleared.candidatePath().baseSettings().goal());
    // 清除与设置走同一原子路径：候选 turn 内出现明确的取消 USER 消息。
    assertTrue(textOf(clearedUserMessage(cleared)).contains("clearing the goal of this branch"));
  }

  /** 测试意图：CONTINUATION 只消费 SET_*，排队的 GOAL 必须保持 queued（不得提前消费、不得改 settings、不得产生消息），只留下一次显式 wake。 */
  @Test
  void continuationLeavesQueuedGoalUntouched() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    UUID key = seedCommand(store, baseline.threadId(), new GoalCommandPayload("later"));
    ThreadCommand goal = command(store, baseline.threadId(), key);

    TurnPlan plan =
        builder.build(
            baseline.threadId(),
            path(store, baseline.threadId()),
            TurnStartReason.CONTINUATION,
            List.of(goal),
            ids(),
            NOW,
            null);

    assertEquals(List.of(), plan.consumedCommands());
    assertEquals(1, plan.candidateEntries().size());
    BranchSettings settings =
        ((TurnStartPayload) plan.candidateEntries().get(0).payload()).settings();
    assertNull(settings.goal());
    assertTrue(plan.hasDeferredUserMessages());
  }

  /** 测试意图：compaction turn 不消费任何命令，queued GOAL 同样保留到下一个 INPUT。 */
  @Test
  void compactionConsumesNoGoalCommand() {
    ThreadCommand goal =
        new ThreadCommand(
            TestIds.id(1L),
            1L,
            new GoalCommandPayload("kept"),
            TestIds.id(5L),
            "0".repeat(64),
            null,
            null,
            null,
            NOW);
    assertFalse(TurnPlanBuilder.isConsumed(TurnStartReason.COMPACTION, goal));
  }

  /** 候选 turn 内最后一条 USER Message（normalization suffix 之后仍能稳定定位）。 */
  private static MessagePayload clearedUserMessage(TurnPlan plan) {
    MessagePayload found = null;
    for (Entry entry : plan.candidateEntries()) {
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.USER) {
        found = message;
      }
    }
    assertNotNull(found, "goal turn must contain one user message");
    return found;
  }

  private static String textOf(MessagePayload payload) {
    StringBuilder text = new StringBuilder();
    for (AgentMessageContent content : payload.message().contents()) {
      if (content instanceof TextMessageContent value) {
        text.append(value.text());
      }
    }
    return text.toString();
  }
}
