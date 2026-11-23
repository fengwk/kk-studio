package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.userMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionBranchContract;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@link TurnPlanBuilder} 的压缩相关分支：preparation 与 reason 必须成对、压缩回合不消费任何 Command、压缩分支首轮只消费内部指令。
 *
 * <p>与 {@link TurnPlanTest} 互补：这里只覆盖 TurnPlan 快照之外的 planner 决策，不重复其命令物化与快照校验断言。
 */
class TurnPlanBuilderCompactionTest {

  /** 测试意图：COMPACTION 计划缺少切分事实时 fail closed，绝不生成没有切点的压缩回合。 */
  @Test
  void compactionPlanRequiresPreparation() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath sourcePath = path(store, baseline.threadId());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                build(
                    baseline.threadId(), sourcePath, TurnStartReason.COMPACTION, List.of(), null));

    assertEquals(
        "compaction preparation must be present iff reason is COMPACTION", error.getMessage());
  }

  /** 测试意图：非压缩回合携带切分事实同样非法，避免压缩结果落到普通回合。 */
  @Test
  void nonCompactionPlanRejectsCompactionPreparation() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath sourcePath = path(store, baseline.threadId());
    UUID messageId =
        seedCommand(store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    ThreadCommand message = command(store, baseline.threadId(), messageId);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            build(
                baseline.threadId(),
                sourcePath,
                TurnStartReason.INPUT,
                List.of(message),
                new CompactionPreparation(CompactionTrigger.MANUAL, baseline.rootEntryId())));
  }

  /** 测试意图：压缩回合把全部 queued Command 留给后续回合，只把冻结的切分事实写入 TURN_START。 */
  @Test
  void compactionTurnDefersEveryQueuedCommand() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath sourcePath = path(store, baseline.threadId());
    ThreadCommand message =
        command(
            store,
            baseline.threadId(),
            seedCommand(
                store, baseline.threadId(), new UserMessageCommandPayload(userMessage("later"))));
    ThreadCommand setting =
        command(
            store,
            baseline.threadId(),
            seedCommand(store, baseline.threadId(), new SetAgentCommandPayload("coding")));
    CompactionPreparation preparation =
        new CompactionPreparation(CompactionTrigger.MANUAL, baseline.rootEntryId());

    TurnPlan plan =
        build(
            baseline.threadId(),
            sourcePath,
            TurnStartReason.COMPACTION,
            List.of(message, setting),
            preparation);

    assertEquals(List.of(), plan.consumedCommands());
    assertEquals(List.of(message, setting), plan.plannedCommands());
    assertEquals(1, plan.candidateEntries().size());
    TurnStartPayload start = (TurnStartPayload) plan.candidateEntries().getFirst().payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    assertEquals(preparation.cutEntryId(), start.compaction().cutEntryId());
  }

  /** 测试意图：压缩分支首轮只消费固定内部指令，不消费同一批次排队的设置命令。 */
  @Test
  void compactionBranchInputConsumesOnlyTheInternalInstruction() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath sourcePath = path(store, baseline.threadId());
    AgentMessage instruction =
        AgentMessage.user(CompactionBranchContract.INSTRUCTION_PREFIX + "summarize the history");
    ThreadCommand custom =
        command(
            store,
            baseline.threadId(),
            seedCommand(store, baseline.threadId(), new CustomMessageCommandPayload(instruction)));
    ThreadCommand setting =
        command(
            store,
            baseline.threadId(),
            seedCommand(store, baseline.threadId(), new SetAgentCommandPayload("coding")));

    TurnPlan plan =
        build(
            baseline.threadId(),
            sourcePath,
            TurnStartReason.COMPACTION_BRANCH_INPUT,
            List.of(custom, setting),
            null);

    assertEquals(List.of(custom), plan.consumedCommands());
    assertEquals(List.of(custom, setting), plan.plannedCommands());
    assertEquals(2, plan.candidateEntries().size());
    assertNull(((TurnStartPayload) plan.candidateEntries().getFirst().payload()).compaction());
    CustomMessagePayload frozen = (CustomMessagePayload) plan.candidateEntries().get(1).payload();
    assertEquals(instruction, frozen.message());
  }

  private static TurnPlan build(
      UUID threadId,
      EntryPath sourcePath,
      TurnStartReason reason,
      List<ThreadCommand> plannedCommands,
      CompactionPreparation preparation) {
    return new TurnPlanBuilder()
        .build(
            threadId,
            sourcePath,
            reason,
            plannedCommands,
            2L,
            ids(8_000L),
            NOW,
            preparation,
            false,
            false,
            null);
  }

  /** 确定性 id 分配器：planner 为 TURN_START 与每个冻结 Entry 各取一个新 id。 */
  private static Supplier<UUID> ids(long base) {
    AtomicLong sequence = new AtomicLong();
    return () -> TestIds.id(base + sequence.incrementAndGet());
  }
}
