package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCompactionReadyClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
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
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.UUID;

/** 手动压缩 control：availability、version fence 与无 mailbox 的 durable plan 提交。 */
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

    assertEquals(1L, result.thread().version());
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
  void compactThreadUsesTypedVersionAndAvailabilityConflicts() {
    Fixture staleFixture = fixture();
    var stale = seedCompactionReadyClosedTurn(staleFixture.store, USAGE);
    HarnessRuntimeConflictException staleError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                staleFixture.processor.compactThread(
                    new CompactThreadCommand(stale.threadId(), 1)));
    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, staleError.reason());
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
    assertEquals(1L, result.thread().version());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
  }

  @Test
  void threadMustExistForAvailabilityAndCompact() {
    // 线程不存在时，availability 查询与 compact 提交必须确定性抛出 NotFound 异常。
    Fixture fixture = fixture();
    UUID nonExistentId = UUID.randomUUID();
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> fixture.processor.manualCompactionAvailability(nonExistentId));
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> fixture.processor.compactThread(new CompactThreadCommand(nonExistentId, 0)));
  }

  @Test
  void compactThreadRejectsNullResolverResult() {
    // Resolver 返回 null 违背契约，必须直接抛出 IllegalStateException，零 durable mutation。
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, USAGE);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.processor.compactThread(new CompactThreadCommand(baseline.threadId(), 0)));
    assertEquals("turn resolver returned null for manual compaction", error.getMessage());
    assertEquals(0L, thread(fixture.store, baseline.threadId()).version());
  }

  @Test
  void compactThreadRejectsConcurrentCommandAcceptedDuringResolve() {
    // plan 后若有新命令推进 Thread version，第二事务必须命中 CAS fence，不能提交基于旧快照的压缩 Turn。
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, USAGE);
    fixture.resolver.autoConsistent = true;
    fixture.resolver.onResolve =
        () ->
            seedCommand(
                fixture.store,
                baseline.threadId(),
                new UserMessageCommandPayload(userMessage("concurrent input")));

    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                fixture.processor.compactThread(new CompactThreadCommand(baseline.threadId(), 0)));

    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, error.reason());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).version());
    assertEquals(
        baseline.turnEndEntryId(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(9, path(fixture.store, baseline.threadId()).entries().size());
  }

  @Test
  void resolverRejectionWakesThreadWhenDeferredUserMessagesExist() {
    // Resolver 业务拒绝且存在待处理 deferred user message 时，commit 必须追加 FAILED turn 并唤醒 THREAD Work。
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, USAGE);
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("deferred input")));
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable")));

    long currentVersion = thread(fixture.store, baseline.threadId()).version();
    CompactThreadResult result =
        fixture.processor.compactThread(
            new CompactThreadCommand(baseline.threadId(), currentVersion));

    assertNull(result.modelInvocationId());
    assertEquals(currentVersion + 1, result.thread().version());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }
}
