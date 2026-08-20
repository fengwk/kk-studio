package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimLosingStore;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** ThreadProcessor claim 与 admission：非 THREAD claim、duplicate / stale claim 一律 LOST no-op。 */
class ThreadProcessorClaimTest extends ThreadProcessorTestBase {

  @Test
  void rejectsNonThreadClaim() {
    Fixture fixture = fixture();
    ClaimedWork modelClaim =
        new ClaimedWork(
            new WorkTarget(WorkTargetType.MODEL, new UUID(0L, 1L)),
            1L,
            "token",
            NOW.plusSeconds(60));
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(modelClaim));
  }

  @Test
  void duplicateClaimAfterCompletionIsLostNoOp() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
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
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));
    // 同一 claim 再次投递：Work 已完成删除，claimOwned 失败 -> LOST no-op。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    // SUCCEEDED-no-calls 关闭 turn 后 Model 行被物理删除。
    assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));
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
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    // resolve 期间重入投递同一 claim：admission guard 拒绝 -> LOST no-op；外层继续完成提交。
    fixture.resolver.onResolve =
        () ->
            assertEquals(
                ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(fixture.claim));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    fixture.claim = claim;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }

  @Test
  void nonterminalModelBlockerWithLostClaimIsLostNoOp() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var baseline = seedOpenInputTurn(real);
    seedModelInvocation(
        real,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.READY,
        plainRequest(),
        null,
        null);
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // fence 调用序列：claimOwned（1）-> ModelActive blocker 的 Work fence（2）丢失 -> ClaimLostSignal -> LOST
    // no-op。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(3, path(real, baseline.threadId()).entries().size());
    assertEquals(0L, thread(real, baseline.threadId()).revision());
    assertNotNull(work(real, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void nonterminalToolSiblingsWithLostClaimIsLostNoOp() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var chain =
        seedToolChain(
            real,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.READY));
    requestThreadWork(real, chain.turn().threadId());
    ClaimedWork claim = claimThreadWork(real, chain.turn().threadId());

    // fence 调用序列：claimOwned（1）-> ToolActive blocker 的 Work fence（2）丢失 -> LOST no-op（不挂结果）。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(4, path(real, chain.turn().threadId()).entries().size());
    assertEquals(chain.assistantEntryId(), thread(real, chain.turn().threadId()).headEntryId());
    assertNotNull(work(real, new WorkTarget(WorkTargetType.THREAD, chain.turn().threadId())));
  }

  @Test
  void quiescentClaimLostAtFinalFenceIsLostNoOp() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var baseline = seedBaseline(real);
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // fence 调用序列：claimOwned（1）-> quiescent 完成 fence（2）丢失 -> LOST，零 mutation。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(1, path(real, baseline.threadId()).entries().size());
    assertEquals(0L, thread(real, baseline.threadId()).revision());
  }

  @Test
  void planClaimLostAtPlanFenceIsLostNoOp() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var baseline = seedBaseline(real);
    seedCommand(real, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // fence 调用序列：claimOwned（1）-> planStep 的 claim fence（2）丢失 -> ClaimLostSignal，resolver 不被调用。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(1, path(real, baseline.threadId()).entries().size());
    assertEquals(0, fixture.resolver.calls);
  }

  @Test
  void heartbeatSchedulingFailureWithLostRescheduleIsLostNoOp() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 3));
    var baseline = seedBaseline(real);
    UUID userCommand =
        seedCommand(real, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());
    // scheduler 已关闭：resolve 前 heartbeat 无法启动 -> reschedule，但 reschedule 的 claim fence（3）丢失 -> LOST。
    fixture.scheduler.shutdownNow();

    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(1, path(real, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED, command(real, baseline.threadId(), userCommand).state());
    // reschedule 未发生：Work 行仍持有原 lease（到期后才能被再次 claim）。
    assertNotNull(
        work(real, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())).leaseToken());
  }

  @Test
  void resolverNullWithLostRescheduleIsLostNoOp() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 3));
    var baseline = seedBaseline(real);
    UUID userCommand =
        seedCommand(real, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // resolver 返回 null -> reschedule，但 reschedule 的 claim fence（3）丢失 -> LOST，零 durable mutation。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(1, path(real, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED, command(real, baseline.threadId(), userCommand).state());
    assertEquals(1L, thread(real, baseline.threadId()).revision());
  }
}
