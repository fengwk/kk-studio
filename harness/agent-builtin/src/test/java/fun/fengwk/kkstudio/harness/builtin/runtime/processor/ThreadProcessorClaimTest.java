package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.claimLosingStore;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.work;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    assertNull(
        fixture
            .store
            .transaction(tx -> builtin(fixture.store, tx).findModelInvocation(modelId))
            .orElse(null));
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

  /**
   * 测试意图：同一 claim 被两个独立线程重复投递时由 store 事务串行化；第二个投递在第一个事务提交后看到 claim 已消费， 返回 LOST no-op，不产生第二套 Turn
   * 写入。
   */
  @Test
  void duplicateClaimDeliveredConcurrentlyIsLostNoOp() throws Exception {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    CountDownLatch resolverEntered = new CountDownLatch(1);
    CountDownLatch releaseResolver = new CountDownLatch(1);
    fixture.resolver.onResolve =
        () -> {
          resolverEntered.countDown();
          try {
            releaseResolver.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        };
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    AtomicReference<ThreadProcessResult> firstResult = new AtomicReference<>();
    AtomicReference<ThreadProcessResult> secondResult = new AtomicReference<>();
    Thread first = new Thread(() -> firstResult.set(fixture.processor.process(claim)));
    first.start();
    assertTrue(resolverEntered.await(5, TimeUnit.SECONDS));
    // 第二个投递使用独立线程：store 事务锁使其等待第一个事务结束，而不是在 resolver 内重入 process。
    Thread second = new Thread(() -> secondResult.set(fixture.processor.process(claim)));
    second.start();
    releaseResolver.countDown();
    first.join(5_000);
    second.join(5_000);
    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertEquals(ThreadProcessResult.COMPLETED, firstResult.get());
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, secondResult.get());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(1, fixture.resolver.calls);
  }

  /**
   * 测试意图：resolved turn 在写完全部低序 mutation 后的最终 claim fence 丢失时，整事务回滚；resolver 已调用但无任何 Turn / Command
   * / Thread mutation 泄漏。
   */
  @Test
  void resolvedTurnWithLostFinalFenceRollsBackWholeTransaction() {
    InMemoryBuiltinHarnessStore real = new InMemoryBuiltinHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var baseline = seedBaseline(real);
    UUID userCommand =
        seedCommand(real, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(real, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // fence 序列：claimOwned(1) -> commit final fence(2) 丢失 -> ClaimLostSignal -> 整体回滚。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(1, fixture.resolver.calls);
    assertEquals(1, path(real, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED, command(real, baseline.threadId(), userCommand).state());
    assertEquals(1L, thread(real, baseline.threadId()).version());
  }

  @Test
  void nonterminalModelBlockerWithLostClaimIsLostNoOp() {
    InMemoryBuiltinHarnessStore real = new InMemoryBuiltinHarnessStore();
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
    assertEquals(0L, thread(real, baseline.threadId()).version());
    assertNotNull(work(real, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void nonterminalToolSiblingsWithLostClaimIsLostNoOp() {
    InMemoryBuiltinHarnessStore real = new InMemoryBuiltinHarnessStore();
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
    InMemoryBuiltinHarnessStore real = new InMemoryBuiltinHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var baseline = seedBaseline(real);
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // fence 调用序列：claimOwned（1）-> quiescent 完成 fence（2）丢失 -> LOST，零 mutation。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));
    assertEquals(1, path(real, baseline.threadId()).entries().size());
    assertEquals(0L, thread(real, baseline.threadId()).version());
  }
}
