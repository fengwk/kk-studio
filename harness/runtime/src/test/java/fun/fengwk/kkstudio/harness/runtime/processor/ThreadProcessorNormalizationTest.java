package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.insertAssistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.model;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.realToolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedHistoricalOpenTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedSecondThreadAtHistoricalAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.toolsByAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultReason;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.util.List;
import java.util.UUID;

/** ThreadProcessor history normalization：历史分支绝不恢复 / 复用，只补 synthetic 后新开 Turn。 */
class ThreadProcessorNormalizationTest extends ThreadProcessorTestBase {

  @Test
  void normalizationAtRootOrClosedTurnEndProducesNoSuffix() {
    Fixture rootFixture = fixture();
    var rootBaseline = seedBaseline(rootFixture.store);
    seedCommand(
        rootFixture.store,
        rootBaseline.threadId(),
        new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(rootFixture.store, rootBaseline.threadId());
    rootFixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork rootClaim = claimThreadWork(rootFixture.store, rootBaseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, rootFixture.processor.process(rootClaim));
    // ROOT -> TURN_START + USER：无 normalization suffix。
    assertEquals(3, rootFixture.resolver.lastPath.entries().size());

    Fixture closedFixture = fixture();
    var closedBaseline = seedClosedTurn(closedFixture.store, false);
    seedCommand(
        closedFixture.store,
        closedBaseline.threadId(),
        new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(closedFixture.store, closedBaseline.threadId());
    closedFixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork closedClaim = claimThreadWork(closedFixture.store, closedBaseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, closedFixture.processor.process(closedClaim));
    // TURN_END(continueModel=false) + USER -> 直接新 INPUT Turn，无 HISTORY_CUT suffix。
    assertEquals(7, closedFixture.resolver.lastPath.entries().size());
    assertFalse(
        closedFixture.resolver.lastPath.entries().stream()
            .anyMatch(
                e ->
                    e.payload() instanceof TurnEndPayload end
                        && end.reason() == TurnEndReason.HISTORY_CUT));
  }

  @Test
  void normalizationAtOpenUserTurnClosesHistoricalTurnWithCancelledTurnEnd() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));

    // ROOT, TS1, USER1, TURN_END(CANCELLED), TS2, USER2
    EntryPath path = fixture.resolver.lastPath;
    assertEquals(6, path.entries().size());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(3).payload();
    assertEquals(baseline.turnStartEntryId(), end.turnStartEntryId());
    assertEquals(TurnEndOutcome.CANCELLED, end.outcome());
    assertEquals(TurnEndReason.HISTORY_CUT, end.reason());
    TurnStartPayload newTurn = (TurnStartPayload) path.entries().get(4).payload();
    assertEquals(TurnStartReason.INPUT, newTurn.reason());
    assertEquals(6, path(fixture.store, baseline.threadId()).entries().size());
  }

  @Test
  void normalizationAtAssistantWithToolCallsAppendsOrderedSyntheticResults() {
    Fixture fixture = fixture();
    var baseline = seedHistoricalOpenTurn(fixture.store, 3, 0);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));

    // ROOT, TS, USER, ASSISTANT, SYNTH0, SYNTH1, SYNTH2, TURN_END(CANCELLED), TS2, USER2
    EntryPath path = fixture.resolver.lastPath;
    assertEquals(10, path.entries().size());
    for (int ordinal = 0; ordinal < 3; ordinal++) {
      MessagePayload synthetic = (MessagePayload) path.entries().get(4 + ordinal).payload();
      ToolResultMetadata metadata = synthetic.toolResultMetadata();
      assertEquals(ordinal, metadata.ordinal());
      assertEquals("call-" + ordinal, metadata.toolCallId());
      assertEquals(ToolResultStatus.UNKNOWN, metadata.status());
      assertTrue(metadata.synthetic());
      assertEquals(ToolResultReason.HISTORY_CUT, metadata.reason());
      assertEquals(baseline.assistantEntryId(), metadata.assistantEntryId());
      ToolResultMessageContent content =
          (ToolResultMessageContent) synthetic.message().contents().get(0);
      assertEquals("No result provided", ((TextMessageContent) content.contents().get(0)).text());
      assertTrue(content.error());
    }
    TurnEndPayload end = (TurnEndPayload) path.entries().get(7).payload();
    assertEquals(TurnEndOutcome.CANCELLED, end.outcome());
    assertEquals(baseline.turnStartEntryId(), end.turnStartEntryId());
    // synthetic 结果不创建 ToolInvocation。
    assertTrue(toolsByAssistant(fixture.store, baseline.assistantEntryId()).isEmpty());
  }

  @Test
  void normalizationAtPartialToolResultAppendsOnlyMissingOrdinals() {
    Fixture fixture = fixture();
    var baseline = seedHistoricalOpenTurn(fixture.store, 3, 1);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));

    // ROOT, TS, USER, ASSISTANT, RESULT0(real), SYNTH1, SYNTH2, TURN_END(CANCELLED), TS2, USER2
    EntryPath path = fixture.resolver.lastPath;
    assertEquals(10, path.entries().size());
    MessagePayload real0 = (MessagePayload) path.entries().get(4).payload();
    assertFalse(real0.toolResultMetadata().synthetic());
    MessagePayload synth1 = (MessagePayload) path.entries().get(5).payload();
    assertEquals(1, synth1.toolResultMetadata().ordinal());
    assertTrue(synth1.toolResultMetadata().synthetic());
    MessagePayload synth2 = (MessagePayload) path.entries().get(6).payload();
    assertEquals(2, synth2.toolResultMetadata().ordinal());
    assertTrue(synth2.toolResultMetadata().synthetic());
  }

  @Test
  void twoThreadsSharingHistoricalOpenHeadCreateFreshDescendantTurnStarts() {
    Fixture fixture = fixture();
    var shared = seedHistoricalOpenTurn(fixture.store, 2, 0);
    var second =
        seedSecondThreadAtHistoricalAssistant(
            fixture.store, shared.sessionId(), shared.assistantEntryId());

    // Thread B 先处理：normalization + 新 descendant branch。
    seedCommand(
        fixture.store, second.threadId(), new UserMessageCommandPayload(userMessage("from-b")));
    requestThreadWork(fixture.store, second.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork bClaim = claimThreadWork(fixture.store, second.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(bClaim));

    // Thread A 后处理：自己的 normalization，不读取 B 的 descendant 结果。
    seedCommand(
        fixture.store, shared.threadId(), new UserMessageCommandPayload(userMessage("from-a")));
    requestThreadWork(fixture.store, shared.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork aClaim = claimThreadWork(fixture.store, shared.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(aClaim));

    EntryPath aPath = path(fixture.store, shared.threadId());
    EntryPath bPath = path(fixture.store, second.threadId());
    // A 的 path 是 A 自己的 descendant 分支：与 B 共享的只可能是历史前缀（ROOT/TS/USER/ASSISTANT）。
    List<UUID> historicalPrefix =
        List.of(
            shared.rootEntryId(),
            shared.turnStartEntryId(),
            shared.userEntryId(),
            shared.assistantEntryId());
    for (Entry entry : aPath.entries()) {
      if (historicalPrefix.contains(entry.id())) {
        continue;
      }
      assertFalse(bPath.entries().stream().anyMatch(other -> other.id().equals(entry.id())));
    }
    // 各自都有独立 synthetic 结果与 HISTORY_CUT TURN_END。
    assertEquals(
        2,
        aPath.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof MessagePayload m
                        && m.toolResultMetadata() != null
                        && m.toolResultMetadata().synthetic())
            .count());
    assertEquals(
        2,
        bPath.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof MessagePayload m
                        && m.toolResultMetadata() != null
                        && m.toolResultMetadata().synthetic())
            .count());
    assertEquals(
        1,
        aPath.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof TurnEndPayload end
                        && end.reason() == TurnEndReason.HISTORY_CUT)
            .count());
    assertEquals(
        1,
        bPath.entries().stream()
            .filter(
                e ->
                    e.payload() instanceof TurnEndPayload end
                        && end.reason() == TurnEndReason.HISTORY_CUT)
            .count());
    // 历史 assistant 从头到尾没有 ToolInvocation。
    assertTrue(toolsByAssistant(fixture.store, shared.assistantEntryId()).isEmpty());
  }

  @Test
  void relocationToAttachedAssistantWithoutToolsNormalizesFreshTurn() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    ModelRequestSpec request = plainRequest();
    ProviderResponse response = successResponse(List.of(), "bash");
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            request,
            response,
            null);
    // live attached assistant 由同一 request/response 经 mapper 生成（strict attach 校验要求全等）。
    UUID assistantId =
        insertAssistantPayload(
            fixture.store,
            baseline,
            new HistoryPayloadMapper().assistantPayload(response, request.toolBindings()));
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // head 位于 attached assistant（无调用）：不锁 Model/Tool，normalization 补 CANCELLED TURN_END 后新开
    // INPUT Turn。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));

    assertEquals(assistantId, model(fixture.store, modelId).resultEntryId());
    assertTrue(toolsByAssistant(fixture.store, assistantId).isEmpty());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(7, path.entries().size());
    // ROOT, TS, USER, ASSISTANT, TURN_END(CANCELLED), TS2, USER2
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(TurnEndOutcome.CANCELLED, end.outcome());
    assertEquals(TurnEndReason.HISTORY_CUT, end.reason());
    TurnStartPayload newTurn = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.INPUT, newTurn.reason());
    assertEquals(
        AgentMessageRole.USER, ((MessagePayload) path.entries().get(6).payload()).message().role());
    assertEquals(1, fixture.resolver.calls);
  }

  /**
   * 历史 open Turn 的 head 已落到部分 ToolResult descendant（partial prefix）：新的 {@link
   * ThreadContextClassifier}（rule 6：head/basis/result 任一不等视为历史）走 IDLE_OR_HISTORICAL → INPUT
   * normalization，按 path 中已有的 ToolResult ordinal 前缀补写缺失 synthetic 并补 CANCELLED TURN_END 与新 INPUT
   * Turn。Tool 行 不再有 resultEntryId 字段，"已挂载" 状态由 Entry 路径事实承载。
   */
  @Test
  void partialHistoricalToolResultPathWithRealInvocationsNormalizesFreshTurn() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    UUID threadId = chain.turn().threadId();
    UUID assistantId = chain.assistantEntryId();
    // 真实 TOOL0 Entry 已存在于 path（作为 assistant 的 descendant，head 停在 TOOL0）；tool1 行仍是 terminal
    // SUCCEEDED。
    UUID tool0EntryId =
        inTx(
            fixture,
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      chain.turn().sessionId(),
                      assistantId,
                      realToolResultPayload(assistantId, 0, "call-1"),
                      NOW));
              return id;
            });
    inTx(
        fixture,
        tx -> {
          ThreadState current = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(current.advanceHead(tool0EntryId, NOW));
          return null;
        });
    seedCommand(fixture.store, threadId, new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, threadId);
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    ClaimedWork claim = claimThreadWork(fixture.store, threadId);

    // head != assistant、不在 basis 与 resultEntryId 上：IDLE_OR_HISTORICAL → INPUT normalization 只补缺失
    // ordinal。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, threadId);
    assertEquals(9, path.entries().size());
    // ROOT, TS, USER, ASSISTANT, TOOL0(real), SYNTH1, TURN_END(CANCELLED), TS2, USER2
    MessagePayload real0 = (MessagePayload) path.entries().get(4).payload();
    assertFalse(real0.toolResultMetadata().synthetic());
    assertEquals(0, real0.toolResultMetadata().ordinal());
    MessagePayload synth1 = (MessagePayload) path.entries().get(5).payload();
    assertEquals(1, synth1.toolResultMetadata().ordinal());
    assertTrue(synth1.toolResultMetadata().synthetic());
    assertEquals(1, fixture.resolver.calls);
  }
}
