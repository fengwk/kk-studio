package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CONTEXT_WINDOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.ClosedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.compactionRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.compactionUserText;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.resolvedCompactionTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.resolvedInputTurnStart;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCompactionReadyClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** ThreadProcessor 自动压缩 turn：阈值触发、输入不绕过、FULL 应用、HISTORY 部分延续与 overflow 语义。 */
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
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    // 压缩优先于输入：最新 turn 是 COMPACTION（消费零 Command），用户消息仍在队列。
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

  @Test
  void fullCompactionAppliesCompletePayloadAndClosesWithContinueModelFalse() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
  }

  @Test
  void completeCompactionIsFreshnessBarrierAfterLaterFailedTurn() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    fixture.resolver.autoConsistent = true;
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));
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
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("after complete compaction")));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));
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

    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));
    EntryPath failed = path(fixture.store, baseline.threadId());
    assertEquals(16, failed.entries().size());
    assertTrue(failed.head().payload() instanceof TurnEndPayload);
  }

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
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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

  @Test
  void historyContinuationAppliesCompleteTurnPrefixPayloadAndClosesFalse() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE);
    seedClosedHistoryPartial(fixture, baseline);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    // 让 TURN_PREFIX 延续成功：applyModel 组装 complete payload（合并紧邻 partial 的 history summary）。
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
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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

    // OVERFLOW 完成的压缩：TURN_END.continueModel=true，同一 process 内下一次 step 即偿还既有
    // CONTINUATION 义务（产生 CONTINUATION turn，绝不插队阈值压缩）-> 最终 SUSPENDED。
    transitionModel(fixture.store, invocation.id(), m -> m.beginDispatch(NOW));
    transitionModel(fixture.store, invocation.id(), m -> m.markRunning(NOW));
    transitionModel(
        fixture.store, invocation.id(), m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    EntryPath applied = path(fixture.store, baseline.threadId());
    assertEquals(13, applied.entries().size());
    CompactionPayload appliedPayload = (CompactionPayload) applied.entries().get(10).payload();
    assertEquals(CompactionPhase.FULL, appliedPayload.phase());
    assertTrue(appliedPayload.complete());
    TurnEndPayload end = (TurnEndPayload) applied.entries().get(11).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    TurnStartPayload continuation = (TurnStartPayload) applied.entries().get(12).payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
  }

  @Test
  void overflowRecoveryRetriesOnlyOnceWhenImmediateContinuationOverflowsAgain() {
    Fixture fixture = fixture();
    var baseline = seedOverflowFailedClosedTurn(fixture.store);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    EntryPath failedRetry = path(fixture.store, baseline.threadId());
    assertEquals(15, failedRetry.entries().size());
    TurnEndPayload end = (TurnEndPayload) failedRetry.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
  }

  @Test
  void stoppedCompactionTurnDoesNotSpin() {
    Fixture fixture = fixture();
    var baseline = seedStoppedCompactionTurn(fixture.store);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    // 停止压缩 turn（无 incomplete payload）绝不立即再次压缩：head 不变、无新 TURN_START。
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(4, path.entries().size());
  }

  @Test
  void latestClosedTurnOwnedByAnotherThreadIsNotCompacted() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurnOwnedByOtherThread(fixture.store);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    // 最新已关闭 turn 属于另一 Thread 的共享历史：绝不压缩。
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

    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(TurnStartReason.CONTINUATION, start.reason());
  }

  @Test
  void failedContinuationDoesNotForgetEarlierOverThresholdSuccess() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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

    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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

  @Test
  void rejectedContinuationDoesNotForgetEarlierOverThresholdSuccess() {
    Fixture fixture = fixture();
    var baseline = seedCompactionReadyClosedTurn(fixture.store, OVER_THRESHOLD_USAGE, true);
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));
    fixture.resolver.onResolve = () -> fixture.resolver.autoConsistent = fixture.resolver.calls > 1;
    requestThreadWork(fixture.store, baseline.threadId());

    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
    assertEquals(
        ThreadProcessResult.QUIESCENT,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

    // 失败压缩 turn 绝不立即再次压缩：head 不变、无新 TURN_START。
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());

    // 一旦用户发起了新的普通 turn，失败 compaction 不再是 freshness barrier。即使该 turn 普通失败，
    // 更早最新成功 invocation 的超阈值 usage 仍会触发新的压缩。
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("retry after failed compaction")));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));
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
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(
        ThreadProcessResult.SUSPENDED,
        fixture.processor.process(claimThreadWork(fixture.store, baseline.threadId())));

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
          tx.insertThread(new ThreadState(threadId, secondTurnEndEntryId, false, 1, 0, NOW, NOW));
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
          tx.insertThread(new ThreadState(threadId, turnEndEntryId, false, 1, 0, NOW, NOW));
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
          tx.insertThread(new ThreadState(threadId, secondUserEntryId, false, 1, 0, NOW, NOW));
          tx.insertThread(new ThreadState(otherThreadId, secondUserEntryId, false, 1, 0, NOW, NOW));
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
