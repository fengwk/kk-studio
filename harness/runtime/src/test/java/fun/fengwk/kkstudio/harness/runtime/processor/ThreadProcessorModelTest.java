package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimLosingStore;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.declarativeBinding;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.hostBindingWithSchema;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.insertAssistantWithCalls;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.model;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestWithBindings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tooledRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.toolsByAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.touchModelTimestamp;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.touchThreadTimestamp;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ThreadProcessor terminal Model apply：每个 claim 恰执行一个动作的成功 / 错误 / blocker / 非 applicable head /
 * fence 回滚。
 */
class ThreadProcessorModelTest extends ThreadProcessorTestBase {

  @Test
  void modelSuccessWithoutToolsAppliesAssistantAndClosesTurnInOneClaim() {
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
    requestThreadWork(fixture.store, baseline.threadId());

    // terminal no-tool：一个 claim 只 apply/close（COMPLETE 无 calls 关闭 turn），无 queued input 不请求 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

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
    // COMPLETE 无 calls 关闭 turn：Model 行被物理删除，Assistant Entry 仍存在于 path。
    assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(baseline.turnStartEntryId(), end.turnStartEntryId());
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertFalse(end.continueModel());
    assertEquals(
        path.entries().get(4).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).version());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /** no-tool terminal 关闭 turn 但已有 queued USER：applyModel 同事务先请求 THREAD 再 complete（wake 可被消费）。 */
  @Test
  void noToolTerminalWithQueuedMessageKeepsThreadWorkForDeferredInput() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        plainRequest(),
        successResponse(List.of(), "bash"),
        null);
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new UserMessageCommandPayload(userMessage("follow-up")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：closed apply（COMPLETE 无 calls）按 queued 快照先 request THREAD 再 complete。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    // close 后无 continuation 义务，但 queued USER 使 THREAD Work 保留（lease 已清，wakeVersion 抬升）。
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(2L, threadWork.wakeVersion());

    // claim2：保留的 wake 可被消费（非丢失）-> queued USER 启动 INPUT turn，命令被 consume。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(7, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.INPUT, start.reason());
    MessagePayload user = (MessagePayload) path.entries().get(6).payload();
    assertEquals(AgentMessageRole.USER, user.message().role());
    assertEquals("follow-up", ((TextMessageContent) user.message().contents().get(0)).text());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void modelSuccessWithToolCallsMaterializesAllToolInvocationsAndRequestsToolWorkInOneClaim() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
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

    // 一个 claim：active Tool phase 只 materialize 并请求 TOOL Work，完成 claim；THREAD Work 不保留。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

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
    for (int callIndex = 0; callIndex < 2; callIndex++) {
      ToolInvocation tool = tools.get(callIndex);
      assertEquals(callIndex, tool.callIndex());
      assertEquals(ToolInvocationStatus.READY, tool.status());
      assertEquals(0, tool.attempt());
      assertNull(tool.approval());
      assertEquals(modelId, tool.modelInvocationId());
      assertEquals("bash", tool.binding().descriptor().name());
      assertEquals(calls.get(callIndex).toolCallId(), tool.call().id());
      assertEquals("{}", tool.call().argumentsJson());
      assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tool.id())));
    }
    assertEquals(assistant.id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).version());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /**
   * Provider 原始 function_call 必须在 Model、assistant history 与 durable ToolInvocation
   * 三处精确一致；执行边界再生成归一化 副本，避免 strict schema 为原可选字段填入 null 后阻断执行。
   */
  @Test
  void modelSuccessPreservesRawCallAndDefersNormalizationToExecutionBoundary() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    InputSchema schema =
        new InputSchema(
            "arguments",
            Map.of("path", new StringSchema(null), "offset", new IntegerSchema(null)),
            Set.of(),
            false);
    ToolBinding binding = hostBindingWithSchema("read", schema);
    String rawArguments = "{\"path\":null,\"offset\":\"10\"}";
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            requestWithBindings(List.of(binding)),
            successResponse(List.of(new ProviderToolCall("call-1", "read", rawArguments))),
            null);
    requestThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    Entry assistant = path(fixture.store, baseline.threadId()).head();
    ToolCallMessageContent historyCall =
        (ToolCallMessageContent)
            ((MessagePayload) assistant.payload())
                .message().contents().stream()
                    .filter(ToolCallMessageContent.class::isInstance)
                    .findFirst()
                    .orElseThrow();
    assertEquals(rawArguments, historyCall.argumentsJson());
    assertEquals(
        rawArguments,
        model(fixture.store, modelId).result().toolCalls().getFirst().argumentsJson());

    ToolInvocation invocation = toolsByAssistant(fixture.store, assistant.id()).getFirst();
    assertEquals(ToolInvocationStatus.READY, invocation.status());
    assertEquals(rawArguments, invocation.call().argumentsJson());
    assertEquals(
        "{\"offset\":10}",
        new ToolInvocationRequest(invocation.call(), invocation.binding()).call().argumentsJson());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, invocation.id())));
  }

  /**
   * terminal apply 的 durable 时间取已锁定 Thread/path/Model 下界；即使该下界已越过 raw leaseUntil，也不能把 lease
   * ownership 时钟一起抬升。
   */
  @Test
  void modelTerminalApplyUsesDurableFloorsWithoutClampingLeaseClock() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse(List.of("call-1"), "bash"),
            null);
    Instant threadFloor = NOW.plusSeconds(120);
    Instant modelFloor = NOW.plusSeconds(121);
    touchThreadTimestamp(fixture.store, baseline.threadId(), threadFloor);
    touchModelTimestamp(fixture.store, modelId, modelFloor);
    requestThreadWork(fixture.store, baseline.threadId());

    // active Tool phase：一个 claim 完成 materialize（head 停在 assistant，无 TURN_END）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath durablePath = path(fixture.store, baseline.threadId());
    Entry assistant = durablePath.head();
    assertEquals(modelFloor, assistant.createdAt());
    assertEquals(modelFloor, model(fixture.store, modelId).updatedAt());
    assertEquals(modelFloor, thread(fixture.store, baseline.threadId()).updatedAt());
    ToolInvocation child = toolsByAssistant(fixture.store, assistant.id()).getFirst();
    assertEquals(modelFloor, child.createdAt());
    assertEquals(modelFloor, child.updatedAt());
  }

  @Test
  void contributorSiblingStateAccessRejectsOnlyCallsAfterAWriteToTheSameKey() {
    assertSiblingStateAccesses(
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.READ),
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.READ),
        ToolInvocationStatus.READY);
    assertSiblingStateAccesses(
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.READ),
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        ToolInvocationStatus.READY);
    assertSiblingStateAccesses(
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.READ),
        ToolInvocationStatus.FAILED);
    assertSiblingStateAccesses(
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        ToolInvocationStatus.FAILED);
    assertSiblingStateAccesses(
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        "goal",
        new ContributorStateAccess("other", ContributorStateAccessMode.WRITE),
        ToolInvocationStatus.READY);
    assertSiblingStateAccesses(
        "goal",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        "memory",
        new ContributorStateAccess("state", ContributorStateAccessMode.WRITE),
        ToolInvocationStatus.READY);
  }

  private void assertSiblingStateAccesses(
      String firstContributor,
      ContributorStateAccess firstAccess,
      String secondContributor,
      ContributorStateAccess secondAccess,
      ToolInvocationStatus expectedSecondStatus) {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    ToolBinding first =
        declarativeBinding("first_tool", firstContributor, "first", List.of(firstAccess));
    ToolBinding second =
        declarativeBinding("second_tool", secondContributor, "second", List.of(secondAccess));
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            requestWithBindings(List.of(first, second)),
            successResponse(
                List.of(
                    new ProviderToolCall("call-1", "first_tool", "{}"),
                    new ProviderToolCall("call-2", "second_tool", "{}"))),
            null);
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：materialize 全部槽位并按 sibling 约束拒绝后续 WRITE 后读取同一 key 的调用。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    UUID assistantId = model(fixture.store, modelId).resultEntryId();
    List<ToolInvocation> tools = toolsByAssistant(fixture.store, assistantId);
    assertEquals(ToolInvocationStatus.READY, tools.get(0).status());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tools.get(0).id())));
    assertEquals(expectedSecondStatus, tools.get(1).status());
    if (expectedSecondStatus == ToolInvocationStatus.FAILED) {
      assertEquals("SIBLING_STATE_CONFLICT", tools.get(1).error().kind());
      assertTrue(tools.get(1).effects().isEmpty());
      assertNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tools.get(1).id())));
    } else {
      assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tools.get(1).id())));
    }
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
      UUID modelId =
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

      // 一个 claim：terminal failure / cancel / unknown 关闭 turn（无 queued input 不请求 THREAD）。
      assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

      EntryPath path = path(fixture.store, baseline.threadId());
      assertEquals(5, path.entries().size());
      Entry errorEntry = path.entries().get(3);
      AssistantErrorPayload errorPayload = (AssistantErrorPayload) errorEntry.payload();
      assertEquals(error.kind().name(), errorPayload.error().code());
      assertEquals(error.message(), errorPayload.error().message());
      // terminal failure / cancel / unknown 关闭 turn：Model 行被物理删除，error Entry 仍存在。
      assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));
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
    // head relocation 回 TURN_START：open turn 仍在但 head != requestHead（USER），model 不再 applicable。
    inTx(
        fixture,
        tx -> {
          var current = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(current.advanceHead(baseline.turnStartEntryId(), NOW));
          return null;
        });
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：idle quiesce（不 apply 非 applicable model、不启动 turn）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(2, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(model(fixture.store, modelId).resultEntryId());
    assertEquals(0, fixture.resolver.calls);
  }

  @Test
  void descendantHeadStaleTerminalModelIsNotAppliedAndNormalizes() {
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
    // head relocation 到 requestHead 的 descendant（独立 ASSISTANT Entry）：model 仍 terminal 未挂结果但不再
    // applicable。
    insertAssistantWithCalls(fixture.store, baseline, List.of());
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // 一个 claim：stale model 不 apply；queued USER 触发历史 open Turn 的 INPUT normalization。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

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
  void nonterminalModelBlocksAndCompletesWorkInOneClaim() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
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

    // 一个 claim：model 仍活跃则直接完成 claim（不启动 turn、不循环）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(3, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(model(fixture.store, modelId).resultEntryId());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(0, fixture.resolver.calls);
  }

  /**
   * COMPLETE + unknown tool：immediate FAILED(UNKNOWN_TOOL) 槽位（binding null），无 TOOL Work，自唤醒 THREAD
   * 反馈模型。分三个 claim：Model apply -> Tool batch -> continuation。
   */
  @Test
  void unknownToolCallMaterializesImmediateFailedInvocationAndFeedsBackToModel() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
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
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Model apply 物化 immediate FAILED(UNKNOWN_TOOL) 槽位，仅请求 THREAD、完成 claim（不写 TURN_END）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterModel = path(fixture.store, baseline.threadId());
    assertEquals(4, afterModel.entries().size());
    Entry assistant = afterModel.entries().get(3);
    assertEquals(assistant.id(), model(fixture.store, modelId).resultEntryId());
    List<ToolInvocation> slots = toolsByAssistant(fixture.store, assistant.id());
    assertEquals(1, slots.size());
    assertEquals(ToolInvocationStatus.FAILED, slots.get(0).status());
    assertEquals("UNKNOWN_TOOL", slots.get(0).error().kind());
    assertNull(slots.get(0).binding());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, slots.get(0).id())));
    // 全部 immediate terminal：applyModel 已自唤醒 THREAD，行保留。
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // claim2：Tool batch 一次 append outcomes + continueModel TURN_END，删除 children+parent，请求 THREAD 后
    // complete。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterBatch = path(fixture.store, baseline.threadId());
    assertEquals(6, afterBatch.entries().size());
    // 反馈错误：TOOL entry（renderer fallback "tool"）+ TURN_END(COMPLETED, continueModel=true)。
    MessagePayload toolResult = (MessagePayload) afterBatch.entries().get(4).payload();
    assertEquals(AgentMessageRole.TOOL, toolResult.message().role());
    assertEquals(
        "tool", ((ToolResultMessageContent) toolResult.message().contents().get(0)).rendererKey());
    TurnEndPayload end = (TurnEndPayload) afterBatch.entries().get(5).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));
    assertTrue(toolsByAssistant(fixture.store, assistant.id()).isEmpty());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // claim3：下一 claim 才创建 continuation Model 并请求 MODEL Work（consumed 本 claim，THREAD 行被清除）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(7, path.entries().size());
    TurnStartPayload continuation = (TurnStartPayload) path.entries().get(6).payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
    ModelInvocation continuationModel =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), path.entries().get(6).id()))
            .orElseThrow();
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, continuationModel.id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(1, fixture.resolver.calls);
  }

  /** COMPLETE + schema-invalid call：immediate FAILED(INVALID_TOOL_ARGUMENTS)，binding 保留，反馈模型。 */
  @Test
  void schemaInvalidToolCallMaterializesImmediateFailedInvocationAndFeedsBackToModel() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        tooledRequest(List.of("bash")),
        successResponse(List.of(new ProviderToolCall("call-1", "bash", "{\"unexpected\":1}"))),
        null);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Model apply（schema-invalid immediate FAILED 槽位）-> 请求 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    // claim2：Tool batch 反馈 INVALID_TOOL_ARGUMENTS -> TURN_END(COMPLETED, continueModel=true) -> 请求
    // THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterBatch = path(fixture.store, baseline.threadId());
    assertEquals(6, afterBatch.entries().size());
    assertTrue(toolsByAssistant(fixture.store, afterBatch.entries().get(3).id()).isEmpty());
    TurnEndPayload end = (TurnEndPayload) afterBatch.entries().get(5).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    // claim3：continuation。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(7, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(1, fixture.resolver.calls);
  }

  /** COMPLETE + mixed batch：每 call 一个槽位，只为 READY 请求 TOOL Work，FAILED 槽位等待 batch 应用。 */
  @Test
  void mixedToolCallBatchRequestsToolWorkOnlyForReadySlots() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        tooledRequest(List.of("bash")),
        successResponse(
            List.of(
                new ProviderToolCall("call-1", "bash", "{}"),
                new ProviderToolCall("call-2", "undeclared", "{}"),
                new ProviderToolCall("call-3", "bash", "{\"unexpected\":1}"))),
        null);
    requestThreadWork(fixture.store, baseline.threadId());

    // READY 槽位使 batch 非全部 terminal：一个 claim 只 materialize + 请求 TOOL Work，不写 TURN_END、不自唤醒 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    UUID assistantId = thread(fixture.store, baseline.threadId()).headEntryId();
    List<ToolInvocation> tools = toolsByAssistant(fixture.store, assistantId);
    assertEquals(3, tools.size());
    assertEquals(ToolInvocationStatus.READY, tools.get(0).status());
    assertEquals(ToolInvocationStatus.FAILED, tools.get(1).status());
    assertEquals("UNKNOWN_TOOL", tools.get(1).error().kind());
    assertNull(tools.get(1).binding());
    assertEquals(ToolInvocationStatus.FAILED, tools.get(2).status());
    assertEquals("INVALID_TOOL_ARGUMENTS", tools.get(2).error().kind());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tools.get(0).id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tools.get(1).id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.TOOL, tools.get(2).id())));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /** LENGTH 无 calls：failed turn，stable reason OUTPUT_TRUNCATED，零 ToolInvocation。 */
  @Test
  void lengthWithoutCallsClosesFailedTurnWithOutputTruncated() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse("partial text", List.of(), GenerationStopReason.LENGTH),
            null);
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：LENGTH 无 calls 关闭 turn（无 queued input 不请求 THREAD）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    MessagePayload assistant = (MessagePayload) path.entries().get(3).payload();
    assertEquals(
        "partial text", ((TextMessageContent) assistant.message().contents().get(0)).text());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(TurnEndReason.OUTPUT_TRUNCATED, end.reason());
    assertFalse(end.continueModel());
    assertEquals(0, toolsByAssistant(fixture.store, path.entries().get(3).id()).size());
    // LENGTH 无 calls 关闭 turn：Model 行被物理删除。
    assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));
  }

  /** FILTERED：failed turn，stable reason CONTENT_FILTERED，零 ToolInvocation。 */
  @Test
  void filteredResponseClosesFailedTurnWithContentFiltered() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            tooledRequest(List.of("bash")),
            successResponse("", List.of(), GenerationStopReason.FILTERED),
            null);
    requestThreadWork(fixture.store, baseline.threadId());

    // 一个 claim：FILTERED 关闭 turn。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(TurnEndReason.CONTENT_FILTERED, end.reason());
    assertFalse(end.continueModel());
    assertEquals(0, toolsByAssistant(fixture.store, path.entries().get(3).id()).size());
    // FILTERED 关闭 turn：Model 行被物理删除。
    assertNull(fixture.store.transaction(tx -> tx.findModelInvocation(modelId)).orElse(null));
  }

  /** LENGTH 有 calls：每个 observed call 一个 immediate FAILED(MODEL_OUTPUT_TRUNCATED)，执行零个并反馈模型。 */
  @Test
  void lengthWithCallsMaterializesTruncatedFailuresAndFeedsBackToModel() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    seedModelInvocation(
        fixture.store,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.userEntryId(),
        ModelInvocationStatus.SUCCEEDED,
        tooledRequest(List.of("bash")),
        successResponse(
            "partial",
            List.of(
                new ProviderToolCall("call-1", "bash", "{}"),
                new ProviderToolCall("call-2", "bash", "{}")),
            GenerationStopReason.LENGTH),
        null);
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Model apply materialize 两个 immediate FAILED(MODEL_OUTPUT_TRUNCATED) 槽位 -> 请求 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    // claim2：Tool batch 反馈两个 LENGTH 错误 -> TURN_END(COMPLETED, continueModel=true)。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterBatch = path(fixture.store, baseline.threadId());
    assertEquals(7, afterBatch.entries().size());
    assertEquals(
        AgentMessageRole.TOOL,
        ((MessagePayload) afterBatch.entries().get(4).payload()).message().role());
    assertEquals(
        AgentMessageRole.TOOL,
        ((MessagePayload) afterBatch.entries().get(5).payload()).message().role());
    TurnEndPayload end = (TurnEndPayload) afterBatch.entries().get(6).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    // 全部 immediate FAILED 槽位所在的 batch 已应用并物理删除 rows。
    assertTrue(toolsByAssistant(fixture.store, afterBatch.entries().get(3).id()).isEmpty());
    // claim3：下一 claim 才 start continuation。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(8, path.entries().size());
    assertEquals(
        TurnStartReason.CONTINUATION,
        ((TurnStartPayload) path.entries().get(7).payload()).reason());
    assertEquals(1, fixture.resolver.calls);
  }

  @Test
  void lostClaimAtModelApplyFenceRollsBackAllMutations() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var baseline = seedOpenInputTurn(real);
    UUID modelId =
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
    assertEquals(0L, thread(real, baseline.threadId()).version());
  }
}
