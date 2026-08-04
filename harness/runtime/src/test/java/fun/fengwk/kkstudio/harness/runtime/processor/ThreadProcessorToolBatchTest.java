package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.STEP_LIMIT;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimLosingStore;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.insertAssistantWithCalls;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.model;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.realToolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tool;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tooledRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.toolsByAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionTool;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;

/** ThreadProcessor Tool sibling batch：原子 ordinal 回写、错误 payload、blocker 与不变量违反回滚。 */
class ThreadProcessorToolBatchTest extends ThreadProcessorTestBase {

  @Test
  void toolSiblingBatchAppliesAtomicallyInOrdinalOrderThenStartsContinuation() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    requestThreadWork(fixture.store, chain.turn().threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, chain.turn().threadId());

    // batch apply -> TURN_END(COMPLETED, continueModel=true) -> 同 claim 继续启动 continuation。
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, chain.turn().threadId());
    List<Entry> entries = path.entries();
    assertEquals(8, entries.size());
    // ROOT, TURN_START, USER, ASSISTANT, TOOL0, TOOL1, TURN_END, TURN_START(CONTINUATION)
    assertEquals(chain.assistantEntryId(), entries.get(3).id());
    MessagePayload tool0 = (MessagePayload) entries.get(4).payload();
    MessagePayload tool1 = (MessagePayload) entries.get(5).payload();
    assertEquals(AgentMessageRole.TOOL, tool0.message().role());
    assertEquals(0, tool0.toolResultMetadata().ordinal());
    assertEquals("call-1", tool0.toolResultMetadata().toolCallId());
    assertEquals(ToolResultStatus.SUCCEEDED, tool0.toolResultMetadata().status());
    ToolResultMessageContent result0 = (ToolResultMessageContent) tool0.message().contents().get(0);
    assertEquals("tool ok", ((TextMessageContent) result0.contents().get(0)).text());
    assertEquals(1, tool1.toolResultMetadata().ordinal());
    assertEquals("call-2", tool1.toolResultMetadata().toolCallId());
    TurnEndPayload end = (TurnEndPayload) entries.get(6).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    TurnStartPayload continuation = (TurnStartPayload) entries.get(7).payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
    List<ToolInvocation> siblings = toolsByAssistant(fixture.store, chain.assistantEntryId());
    assertEquals(entries.get(4).id(), siblings.get(0).resultEntryId());
    assertEquals(entries.get(5).id(), siblings.get(1).resultEntryId());
    assertEquals(entries.get(7).id(), thread(fixture.store, chain.turn().threadId()).headEntryId());
    // baseline(1) + seed assistant advance(2) + batch apply(3)
    assertEquals(3L, thread(fixture.store, chain.turn().threadId()).revision());
    ModelInvocation continuationModel =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(chain.turn().threadId(), entries.get(7).id()))
            .orElseThrow();
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, continuationModel.id())));
  }

  @Test
  void toolSiblingBatchMapsFailureCancelledUnknownPayloadsExactly() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2", "call-3"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(
                ToolInvocationStatus.FAILED,
                ToolInvocationStatus.CANCELLED,
                ToolInvocationStatus.UNKNOWN));
    requestThreadWork(fixture.store, chain.turn().threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, chain.turn().threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    List<Entry> entries = path(fixture.store, chain.turn().threadId()).entries();
    String[] expectedText = {"tool failed", "tool cancelled", "tool unknown"};
    ToolResultStatus[] expectedStatus = {
      ToolResultStatus.FAILED, ToolResultStatus.CANCELLED, ToolResultStatus.UNKNOWN
    };
    for (int i = 0; i < 3; i++) {
      MessagePayload toolPayload = (MessagePayload) entries.get(4 + i).payload();
      ToolResultMessageContent result =
          (ToolResultMessageContent) toolPayload.message().contents().get(0);
      assertEquals(expectedText[i], ((TextMessageContent) result.contents().get(0)).text());
      assertTrue(result.error());
      assertTrue(result.detailsJson().contains("\"kind\""));
      ToolResultMetadata metadata = toolPayload.toolResultMetadata();
      assertEquals(expectedStatus[i], metadata.status());
      assertFalse(metadata.synthetic());
      assertNull(metadata.reason());
      assertEquals("call-" + (i + 1), metadata.toolCallId());
      assertEquals(i, metadata.ordinal());
      assertEquals(chain.assistantEntryId(), metadata.assistantEntryId());
    }
  }

  @Test
  void nonterminalToolSiblingBlocksAndCompletesWorkAsSuspended() {
    Fixture toolFixture = fixture();
    var chain =
        seedToolChain(
            toolFixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.READY));
    requestThreadWork(toolFixture.store, chain.turn().threadId());
    ClaimedWork toolClaim = claimThreadWork(toolFixture.store, chain.turn().threadId());
    assertEquals(ThreadProcessResult.SUSPENDED, toolFixture.processor.process(toolClaim));
    List<ToolInvocation> siblings = toolsByAssistant(toolFixture.store, chain.assistantEntryId());
    assertNull(siblings.get(0).resultEntryId());
    assertNull(siblings.get(1).resultEntryId());
    assertEquals(4, path(toolFixture.store, chain.turn().threadId()).entries().size());
  }

  @Test
  void toolSiblingCountMismatchIsRejectedAtomically() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse(List.of("call-1", "call-2"), "bash"),
            null);
    long assistantId =
        insertAssistantWithCalls(fixture.store, baseline, List.of("call-1", "call-2"));
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    long toolId =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 0, "call-1", ToolInvocationStatus.SUCCEEDED);
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // assistant 有 2 个 call 但只有 1 个 sibling：防御性 ISE，不写入任何 TOOL Entry。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(tool(fixture.store, toolId).resultEntryId());
  }

  @Test
  void toolSiblingCountMismatchOnNonterminalSiblingsIsInvariantViolation() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse(List.of("call-1", "call-2"), "bash"),
            null);
    long assistantId =
        insertAssistantWithCalls(fixture.store, baseline, List.of("call-1", "call-2"));
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    long toolId =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 0, "call-1", ToolInvocationStatus.READY);
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // 2 个 call 但只有 1 个非 terminal sibling：数量一致性校验先于状态分类 -> ISE 回滚，绝非 blocker。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(tool(fixture.store, toolId).resultEntryId());
    assertEquals(ToolInvocationStatus.READY, tool(fixture.store, toolId).status());
  }

  @Test
  void attachedNonterminalMixedSiblingsAreInvariantViolationNotBlocker() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.READY));
    long tool0EntryId =
        inTx(
            fixture,
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      chain.turn().sessionId(),
                      chain.assistantEntryId(),
                      realToolResultPayload(chain.assistantEntryId(), 0, "call-1"),
                      NOW));
              return id;
            });
    transitionTool(
        fixture.store,
        chain.toolInvocationIds().get(0),
        t -> t.attachResultEntry(tool0EntryId, NOW));
    requestThreadWork(fixture.store, chain.turn().threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, chain.turn().threadId());

    // 已挂 result 但并非全部 terminal+已挂：不变量违反（旧行为会当作 blocker SUSPENDED）-> ISE 回滚零写入。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, chain.turn().threadId()).entries().size());
    assertEquals(
        tool0EntryId, tool(fixture.store, chain.toolInvocationIds().get(0)).resultEntryId());
    assertNull(tool(fixture.store, chain.toolInvocationIds().get(1)).resultEntryId());
    assertEquals(
        chain.assistantEntryId(), thread(fixture.store, chain.turn().threadId()).headEntryId());
  }

  @Test
  void assistantWithCallsButEmptySiblingsIsInvariantViolationNotNormalization() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse(List.of("call-1", "call-2"), "bash"),
            null);
    long assistantId =
        insertAssistantWithCalls(fixture.store, baseline, List.of("call-1", "call-2"));
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // assistant 有 2 个 call 但没有 sibling：不变量违反（旧行为静默历史 normalization）-> ISE 回滚，resolver 不被调用。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(0, fixture.resolver.calls);
  }

  @Test
  void nonSucceededModelAttachedToAssistantMessageIsInvariantViolation() {
    for (ModelInvocationStatus status :
        List.of(
            ModelInvocationStatus.FAILED,
            ModelInvocationStatus.CANCELLED,
            ModelInvocationStatus.UNKNOWN)) {
      Fixture fixture = fixture();
      var baseline = seedOpenInputTurn(fixture.store);
      long modelId =
          seedModelInvocation(
              fixture.store,
              baseline.threadId(),
              baseline.turnStartEntryId(),
              baseline.userEntryId(),
              status,
              tooledRequest(List.of("bash")),
              null,
              new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom"));
      long assistantId = insertAssistantWithCalls(fixture.store, baseline, List.of("call-1"));
      transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
      requestThreadWork(fixture.store, baseline.threadId());
      ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

      // 非 SUCCEEDED model 挂到 assistant Message：不变量违反（旧行为静默历史 fallback）-> ISE 回滚，resolver 不被调用。
      assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
      assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
      assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
      assertEquals(assistantId, model(fixture.store, modelId).resultEntryId());
      assertEquals(0, fixture.resolver.calls);
    }
  }

  @Test
  void modelResultToolCallsMismatchingAssistantAreInvariantViolation() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse(List.of("call-1", "call-2"), "bash"),
            null);
    // assistant 与 response 不一致（第二个 call id 不同）：result 与历史 Entry 的机械一致性被破坏。
    long assistantId =
        insertAssistantWithCalls(fixture.store, baseline, List.of("call-1", "call-X"));
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // response toolCalls 与 assistant ToolCall contents 不一致：不变量违反 -> ISE 回滚，零写入。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(assistantId, model(fixture.store, modelId).resultEntryId());
    assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
    assertTrue(toolsByAssistant(fixture.store, assistantId).isEmpty());
  }

  @Test
  void mixedAttachedUnattachedSiblingsRollBackAtomically() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    // 另一 descendant 上已有 TOOL0 结果并挂载 tool0（head 不动，模拟 relocation 前的历史分支）。
    long tool0EntryId =
        inTx(
            fixture,
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      chain.turn().sessionId(),
                      chain.assistantEntryId(),
                      realToolResultPayload(chain.assistantEntryId(), 0, "call-1"),
                      NOW));
              return id;
            });
    transitionTool(
        fixture.store,
        chain.toolInvocationIds().get(0),
        t -> t.attachResultEntry(tool0EntryId, NOW));
    requestThreadWork(fixture.store, chain.turn().threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, chain.turn().threadId());

    // mixed attached/unattached 是不变量违反：ISE 回滚，零写入、零重挂载。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, chain.turn().threadId()).entries().size());
    assertEquals(
        tool0EntryId, tool(fixture.store, chain.toolInvocationIds().get(0)).resultEntryId());
    assertNull(tool(fixture.store, chain.toolInvocationIds().get(1)).resultEntryId());
    assertEquals(
        chain.assistantEntryId(), thread(fixture.store, chain.turn().threadId()).headEntryId());
  }

  @Test
  void lostClaimAtToolBatchFenceRollsBackAllMutations() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(STEP_LIMIT, claimLosingStore(real, 2));
    var chain =
        seedToolChain(
            real,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    requestThreadWork(real, chain.turn().threadId());
    ClaimedWork claim = claimThreadWork(real, chain.turn().threadId());

    // fence 调用序列：claimOwned（1）-> tool batch final fence（2）丢失 -> 整事务回滚（零 TOOL Entry / 挂载）。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));

    assertEquals(4, path(real, chain.turn().threadId()).entries().size());
    assertNull(tool(real, chain.toolInvocationIds().get(0)).resultEntryId());
    assertNull(tool(real, chain.toolInvocationIds().get(1)).resultEntryId());
    assertEquals(chain.assistantEntryId(), thread(real, chain.turn().threadId()).headEntryId());
  }

  @Test
  void binaryToolContentRollsBackAtomically() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse(List.of("call-1", "call-2"), "bash"),
            null);
    long assistantId =
        insertAssistantWithCalls(fixture.store, baseline, List.of("call-1", "call-2"));
    transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW));
    long tool0 =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 0, "call-1", ToolInvocationStatus.READY);
    long tool1 =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 1, "call-2", ToolInvocationStatus.SUCCEEDED);
    succeedToolWith(
        fixture.store,
        tool0,
        new ToolResult(
            "call-1",
            List.of(new BinaryToolContent("application/octet-stream", new byte[] {1, 2})),
            false,
            "{}",
            false));
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // Binary content 无法进入 Session 语义消息：mapper IAE -> 事务回滚零写入。
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(claim));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(tool(fixture.store, tool0).resultEntryId());
    assertNull(tool(fixture.store, tool1).resultEntryId());
    assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
  }
}
