package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.LEASE_CONFIG;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.RESOLVE_FAILURE_DELAY;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.touchThreadTimestamp;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.SessionFirstThreadLockStore.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ThreadProcessor continuation / input planning 与 speculative plan + CAS 提交、reschedule、lease
 * margin：每个 claim 恰执行一个动作。
 */
class ThreadProcessorPlanningTest extends ThreadProcessorTestBase {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  @Test
  void entryMutationsLockSessionBeforeThread() {
    // INPUT 的 plan 与 commit 两个事务都经过守卫；任一事务先锁 Thread 都会确定性失败，而不是等待偶发数据库死锁。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Fixture fixture = fixture(wrap(store));
    var baseline = seedBaseline(store);
    seedCommand(
        store, baseline.threadId(), new UserMessageCommandPayload(userMessage("session-first")));
    requestThreadWork(store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    ClaimedWork claim = claimThreadWork(store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));
  }

  @Test
  void inputConsumesExactlyOnePreexistingUserMessage() {
    // INPUT 只消费到首条 user-like，后续消息保持 QUEUED（不把整个 queued snapshot 写进同一 INPUT）。
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID first =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("first")));
    UUID second =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("second")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(3, path.entries().size());
    MessagePayload user = (MessagePayload) path.head().payload();
    assertEquals("first", ((TextMessageContent) user.message().contents().getFirst()).text());
    assertEquals(
        ThreadCommandState.APPLIED, command(fixture.store, baseline.threadId(), first).state());
    assertEquals(
        ThreadCommandState.QUEUED, command(fixture.store, baseline.threadId(), second).state());
  }

  /**
   * continuation 优先于 queued USER：claim1 只创建 continuation（不消费 USER、不 wake THREAD）；模拟 ModelProcessor
   * 完成后 claim2 关闭 turn 时按 queued 快照 wake THREAD；claim3 才用保留 message 启动 INPUT。
   */
  @Test
  void continuationHasPriorityOverQueuedInputAndLeavesMessagesForDeferredWake() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：continuation plan -> resolve -> commit resolved：只请求 MODEL Work，不因 deferred USER 制造
    // THREAD claim。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterContinuation = path(fixture.store, baseline.threadId());
    assertEquals(6, afterContinuation.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) afterContinuation.entries().get(5).payload();
    assertEquals(TurnStartReason.CONTINUATION, turnStart.reason());
    // continuation 不消费 USER_MESSAGE。
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // 无 THREAD wake：本 claim 的 THREAD Work 被 complete 删除，仅 MODEL Work 保留。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    ModelInvocation continuationModel =
        inTx(
                fixture,
                tx ->
                    tx.findModelInvocationByTurn(
                        baseline.threadId(), afterContinuation.entries().get(5).id()))
            .orElseThrow();
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, continuationModel.id())));
    assertEquals(1, fixture.resolver.calls);

    // 模拟 ModelProcessor 完成 continuation Model 并请求 THREAD（enqueue 侧调度原语，重建 Work 行 v1）。
    transitionModel(fixture.store, continuationModel.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, continuationModel.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        continuationModel.id(),
        m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());

    // claim2：closed apply（COMPLETE 无 calls）按 queued 快照重建 wake：先 request THREAD（wakeVersion=2）再
    // complete。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterApply = path(fixture.store, baseline.threadId());
    assertEquals(8, afterApply.entries().size());
    // deferred wake：THREAD Work 保留、lease 已清，wakeVersion 从 enqueue 侧 v1 抬升到 apply 侧 v2。
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(2L, threadWork.wakeVersion());
    // 关闭的 turn 未消费 USER；Model 行已被物理删除。
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertTrue(inTx(fixture, tx -> tx.findModelInvocation(continuationModel.id())).isEmpty());
    assertEquals(1, fixture.resolver.calls);

    // claim3：保留 message 触发 INPUT turn（consume 后本 claim 的 THREAD Work 被 complete 删除）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(10, path.entries().size());
    TurnStartPayload input = (TurnStartPayload) path.entries().get(8).payload();
    assertEquals(TurnStartReason.INPUT, input.reason());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    ModelInvocation inputModel =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(8).id()))
            .orElseThrow();
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, inputModel.id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(2, fixture.resolver.calls);
  }

  @Test
  void continuationConsumesOnlyOrdinaryConfigCommandsAndAppliesSettings() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    // final branch 事实 = 消费 SET_MODEL 后的 candidate settings；auto 模式按同源事实构造一致请求。
    fixture.resolver.autoConsistent = true;

    // 一个 claim：continuation 只消费 SET_MODEL（USER 保留为 deferred，不在本 claim wake）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals("model-b", turnStart.settings().model().modelName());
    assertEquals("v2", turnStart.settings().model().variant());
    // SET_MODEL 被 continuation 消费；USER_MESSAGE 保留。
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path.entries().get(5).id(),
        command(fixture.store, baseline.threadId(), modelCommand).appliedTurnStartEntryId());
    assertEquals(1, fixture.resolver.calls);
  }

  @Test
  void configOnlyWithoutContinuationCreatesNoTurnAndNeverCallsResolver() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID configCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：无 continuation / 无 message，config-only 直接 quiesce。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(0, fixture.resolver.calls);
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), configCommand).state());
  }

  /** 测试意图：CONTINUATION 消费 SET_* 后只写 TURN_START.settings，不生成模型可见消息。 */
  @Test
  void continuationAppliesSettingsWithoutInjectingMessages() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    // 关闭的原 turn 5 条 Entry + TURN_START(CONTINUATION)。
    assertEquals(6, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.CONTINUATION, turnStart.reason());
    assertEquals("model-b", turnStart.settings().model().modelName());
    assertEquals("v2", turnStart.settings().model().variant());
    assertEquals(turnStart, path.head().payload());
    // SET_MODEL 已被 continuation 消费并记录在其 TURN_START 上；无 deferred user demand。
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        path.entries().get(5).id(),
        command(fixture.store, baseline.threadId(), modelCommand).appliedTurnStartEntryId());
  }

  @Test
  void inputTurnAppliesSettingsAndMessagesInOneAtomicTurn() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：INPUT turn 一次构建并提交（Model Work 驱动，无 THREAD wake）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    // ROOT + TURN_START(INPUT) + USER message；配置只存在于 TURN_START.settings。
    assertEquals(3, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(1).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    assertEquals("model-b", turnStart.settings().model().modelName());
    MessagePayload userPayload = (MessagePayload) path.entries().get(2).payload();
    assertEquals(AgentMessageRole.USER, userPayload.message().role());
    assertEquals("hi", ((TextMessageContent) userPayload.message().contents().get(0)).text());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path.entries().get(2).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    // ModelInvocation：requestHead == candidate head，turnStart 指向新 TURN_START，MODEL Work 已请求。
    ModelInvocation invocation =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(1).id()))
            .orElseThrow();
    assertEquals(path.entries().get(2).id(), invocation.requestHeadEntryId());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, invocation.id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(1, fixture.resolver.calls);
  }

  /**
   * plan 使用首次锁定 Thread/path 的 durable floor；resolve 期间同 head 的并发 Thread touch 进一步推进时间后，commit 会重标
   * candidate Entries 与新 ModelInvocation，而 Work lease 判断仍使用 raw local clock。
   */
  @Test
  void resolvedCommitRebasesNewFactsToConcurrentThreadTimestampWithoutClampingLeaseClock() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    Instant planFloor = NOW.plusSeconds(120);
    Instant commitFloor = NOW.plusSeconds(180);
    touchThreadTimestamp(fixture.store, baseline.threadId(), planFloor);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    fixture.resolver.onResolve =
        () -> {
          for (Entry candidate : fixture.resolver.lastPath.entries().subList(1, 3)) {
            assertEquals(planFloor, candidate.createdAt());
          }
          touchThreadTimestamp(fixture.store, baseline.threadId(), commitFloor);
        };

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath durablePath = path(fixture.store, baseline.threadId());
    for (Entry committed : durablePath.entries().subList(1, 3)) {
      assertEquals(commitFloor, committed.createdAt());
    }
    ModelInvocation invocation =
        inTx(
                fixture,
                tx ->
                    tx.findModelInvocationByTurn(
                        baseline.threadId(), durablePath.entries().get(1).id()))
            .orElseThrow();
    assertEquals(commitFloor, invocation.createdAt());
    assertEquals(commitFloor, invocation.updatedAt());
    assertEquals(commitFloor, thread(fixture.store, baseline.threadId()).updatedAt());
  }

  /**
   * Resolver 两阶段提交的 YOLO 以第二事务锁到的 Thread 当前值为准：plan 在 yolo=false 时构造，resolve 期间并发 {@code
   * setThreadYolo(true)}（version 0-&gt;1）成功，commit 重锁 Thread 后仍用最新的 {@code yoloEnabled=true} 推进
   * head（version 再 +1），绝不回写 plan 冻结值。
   */
  @Test
  void resolvedCommitAdvancesYoloFromSecondTransactionLockedValue() throws Exception {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
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

    Thread processing = new Thread(() -> fixture.processor.process(claim));
    processing.start();
    assertTrue(resolverEntered.await(5, TimeUnit.SECONDS));
    // 并发直接控制面：与 plan 无关的独立短事务，成功（seedCommand 已把 version 推进到 1，CAS 精确匹配）。
    ThreadState yoloUpdate =
        fixture.runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), 1, true));
    assertTrue(yoloUpdate.yoloEnabled());
    assertEquals(2L, yoloUpdate.version());
    releaseResolver.countDown();
    processing.join(5000);
    assertFalse(processing.isAlive());

    ThreadState finalThread = thread(fixture.store, baseline.threadId());
    assertTrue(finalThread.yoloEnabled());
    // seedCommand +1，setThreadYolo +1，commit advanceHead 再 +1。
    assertEquals(3L, finalThread.version());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path(fixture.store, baseline.threadId()).entries().get(2).id(), finalThread.headEntryId());
    assertEquals(1, fixture.resolver.calls);
  }

  @Test
  void typedRejectionWritesAssistantErrorAndFailedTurnEndWithoutInvocation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));

    // 一个 claim：rejected（message 已消费，无 deferred）直接完成，不请求 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(1).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    AssistantErrorPayload error = (AssistantErrorPayload) path.entries().get(3).payload();
    assertEquals("CONFIG_ERROR", error.error().code());
    assertEquals("bad config", error.error().message());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(path.entries().get(1).id(), end.turnStartEntryId());
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertFalse(end.continueModel());
    assertEquals(TurnEndReason.TURN_FAILED, end.reason());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path.entries().get(4).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertTrue(
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(1).id()))
            .isEmpty());
    // 无 deferred message、闭合也未触发 compaction（ROOT 无历史）-> 不保留 THREAD Work。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /**
   * continuation 被 reject 但保留 deferred USER：commit 必须先请求 THREAD 再 complete（wake 保留），queued message
   * 才能由后续 claim 处理。
   */
  @Test
  void rejectedContinuationWithDeferredMessagesKeepsThreadWork() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));

    // 一个 claim：rejected 提交（候选 TURN_START + error + FAILED TURN_END）；deferred USER 保留 -> 先请求 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(8, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.CONTINUATION, turnStart.reason());
    AssistantErrorPayload error = (AssistantErrorPayload) path.entries().get(6).payload();
    assertEquals("CONFIG_ERROR", error.error().code());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(7).payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(TurnEndReason.TURN_FAILED, end.reason());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // deferred wake：THREAD Work 保留（lease 已清）。
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(1, fixture.resolver.calls);
  }

  /**
   * resolve 期间 enqueue 侧新 wake 保留 THREAD Work；本 claim 的 commit 只请求 MODEL Work，complete 仍保留新 wake。
   */
  @Test
  void cutoffCommandArrivingDuringResolveCommitsButIsExcluded() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID firstCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("first")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    // resolve 期间入队第二条 USER（sequence > cutoff）并请求 THREAD Work（enqueue 侧行为）。
    fixture.resolver.onResolve =
        () -> {
          UUID second =
              seedCommand(
                  fixture.store,
                  baseline.threadId(),
                  new UserMessageCommandPayload(userMessage("second")));
          assertEquals(2L, command(fixture.store, baseline.threadId(), second).sequence());
          requestThreadWork(fixture.store, baseline.threadId());
        };

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // 本 turn 只消费 cutoff 内命令；第二条命令保留 -> lost-wake 保留 Work 行（lease 已清）。
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(3, path.entries().size());
    MessagePayload message = (MessagePayload) path.entries().get(2).payload();
    assertEquals("first", ((TextMessageContent) message.message().contents().get(0)).text());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), firstCommand).state());
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(2L, threadWork.wakeVersion());
    // resolver 看到的 candidate path 只包含 first message。
    assertEquals(3, fixture.resolver.lastPath.entries().size());
    assertTrue(
        fixture.resolver.lastPath.entries().stream()
            .noneMatch(
                e ->
                    e.payload() instanceof MessagePayload m
                        && m.message().role() == AgentMessageRole.USER
                        && ((TextMessageContent) m.message().contents().get(0))
                            .text()
                            .equals("second")));
  }

  @Test
  void cancelledCutoffCommandLosesCommitAtomically() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    fixture.resolver.onResolve =
        () ->
            inTx(
                fixture,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  List<ThreadCommand> cancelled = new ArrayList<>();
                  for (ThreadCommand queued : tx.loadQueuedCommands(baseline.threadId())) {
                    cancelled.add(queued.cancel(TestIds.id(1), NOW.plusSeconds(1)));
                  }
                  tx.updateCommands(cancelled);
                  return null;
                });

    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.nextClaim(baseline.threadId()));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.CANCELLED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }

  @Test
  void movedHeadDuringResolveLosesCommitAtomically() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    // resolve 期间另一 actor 在 ROOT 下打开新 Turn 并移动 head。
    fixture.resolver.onResolve =
        () ->
            inTx(
                fixture,
                tx -> {
                  UUID turnStartId = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          turnStartId,
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          new TurnStartPayload(
                              TurnStartReason.INPUT,
                              ThreadProcessorTestSupport.branchSettings(),
                              OWNER_THREAD_ID),
                          NOW));
                  ThreadState current = tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.updateThread(current.advanceHead(turnStartId, NOW));
                  return null;
                });

    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.nextClaim(baseline.threadId()));

    // 其他 actor 的 TURN_START 保留，本 plan 零写入。
    assertEquals(2, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }

  @Test
  void deletedWorkDuringResolveLosesCommitAtomically() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    fixture.resolver.onResolve =
        () ->
            inTx(
                fixture,
                tx -> {
                  tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.deleteWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
                  return null;
                });

    // commit 的 final fence（在全部低序 mutation 之后）丢失：整事务回滚，零 Entry / Command / Thread mutation。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.nextClaim(baseline.threadId()));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // seedCommand 的 reserveCommandSequences 已 +1；失败的提交没有再次改变 version。
    assertEquals(1L, thread(fixture.store, baseline.threadId()).version());
  }

  @Test
  void resolverExceptionReschedulesWithZeroDurableMutation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.failure = new IllegalStateException("resolver db down");
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.RESCHEDULED, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(NOW.plus(RESOLVE_FAILURE_DELAY), threadWork.availableAt());
  }

  @Test
  void resolverNullResultReschedulesWithZeroDurableMutation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.RESCHEDULED, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void heartbeatSchedulingFailureReschedulesWithZeroDurableMutation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    // scheduler 已关闭：resolve 期间无法启动 heartbeat -> reschedule，零 durable mutation。
    fixture.scheduler.shutdownNow();

    assertEquals(ThreadProcessResult.RESCHEDULED, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void nearExpiryClaimRenewsLeaseAtPlanTimeAndCommits() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    // claim 的 lease 仅剩 4s（< heartbeat interval 5s）：plan 事务必须 renew 到 now + leaseDuration，否则
    // Resolver 首次 heartbeat 前 lease 即过期。
    fixture.resolver.onResolve =
        () -> {
          Work threadWork =
              work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          assertEquals(NOW.plus(LEASE_CONFIG.leaseDuration()), threadWork.leaseUntil());
          assertNotNull(threadWork.leaseToken());
        };
    ClaimedWork claim =
        claimThreadWork(fixture.store, baseline.threadId(), NOW, NOW.plusSeconds(4));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }
}
