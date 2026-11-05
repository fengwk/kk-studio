package fun.fengwk.kkstudio.harness.builtin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 流式与响应收敛：checkpoint 前缀、tool fragment gap、replayState、压缩子 final 完整性与 invalid response 重试。 */
class ModelProcessorStreamTest extends ModelProcessorTestSupport {
  // ---------------------------------------------------------------------------------------------
  // stream / terminal 回调
  // ---------------------------------------------------------------------------------------------

  /** checkpoint 只累积 text/thinking；tool-call fragment 只推进 sequence，绝不进入 checkpoint。 */
  @Test
  void checkpointKeepsPrefixAndExcludesToolFragments() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool(), IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("hel"));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{\"a\""));

    // 方案 B：delta 立即推送、checkpoint 由独立节奏落库；这里用追帧请求触发一次 checkpoint（异步落库）。
    assertTrue(fixture.processor.requestCheckpoint(fixture.invocationId, 1));
    awaitTrue(() -> model(fixture.store, fixture.invocationId).streamCheckpoint() != null);
    ModelInvocation mid = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, mid.status());
    assertEquals(1, mid.attempt());
    assertEquals(1, mid.streamCheckpoint().sequence());
    assertEquals("hel", mid.streamCheckpoint().text());
    assertFalse(mid.streamCheckpoint().text().contains("call_1"));

    listener.event(new ProviderStreamEvent.TextDelta("lo"));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, null, null, ":1}"));
    listener.succeed(toolResponse("hello", new ProviderToolCall("call_1", "bash", "{\"a\":1}")));

    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertEquals(1, terminal.attempt());
    assertNotNull(terminal.streamCheckpoint());
    assertEquals(3, terminal.streamCheckpoint().sequence());
    assertEquals("hello", terminal.streamCheckpoint().text());
    assertFalse(terminal.streamCheckpoint().text().contains("call_1"));
    List<ProviderStreamEvent> deltas = deltas(fixture.sink);
    assertEquals(4, deltas.size());
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("hel"), new ProviderStreamEvent.TextDelta("lo")),
        deltas.stream().filter(delta -> delta instanceof ProviderStreamEvent.TextDelta).toList());
  }

  /**
   * 意图：Tool-owned 历史 action 必须在 terminal ProviderResponse 成为 durable 事实之前冻结——`onSucceeded` 返回时
   * durable ModelInvocation 结果已携带 action；渲染器缺失、失败或抛异常都只让该调用保持 null，completion 仍然
   * SUCCEEDED，绝不因此让模型请求 失败。
   */
  @Test
  void freezesToolHistoryActionIntoDurableResponseBeforeCommit() {
    Fixture fixture =
        new Fixture(
            NO_RETRY, requestWithTool(), (binding, call) -> Optional.of("run bash " + call.id()));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(toolResponse("hello", new ProviderToolCall("call_1", "bash", "{}")));

    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertEquals("run bash call_1", terminal.result().toolCalls().getFirst().historyAction());
    assertEquals("{}", terminal.result().toolCalls().getFirst().argumentsJson());

    // 渲染器抛异常：durable response 的 action 保持 null，completion 仍然成功。
    Fixture failing =
        new Fixture(
            NO_RETRY,
            requestWithTool(),
            (binding, call) -> {
              throw new IllegalStateException("renderer exploded");
            });
    failing.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        failing.processor.process(claim(failing.store, failing.invocationId)));
    failing
        .gateway
        .controlled(failing.invocationId)
        .succeed(toolResponse("hello", new ProviderToolCall("call_1", "bash", "{}")));

    ModelInvocation failedRender = model(failing.store, failing.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, failedRender.status());
    assertNull(failedRender.result().toolCalls().getFirst().historyAction());
  }

  /**
   * 压缩子 final 被输出上限截断（LENGTH 且无 tool intent）：第一次 attempt 绝不落摘要，走既有调用重试（同一预算、attempt+1）； 第二次合法
   * COMPLETE 摘要才 SUCCEEDED。重试期间不请求 THREAD wake，父 durable wait 不被子的重试唤醒。
   */
  @Test
  void compactionChildTruncatedFinalRetriesUnderTheSameBudgetThenCommitsSummary() {
    Fixture fixture =
        compactionFixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("truncated partial"));
    listener.succeed(
        new ProviderCompletion(
            response("truncated partial", GenerationStopReason.LENGTH), sampleReplayState()));
    awaitExecutionExit(fixture);

    ModelInvocation afterFirst = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, afterFirst.status());
    assertEquals(1, afterFirst.attempt());
    assertNull(afterFirst.resultEntryId(), "a truncated final must not commit any summary");
    assertEquals(1, afterFirst.failedAttempts().size());
    assertEquals(1, afterFirst.failedAttempts().getFirst().attempt());
    assertEquals("truncated partial", afterFirst.failedAttempts().getFirst().text());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, afterFirst.failedAttempts().getFirst().error().kind());
    assertEquals(
        "compaction model response was truncated before the summary completed",
        afterFirst.failedAttempts().getFirst().error().message());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion(),
        "a compaction child retry must not wake the parent thread");

    // 第二次 attempt：合法摘要提交，attempt 计数不重置。
    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);
    secondListener.succeed(
        new ProviderCompletion(
            response("valid summary", GenerationStopReason.COMPLETE), sampleReplayState()));

    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertEquals(2, terminal.attempt());
    assertEquals("valid summary", terminal.result().text());
    assertNotNull(terminal.providerReplayState(), "compaction child follows normal replay rules");
  }

  /** 截断 final 耗尽重试预算：第二次仍是截断 final 即 FAILED，保留 attempt 1 的失败审计，绝不交付部分摘要。 */
  @Test
  void compactionChildTruncatedFinalExhaustionTerminatesFailedWithoutPartialSummary() {
    Fixture fixture =
        compactionFixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    listener.event(new ProviderStreamEvent.TextDelta("partial one"));
    listener.succeed(
        new ProviderCompletion(
            response("partial one", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);
    secondListener.event(new ProviderStreamEvent.TextDelta("partial two"));
    secondListener.succeed(
        new ProviderCompletion(
            response("partial two", GenerationStopReason.LENGTH), sampleReplayState()));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(2, failed.attempt());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failed.error().kind());
    assertEquals(1, failed.failedAttempts().size());
    assertEquals("partial one", failed.failedAttempts().getFirst().text());
    assertNull(failed.resultEntryId());
    Boolean assistantMaterialized =
        fixture.store.transaction(
            tx ->
                tx
                    .entries()
                    .loadPath(
                        tx.threads().find(fixture.baseline.threadId()).orElseThrow().headEntryId())
                    .entries()
                    .stream()
                    .anyMatch(
                        entry ->
                            entry.payload() instanceof MessagePayload message
                                && message.message().role() == AgentMessageRole.ASSISTANT));
    assertFalse(
        assistantMaterialized, "no partial text may ever be materialized as the child result");
  }

  /** 压缩子空 final（COMPLETE 且无 tool calls 且空文本）按 INVALID_RESPONSE 处理，不落空摘要。 */
  @Test
  void compactionChildEmptyCompleteFinalFailsAsInvalidResponse() {
    Fixture fixture = compactionFixture(NO_RETRY, IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(
        new ProviderCompletion(
            new ProviderResponse(
                "", null, List.of(), GenerationStopReason.COMPLETE, usage(), "req-1", null, null),
            sampleReplayState()));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failed.error().kind());
    assertEquals("compaction model returned an empty summary", failed.error().message());
    assertNull(failed.resultEntryId());
  }

  /**
   * 压缩子纯空白 final（COMPLETE 且无 tool calls 且文本为 " \n"）同样是空摘要：绝不落 receipt 让父回合以空摘要失败，而是走既有调用重试；
   * 第二次合法摘要才 SUCCEEDED。
   */
  @Test
  void compactionChildWhitespaceOnlyCompleteFinalRetriesThenCommitsValidSummary() {
    Fixture fixture =
        compactionFixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(
        new ProviderCompletion(
            response(" \n\t", GenerationStopReason.COMPLETE), sampleReplayState()));

    ModelInvocation afterFirst = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, afterFirst.status());
    assertEquals(1, afterFirst.attempt());
    assertNull(afterFirst.resultEntryId(), "a blank final must not commit any summary");
    assertEquals(1, afterFirst.failedAttempts().size());
    assertEquals(" \n\t", afterFirst.failedAttempts().getFirst().text());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, afterFirst.failedAttempts().getFirst().error().kind());
    assertEquals(
        "compaction model returned an empty summary",
        afterFirst.failedAttempts().getFirst().error().message());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);
    secondListener.succeed(
        new ProviderCompletion(
            response("valid summary", GenerationStopReason.COMPLETE), sampleReplayState()));

    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertEquals(2, terminal.attempt());
    assertEquals("valid summary", terminal.result().text());
  }

  /** 压缩子纯空白 final 耗尽重试预算：FAILED 且无任何摘要提交，绝不把空白当摘要。 */
  @Test
  void compactionChildWhitespaceOnlyCompleteFinalExhaustionFailsClosed() {
    Fixture fixture =
        compactionFixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .succeed(
            new ProviderCompletion(
                response(" \n", GenerationStopReason.COMPLETE), sampleReplayState()));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .succeed(
            new ProviderCompletion(
                response("\t \n", GenerationStopReason.COMPLETE), sampleReplayState()));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(2, failed.attempt());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failed.error().kind());
    assertEquals(1, failed.failedAttempts().size());
    assertEquals(" \n", failed.failedAttempts().getFirst().text());
    assertNull(failed.resultEntryId());
  }

  /** 压缩子 FILTERED 明确不可恢复：INVALID_REQUEST 直接 FAILED，保留同一 attempt（不重试、不落摘要），即使策略允许重试。 */
  @Test
  void compactionChildFilteredFinalFailsWithoutRetry() {
    Fixture fixture =
        compactionFixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(
        new ProviderCompletion(
            response("filtered text", GenerationStopReason.FILTERED), sampleReplayState()));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(1, failed.attempt());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failed.error().kind());
    assertEquals(
        "compaction model response was filtered and cannot be used as a summary",
        failed.error().message());
    assertEquals(1, fixture.gateway.admitCalls, "a filtered result must never be replayed");
    assertNull(failed.resultEntryId());
  }

  /** 配置了 tools 的压缩子合法 tool 回合（COMPLETE + tool calls）按普通 Runtime 推进，且不再抑制 replay 与实时 delta 发布。 */
  @Test
  void compactionChildToolTurnProceedsLikeOrdinaryRuntimeAndPublishesDeltas() {
    Fixture fixture = compactionFixture(NO_RETRY, IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("calling tool"));
    listener.succeed(
        new ProviderCompletion(
            new ProviderResponse(
                "calling tool",
                null,
                List.of(new ProviderToolCall("call_1", "bash", "{\"command\":\"ls\"}")),
                GenerationStopReason.COMPLETE,
                usage(),
                "req-1",
                null,
                null),
            sampleReplayState()));

    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertEquals(1, terminal.result().toolCalls().size());
    assertNotNull(terminal.providerReplayState());
    assertEquals(List.of("calling tool"), deltaTexts(fixture.sink));
  }

  /** 压缩子 LENGTH 即使同时带 tool call 也是被截断的结果：截断的 tool intent 不是可执行意图，必须走 INVALID_RESPONSE 而不执行工具。 */
  @Test
  void compactionChildTruncatedToolIntentIsInvalidResponseWithoutExecutingTools() {
    Fixture fixture = compactionFixture(NO_RETRY, IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("truncated call"));
    listener.succeed(
        new ProviderCompletion(
            new ProviderResponse(
                "truncated call",
                null,
                List.of(new ProviderToolCall("call_1", "bash", "{\"command\":\"ls\"}")),
                GenerationStopReason.LENGTH,
                usage(),
                "req-1",
                null,
                null),
            sampleReplayState()));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failed.error().kind());
    assertEquals(
        "compaction model response was truncated before the summary completed",
        failed.error().message());
    assertNull(failed.resultEntryId());
    Boolean assistantMaterialized =
        fixture.store.transaction(
            tx ->
                tx
                    .entries()
                    .loadPath(
                        tx.threads().find(fixture.baseline.threadId()).orElseThrow().headEntryId())
                    .entries()
                    .stream()
                    .anyMatch(entry -> entry.payload() instanceof MessagePayload));
    assertFalse(
        assistantMaterialized,
        "a truncated tool intent must never be materialized as an executable tool invocation");
  }

  /** 普通 Agent（非压缩子）的截断 / 空 final 语义保持不变：LENGTH 与空 COMPLETE 仍按普通 Runtime 提交。 */
  @Test
  void ordinaryAgentTruncatedAndEmptyFinalsKeepExistingSemantics() {
    Fixture truncated = fixture();
    truncated.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        truncated.processor.process(claim(truncated.store, truncated.invocationId)));
    truncated
        .gateway
        .controlled(truncated.invocationId)
        .succeed(
            new ProviderCompletion(
                response("partial answer", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(truncated.store, truncated.invocationId).status(),
        "ordinary LENGTH semantics must not change");

    Fixture empty = fixture();
    empty.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED, empty.processor.process(claim(empty.store, empty.invocationId)));
    empty
        .gateway
        .controlled(empty.invocationId)
        .succeed(
            new ProviderCompletion(
                new ProviderResponse(
                    "",
                    null,
                    List.of(),
                    GenerationStopReason.COMPLETE,
                    usage(),
                    "req-1",
                    null,
                    null),
                sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(empty.store, empty.invocationId).status(),
        "ordinary empty COMPLETE semantics must not change");

    // 默认约束的普通 Thread（含 task child）不是 owner-settled 摘要任务：截断 final 不做摘要完整性校验。
    Fixture taskChild = fixture();
    taskChild.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        taskChild.processor.process(claim(taskChild.store, taskChild.invocationId)));
    taskChild
        .gateway
        .controlled(taskChild.invocationId)
        .succeed(
            new ProviderCompletion(
                response("task partial", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(taskChild.store, taskChild.invocationId).status(),
        "ordinary children never get summary-only validation from execution constraints");
  }

  /** 普通子代理即使继承 allowAutoCompaction=false，也不是摘要任务：LENGTH/FILTERED 仍按普通 Runtime 语义提交。 */
  @Test
  void inheritedNoAutoCompactionDoesNotTurnOrdinaryChildIntoSummaryAgent() {
    ThreadConstraints inherited = new ThreadConstraints(4096, false, false);
    Fixture truncated =
        new Fixture(
            NO_RETRY,
            requestSpec(),
            newScheduler(),
            inherited,
            false,
            ModelStreamConfig.DEFAULT,
            null,
            providerName -> ModelHttpErrorPolicy.DEFAULT);
    truncated.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        truncated.processor.process(claim(truncated.store, truncated.invocationId)));
    truncated
        .gateway
        .controlled(truncated.invocationId)
        .succeed(
            new ProviderCompletion(
                response("ordinary partial", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(truncated.store, truncated.invocationId).status(),
        "inherited constraints must not redefine the Thread as a summary Agent");

    Fixture filtered =
        new Fixture(
            NO_RETRY,
            requestSpec(),
            newScheduler(),
            inherited,
            false,
            ModelStreamConfig.DEFAULT,
            null,
            providerName -> ModelHttpErrorPolicy.DEFAULT);
    filtered.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        filtered.processor.process(claim(filtered.store, filtered.invocationId)));
    filtered
        .gateway
        .controlled(filtered.invocationId)
        .succeed(
            new ProviderCompletion(
                response("ordinary filtered", GenerationStopReason.FILTERED), sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(filtered.store, filtered.invocationId).status(),
        "ordinary FILTERED keeps ordinary semantics");
  }

  /**
   * 验证 ModelExecution 完成路径：普通非 compaction 的 COMPLETE 与 LENGTH 在无 toolCallDiagnostics 时持久化
   * replayState。
   */
  @Test
  void modelExecutionPersistsReplayStateOnCompleteAndLengthWithoutDiagnostics() {
    // 1. COMPLETE 场景
    Fixture fixtureComplete = fixture();
    fixtureComplete.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixtureComplete.processor.process(
            claim(fixtureComplete.store, fixtureComplete.invocationId)));
    ControlledRun listenerComplete =
        fixtureComplete.gateway.controlled(fixtureComplete.invocationId);

    ProviderReplayState replayState1 = sampleReplayState();
    listenerComplete.succeed(
        new ProviderCompletion(response("hello", GenerationStopReason.COMPLETE), replayState1));

    ModelInvocation modelComplete = model(fixtureComplete.store, fixtureComplete.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, modelComplete.status());
    assertEquals(
        replayState1,
        modelComplete.providerReplayState(),
        "COMPLETE without diagnostics must persist replayState");

    // 2. LENGTH 场景
    Fixture fixtureLength = fixture();
    fixtureLength.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixtureLength.processor.process(claim(fixtureLength.store, fixtureLength.invocationId)));
    ControlledRun listenerLength = fixtureLength.gateway.controlled(fixtureLength.invocationId);

    ProviderReplayState replayState2 = sampleReplayState();
    listenerLength.succeed(
        new ProviderCompletion(response("partial", GenerationStopReason.LENGTH), replayState2));

    ModelInvocation modelLength = model(fixtureLength.store, fixtureLength.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, modelLength.status());
    assertEquals(
        replayState2,
        modelLength.providerReplayState(),
        "LENGTH without diagnostics must persist replayState");
  }

  /** 验证 ModelExecution 完成路径：FILTERED 与含 incomplete tool diagnostics 时严格不持久化 replayState。 */
  @Test
  void modelExecutionSuppressesReplayStateOnFilteredAndIncompleteDiagnostics() {
    // 1. FILTERED 场景
    Fixture fixtureFiltered = fixture();
    fixtureFiltered.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixtureFiltered.processor.process(
            claim(fixtureFiltered.store, fixtureFiltered.invocationId)));
    ControlledRun listenerFiltered =
        fixtureFiltered.gateway.controlled(fixtureFiltered.invocationId);

    listenerFiltered.succeed(
        new ProviderCompletion(
            response("filtered output", GenerationStopReason.FILTERED), sampleReplayState()));

    ModelInvocation modelFiltered = model(fixtureFiltered.store, fixtureFiltered.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, modelFiltered.status());
    assertNull(modelFiltered.providerReplayState(), "FILTERED must not persist replayState");

    // 2. 含 incomplete tool diagnostics 场景
    Fixture fixtureDiag = fixture();
    fixtureDiag.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixtureDiag.processor.process(claim(fixtureDiag.store, fixtureDiag.invocationId)));
    ControlledRun listenerDiag = fixtureDiag.gateway.controlled(fixtureDiag.invocationId);

    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(0, "call_1", "bash", "{\"param\":", "truncated json");
    ProviderResponse responseWithDiagnostics =
        new ProviderResponse(
            "truncated",
            null,
            List.of(),
            GenerationStopReason.COMPLETE,
            usage(),
            "req-diag",
            null,
            null,
            List.of(diagnostic));

    listenerDiag.succeed(new ProviderCompletion(responseWithDiagnostics, sampleReplayState()));

    ModelInvocation modelDiag = model(fixtureDiag.store, fixtureDiag.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, modelDiag.status());
    assertNull(
        modelDiag.providerReplayState(),
        "Incomplete tool diagnostics must suppress replayState persistence");
  }

  /** 验证 ModelExecution 完成路径：失败与 UNKNOWN 路径绝对不会残留 replayState。 */
  @Test
  void modelExecutionDoesNotLeaveReplayStateOnFailureOrUnknown() {
    // 1. FAILED 终态
    Fixture fixtureFailed = fixture();
    fixtureFailed.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixtureFailed.processor.process(claim(fixtureFailed.store, fixtureFailed.invocationId)));
    ControlledRun listenerFailed = fixtureFailed.gateway.controlled(fixtureFailed.invocationId);

    listenerFailed.fail(
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "terminal failure"));

    ModelInvocation modelFailed = model(fixtureFailed.store, fixtureFailed.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, modelFailed.status());
    assertNull(modelFailed.providerReplayState(), "Failed invocation must not retain replayState");

    // 2. UNKNOWN 终态
    Fixture fixtureUnknown = fixture();
    fixtureUnknown.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixtureUnknown.processor.process(claim(fixtureUnknown.store, fixtureUnknown.invocationId)));
    ControlledRun listenerUnknown = fixtureUnknown.gateway.controlled(fixtureUnknown.invocationId);

    listenerUnknown.unknown(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "indeterminate execution"));

    ModelInvocation modelUnknown = model(fixtureUnknown.store, fixtureUnknown.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, modelUnknown.status());
    assertNull(
        modelUnknown.providerReplayState(), "UNKNOWN invocation must not retain replayState");
  }

  /**
   * 成功终态：最终 reconcile 补发布 gap、写 terminal + version+1 + THREAD wake + complete，并保留完整 safe
   * checkpoint。
   */
  @Test
  void conflictingFinalResponseFailsWithInvalidResponse() {
    Fixture fixture = fixture(IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("hello"));
    listener.succeed(response("world", GenerationStopReason.COMPLETE));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
    assertNotNull(model.streamCheckpoint());
    assertEquals(1, model.streamCheckpoint().sequence());
    assertEquals("hello", model.streamCheckpoint().text());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
    assertEquals(1, deltas(fixture.sink).size());
  }

  /**
   * 未声明（unknown）工具调用不再是 canonical 校验错误：validator 不做 binding 可见性校验，响应合法并 SUCCEEDED； unknown tool 由
   * {@link ModelResponsePlanner} 在 Thread 边界规划为 FAILED(UNKNOWN_TOOL) 槽位（见 ThreadProcessor 测试）。
   */
  @Test
  void undeclaredToolCallIsAcceptedByModelProcessor() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(
        new ProviderResponse(
            "",
            null,
            List.of(new ProviderToolCall("call_1", "undeclared", "{}")),
            GenerationStopReason.COMPLETE,
            usage(),
            "req-1",
            null,
            null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.result().toolCalls().size());
    assertEquals("undeclared", model.result().toolCalls().getFirst().name());
  }

  /** 事件管线异常（tool-call identity 冲突）：转 FAILED(INVALID_RESPONSE)，已发布 delta 保持。 */
  @Test
  void conflictingToolIdentityEventFailsWithInvalidResponse() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool(), IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
    assertEquals(1, deltas(fixture.sink).size());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
  }

  /** 无任何 streamed 内容且 final 为空：terminal 不写空 checkpoint。 */
  @Test
  void successWithoutStreamedContentKeepsNoCheckpoint() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(response("", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertNull(model.streamCheckpoint());
    assertTrue(fixture.sink.events.isEmpty());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /** thinking delta 进 checkpoint；final thinking 补发布 gap。 */
  @Test
  void thinkingDeltasAreCheckpointedAndGapProjected() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.ThinkingDelta("think"));
    listener.succeed(
        new ProviderResponse(
            "",
            "thinking",
            List.of(),
            GenerationStopReason.COMPLETE,
            usage(),
            "req-1",
            null,
            null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals("thinking", model.result().thinking());
    assertNotNull(model.streamCheckpoint());
    assertEquals("thinking", model.streamCheckpoint().thinking());
    assertEquals(2, model.streamCheckpoint().sequence());
    assertEquals(
        List.of(
            new ProviderStreamEvent.ThinkingDelta("think"),
            new ProviderStreamEvent.ThinkingDelta("ing")),
        deltas(fixture.sink));
  }

  /** final response 省略 thinking 时，durable result 补入已 streamed 的 thinking。 */
  @Test
  void streamedThinkingIsPersistedWhenFinalOmitsIt() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.ThinkingDelta("think"));
    listener.succeed(response("", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals("think", model.result().thinking());
    assertEquals("think", model.streamCheckpoint().thinking());
    assertEquals(1, model.streamCheckpoint().sequence());
  }

  /** streamed tool-call fragment 与 final 的差异（id/name/arguments gap）在 terminal 后补发布。 */
  @Test
  void toolCallFragmentGapsAreProjectedAtTerminal() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call", null, null));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, null, "bas", null));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, null, null, "{\"a\""));
    listener.succeed(toolResponse("", new ProviderToolCall("call_1", "bash", "{\"a\":1}")));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    List<ProviderStreamEvent> deltas = deltas(fixture.sink);
    assertEquals(4, deltas.size());
    assertEquals(new ProviderStreamEvent.ToolCallDelta(0, "_1", "h", ":1}"), deltas.get(3));
  }

  /** final response 遗漏 streamed 过的 tool call：reconcile 拒绝并 FAILED(INVALID_RESPONSE)。 */
  @Test
  void finalResponseOmittingStreamedToolCallFails() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{}"));
    listener.succeed(response("", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
  }

  /** 重复 tool call id：canonical 校验拒绝并 FAILED(INVALID_RESPONSE)。 */
  @Test
  void duplicateToolCallIdsFail() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(
        new ProviderResponse(
            "",
            null,
            List.of(
                new ProviderToolCall("call_1", "bash", "{}"),
                new ProviderToolCall("call_1", "bash", "{}")),
            GenerationStopReason.COMPLETE,
            usage(),
            "req-1",
            null,
            null));

    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        model(fixture.store, fixture.invocationId).error().kind());
  }

  /** 非法 arguments JSON：canonical 校验拒绝并 FAILED(INVALID_RESPONSE)。 */
  @Test
  void malformedToolArgumentsFail() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.succeed(toolResponse("", new ProviderToolCall("call_1", "bash", "not-json")));

    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        model(fixture.store, fixture.invocationId).error().kind());
  }

  /**
   * INVALID_RESPONSE 复用既有 {@link InvocationRetryPolicy}：第一次 canonical 校验失败进入 retry（RUNNING -&gt;
   * READY，failedAttempts 追加 INVALID_RESPONSE），重试耗尽后第二次失败转 FAILED terminal。
   */
  @Test
  void invalidResponseRetriesWithTheSharedPolicyThenFailsAfterExhaustion() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            requestSpec());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    // 第一次 canonical 校验失败：INVALID_RESPONSE 走 retry 路径（不是 FAILED）。
    listener.event(new ProviderStreamEvent.TextDelta("hello"));
    listener.succeed(response("world", GenerationStopReason.COMPLETE));

    ModelInvocation retried = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(1, retried.attempt());
    assertEquals(1, retried.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, retried.failedAttempts().getFirst().error().kind());
    assertEquals(
        NOW.plusSeconds(5),
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId))
            .availableAt());

    // 重试耗尽：第二次 INVALID_RESPONSE 转 FAILED。
    fixture.gateway.queueControlled();
    fixture.clock.set(NOW.plusSeconds(5));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun retriedListener = fixture.gateway.controlled(fixture.invocationId);
    retriedListener.event(new ProviderStreamEvent.TextDelta("hello"));
    retriedListener.succeed(response("world", GenerationStopReason.COMPLETE));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failed.error().kind());
  }

  /**
   * 同一 JVM 内旧 lease 过期后新 claim（不同 token）：supersede 本地旧 execution，按既有重试策略恢复 durable RUNNING（保留已确认
   * attempt / 取消旧 handle / 绝不重放 Provider）；无重试预算时如实终止 FAILED。
   *
   * <p>{@code process} 返回 STARTED 只表示已把运行交给执行线程，不保证 body 已进入。必须先等待旧 body 真正进入再推进 clock / reclaim；否则
   * supersede 可能发生在 runner 登记之前，退出观察没有可 join 的旧线程。
   */

  /**
   * 意图：方案 B 的推送与落库解耦——safe delta 立即作为 realtime overlay 推送，但 checkpoint 不会逐 delta 落库；只有追帧请求
   * 显式触发时才持久化完整 safe 前缀，且 CHECKPOINT 事件严格晚于提交（此前零 checkpoint 事件）。
   */
  @Test
  void safeDeltasArePushedBeforeCheckpointAndCatchUpPublishesOnlyAfterCommit() {
    // 长 checkpoint 间隔保证断言期间只有显式追帧才会落库；推送仍逐事件立即发生。
    ModelStreamConfig streamConfig =
        new ModelStreamConfig(Duration.ofMillis(1), 1, 1, Duration.ofMinutes(10));
    Fixture fixture = fixture(NO_RETRY, requestWithTool(), streamConfig);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    long runningVersion = thread(fixture.store, fixture.baseline.threadId()).version();
    assertNull(model(fixture.store, fixture.invocationId).streamCheckpoint());
    assertTrue(checkpoints(fixture.sink).isEmpty());

    listener.event(new ProviderStreamEvent.TextDelta("a"));
    assertEquals(1, deltas(fixture.sink).size());
    assertNull(model(fixture.store, fixture.invocationId).streamCheckpoint());
    assertTrue(checkpoints(fixture.sink).isEmpty());

    listener.event(new ProviderStreamEvent.TextDelta("b"));
    assertEquals(2, deltas(fixture.sink).size());
    assertNull(
        model(fixture.store, fixture.invocationId).streamCheckpoint(),
        "deltas must not persist a checkpoint on the push path");

    assertTrue(fixture.processor.requestCheckpoint(fixture.invocationId, 1));
    awaitTrue(() -> !checkpoints(fixture.sink).isEmpty());
    ModelInvocation checkpointed = model(fixture.store, fixture.invocationId);
    assertEquals(2, checkpointed.streamCheckpoint().sequence());
    assertEquals("ab", checkpointed.streamCheckpoint().text());
    assertEquals(List.of(2L), checkpoints(fixture.sink));
    assertEquals(runningVersion, thread(fixture.store, fixture.baseline.threadId()).version());

    listener.event(new ProviderStreamEvent.TextDelta("c"));
    assertEquals(3, deltas(fixture.sink).size());
    assertEquals(
        2,
        model(fixture.store, fixture.invocationId).streamCheckpoint().sequence(),
        "later deltas must wait for the next checkpoint deadline or catch-up");

    // tool fragment 永不 checkpoint，但会作为 overlay 推送并推进 sequence。
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    assertEquals(4, deltas(fixture.sink).size());
    assertEquals(2, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());

    // terminal success 总是 flush 完整 safe snapshot（tool 的 name/args 差异聚合成 gap delta）。
    listener.succeed(toolResponse("abc", new ProviderToolCall("call_1", "bash", "{}")));
    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertNotNull(terminal.streamCheckpoint());
    assertEquals("abc", terminal.streamCheckpoint().text());
    assertEquals(3, terminal.streamCheckpoint().sequence());
    assertEquals(
        List.of(2L), checkpoints(fixture.sink), "terminal does not add a checkpoint event");
    assertFalse(fixture.processor.requestCheckpoint(fixture.invocationId, 1));
  }
}
