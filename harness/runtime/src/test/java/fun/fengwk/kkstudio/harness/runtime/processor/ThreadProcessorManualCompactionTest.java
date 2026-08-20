package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCompactionReadyClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

/** 手动压缩 control：availability、revision fence 与无 mailbox 的 durable plan 提交。 */
class ThreadProcessorManualCompactionTest extends ThreadProcessorTestBase {

  private static final ModelUsage USAGE = new ModelUsage(50_000L, 2L, 0L, 0L, 0L, 0L, 50_002L);

  @Test
  void availabilityDisablesSmallAndBusyThreads() {
    // 小上下文低于 manualMinimum；open turn 即使无 Model 行也属于不安全控制面。
    Fixture smallFixture = fixture();
    var small = seedClosedTurn(smallFixture.store, false);
    ManualCompactionAvailability smallAvailability =
        smallFixture.processor.manualCompactionAvailability(small.threadId());
    assertFalse(smallAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM,
        smallAvailability.disabledReason());

    Fixture busyFixture = fixture();
    var busy = seedOpenInputTurn(busyFixture.store);
    ManualCompactionAvailability busyAvailability =
        busyFixture.processor.manualCompactionAvailability(busy.threadId());
    assertFalse(busyAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY, busyAvailability.disabledReason());
  }

  @Test
  void compactThreadCommitsManualTurnAndModelWorkWithoutMailboxCommand() {
    // control 同步提交 TURN_START+ModelInvocation；模型执行仍由既有 MODEL Work 异步驱动。
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, USAGE);
    fixture.resolver.autoConsistent = true;
    assertTrue(fixture.processor.manualCompactionAvailability(baseline.threadId()).available());

    CompactThreadResult result =
        fixture.processor.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertEquals(1L, result.thread().revision());
    assertNotNull(result.modelInvocationId());
    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.head().payload();
    assertEquals(result.turnStartEntryId(), path.head().id());
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    assertEquals(branchSettings().model(), start.compaction().executionModel());
    assertNotNull(start.contextWindow());
    assertNotNull(start.maxOutputTokens());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, result.modelInvocationId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void compactThreadUsesTypedRevisionAndAvailabilityConflicts() {
    Fixture staleFixture = fixture();
    var stale = seedCompactionReadyClosedTurn(staleFixture.store, USAGE);
    HarnessRuntimeConflictException staleError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                staleFixture.processor.compactThread(
                    new CompactThreadCommand(stale.threadId(), 1)));
    assertEquals(HarnessRuntimeConflictException.Reason.STALE_REVISION, staleError.reason());
    assertEquals(9, path(staleFixture.store, stale.threadId()).entries().size());

    Fixture busyFixture = fixture();
    var busy = seedOpenInputTurn(busyFixture.store);
    HarnessRuntimeConflictException busyError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                busyFixture.processor.compactThread(new CompactThreadCommand(busy.threadId(), 0)));
    assertEquals(
        HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE, busyError.reason());
  }

  @Test
  void resolverRejectionClosesManualTurnWithoutModelInvocation() {
    // Resolver 业务拒绝仍形成完整 failed turn；没有半开的 manual intent。
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, USAGE);
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable")));

    CompactThreadResult result =
        fixture.processor.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNull(result.modelInvocationId());
    assertEquals(1L, result.thread().revision());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
  }
}
