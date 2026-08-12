package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.insertAssistantWithCalls;
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
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tool;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.toolsByAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultReason;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.util.List;
import java.util.UUID;

/**
 * ThreadProcessor history normalization 与 MOVE_HEAD relocation：历史分支绝不恢复 / 复用，只补 synthetic 后新开 Turn。
 */
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
    rootFixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork rootClaim = claimThreadWork(rootFixture.store, rootBaseline.threadId());
    assertEquals(ThreadProcessResult.SUSPENDED, rootFixture.processor.process(rootClaim));
    // ROOT -> TURN_START + USER：无 normalization suffix。
    assertEquals(3, rootFixture.resolver.lastPath.entries().size());

    Fixture closedFixture = fixture();
    var closedBaseline = seedClosedTurn(closedFixture.store, false);
    seedCommand(
        closedFixture.store,
        closedBaseline.threadId(),
        new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(closedFixture.store, closedBaseline.threadId());
    closedFixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork closedClaim = claimThreadWork(closedFixture.store, closedBaseline.threadId());
    assertEquals(ThreadProcessResult.SUSPENDED, closedFixture.processor.process(closedClaim));
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
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

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
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

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
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

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
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork bClaim = claimThreadWork(fixture.store, second.threadId());
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(bClaim));

    // Thread A 后处理：自己的 normalization，不读取 B 的 descendant 结果。
    seedCommand(
        fixture.store, shared.threadId(), new UserMessageCommandPayload(userMessage("from-a")));
    requestThreadWork(fixture.store, shared.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork aClaim = claimThreadWork(fixture.store, shared.threadId());
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(aClaim));

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
    UUID assistantId = insertAssistantWithCalls(fixture.store, baseline, List.of());
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // MOVE_HEAD 回 attached assistant（无调用）：不锁 Model/Tool，normalization 补 CANCELLED TURN_END 后新开
    // INPUT Turn。
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

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

  @Test
  void relocationToAttachedAssistantWithAttachedToolDescendantsNormalizesFreshTurn() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    UUID threadId = chain.turn().threadId();
    UUID assistantId = chain.assistantEntryId();
    // 模拟已 apply 的 descendant：TOOL0/TOOL1 结果链 + 挂载 + head 推进到 TOOL1，再 MOVE_HEAD 回 assistant。
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
    transitionTool(
        fixture.store,
        chain.toolInvocationIds().get(0),
        t -> t.attachResultEntry(tool0EntryId, NOW));
    UUID tool1EntryId =
        inTx(
            fixture,
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      chain.turn().sessionId(),
                      tool0EntryId,
                      realToolResultPayload(assistantId, 1, "call-2"),
                      NOW));
              return id;
            });
    transitionTool(
        fixture.store,
        chain.toolInvocationIds().get(1),
        t -> t.attachResultEntry(tool1EntryId, NOW));
    inTx(
        fixture,
        tx -> {
          ThreadState current = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(current.advanceHead(tool1EntryId, false, NOW));
          return null;
        });
    inTx(
        fixture,
        tx -> {
          ThreadState current = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(current.advanceHead(assistantId, false, NOW));
          return null;
        });
    seedCommand(fixture.store, threadId, new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, threadId);
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, threadId);

    // 结果已在另一 descendant：不重挂、不 apply，normalization 补 2 个 synthetic 后新开 INPUT Turn。
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    assertEquals(
        tool0EntryId, tool(fixture.store, chain.toolInvocationIds().get(0)).resultEntryId());
    assertEquals(
        tool1EntryId, tool(fixture.store, chain.toolInvocationIds().get(1)).resultEntryId());
    assertEquals(assistantId, model(fixture.store, chain.modelInvocationId()).resultEntryId());
    EntryPath path = path(fixture.store, threadId);
    assertEquals(9, path.entries().size());
    // ROOT, TS, USER, ASSISTANT, SYNTH0, SYNTH1, TURN_END(CANCELLED), TS2, USER2
    for (int ordinal = 0; ordinal < 2; ordinal++) {
      ToolResultMetadata metadata =
          ((MessagePayload) path.entries().get(4 + ordinal).payload()).toolResultMetadata();
      assertEquals(ordinal, metadata.ordinal());
      assertTrue(metadata.synthetic());
      assertEquals(assistantId, metadata.assistantEntryId());
    }
    TurnEndPayload end = (TurnEndPayload) path.entries().get(6).payload();
    assertEquals(TurnEndOutcome.CANCELLED, end.outcome());
    assertEquals(1, fixture.resolver.calls);
  }

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
    // 部分结果链：TOOL0 已挂载且 head 停在 TOOL0（历史前缀）；tool1 terminal 但未挂载（真实 invocation 在别处）。
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
    transitionTool(
        fixture.store,
        chain.toolInvocationIds().get(0),
        t -> t.attachResultEntry(tool0EntryId, NOW));
    inTx(
        fixture,
        tx -> {
          ThreadState current = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(current.advanceHead(tool0EntryId, false, NOW));
          return null;
        });
    seedCommand(fixture.store, threadId, new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, threadId);
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, threadId);

    // path 已含 TOOL 前缀 -> 历史：head != assistant，不再 apply 真实结果，normalization 只补缺失 ordinal。
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    assertNull(tool(fixture.store, chain.toolInvocationIds().get(1)).resultEntryId());
    assertEquals(
        tool0EntryId, tool(fixture.store, chain.toolInvocationIds().get(0)).resultEntryId());
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
