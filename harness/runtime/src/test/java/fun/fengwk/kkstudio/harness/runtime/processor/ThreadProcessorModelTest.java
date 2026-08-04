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
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tooledRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.toolsByAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.ArrayList;
import java.util.List;

/** ThreadProcessor terminal Model apply：成功 / 错误 / blocker / 非 applicable head / final fence 回滚。 */
class ThreadProcessorModelTest extends ThreadProcessorTestBase {

  @Test
  void modelSuccessWithoutToolsAppliesAssistantAndClosesTurnThenQuiesces() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
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

    assertEquals(ThreadProcessResult.QUIESCENT, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    Entry assistant = path.entries().get(3);
    MessagePayload assistantPayload = (MessagePayload) assistant.payload();
    assertEquals(AgentMessageRole.ASSISTANT, assistantPayload.message().role());
    assertEquals(1, assistantPayload.message().contents().size());
    assertEquals(
        "response text",
        ((TextMessageContent) assistantPayload.message().contents().get(0)).text());
    assertNotNull(assistantPayload.assistantMetadata());
    assertEquals(assistant.id(), model(fixture.store, modelId).resultEntryId());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(baseline.turnStartEntryId(), end.turnStartEntryId());
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertFalse(end.continueModel());
    assertEquals(
        path.entries().get(4).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).revision());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void modelSuccessWithToolCallsMaterializesAllToolInvocationsAndRequestsToolWork() {
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
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(4, path.entries().size());
    Entry assistant = path.entries().get(3);
    MessagePayload assistantPayload = (MessagePayload) assistant.payload();
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (var content : assistantPayload.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    assertEquals(2, calls.size());
    assertEquals("call-1", calls.get(0).toolCallId());
    assertEquals("call-2", calls.get(1).toolCallId());
    assertEquals(assistant.id(), model(fixture.store, modelId).resultEntryId());
    List<ToolInvocation> tools = toolsByAssistant(fixture.store, assistant.id());
    assertEquals(2, tools.size());
    for (int ordinal = 0; ordinal < 2; ordinal++) {
      ToolInvocation tool = tools.get(ordinal);
      assertEquals(ordinal, tool.ordinal());
      assertEquals(ToolInvocationStatus.READY, tool.status());
      assertEquals(0, tool.attempt());
      assertNull(tool.approval());
      assertNull(tool.resultEntryId());
      assertEquals(modelId, tool.modelInvocationId());
      assertEquals("bash", tool.request().binding().descriptor().name());
      assertEquals(calls.get(ordinal).toolCallId(), tool.request().call().id());
      assertEquals("{}", tool.request().call().argumentsJson());
      assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tool.id())));
    }
    assertEquals(assistant.id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).revision());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void modelTerminalErrorsMapToAssistantErrorAndFailedTurnEnd() {
    List<ModelInvocationStatus> statuses =
        List.of(
            ModelInvocationStatus.FAILED,
            ModelInvocationStatus.CANCELLED,
            ModelInvocationStatus.UNKNOWN);
    for (ModelInvocationStatus status : statuses) {
      Fixture fixture = fixture();
      var baseline = seedOpenInputTurn(fixture.store);
      ModelInvocationError error =
          status == ModelInvocationStatus.CANCELLED
              ? new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled")
              : new ModelInvocationError(ProviderErrorKind.TRANSIENT, "model boom");
      long modelId =
          seedModelInvocation(
              fixture.store,
              baseline.threadId(),
              baseline.turnStartEntryId(),
              baseline.userEntryId(),
              status,
              plainRequest(),
              null,
              error);
      requestThreadWork(fixture.store, baseline.threadId());
      ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

      assertEquals(ThreadProcessResult.QUIESCENT, fixture.processor.process(claim));

      EntryPath path = path(fixture.store, baseline.threadId());
      assertEquals(5, path.entries().size());
      Entry errorEntry = path.entries().get(3);
      AssistantErrorPayload errorPayload = (AssistantErrorPayload) errorEntry.payload();
      assertEquals(error.kind().name(), errorPayload.error().code());
      assertEquals(error.message(), errorPayload.error().message());
      assertEquals(errorEntry.id(), model(fixture.store, modelId).resultEntryId());
      TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
      assertEquals(TurnEndOutcome.FAILED, end.outcome());
      assertFalse(end.continueModel());
      assertEquals(TurnEndReason.TURN_FAILED, end.reason());
      assertEquals(
          path.entries().get(4).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    }
  }

  @Test
  void modelTerminalWithBasisOffCurrentBranchIsNotApplied() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            plainRequest(),
            successResponse(List.of(), "bash"),
            null);
    // head relocation 回 TURN_START：open turn 仍在但 head != basis（USER），model 不再 applicable。
    inTx(
        fixture,
        tx -> {
          var current = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(current.advanceHead(baseline.turnStartEntryId(), false, NOW));
          return null;
        });
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.QUIESCENT, fixture.processor.process(claim));

    assertEquals(2, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(model(fixture.store, modelId).resultEntryId());
    assertEquals(0, fixture.resolver.calls);
  }

  @Test
  void descendantHeadStaleTerminalModelIsNotAppliedAndNormalizes() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            plainRequest(),
            successResponse(List.of(), "bash"),
            null);
    // head relocation 到 basis 的 descendant（独立 ASSISTANT Entry）：model 仍 terminal 未挂结果但不再 applicable。
    insertAssistantWithCalls(fixture.store, baseline, List.of());
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest()));
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));

    // stale model 未被应用；历史 open Turn 走 INPUT normalization（CANCELLED TURN_END + 新 INPUT Turn）。
    assertNull(model(fixture.store, modelId).resultEntryId());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(7, path.entries().size());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(TurnEndOutcome.CANCELLED, end.outcome());
    assertEquals(TurnEndReason.HISTORY_CUT, end.reason());
    TurnStartPayload newTurn = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.INPUT, newTurn.reason());
    assertEquals(1, fixture.resolver.calls);
  }

  @Test
  void nonterminalModelBlocksAndCompletesWorkAsSuspended() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    long modelId =
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
    assertEquals(ThreadProcessResult.SUSPENDED, fixture.processor.process(claim));
    assertEquals(3, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(model(fixture.store, modelId).resultEntryId());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(0, fixture.resolver.calls);
  }

  @Test
  void unboundToolCallInTerminalResponseIsRejectedAtomically() {
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
            successResponse(List.of("call-1"), "unknown-tool"),
            null);
    requestThreadWork(fixture.store, baseline.threadId());
    ClaimedWork claim = claimThreadWork(fixture.store, baseline.threadId());

    // request 的 frozen bindings 不匹配 response 的 tool call name：防御性 ISE，事务回滚零 mutation。
    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claim));
    assertEquals(3, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(model(fixture.store, modelId).resultEntryId());
    assertEquals(ModelInvocationStatus.SUCCEEDED, model(fixture.store, modelId).status());
  }

  @Test
  void lostClaimAtModelApplyFenceRollsBackAllMutations() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(STEP_LIMIT, claimLosingStore(real, 2));
    var baseline = seedOpenInputTurn(real);
    long modelId =
        seedModelInvocation(
            real,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            plainRequest(),
            successResponse(List.of(), "bash"),
            null);
    requestThreadWork(real, baseline.threadId());
    ClaimedWork claim = claimThreadWork(real, baseline.threadId());

    // fence 调用序列：process 前置 claimOwned（1）-> applyModel final fence（2）丢失 -> 整事务回滚（零 mutation）。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));

    assertEquals(3, path(real, baseline.threadId()).entries().size());
    assertNull(model(real, modelId).resultEntryId());
    assertEquals(baseline.userEntryId(), thread(real, baseline.threadId()).headEntryId());
    assertEquals(0L, thread(real, baseline.threadId()).revision());
  }
}
