package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanTestSupport.seedCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** TurnPlan 不可变快照校验：positive id、非负 cutoff、非空引用与 deferred user demand 推导。 */
class TurnPlanTest {

  @Test
  void validPlanIsAcceptedAndListsAreDefensivelyCopied() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath candidatePath = path(store, baseline.threadId());
    List<Entry> entries = candidatePath.entries();
    TurnPlan plan =
        new TurnPlan(
            baseline.threadId(),
            baseline.sessionId(),
            1L,
            List.of(),
            List.of(),
            Map.of(),
            entries,
            candidatePath,
            TestIds.id(99),
            TestIds.id(100),
            TurnStartReason.INPUT,
            null);
    assertEquals(baseline.threadId(), plan.threadId());
    assertEquals(TestIds.id(100), plan.candidateHeadEntryId());
    assertEquals(TurnStartReason.INPUT, plan.reason());
    assertEquals(entries, plan.candidateEntries());
    assertEquals(List.of(), plan.plannedCommands());
    assertEquals(List.of(), plan.consumedCommands());
    assertFalse(plan.hasDeferredUserMessages());
  }

  @Test
  void hasDeferredUserMessagesDerivesFromPlannedMinusConsumed() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    UUID messageCommandId =
        seedCommand(
            store,
            baseline.threadId(),
            new UserMessageCommandPayload(TurnPlanTestSupport.userMessage("hi")));
    ThreadCommand message = command(store, baseline.threadId(), messageCommandId);
    EntryPath candidatePath = path(store, baseline.threadId());
    // continuation 保留 message 命令（未消费）-> deferred wake。
    TurnPlan withDeferred =
        new TurnPlan(
            baseline.threadId(),
            baseline.sessionId(),
            1L,
            List.of(message),
            List.of(),
            Map.of(),
            candidatePath.entries(),
            candidatePath,
            TestIds.id(99),
            TestIds.id(100),
            TurnStartReason.CONTINUATION,
            null);
    assertTrue(withDeferred.hasDeferredUserMessages());
    // 同一命令已被消费 -> 不再需要 wake。
    TurnPlan consumed =
        new TurnPlan(
            baseline.threadId(),
            baseline.sessionId(),
            1L,
            List.of(message),
            List.of(message),
            Map.of(),
            candidatePath.entries(),
            candidatePath,
            TestIds.id(99),
            TestIds.id(100),
            TurnStartReason.CONTINUATION,
            null);
    assertFalse(consumed.hasDeferredUserMessages());
  }

  /**
   * CONTINUATION 只消费 SET_* 配置命令：所有 queued USER/CUSTOM message command 都保留给后续 INPUT 由 planner
   * 物化；配置只写入 TURN_START.settings。
   */
  @Test
  void continuationConsumesOnlySettingCommands() {
    ThreadCommand setting =
        new ThreadCommand(
            TestIds.id(1),
            1L,
            new SetAgentCommandPayload("coding"),
            TestIds.id(5),
            ThreadCommandPayloadJsonCodec.requestHash(new SetAgentCommandPayload("coding")),
            null,
            null,
            null,
            Instant.EPOCH);
    ThreadCommand custom =
        new ThreadCommand(
            TestIds.id(2),
            1L,
            new CustomMessageCommandPayload(AgentMessage.user("later input")),
            TestIds.id(6),
            ThreadCommandPayloadJsonCodec.requestHash(
                new CustomMessageCommandPayload(AgentMessage.user("later input"))),
            null,
            null,
            null,
            Instant.EPOCH);

    assertTrue(TurnPlanBuilder.isConsumed(TurnStartReason.CONTINUATION, setting));
    assertFalse(TurnPlanBuilder.isConsumed(TurnStartReason.CONTINUATION, custom));
  }

  /** 测试意图：STOP Turn 只由 StopControl 在其停止事务内完整写入，planner 对 STOP 必须 fail closed，绝不生成模型工作计划。 */
  @Test
  void stopReasonIsNeverPlannedByTheProcessor() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath sourcePath = path(store, baseline.threadId());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new TurnPlanBuilder()
                    .build(
                        baseline.threadId(),
                        sourcePath,
                        TurnStartReason.STOP,
                        List.of(),
                        0L,
                        ids(7_000L),
                        NOW,
                        null,
                        false,
                        false,
                        null));

    assertEquals("STOP turns are never planned by the processor", error.getMessage());
  }

  /** 测试意图：INPUT 计划把被消费的 CUSTOM_MESSAGE 冻结为 CORE CustomMessagePayload Entry，且不改写 settings 快照。 */
  @Test
  void consumedCustomMessageIsFrozenAsCoreCustomEntryWithoutChangingSettings() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath sourcePath = path(store, baseline.threadId());
    UUID key =
        seedCommand(
            store,
            baseline.threadId(),
            new CustomMessageCommandPayload(AgentMessage.user("runtime reminder")));
    ThreadCommand custom = command(store, baseline.threadId(), key);

    TurnPlan plan =
        new TurnPlanBuilder()
            .build(
                baseline.threadId(),
                sourcePath,
                TurnStartReason.INPUT,
                List.of(custom),
                1L,
                ids(7_100L),
                NOW,
                null,
                false,
                false,
                null);

    assertEquals(List.of(custom), plan.consumedCommands());
    assertEquals(2, plan.candidateEntries().size());
    assertEquals(
        TurnStartReason.INPUT,
        ((TurnStartPayload) plan.candidateEntries().get(0).payload()).reason());
    assertEquals(sourcePath.baseSettings(), plan.candidatePath().baseSettings());
    CustomMessagePayload frozen = (CustomMessagePayload) plan.candidateEntries().get(1).payload();
    assertEquals(CustomMessagePayload.CORE_CONTRIBUTOR_ID, frozen.contributorId());
    assertEquals(CustomMessagePayload.CORE_CUSTOM_TYPE, frozen.customType());
    assertEquals(AgentMessage.user("runtime reminder"), frozen.message());
  }

  /** 确定性 id 分配器：planner 为 TURN_START 与每个冻结 Entry 各取一个新 id。 */
  private static Supplier<UUID> ids(long base) {
    AtomicLong sequence = new AtomicLong();
    return () -> TestIds.id(base + sequence.incrementAndGet());
  }

  @Test
  void rejectsNonPositiveIdsAndNegativeCutoff() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath candidatePath = path(store, baseline.threadId());
    UUID threadId = baseline.threadId();
    UUID sessionId = baseline.sessionId();
    assertThrows(
        IllegalArgumentException.class,
        () -> validPlan(threadId, sessionId, -1L, candidatePath, TestIds.id(99), TestIds.id(100)));
  }

  @Test
  void rejectsNullReferences() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    var baseline = seedBaseline(store);
    EntryPath candidatePath = path(store, baseline.threadId());
    assertThrows(
        NullPointerException.class,
        () ->
            new TurnPlan(
                baseline.threadId(),
                baseline.sessionId(),
                0L,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                null,
                TestIds.id(99),
                TestIds.id(100),
                TurnStartReason.INPUT,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new TurnPlan(
                baseline.threadId(),
                baseline.sessionId(),
                0L,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                candidatePath,
                TestIds.id(99),
                TestIds.id(100),
                null,
                null));
  }

  private static TurnPlan validPlan(
      UUID threadId,
      UUID sessionId,
      long cutoffSequence,
      EntryPath candidatePath,
      UUID turnStartEntryId,
      UUID candidateHeadEntryId) {
    return new TurnPlan(
        threadId,
        sessionId,
        cutoffSequence,
        List.of(),
        List.of(),
        Map.of(),
        candidatePath.entries(),
        candidatePath,
        turnStartEntryId,
        candidateHeadEntryId,
        TurnStartReason.INPUT,
        null);
  }
}
