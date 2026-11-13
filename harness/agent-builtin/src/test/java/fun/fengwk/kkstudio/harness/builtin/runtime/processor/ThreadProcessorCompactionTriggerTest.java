package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.CONTEXT_WINDOW;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.MAX_OUTPUT_TOKENS;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.seedCompactionReadyClosedTurn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport.ClosedTurnBaseline;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 请求前预算触发的压缩规划：先压缩同一历史再重试，且压缩后的唯一重试失败即停止。 */
class ThreadProcessorCompactionTriggerTest extends ThreadProcessorTestBase {

  @Test
  void requestBudgetPressurePlansCompactionBeforeQueuedInput() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline =
        seedCompactionReadyClosedTurn(fixture.store, overThresholdUsage());
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("queued input")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.CompactionRequired(CONTEXT_WINDOW));
    ExecutionSelection compactor =
        BuiltinExecution.of(ModelSelection.model("compactor-model", "v1"));
    fixture.resolver.results.add(
        new TurnResolver.CompactionResolved(
            compactor,
            4096L,
            CONTEXT_WINDOW,
            MAX_OUTPUT_TOKENS,
            new BranchSettings("compaction", compactor, null)));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = assertInstanceOf(TurnStartPayload.class, path.head().payload());
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(CompactionTrigger.THRESHOLD, start.compaction().trigger());
    assertNotNull(fixture.resolver.lastPreparation);
    assertEquals(CompactionTrigger.THRESHOLD, fixture.resolver.lastPreparation.trigger());
    // 压缩不消费 queued input：压缩成功后同一输入才被消费。
    assertEquals(
        1,
        inTx(
                fixture,
                tx -> {
                  tx.threads().lock(baseline.threadId());
                  return tx.commands().loadQueued(baseline.threadId()).size();
                })
            .intValue());
  }

  @Test
  void postCompactionRetryThatStillDoesNotFitFailsWithoutSecondCompaction() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline =
        seedCompactionReadyClosedTurn(fixture.store, overThresholdUsage());
    UUID threadId = baseline.threadId();
    seedPostCompactionContinuation(fixture, baseline);
    requestThreadWork(fixture.store, threadId);
    fixture.resolver.results.add(new TurnResolver.CompactionRequired(CONTEXT_WINDOW));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(threadId));

    EntryPath path = path(fixture.store, threadId);
    TurnEndPayload end = assertInstanceOf(TurnEndPayload.class, path.head().payload());
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertFalse(end.continueModel());
    AssistantErrorPayload error =
        assertInstanceOf(
            AssistantErrorPayload.class, path.entries().get(path.entries().size() - 2).payload());
    assertEquals("CONTEXT_LIMIT", error.error().code());
    assertTrue(inTx(fixture, tx -> tx.threads().listChildren(threadId).isEmpty()).booleanValue());
  }

  /** compact 工具只排队：批次结束后的安全边界按手动压缩规则启动 MANUAL 压缩，而不是直接续作。 */
  @Test
  void compactToolQueuesManualCompactionAtTheNextSafeBoundary() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline =
        seedCompactionReadyClosedTurn(fixture.store, overThresholdUsage());
    UUID threadId = baseline.threadId();
    seedCompactToolTurn(fixture, baseline);
    requestThreadWork(fixture.store, threadId);
    ExecutionSelection compactor =
        BuiltinExecution.of(ModelSelection.model("compactor-model", "v1"));
    fixture.resolver.results.add(
        new TurnResolver.CompactionResolved(
            compactor,
            4096L,
            CONTEXT_WINDOW,
            MAX_OUTPUT_TOKENS,
            new BranchSettings("compaction", compactor, null)));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(threadId));

    EntryPath path = path(fixture.store, threadId);
    TurnStartPayload start = assertInstanceOf(TurnStartPayload.class, path.head().payload());
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
  }

  /** 创建约束禁止自动压缩的 Thread 即使请求放不下也只失败，绝不启动压缩分支。 */
  @Test
  void autoCompactionDisabledThreadFailsOnBudgetPressureWithoutCompacting() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline =
        seedCompactionReadyClosedTurn(
            fixture.store, overThresholdUsage(), false, new ThreadConstraints(null, false));
    UUID threadId = baseline.threadId();
    seedCommand(
        fixture.store, threadId, new UserMessageCommandPayload(userMessage("queued input")));
    requestThreadWork(fixture.store, threadId);
    fixture.resolver.results.add(new TurnResolver.CompactionRequired(CONTEXT_WINDOW));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(threadId));

    EntryPath path = path(fixture.store, threadId);
    TurnEndPayload end = assertInstanceOf(TurnEndPayload.class, path.head().payload());
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    AssistantErrorPayload error =
        assertInstanceOf(
            AssistantErrorPayload.class, path.entries().get(path.entries().size() - 2).payload());
    assertEquals("CONTEXT_LIMIT", error.error().code());
    assertTrue(inTx(fixture, tx -> tx.threads().listChildren(threadId).isEmpty()).booleanValue());
  }

  /** 追加一个以 compact 成功回执结束、仍要求续作的普通 INPUT 回合。 */
  private static void seedCompactToolTurn(Fixture fixture, ClosedTurnBaseline baseline) {
    fixture.store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          UUID turnStart = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStart,
                      baseline.sessionId(),
                      baseline.turnEndEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          branchSettings(),
                          thread.id(),
                          CONTEXT_WINDOW,
                          MAX_OUTPUT_TOKENS,
                          null,
                          null),
                      NOW));
          UUID user = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      user,
                      baseline.sessionId(),
                      turnStart,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.USER,
                              List.of(new TextMessageContent("compact now"))),
                          null,
                          null),
                      NOW));
          UUID assistant = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistant,
                      baseline.sessionId(),
                      user,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.ASSISTANT,
                              List.of(
                                  new ToolCallMessageContent(
                                      "call-1", "compact", "compact", "{}"))),
                          new AssistantMessageMetadata(
                              GenerationStopReason.COMPLETE,
                              new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                          null),
                      NOW));
          UUID result = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      result,
                      baseline.sessionId(),
                      assistant,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.TOOL,
                              List.of(
                                  new ToolResultMessageContent(
                                      "call-1",
                                      "compact",
                                      "compact",
                                      List.of(new TextMessageContent("compaction queued")),
                                      false,
                                      "{}"))),
                          null,
                          new ToolResultMetadata(
                              tx.nextId(),
                              assistant,
                              "call-1",
                              0,
                              ToolResultStatus.SUCCEEDED,
                              false,
                              null,
                              null,
                              "compact")),
                      NOW));
          UUID end = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      end,
                      baseline.sessionId(),
                      result,
                      new TurnEndPayload(turnStart, TurnEndOutcome.COMPLETED, true, null, null),
                      NOW));
          tx.threads().update(thread.advanceHead(end, NOW));
          return null;
        });
  }

  /** 追加 complete COMPACTION turn 与其唯一的 CONTINUATION 恢复回合，head 停在恢复回合的 TURN_END。 */
  private static void seedPostCompactionContinuation(Fixture fixture, ClosedTurnBaseline baseline) {
    fixture.store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          UUID compactionStart = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      compactionStart,
                      baseline.sessionId(),
                      baseline.turnEndEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.COMPACTION,
                          branchSettings(),
                          thread.id(),
                          CONTEXT_WINDOW,
                          MAX_OUTPUT_TOKENS,
                          CompactionStart.pending(
                              CompactionTrigger.THRESHOLD, baseline.assistantEntryId()),
                          null),
                      NOW));
          UUID result = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      result,
                      baseline.sessionId(),
                      compactionStart,
                      new CompactionPayload("summary", null),
                      NOW));
          UUID compactionEnd = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      compactionEnd,
                      baseline.sessionId(),
                      result,
                      new TurnEndPayload(
                          compactionStart, TurnEndOutcome.COMPLETED, true, null, null),
                      NOW));
          UUID continuationStart = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      continuationStart,
                      baseline.sessionId(),
                      compactionEnd,
                      new TurnStartPayload(
                          TurnStartReason.CONTINUATION,
                          branchSettings(),
                          thread.id(),
                          CONTEXT_WINDOW,
                          MAX_OUTPUT_TOKENS,
                          null,
                          null),
                      NOW));
          UUID assistant = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistant,
                      baseline.sessionId(),
                      continuationStart,
                      assistantPayload(List.of()),
                      NOW));
          UUID continuationEnd = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      continuationEnd,
                      baseline.sessionId(),
                      assistant,
                      new TurnEndPayload(
                          continuationStart, TurnEndOutcome.COMPLETED, true, null, null),
                      NOW));
          tx.threads().update(thread.advanceHead(continuationEnd, NOW));
          return null;
        });
  }

  private static ModelUsage overThresholdUsage() {
    return new ModelUsage(90_000L, 2L, 0L, 0L, Map.of(), 0L, 90_002L);
  }
}
