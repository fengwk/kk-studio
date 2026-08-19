package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CONTEXT_WINDOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.ClosedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.compactionRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.compactionUserText;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.resolvedCompactionTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.resolvedInputTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCompactionReadyClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryAssembler;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** ThreadProcessor 自动压缩 turn：阈值触发、输入不绕过、FULL 应用、HISTORY 部分延续与 overflow 语义（每 claim 一个动作）。 */
class ThreadProcessorCompactionTest extends ThreadProcessorTestBase {

  private static final ModelUsage OVER_THRESHOLD_USAGE =
      new ModelUsage(90_000L, 2L, 0L, 0L, 0L, 0L, 90_002L);

  @Test
  void thresholdCompactionRunsBeforeQueuedInputAndConsumesZeroCommands() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    // closed historical turn 不保留 invocation：阈值压缩只按 Entry 上的 owner/contextWindow + usage 事实触发。
    assertTrue(
        fixture
            .store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(baseline.threadId(), baseline.turnStartEntryId()))
            .isEmpty());
    UUID userCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("queued input")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：压缩优先于输入，启动 COMPACTION turn 并提交（消费零 Command），用户消息仍在队列。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(10, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    Boolean inputStillQueued =
        fixture.store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId()).stream()
                  .anyMatch(c -> c.clientCommandId().equals(userCommand));
            });
    assertTrue(inputStillQueued);
    // resolved 压缩不制造 active 期无意义 THREAD Work：本 claim 的 THREAD 行已 complete 删除（deferred input
    // 由压缩关闭时的 queued 快照重建 wake）。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    ModelInvocation compactionInvocation =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(9).id()))
            .orElseThrow();
    assertEquals(CompactionPhase.FULL, compactionInvocation.request().compaction().phase());
    assertEquals(
        CompactionTrigger.THRESHOLD, compactionInvocation.request().compaction().trigger());
  }

  /**
   * 真实普通成功 turn 的 usage 刚超过 threshold：Model apply 关闭 turn 时同事务用新 head path 判定 compaction 立即到期 ->
   * 自动保留 THREAD Work，下一 claim 启动压缩（无任何手工 wake）。
   */
  @Test
  void overThresholdSuccessfulTurnAutoWakesCompactionAfterClose() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    fixture.resolver.autoConsistent = true;

    // claim1：turn1 短消息普通成功（usage 未超阈值）关闭，无 THREAD wake。
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        plainRequest(),
        successResponse(usage(), cost(), "first reply"),
        null);
    requestThreadWork(
        fixture.store, baseline.threadId()); // ModelProcessor 完成时的真实 enqueue-side wake
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterTurn1 = path(fixture.store, baseline.threadId());
    assertEquals(5, afterTurn1.entries().size());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // turn2：长 USER + 超阈值成功 ASSISTANT，先经 INPUT claim 落库（queued 命令进入历史）。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage(compactionUserText())));
    requestThreadWork(fixture.store, baseline.threadId()); // 入站命令的 enqueue-side wake
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath inputPlanned = path(fixture.store, baseline.threadId());
    assertEquals(7, inputPlanned.entries().size());
    Entry inputStartEntry = inputPlanned.entries().get(5);
    TurnStartPayload inputStart = (TurnStartPayload) inputStartEntry.payload();
    assertEquals(TurnStartReason.INPUT, inputStart.reason());
    assertEquals(baseline.threadId(), inputStart.ownerThreadId());
    ModelInvocation turn2Model =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), inputStartEntry.id()))
            .orElseThrow();

    // 模拟 ModelProcessor 完成 turn2 Model 并请求 THREAD（enqueue 侧调度原语，重建 Work 行 v1）。
    transitionModel(fixture.store, turn2Model.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, turn2Model.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        turn2Model.id(),
        m -> m.succeed(successResponse(OVER_THRESHOLD_USAGE, cost(), "second reply"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());

    // claim2：closed apply（COMPLETE 无 calls）关闭 turn；闭合后 compactionPreparation 判定 THRESHOLD 立即
    // 到期 -> 自动保留 Work（wakeVersion v1 -> v2）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(9, applied.entries().size());
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(2L, threadWork.wakeVersion());

    // claim3：下一 claim 启动 threshold 压缩（无任何手工 wake）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath planned = path(fixture.store, baseline.threadId());
    assertEquals(10, planned.entries().size());
    TurnStartPayload start = (TurnStartPayload) planned.entries().get(9).payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(baseline.threadId(), start.ownerThreadId());
    assertEquals(CONTEXT_WINDOW, start.contextWindow());
  }

  /** 阈值 FULL 压缩：claim1 启动，claim2 成功应用并关闭 turn（continueModel=false，无 THREAD wake）。 */
  @Test
  void fullCompactionAppliesCompletePayloadAndClosesWithContinueModelFalse() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath planned = path(fixture.store, baseline.threadId());
    Entry start = planned.entries().get(9);
    ModelInvocation invocation =
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), start.id()))
            .orElseThrow();
    // 推进压缩调用成功（COMPLETED + 非空摘要文本）。
    transitionModel(fixture.store, invocation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, invocation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, invocation.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：THRESHOLD FULL 完成压缩关闭 turn（不继续，直接 complete）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(12, applied.entries().size());
    Entry result = applied.entries().get(10);
    CompactionPayload payload = (CompactionPayload) result.payload();
    assertEquals(CompactionPhase.FULL, payload.phase());
    assertEquals(CompactionTrigger.THRESHOLD, payload.trigger());
    assertTrue(payload.complete());
    assertTrue(payload.summaryText().contains("response text"));
    // COMPLETED 无 tool 的压缩 turn 已关闭：Model 行被物理删除（closed turn 不保留 Invocation）。
    assertNull(
        fixture.store.transaction(tx -> tx.findModelInvocation(invocation.id())).orElse(null));
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(11).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertFalse(end.continueModel()); // THRESHOLD 完成压缩不继续。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /** 完整压缩后失败普通 turn：压缩保持 freshness barrier；失败 turn 后的下一次 INPUT 仍正常。 */
  @Test
  void completeCompactionIsFreshnessBarrierAfterLaterFailedTurn() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    fixture.resolver.autoConsistent = true;
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Entry compactionStart = path(fixture.store, baseline.threadId()).head();
    ModelInvocation compaction =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), compactionStart.id()))
            .orElseThrow();
    transitionModel(fixture.store, compaction.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, compaction.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, compaction.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("after complete compaction")));
    requestThreadWork(fixture.store, baseline.threadId());
    // 完整压缩后的下一个 claim：queued USER 启动 INPUT（不再触发压缩）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath inputPlanned = path(fixture.store, baseline.threadId());
    Entry inputTurnStart = inputPlanned.entries().get(12);
    ModelInvocation input =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), inputTurnStart.id()))
            .orElseThrow();
    transitionModel(fixture.store, input.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, input.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        input.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider failed"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath failed = path(fixture.store, baseline.threadId());
    assertEquals(16, failed.entries().size());
    assertTrue(failed.head().payload() instanceof TurnEndPayload);
  }

  /** HISTORY 部分成功后的下一次 claim 机械延续 TURN_PREFIX 并冻结原切分事实。 */
  @Test
  void historyPartialThenFrozenTurnPrefixContinuation() {
    Fixture fixture = fixture();
    // 种子切分场景：HISTORY 部分成功（complete=false）后，下一次处理必须机械延续 TURN_PREFIX 且冻结原切分事实。
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    UUID[] ids = seedClosedHistoryPartial(fixture, baseline);
    // 关闭的 HISTORY partial 压缩 turn 不保留 invocation：TURN_PREFIX 延续只按 incomplete HISTORY payload 的
    // Entry 事实触发。
    assertTrue(
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), ids[0]))
            .isEmpty());

    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(13, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(12).payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    ModelInvocation continuation =
        fixture
            .store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(12).id()))
            .orElseThrow();
    assertEquals(CompactionPhase.TURN_PREFIX, continuation.request().compaction().phase());
    // 冻结复用 HISTORY payload 的切分事实（seed 布局：ROOT=2/TS1=3/USER1=4/ASST1=5），绝不重新选 cut。
    assertEquals(id(3L), continuation.request().compaction().firstKeptEntryId());
    assertEquals(id(5L), continuation.request().compaction().cutEntryId());
    assertEquals(id(4L), continuation.request().compaction().turnPrefixStartEntryId());
    assertEquals(500L, continuation.request().compaction().tokensBefore());
    assertEquals(CompactionTrigger.THRESHOLD, continuation.request().compaction().trigger());
  }

  /** TURN_PREFIX 延续成功：claim2 组装 complete payload 并关闭 turn（continueModel=false）。 */
  @Test
  void historyContinuationAppliesCompleteTurnPrefixPayloadAndClosesFalse() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    seedClosedHistoryPartial(fixture, baseline);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath planned = path(fixture.store, baseline.threadId());
    ModelInvocation continuation =
        fixture
            .store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(
                        baseline.threadId(), planned.entries().get(12).id()))
            .orElseThrow();
    transitionModel(fixture.store, continuation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, continuation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, continuation.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：TURN_PREFIX 完整 payload 应用并关闭（不继续）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(15, applied.entries().size());
    Entry result = applied.entries().get(13);
    CompactionPayload payload = (CompactionPayload) result.payload();
    assertEquals(CompactionPhase.TURN_PREFIX, payload.phase());
    assertTrue(payload.complete());
    assertEquals(
        "history summary\n\n---\n\n**Turn Context (split turn):**\n\nresponse text",
        payload.summaryText());
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(14).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertFalse(end.continueModel()); // HISTORY 派生的 TURN_PREFIX 完成不继续。
  }

  /**
   * 活 HISTORY 压缩模型成功 apply：compactionWake=true 保留 THREAD Work，下一 claim 才机械延续 TURN_PREFIX（不
   * self-poll）。
   */
  @Test
  void historyCompactionAppliesPartialPayloadAndWakesThreadForTurnPrefix() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    fixture.resolver.autoConsistent = true;
    // 手工打开一个 HISTORY 阶段 COMPACTION turn（模拟 planner 对切分事实的 HISTORY 阶段派生）。
    UUID turnStartId =
        inTx(
            fixture,
            tx -> {
              tx.lockThread(baseline.threadId());
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      baseline.sessionId(),
                      baseline.turnEndEntryId(),
                      resolvedCompactionTurnStart(baseline.threadId()),
                      NOW));
              tx.updateThread(
                  tx.findThread(baseline.threadId()).orElseThrow().advanceHead(id, NOW));
              return id;
            });
    ModelRequestSpec request = compactionRequest(historyPreparation());
    ProviderResponse response =
        successResponse("history summary", List.of(), GenerationStopReason.COMPLETE);
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            turnStartId,
            turnStartId,
            ModelInvocationStatus.SUCCEEDED,
            request,
            response,
            null);
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：HISTORY 成功 apply 关闭 turn，但 compactionWake=true -> 先请求 THREAD 再 complete。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(12, applied.entries().size());
    Entry result = applied.entries().get(10);
    CompactionPayload payload = (CompactionPayload) result.payload();
    assertEquals(CompactionPhase.HISTORY, payload.phase());
    assertFalse(payload.complete());
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(11).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertFalse(end.continueModel());
    // HISTORY 部分成功的延续 wake：THREAD Work 保留（lease 已清，wakeVersion 抬升）。
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    // 关闭的 HISTORY turn 不保留 invocation。
    assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));

    // claim2：下一 claim 才机械延续 TURN_PREFIX（冻结原切分事实）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath planned = path(fixture.store, baseline.threadId());
    assertEquals(13, planned.entries().size());
    TurnStartPayload nextStart = (TurnStartPayload) planned.entries().get(12).payload();
    assertEquals(TurnStartReason.COMPACTION, nextStart.reason());
    ModelInvocation prefix =
        fixture
            .store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(
                        baseline.threadId(), planned.entries().get(12).id()))
            .orElseThrow();
    assertEquals(CompactionPhase.TURN_PREFIX, prefix.request().compaction().phase());
    assertEquals(id(3L), prefix.request().compaction().firstKeptEntryId());
    assertEquals(id(5L), prefix.request().compaction().cutEntryId());
    assertEquals(id(4L), prefix.request().compaction().turnPrefixStartEntryId());
  }

  /** OVERFLOW 压缩：claim2 应用 continueModel=true，claim3 偿还原有 CONTINUATION 义务。 */
  @Test
  void overflowCompactionAppliesContinueModelTrueThenNextProcessingCreatesContinuation() {
    Fixture fixture = fixture();
    var baseline = seedOverflowFailedClosedTurn(fixture.store);
    // closed historical turn 不保留 invocation：OVERFLOW 压缩只按 Entry 上的错误事实触发。
    assertTrue(
        fixture
            .store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(baseline.threadId(), baseline.turnStartEntryId()))
            .isEmpty());
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：OVERFLOW 压缩启动。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath planned = path(fixture.store, baseline.threadId());
    assertEquals(10, planned.entries().size());
    Entry startEntry = planned.entries().get(9);
    TurnStartPayload start = (TurnStartPayload) startEntry.payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    ModelInvocation invocation =
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), startEntry.id()))
            .orElseThrow();
    assertEquals(CompactionTrigger.OVERFLOW, invocation.request().compaction().trigger());

    // OVERFLOW 完成的压缩：TURN_END.continueModel=true，先请求 THREAD 再 complete。
    transitionModel(fixture.store, invocation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, invocation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, invocation.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(12, applied.entries().size());
    CompactionPayload appliedPayload = (CompactionPayload) applied.entries().get(10).payload();
    assertEquals(CompactionPhase.FULL, appliedPayload.phase());
    assertTrue(appliedPayload.complete());
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(11).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // claim3：下一 claim 才偿还 CONTINUATION 义务（绝不插队阈值压缩）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath continued = path(fixture.store, baseline.threadId());
    assertEquals(13, continued.entries().size());
    TurnStartPayload continuation = (TurnStartPayload) continued.entries().get(12).payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
  }

  /** OVERFLOW immediate CONTINUATION 重试再 overflow 时只允许一次：重试失败后绝不再次压缩（guard 生效）。 */
  @Test
  void overflowRecoveryRetriesOnlyOnceWhenImmediateContinuationOverflowsAgain() {
    Fixture fixture = fixture();
    var baseline = seedOverflowFailedClosedTurn(fixture.store);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath compactionPlanned = path(fixture.store, baseline.threadId());
    Entry compactionStart = compactionPlanned.head();
    ModelInvocation compaction =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), compactionStart.id()))
            .orElseThrow();
    transitionModel(fixture.store, compaction.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, compaction.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, compaction.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // 下一 claim：OVERFLOW 完成压缩保留的 CONTINUATION 义务。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath continuationPlanned = path(fixture.store, baseline.threadId());
    Entry continuationStart = continuationPlanned.head();
    assertEquals(
        TurnStartReason.CONTINUATION, ((TurnStartPayload) continuationStart.payload()).reason());
    ModelInvocation continuation =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), continuationStart.id()))
            .orElseThrow();
    transitionModel(fixture.store, continuation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, continuation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        continuation.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.OVERFLOW, "still too large"), NOW));

    requestThreadWork(fixture.store, baseline.threadId());
    // 单个 claim：失败 overflow 重试关闭 turn；闭合后 compactionPreparation 经 isOverflowRecoveryRetry guard 返回
    // null -> 无 wake，绝不进入无界 compaction/retry 循环（无需任何手工 wake 验证）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath failedRetry = path(fixture.store, baseline.threadId());
    assertEquals(15, failedRetry.entries().size());
    TurnEndPayload end = (TurnEndPayload) failedRetry.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void stoppedCompactionTurnDoesNotSpin() {
    Fixture fixture = fixture();
    var baseline = seedStoppedCompactionTurn(fixture.store);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：停止压缩 turn（无 incomplete payload）绝不立即再次压缩：head 不变、无新 TURN_START。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(4, path.entries().size());
  }

  @Test
  void latestClosedTurnOwnedByAnotherThreadIsNotCompacted() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurnOwnedByOtherThread(fixture.store);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：最新已关闭 turn 属于另一 Thread 的共享历史：绝不压缩。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(9, path.entries().size());
  }

  @Test
  void rejectedTurnWithNullContextWindowDoesNotEraseEarlierOwnedUsage() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    fixture.store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID rejectedStart = tx.nextId();
          tx.insertEntry(
              new Entry(
                  rejectedStart,
                  baseline.sessionId(),
                  baseline.turnEndEntryId(),
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), baseline.threadId()),
                  NOW));
          UUID userId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  userId,
                  baseline.sessionId(),
                  rejectedStart,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("rejected input"))),
                      null,
                      null),
                  NOW));
          UUID errorId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  errorId,
                  baseline.sessionId(),
                  userId,
                  new AssistantErrorPayload(
                      new AssistantError("PLANNING_FAILED", "missing model"), null),
                  NOW));
          UUID endId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  endId,
                  baseline.sessionId(),
                  errorId,
                  new TurnEndPayload(
                      rejectedStart, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                  NOW));
          tx.updateThread(tx.findThread(baseline.threadId()).orElseThrow().advanceHead(endId, NOW));
          return null;
        });
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：Rejected turn（contextWindow == null）不抹掉更早成功 usage -> 压缩启动。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.head().payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(baseline.threadId(), start.ownerThreadId());
    assertEquals(CONTEXT_WINDOW, start.contextWindow());
  }

  @Test
  void continuationDueRunsExistingContinuationWithoutCompactionInsertion() {
    Fixture fixture = fixture();
    // continueModel=true 的关闭 turn + 超阈值 usage：continuation 义务必须原样执行，阈值压缩绝不插队。
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：CONTINUATION 优先于阈值压缩。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(TurnStartReason.CONTINUATION, start.reason());
  }

  /** 失败的 continuation 不抹掉更早成功 usage：闭合后 compactionPreparation 自动保留 Work，下一 claim 触发压缩。 */
  @Test
  void failedContinuationDoesNotForgetEarlierOverThresholdSuccess() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath continuationPlanned = path(fixture.store, baseline.threadId());
    Entry continuationStart = continuationPlanned.head();
    assertEquals(
        TurnStartReason.CONTINUATION, ((TurnStartPayload) continuationStart.payload()).reason());
    ModelInvocation continuation =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), continuationStart.id()))
            .orElseThrow();
    transitionModel(fixture.store, continuation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, continuation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        continuation.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider failed"), NOW));

    // ModelProcessor 完成（失败）时的真实 enqueue-side wake；claim 由它投递。
    requestThreadWork(fixture.store, baseline.threadId());

    // claim1：失败 continuation 关闭 turn；闭合后 compactionPreparation 越过该失败 turn，命中更早超阈值 usage
    // -> 自动保留 THREAD Work（无需任何手工 wake）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Work failedContinuationWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(failedContinuationWork);
    assertNull(failedContinuationWork.leaseToken());

    // claim2：下一 claim 直接消费保留 wake 启动 threshold 压缩。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath compactionPlanned = path(fixture.store, baseline.threadId());
    assertEquals(13, compactionPlanned.entries().size());
    Entry compactionStart = compactionPlanned.head();
    assertEquals(
        TurnStartReason.COMPACTION, ((TurnStartPayload) compactionStart.payload()).reason());
    ModelInvocation compaction =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), compactionStart.id()))
            .orElseThrow();
    assertEquals(CompactionTrigger.THRESHOLD, compaction.request().compaction().trigger());
  }

  /** 被 reject 的 continuation 不抹掉更早成功 usage：rejected 提交后 compactionPreparation 自动保留 Work。 */
  @Test
  void rejectedContinuationDoesNotForgetEarlierOverThresholdSuccess() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));
    fixture.resolver.onResolve = () -> fixture.resolver.autoConsistent = fixture.resolver.calls > 1;
    requestThreadWork(fixture.store, baseline.threadId());

    // claim1：continuation 被 reject 成功提交；闭合后用新 head path 判定更早超阈值 usage 立即到期 -> 自动保留 Work。
    // （无 deferred queue 也成立：compactionDue 独立驱动 wake。）
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Work rejectedWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(rejectedWork);
    assertNull(rejectedWork.leaseToken());

    // claim2：下一 claim 直接消费保留 wake 启动 threshold 压缩。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(13, path.entries().size());
    Entry compactionStart = path.head();
    assertEquals(
        TurnStartReason.COMPACTION, ((TurnStartPayload) compactionStart.payload()).reason());
    ModelInvocation compaction =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), compactionStart.id()))
            .orElseThrow();
    assertEquals(CompactionTrigger.THRESHOLD, compaction.request().compaction().trigger());
  }

  /** 失败压缩不 spin；新 INPUT 失败后闭合自触发压缩（failed compaction 已不是 barrier）。 */
  @Test
  void failedCompactionTurnDoesNotSpin() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    UUID sessionId = baseline.sessionId();
    UUID headId = baseline.turnEndEntryId();
    // 手工失败的 COMPACTION turn 种子（closed-turn Entry-only）：TURN_START(COMPACTION) + ASSISTANT_ERROR +
    // FAILED TURN_END，失败压缩事实只由 immutable Entry 承载，不 seed/保留 ModelInvocation。
    fixture.store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  sessionId,
                  headId,
                  resolvedCompactionTurnStart(baseline.threadId()),
                  NOW));
          // 与真实 commit 一致：head 先推进到 TURN_START，再追加错误结果与 FAILED TURN_END。
          tx.updateThread(
              tx.findThread(baseline.threadId()).orElseThrow().advanceHead(turnStartId, NOW));
          UUID errorId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  errorId,
                  sessionId,
                  turnStartId,
                  new AssistantErrorPayload(
                      new AssistantError(ProviderErrorKind.INVALID_REQUEST.name(), "boom"), null),
                  NOW));
          UUID endId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  endId,
                  sessionId,
                  errorId,
                  new TurnEndPayload(
                      turnStartId, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                  NOW));
          tx.updateThread(tx.findThread(baseline.threadId()).orElseThrow().advanceHead(endId, NOW));
          return null;
        });

    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    // 一个 claim：失败压缩 turn 绝不立即再次压缩：head 不变、无新 TURN_START。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());

    // 一旦用户发起了新的普通 turn，失败 compaction 不再是 freshness barrier。即使该 turn 普通失败，
    // 更早最新成功 invocation 的超阈值 usage 仍会触发新的压缩。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("retry after failed compaction")));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath inputPlanned = path(fixture.store, baseline.threadId());
    Entry inputTurnStart = inputPlanned.entries().get(12);
    assertEquals(TurnStartReason.INPUT, ((TurnStartPayload) inputTurnStart.payload()).reason());
    ModelInvocation input =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), inputTurnStart.id()))
            .orElseThrow();
    transitionModel(fixture.store, input.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, input.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        input.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider failed"), NOW));
    // ModelProcessor 完成（失败）时的真实 enqueue-side wake；claim 由它投递。
    requestThreadWork(fixture.store, baseline.threadId());

    // 单个 claim：失败 INPUT 关闭 turn；闭合后 compactionPreparation 越过失败普通 turn 与失败 COMPACTION，命中更早
    // 超阈值 usage -> 自动保留 Work（无需手工 wake）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 下一 claim：消费保留 wake 启动压缩。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath retried = path(fixture.store, baseline.threadId());
    assertEquals(
        TurnStartReason.COMPACTION, ((TurnStartPayload) retried.head().payload()).reason());
  }

  /**
   * 关闭的 HISTORY partial 种子（closed-turn Entry-only）：COMPACTION turn（TURN_START + incomplete HISTORY
   * payload + TURN_END），历史事实仅由 immutable Entry 构成，不 seed/保留 ModelInvocation（closed turn 不保留
   * invocation）。返回 [turnStartId, resultId]。
   */
  private static UUID[] seedClosedHistoryPartial(Fixture fixture, ClosedTurnBaseline baseline) {
    UUID sessionId = baseline.sessionId();
    UUID headId = baseline.turnEndEntryId();
    UUID[] ids = new UUID[2]; // [turnStart, result]
    // 冻结切分事实；HISTORY 响应文本即 partial 的 summary（assembler 重放必须与真实 apply 一致）。
    var request = compactionRequest(historyPreparation());
    ProviderResponse response =
        successResponse("history summary", List.of(), GenerationStopReason.COMPLETE);
    fixture.store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          ids[0] = tx.nextId();
          tx.insertEntry(
              new Entry(
                  ids[0],
                  sessionId,
                  headId,
                  resolvedCompactionTurnStart(baseline.threadId()),
                  NOW));
          // 与真实 commit 一致：head 先推进到 TURN_START。
          tx.updateThread(
              tx.findThread(baseline.threadId()).orElseThrow().advanceHead(ids[0], NOW));
          return null;
        });
    // result payload 由同一 request 的冻结切分事实 + 真实 apply 前缀路径经 assembler 机械派生（HISTORY 前缀即
    // head==TURN_START 的路径）。
    CompactionPayload resultPayload =
        CompactionSummaryAssembler.resultPayload(
            request.compaction(), response.text(), path(fixture.store, baseline.threadId()));
    fixture.store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          ids[1] = tx.nextId();
          tx.insertEntry(new Entry(ids[1], sessionId, ids[0], resultPayload, NOW));
          UUID endId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  endId,
                  sessionId,
                  ids[1],
                  new TurnEndPayload(ids[0], TurnEndOutcome.COMPLETED, false, null, null),
                  NOW));
          tx.updateThread(tx.findThread(baseline.threadId()).orElseThrow().advanceHead(endId, NOW));
          return null;
        });
    return ids;
  }

  /**
   * OVERFLOW 失败关闭 turn 种子（closed-turn Entry-only）：INPUT turn2（长 USER + ASSISTANT_ERROR(OVERFLOW) +
   * FAILED TURN_END 挂在 turn1 后）。历史事实仅由 immutable Entry 构成（TURN_START owner/contextWindow + 错误
   * payload），不 seed/保留 ModelInvocation（closed turn 不保留 invocation；OVERFLOW 触发按 Entry 错误事实判定）。
   */
  private static ClosedTurnBaseline seedOverflowFailedClosedTurn(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId(); // turn1 TURN_START
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID turnEndEntryId = tx.nextId();
          UUID secondTurnStartId = tx.nextId(); // turn2 TURN_START
          UUID secondUserEntryId = tx.nextId();
          UUID errorEntryId = tx.nextId();
          UUID secondTurnEndEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  sessionId,
                  turnStartEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                      null,
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  sessionId,
                  userEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("assistant reply"))),
                      new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  turnEndEntryId,
                  sessionId,
                  assistantEntryId,
                  new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  secondTurnStartId,
                  sessionId,
                  turnEndEntryId,
                  resolvedInputTurnStart(threadId),
                  NOW));
          tx.insertEntry(
              new Entry(
                  secondUserEntryId,
                  sessionId,
                  secondTurnStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent(compactionUserText()))),
                      null,
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  errorEntryId,
                  sessionId,
                  secondUserEntryId,
                  new AssistantErrorPayload(
                      new AssistantError(ProviderErrorKind.OVERFLOW.name(), "context overflow"),
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  secondTurnEndEntryId,
                  sessionId,
                  errorEntryId,
                  new TurnEndPayload(
                      secondTurnStartId,
                      TurnEndOutcome.FAILED,
                      false,
                      TurnEndReason.TURN_FAILED,
                      null),
                  NOW));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(
                  threadId, sessionId, secondTurnEndEntryId, NOW));
          return new ClosedTurnBaseline(
              sessionId,
              rootEntryId,
              secondTurnStartId,
              secondUserEntryId,
              errorEntryId,
              secondTurnEndEntryId,
              threadId);
        });
  }

  /** 被停止的 COMPACTION turn 种子：TURN_START(COMPACTION) -> ABORTED -> TURN_END(STOPPED)。 */
  private static ClosedTurnBaseline seedStoppedCompactionTurn(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID abortedEntryId = tx.nextId();
          UUID turnEndEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  resolvedCompactionTurnStart(threadId),
                  NOW));
          tx.insertEntry(
              new Entry(
                  abortedEntryId,
                  sessionId,
                  turnStartEntryId,
                  new AssistantAbortedPayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial")))),
                  NOW));
          tx.insertEntry(
              new Entry(
                  turnEndEntryId,
                  sessionId,
                  abortedEntryId,
                  new TurnEndPayload(
                      turnStartEntryId,
                      TurnEndOutcome.STOPPED,
                      false,
                      TurnEndReason.USER_STOP,
                      id(1L)),
                  NOW));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(threadId, sessionId, turnEndEntryId, NOW));
          return new ClosedTurnBaseline(
              sessionId,
              rootEntryId,
              turnStartEntryId,
              abortedEntryId,
              abortedEntryId,
              turnEndEntryId,
              threadId);
        });
  }

  /**
   * 与 seedCompactionReadyClosedTurn 相同的双 turn 形状，但 turn2 的 TURN_START 由另一 Thread 拥有（共享历史的 ownership
   * barrier）：处理本 thread 时不得压缩该 turn。共享历史仅由 immutable Entry 构成（ {@link
   * TurnStartPayload#ownerThreadId()} + 两个 Thread 指向同一 closed head），不依赖任何 ModelInvocation。
   */
  private static ClosedTurnBaseline seedClosedTurnOwnedByOtherThread(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId(); // turn1 TURN_START
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID turnEndEntryId = tx.nextId();
          UUID secondTurnStartId = tx.nextId();
          UUID secondUserEntryId = tx.nextId();
          UUID secondAssistantEntryId = tx.nextId();
          UUID secondTurnEndId = tx.nextId();
          UUID threadId = tx.nextId(); // 被处理的 thread
          UUID otherThreadId = tx.nextId(); // 拥有 turn2 的 thread（仅由 TURN_START ownerThreadId 表达）
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  sessionId,
                  turnStartEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                      null,
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  sessionId,
                  userEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("assistant reply"))),
                      new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  turnEndEntryId,
                  sessionId,
                  assistantEntryId,
                  new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  secondTurnStartId,
                  sessionId,
                  turnEndEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), otherThreadId, CONTEXT_WINDOW),
                  NOW));
          tx.insertEntry(
              new Entry(
                  secondUserEntryId,
                  sessionId,
                  secondTurnStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent(compactionUserText()))),
                      null,
                      null),
                  NOW));
          // 两个 Thread 都指向同一 closed head（共享历史由 immutable Entry 事实承载，而非 foreign active model）。
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(threadId, sessionId, secondUserEntryId, NOW));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(
                  otherThreadId, sessionId, secondUserEntryId, NOW));
          tx.insertEntry(
              new Entry(
                  secondAssistantEntryId,
                  sessionId,
                  secondUserEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("assistant reply"))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE, OVER_THRESHOLD_USAGE, cost()),
                      null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  secondTurnEndId,
                  sessionId,
                  secondAssistantEntryId,
                  new TurnEndPayload(
                      secondTurnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  NOW));
          tx.updateThread(tx.findThread(threadId).orElseThrow().advanceHead(secondTurnEndId, NOW));
          tx.updateThread(
              tx.findThread(otherThreadId).orElseThrow().advanceHead(secondTurnEndId, NOW));
          return new ClosedTurnBaseline(
              sessionId,
              rootEntryId,
              secondTurnStartId,
              secondUserEntryId,
              secondAssistantEntryId,
              secondTurnEndId,
              threadId);
        });
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  /**
   * 与 seedCompactionReadyClosedTurn 形状一致的 HISTORY preparation 事实（1..4 为 ROOT/TS/USER/ASSISTANT）。
   */
  private static CompactionPreparation historyPreparation() {
    return new CompactionPreparation(
        CompactionPhase.HISTORY,
        CompactionTrigger.THRESHOLD,
        500L,
        100_000L,
        id(3L),
        id(5L),
        id(4L),
        null,
        List.of());
  }
}
