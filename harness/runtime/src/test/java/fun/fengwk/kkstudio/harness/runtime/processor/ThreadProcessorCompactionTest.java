package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CONTEXT_WINDOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.ClosedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.MAX_OUTPUT_TOKENS;
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

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionNoGain;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionSummaryAssembler;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
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
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
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
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
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
  private static final ModelUsage BELOW_THRESHOLD_USAGE =
      new ModelUsage(80_000L, 2L, 0L, 0L, 0L, 0L, 80_002L);

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
                  .anyMatch(c -> c.idempotencyKey().equals(userCommand));
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
    assertEquals(CompactionPhase.FULL, start.compaction().phase());
    assertEquals(CompactionTrigger.THRESHOLD, start.compaction().trigger());
  }

  /** 普通成功 turn 越过 threshold 后保持 idle；下一条真实 user demand 到达时才先压缩。 */
  @Test
  void overThresholdSuccessfulTurnWaitsForNextUserDemand() {
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

    // claim2：closed apply 关闭 turn；没有新的 user demand，threshold 不 self-wake。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(9, applied.entries().size());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 新 user demand 自行 enqueue wake；claim3 在消费该消息前启动 threshold 压缩。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("next demand")));
    requestThreadWork(fixture.store, baseline.threadId());
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
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("next demand")));
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
    TurnStartPayload compactionStart = (TurnStartPayload) start.payload();
    assertEquals(CompactionPhase.FULL, compactionStart.compaction().phase());
    assertEquals(CompactionTrigger.THRESHOLD, compactionStart.compaction().trigger());
    assertTrue(payload.summaryText().contains("response text"));
    // COMPLETED 无 tool 的压缩 turn 已关闭：Model 行被物理删除（closed turn 不保留 Invocation）。
    assertNull(
        fixture.store.transaction(tx -> tx.findModelInvocation(invocation.id())).orElse(null));
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(11).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertFalse(end.continueModel()); // THRESHOLD 完成压缩不继续。
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void thresholdFullCompactionResumesPendingContinuation() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：active continuation 越阈，先启动 FULL 压缩。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Entry start = path(fixture.store, baseline.threadId()).head();
    ModelInvocation compaction =
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), start.id()))
            .orElseThrow();
    transitionModel(fixture.store, compaction.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, compaction.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, compaction.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());

    // claim2：成功 checkpoint 必须把原 continuation obligation 重写到自己的 TURN_END。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath applied = path(fixture.store, baseline.threadId());
    TurnEndPayload end = (TurnEndPayload) applied.head().payload();
    assertTrue(end.continueModel());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // claim3：无需 queued user，仍恢复原 CONTINUATION。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    TurnStartPayload continuation =
        (TurnStartPayload) path(fixture.store, baseline.threadId()).head().payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
  }

  /** 完整压缩后失败普通 turn：压缩保持 freshness barrier；失败 turn 后的下一次 INPUT 仍正常。 */
  @Test
  void completeCompactionIsFreshnessBarrierAfterLaterFailedTurn() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    fixture.resolver.autoConsistent = true;
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("initial demand")));
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

    // 完整压缩后的下一个 claim：此前保留的 queued USER 启动 INPUT（不再触发压缩）。
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
    assertEquals(CompactionPhase.TURN_PREFIX, start.compaction().phase());
    // 冻结复用 HISTORY TURN_START 的切分事实（seed 布局：ROOT=2/TS1=3/USER1=4/ASST1=5）。
    assertEquals(id(5L), start.compaction().cutEntryId());
    assertEquals(id(4L), start.compaction().turnPrefixStartEntryId());
    assertEquals(ids[1], start.compaction().historyCompactionEntryId());
    assertEquals(CompactionTrigger.THRESHOLD, start.compaction().trigger());
  }

  /** TURN_PREFIX 延续成功：组装 complete payload，并在 split 两阶段之后恢复原普通 continuation。 */
  @Test
  void historyContinuationAppliesCompletePayloadAndResumesOriginalContinuation() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
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

    // 一个 claim：TURN_PREFIX 完整 payload 应用并关闭。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(15, applied.entries().size());
    Entry result = applied.entries().get(13);
    CompactionPayload payload = (CompactionPayload) result.payload();
    TurnStartPayload prefixStart = (TurnStartPayload) planned.entries().get(12).payload();
    assertEquals(CompactionPhase.TURN_PREFIX, prefixStart.compaction().phase());
    assertEquals(
        "history summary\n\n---\n\n**Turn Context (split turn):**\n\nresponse text",
        payload.summaryText());
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(14).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel()); // split 两阶段完成后恢复压缩前的普通 continuation。
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 下一 claim 偿还的是原普通 continuation，不是第三个压缩阶段。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    TurnStartPayload resumed =
        (TurnStartPayload) path(fixture.store, baseline.threadId()).head().payload();
    assertEquals(TurnStartReason.CONTINUATION, resumed.reason());
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
    CompactionPreparation preparation = historyPreparation();
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
                      resolvedCompactionTurnStart(baseline.threadId(), preparation.frozenStart()),
                      NOW));
              tx.updateThread(
                  tx.findThread(baseline.threadId()).orElseThrow().advanceHead(id, NOW));
              return id;
            });
    ModelRequestSpec requestSpec = compactionRequest(preparation);
    ProviderResponse response =
        successResponse("history summary", List.of(), GenerationStopReason.COMPLETE);
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            turnStartId,
            turnStartId,
            ModelInvocationStatus.SUCCEEDED,
            requestSpec,
            response,
            null);
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：HISTORY 成功 apply 关闭 turn，但 compactionWake=true -> 先请求 THREAD 再 complete。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(12, applied.entries().size());
    Entry result = applied.entries().get(10);
    CompactionPayload payload = (CompactionPayload) result.payload();
    assertEquals("history summary", payload.summaryText());
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(11).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
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
    assertEquals(CompactionPhase.TURN_PREFIX, nextStart.compaction().phase());
    assertEquals(id(5L), nextStart.compaction().cutEntryId());
    assertEquals(id(4L), nextStart.compaction().turnPrefixStartEntryId());
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
    assertEquals(CompactionTrigger.OVERFLOW, start.compaction().trigger());

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
    assertFalse(appliedPayload.summaryText().isBlank());
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
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("next demand")));
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
  void overThresholdContinuationCompactsBeforeResumingModel() {
    Fixture fixture = fixture();
    // continueModel=true 的关闭 turn + 超阈值 usage：先压缩，再恢复模型 continuation。
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // 一个 claim：压缩消费零 Command，并冻结原 continuation 之前的路径。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(CompactionTrigger.THRESHOLD, start.compaction().trigger());
  }

  @Test
  void belowThresholdContinuationRunsWithoutCompaction() {
    Fixture fixture = fixture();
    // 尚未越过阈值时不增加中间动作，既有 continuation 仍直接执行。
    var baseline = seedCompactionReadyClosedTurn(fixture.store, usage(), true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(TurnStartReason.CONTINUATION, start.reason());
  }

  @Test
  void trailingToolResultCanCrossThresholdAtTurnEnd() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, usage(), true);
    appendClosedContinuationWithToolResult(
        fixture, baseline, BELOW_THRESHOLD_USAGE, "tool output ".repeat(2_000));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // Provider usage 本身低于 83_616；turn end 新增的 ToolResult 把 projected context 推过阈值。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    TurnStartPayload start =
        (TurnStartPayload) path(fixture.store, baseline.threadId()).head().payload();
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(CompactionTrigger.THRESHOLD, start.compaction().trigger());
  }

  /** below-threshold continuation 失败后完全结束；没有新 demand 时不自唤醒。 */
  @Test
  void failedBelowThresholdContinuationWaitsForNextUserDemand() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, usage(), true);
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

    // claim1：失败 continuation 关闭 turn；没有新的 user demand，不 self-wake。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 下一条 user demand 到达后直接启动 INPUT；旧 usage 未越阈，不制造无意义压缩。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("next demand")));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath inputPlanned = path(fixture.store, baseline.threadId());
    Entry inputStart = inputPlanned.openTurnStart().orElseThrow();
    assertEquals(TurnStartReason.INPUT, ((TurnStartPayload) inputStart.payload()).reason());
    assertTrue(
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), inputStart.id()))
            .isPresent());
  }

  /** below-threshold continuation 被 reject 后完全结束；没有新 demand 时不自唤醒。 */
  @Test
  void rejectedBelowThresholdContinuationWaitsForNextUserDemand() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, usage(), true);
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));
    fixture.resolver.onResolve = () -> fixture.resolver.autoConsistent = fixture.resolver.calls > 1;
    requestThreadWork(fixture.store, baseline.threadId());

    // claim1：continuation 被 reject 并闭合；无 user demand 时不 self-wake。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 下一条 user demand 到达后直接启动 INPUT。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("next demand")));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    Entry inputStart = path.openTurnStart().orElseThrow();
    assertEquals(TurnStartReason.INPUT, ((TurnStartPayload) inputStart.payload()).reason());
    assertTrue(
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), inputStart.id()))
            .isPresent());
  }

  /** 失败压缩不 spin、不阻塞 queued input；后续 user demand 可再次触发 threshold。 */
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
                  resolvedCompactionTurnStart(baseline.threadId(), fullStart(headId)),
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

    // 用户发起新普通 turn 时，失败 compaction 不阻塞 queued input。
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

    // 单个 claim：失败 INPUT 关闭 turn；没有新的 user demand，不 self-wake。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 再到达一条 user demand 后，下一 claim 才重新启动 threshold 压缩。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("second retry demand")));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath retried = path(fixture.store, baseline.threadId());
    assertEquals(
        TurnStartReason.COMPACTION, ((TurnStartPayload) retried.head().payload()).reason());
  }

  @Test
  void primaryFailureStartsExactlyOneFallbackWithoutChangingBranchModel() {
    ModelSelection fallback = new ModelSelection("fallback", "summary-model", "v2");
    Fixture fixture = fixture(new CompactionConfig(20_000, fallback));
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("queued input")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // primary plan
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Entry primaryEntry = path(fixture.store, baseline.threadId()).head();
    TurnStartPayload primaryStart = (TurnStartPayload) primaryEntry.payload();
    ModelInvocation primary =
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), primaryEntry.id()))
            .orElseThrow();
    transitionModel(fixture.store, primary.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, primary.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        primary.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "bad"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // one fallback plan reuses trigger/phase/anchors but changes only executionModel。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Entry fallbackEntry = path(fixture.store, baseline.threadId()).head();
    TurnStartPayload fallbackStart = (TurnStartPayload) fallbackEntry.payload();
    assertEquals(primaryStart.compaction().phase(), fallbackStart.compaction().phase());
    assertEquals(primaryStart.compaction().trigger(), fallbackStart.compaction().trigger());
    assertEquals(primaryStart.compaction().cutEntryId(), fallbackStart.compaction().cutEntryId());
    assertEquals(fallback, fallbackStart.compaction().executionModel());
    assertEquals(branchSettings().model(), fallbackStart.settings().model());
    assertEquals(
        branchSettings().model(), path(fixture.store, baseline.threadId()).baseSettings().model());

    ModelInvocation fallbackInvocation =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), fallbackEntry.id()))
            .orElseThrow();
    transitionModel(fixture.store, fallbackInvocation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, fallbackInvocation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        fallbackInvocation.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "bad again"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // fallback 已使用：下一 claim 消费 queued input，绝不链式启动第三个 compaction。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath inputPath = path(fixture.store, baseline.threadId());
    assertEquals(
        TurnStartReason.INPUT,
        ((TurnStartPayload) inputPath.openTurnStart().orElseThrow().payload()).reason());
  }

  @Test
  void successfulFallbackResumesPendingContinuation() {
    ModelSelection fallback = new ModelSelection("fallback", "summary-model", "v2");
    Fixture fixture = fixture(new CompactionConfig(20_000, fallback));
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1/2：primary 压缩启动后失败，durable fallback obligation 保留。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Entry primaryEntry = path(fixture.store, baseline.threadId()).head();
    ModelInvocation primary =
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), primaryEntry.id()))
            .orElseThrow();
    transitionModel(fixture.store, primary.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, primary.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        primary.id(),
        m -> m.fail(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "bad"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // claim3/4：fallback 复用冻结 anchors；成功后仍能越过失败 primary 找回原 continuation。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    Entry fallbackEntry = path(fixture.store, baseline.threadId()).head();
    ModelInvocation fallbackInvocation =
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), fallbackEntry.id()))
            .orElseThrow();
    transitionModel(fixture.store, fallbackInvocation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, fallbackInvocation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store,
        fallbackInvocation.id(),
        m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertTrue(
        ((TurnEndPayload) path(fixture.store, baseline.threadId()).head().payload())
            .continueModel());

    // claim5：无 queued input，恢复原模型 continuation。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(
        TurnStartReason.CONTINUATION,
        ((TurnStartPayload) path(fixture.store, baseline.threadId()).head().payload()).reason());
  }

  @Test
  void noGainWritesStableErrorAndKeepsQueuedInput() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("queued input")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    Entry start = path(fixture.store, baseline.threadId()).head();
    ModelInvocation invocation =
        fixture
            .store
            .transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), start.id()))
            .orElseThrow();
    transitionModel(fixture.store, invocation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, invocation.id(), m -> m.markRunning(NOW));
    ProviderResponse oversizedSummary =
        successResponse("summary".repeat(60_000), List.of(), GenerationStopReason.COMPLETE);
    transitionModel(fixture.store, invocation.id(), m -> m.succeed(oversizedSummary, NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath failed = path(fixture.store, baseline.threadId());
    AssistantErrorPayload error =
        (AssistantErrorPayload) failed.entries().get(failed.entries().size() - 2).payload();
    assertEquals(CompactionNoGain.NO_GAIN_ERROR_CODE, error.error().code());
    assertEquals(TurnEndOutcome.FAILED, ((TurnEndPayload) failed.head().payload()).outcome());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath inputPath = path(fixture.store, baseline.threadId());
    assertEquals(
        TurnStartReason.INPUT,
        ((TurnStartPayload) inputPath.openTurnStart().orElseThrow().payload()).reason());
  }

  /**
   * 在既有 logical Agent segment 后追加一个已关闭 CONTINUATION turn；Provider usage 不含其后的 ToolResult， 用于验证
   * turn-end projected token 补算。
   */
  private static void appendClosedContinuationWithToolResult(
      Fixture fixture, ClosedTurnBaseline baseline, ModelUsage usage, String toolResultText) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  baseline.sessionId(),
                  baseline.turnEndEntryId(),
                  new TurnStartPayload(
                      TurnStartReason.CONTINUATION,
                      branchSettings(),
                      baseline.threadId(),
                      CONTEXT_WINDOW,
                      MAX_OUTPUT_TOKENS,
                      null),
                  NOW));
          UUID assistantEntryId = tx.nextId();
          String callId = "large-result";
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  baseline.sessionId(),
                  turnStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new ToolCallMessageContent(callId, "bash", "bash", "{}"),
                              new TextMessageContent("working"))),
                      new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, cost()),
                      null),
                  NOW));
          UUID toolResultEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  toolResultEntryId,
                  baseline.sessionId(),
                  assistantEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.TOOL,
                          List.of(
                              new ToolResultMessageContent(
                                  callId,
                                  "bash",
                                  "bash",
                                  List.of(new TextMessageContent(toolResultText)),
                                  false,
                                  "{}"))),
                      null,
                      new ToolResultMetadata(
                          assistantEntryId, callId, 0, ToolResultStatus.SUCCEEDED, false, null)),
                  NOW));
          UUID turnEndEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndEntryId,
                  baseline.sessionId(),
                  toolResultEntryId,
                  new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, true, null, null),
                  NOW));
          tx.updateThread(
              tx.findThread(baseline.threadId()).orElseThrow().advanceHead(turnEndEntryId, NOW));
          return null;
        });
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
    CompactionPreparation preparation = historyPreparation();
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
                  resolvedCompactionTurnStart(baseline.threadId(), preparation.frozenStart()),
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
            path(fixture.store, baseline.threadId()), preparation.frozenStart(), response.text());
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
                  new TurnEndPayload(ids[0], TurnEndOutcome.COMPLETED, true, null, null),
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
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("historical user " + "h".repeat(50_000)))),
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
                          List.of(
                              new TextMessageContent(
                                  "historical assistant " + "a".repeat(50_000)))),
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
                  resolvedCompactionTurnStart(threadId, fullStart(rootEntryId)),
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
                  resolvedInputTurnStart(otherThreadId),
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
        branchSettings().model(),
        id(5L),
        id(4L),
        null,
        null,
        List.of(userMessage("history")),
        100L);
  }

  private static CompactionStart fullStart(UUID cutEntryId) {
    return new CompactionStart(
        CompactionPhase.FULL,
        CompactionTrigger.THRESHOLD,
        branchSettings().model(),
        cutEntryId,
        null,
        null);
  }
}
