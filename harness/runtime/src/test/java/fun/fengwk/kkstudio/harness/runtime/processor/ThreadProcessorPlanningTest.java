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
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetYoloCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ThreadProcessor continuation / input planning 与 speculative plan + CAS 提交、reschedule、lease
 * margin。
 */
class ThreadProcessorPlanningTest extends ThreadProcessorTestBase {

  @Test
  void continuationHasPriorityOverQueuedInputAndLeavesMessagesForDeferredWake() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(6, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.CONTINUATION, turnStart.reason());
    // continuation 不消费 USER_MESSAGE。
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // deferred wake：continuation 保留 USER/CUSTOM 时先 request THREAD Work 再 complete -> Work 保留、lease
    // 已清。
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(2L, threadWork.wakeVersion());
    assertEquals(1, fixture.resolver.calls);
    assertFalse(fixture.resolver.lastYoloEnabled);
  }

  @Test
  void continuationConsumesOnlyOrdinaryConfigCommandsAndAppliesSettings() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    long modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    long envCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetEnvironmentCommandPayload(new EnvironmentId(UUID.randomUUID().toString())));
    long yoloCommand =
        seedCommand(fixture.store, baseline.threadId(), new SetYoloCommandPayload(true));
    requestThreadWork(fixture.store, baseline.threadId());
    // final branch 事实 = 消费 SET_MODEL/SET_YOLO 后的 candidate settings；auto 模式按同源事实构造一致请求。
    fixture.resolver.autoConsistent = true;
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals("model-b", turnStart.settings().model().modelName());
    assertEquals("v2", turnStart.settings().model().variant());
    // SET_MODEL / SET_YOLO 被 continuation 消费；USER_MESSAGE / SET_ENVIRONMENT 保留。
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        ThreadCommandState.QUEUED, command(fixture.store, baseline.threadId(), envCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), yoloCommand).state());
    assertTrue(thread(fixture.store, baseline.threadId()).yoloEnabled());
    assertTrue(fixture.resolver.lastYoloEnabled);
    assertEquals(
        path.entries().get(5).id(),
        command(fixture.store, baseline.threadId(), modelCommand).consumedTurnStartEntryId());
  }

  @Test
  void configOnlyWithoutContinuationCreatesNoTurnAndNeverCallsResolver() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long configCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.QUIESCENT, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(0, fixture.resolver.calls);
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), configCommand).state());
  }

  @Test
  void inputTurnAppliesSettingsAndMessagesInOneAtomicTurn() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(new ModelSelection("provider", "model-b", "v2")));
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    long yoloCommand =
        seedCommand(fixture.store, baseline.threadId(), new SetYoloCommandPayload(true));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, baseline.threadId());
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
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), yoloCommand).state());
    assertTrue(thread(fixture.store, baseline.threadId()).yoloEnabled());
    assertEquals(
        path.entries().get(2).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    // ModelInvocation：basis == candidate head，turnStart 指向新 TURN_START，MODEL Work 已请求。
    ModelInvocation invocation =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(1).id()))
            .orElseThrow();
    assertEquals(path.entries().get(2).id(), invocation.basisHeadEntryId());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, invocation.id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(1, fixture.resolver.calls);
  }

  @Test
  void typedRejectionWritesAssistantErrorAndFailedTurnEndWithoutInvocation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.QUIESCENT, fixture.processor.process(claim));

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
  }

  @Test
  void cutoffCommandArrivingDuringResolveCommitsButIsExcluded() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long firstCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("first")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    // resolve 期间入队第二条 USER（sequence > cutoff）并请求 THREAD Work（enqueue 侧行为）。
    fixture.resolver.onResolve =
        () -> {
          long second =
              seedCommand(
                  fixture.store,
                  baseline.threadId(),
                  new UserMessageCommandPayload(userMessage("second")));
          assertEquals(2L, command(fixture.store, baseline.threadId(), second).sequence());
          requestThreadWork(fixture.store, baseline.threadId());
        };
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

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
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    fixture.resolver.onResolve =
        () ->
            inTx(
                fixture,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  List<ThreadCommand> cancelled = new ArrayList<>();
                  for (ThreadCommand queued : tx.loadQueuedCommands(baseline.threadId())) {
                    cancelled.add(queued.cancel(NOW.plusSeconds(1)));
                  }
                  tx.updateCommands(cancelled);
                  return null;
                });
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.CANCELLED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }

  @Test
  void movedHeadDuringResolveLosesCommitAtomically() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    // resolve 期间另一 actor 在 ROOT 下打开新 Turn 并移动 head。
    fixture.resolver.onResolve =
        () ->
            inTx(
                fixture,
                tx -> {
                  long turnStartId = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          turnStartId,
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          new TurnStartPayload(
                              TurnStartReason.INPUT, ThreadProcessorTestSupport.branchSettings()),
                          NOW));
                  ThreadState current = tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.updateThread(current.advanceHead(turnStartId, false, NOW));
                  return null;
                });
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));

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
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    fixture.resolver.onResolve =
        () ->
            inTx(
                fixture,
                tx -> {
                  tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.deleteWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
                  return null;
                });
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // commit 的 final fence（在全部低序 mutation 之后）丢失：整事务回滚，零 Entry / Command / Thread mutation。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // seedCommand 的 reserveCommandSequences 已 +1；失败的提交没有再次改变 revision。
    assertEquals(1L, thread(fixture.store, baseline.threadId()).revision());
  }

  @Test
  void resolverExceptionReschedulesWithZeroDurableMutation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long userCommand =
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
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());
    // scheduler 已关闭：resolve 期间无法启动 heartbeat -> reschedule，零 durable mutation。
    fixture.scheduler.shutdownNow();

    assertEquals(ThreadProcessResult.RESCHEDULED, fixture.processor.process(claim));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void stepLimitReschedulesWorkWithoutSilentDrop() {
    Fixture fixture = fixture(1);
    var baseline = seedOpenInputTurn(fixture.store);
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        plainRequest(),
        ThreadProcessorTestSupport.successResponse(List.of(), "bash"),
        null);
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // step 1 应用 terminal Model；step limit 用尽 -> reschedule，不丢 Work。
    assertEquals(ThreadProcessResult.RESCHEDULED, fixture.processor.process(claim));
    assertEquals(5, path(fixture.store, baseline.threadId()).entries().size());
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());

    // 到期后重新 claim：继续处理 queued input，最终 SUSPENDED。
    fixture.clock.advance(RESOLVE_FAILURE_DELAY);
    ClaimedWork nextClaim =
        claimThreadWork(fixture.store, baseline.threadId(), fixture.clock.instant());
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(nextClaim));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(7, path(fixture.store, baseline.threadId()).entries().size());
  }

  @Test
  void nearExpiryClaimRenewsLeaseAtPlanTimeAndCommits() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    long userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
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

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
  }
}
