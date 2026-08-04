package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.model;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.util.List;

/** ThreadProcessor claim 与 admission：非 THREAD claim、duplicate / stale claim 一律 LOST no-op。 */
class ThreadProcessorClaimTest extends ThreadProcessorTestBase {

  @Test
  void rejectsNonThreadClaim() {
    Fixture fixture = fixture();
    ClaimedWork modelClaim =
        new ClaimedWork(new WorkTarget(WorkTargetType.MODEL, 1L), 1L, "token", NOW.plusSeconds(60));
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(modelClaim));
  }

  @Test
  void duplicateClaimAfterCompletionIsLostNoOp() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            plainRequest(),
            successResponse(List.of(), "bash"),
            null);
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.QUIESCENT, fixture.processor.process(claim));
    // 同一 claim 再次投递：Work 已完成删除，claimOwned 失败 -> LOST no-op。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertNotNull(model(fixture.store, modelId).resultEntryId());
  }

  @Test
  void staleLeaseIsLostNoOp() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.READY,
        plainRequest(),
        null,
        null);
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    // lease 在 claim 后过期：所有步骤 claim fence 失败，LOST 且零 mutation。
    fixture.clock.advance(Duration.ofSeconds(61));
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(0, fixture.resolver.calls);
    assertEquals(3, path(fixture.store, baseline.threadId()).entries().size());
  }

  @Test
  void duplicateDeliveryWhileProcessingIsLostNoOp() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    // resolve 期间重入投递同一 claim：admission guard 拒绝 -> LOST no-op；外层继续完成提交。
    fixture.resolver.onResolve =
        () ->
            assertEquals(
                ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(fixture.claim));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    fixture.claim = claim;
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }
}
