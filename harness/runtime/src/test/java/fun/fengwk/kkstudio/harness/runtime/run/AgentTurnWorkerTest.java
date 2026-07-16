package fun.fengwk.kkstudio.harness.runtime.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.agent.extension.ProviderRequestInterceptorChain;
import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.AssistantCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.CompactionCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.RunTerminated;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.TurnStarted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class AgentTurnWorkerTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  /** 真实 Worker 必须把 Host Provider chain 交给 Turn Engine，并在 TURN_STARTED 落库后观察同一时间。 */
  @Test
  void invokesProviderHookAndPublishesDurableTurnStart() {
    Fixture fixture = new Fixture();
    RecordingProvider provider = RecordingProvider.complete(response("answer", List.of()));
    fixture.providers.add(provider);
    boolean[] intercepted = {false};
    fixture.providerRequestInterceptors =
        new ProviderRequestInterceptorChain(
            List.of(
                request -> {
                  intercepted[0] = true;
                  return new ProviderRequest(
                      request.model(),
                      new ModelVariant("hooked", null, null, null, null, List.of()),
                      request.messages(),
                      request.tools(),
                      request.cacheControl());
                }));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertTrue(intercepted[0]);
    assertEquals("hooked", provider.request.variant().name());
    TurnStarted observation = onlyObservation(fixture, TurnStarted.class);
    RunEvent event =
        fixture.store.events.stream()
            .filter(candidate -> candidate.type() == RunEventType.TURN_STARTED)
            .findFirst()
            .orElseThrow();
    assertEquals(event.createdAt(), observation.occurredAt());
    assertEquals(20L, observation.runId());
    assertEquals(1L, observation.sessionId());
    assertEquals(1, observation.attempt());
    assertEquals(0, observation.turnIndex());
  }

  /** 自然 SUCCEEDED 路径：Assistant + Run 按序成对发布，复用 complete CAS now 与真实 stopReason。 */
  @Test
  void publishesAssistantCompletionOnlyAfterCompleteSucceeds() {
    Fixture succeeded = new Fixture();
    succeeded.providers.add(RecordingProvider.complete(response("answer", List.of())));

    succeeded.worker().executeNext("worker-a").orElseThrow();

    AssistantCompleted completed = onlyObservation(succeeded, AssistantCompleted.class);
    assertEquals(0, completed.toolCallCount());
    assertEquals(ProviderStopReason.COMPLETED, completed.stopReason());
    assertAssistantThenTerminal(succeeded, succeeded.store.completeAt);

    Fixture lostOwnership = new Fixture();
    lostOwnership.store.completeResult = false;
    lostOwnership.providers.add(RecordingProvider.complete(response("ignored", List.of())));

    lostOwnership.worker().executeNext("worker-b").orElseThrow();

    assertTrue(observations(lostOwnership, AssistantCompleted.class).isEmpty());
    assertTrue(observations(lostOwnership, RunTerminated.class).isEmpty());
    assertEquals(RunStatus.RUNNING, lostOwnership.store.current.status());
  }

  /** complete cancel-wins：DB 返回 true 但不持久 Assistant，真实 status 是 CANCELLED；只发 RunTerminated。 */
  @Test
  void publishesRunTerminalOnlyWhenCompleteLosesToCancelWins() {
    Fixture fixture = new Fixture();
    fixture.store.cancelOnCompleteAt = START.plusSeconds(1);
    fixture.providers.add(RecordingProvider.complete(response("ignored", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertCancelWinsRunTermination(fixture, fixture.store.completeAt);
  }

  /** control requeue QUEUED：Assistant 持久化但 Run 仍 active；只发 Assistant，不发 RunTerminated。 */
  @Test
  void publishesAssistantOnlyWhenCompleteRequeuesForControl() {
    Fixture fixture = new Fixture();
    fixture.store.pendingSteer = true;
    fixture.providers.add(RecordingProvider.complete(response("answer", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.QUEUED, fixture.store.current.status());
    assertEquals(1, fixture.store.sessionMessages.size());
    assertAssistantOnlyAfterControlRequeue(fixture, fixture.store.completeAt);
  }

  /** Tool barrier observation 同样只在 prepare CAS 成功后发布，并携带真实 Tool stopReason/count。 */
  @Test
  void publishesAssistantCompletionOnlyAfterToolPreparationSucceeds() {
    Fixture succeeded = toolFixture();

    succeeded.worker().executeNext("worker-a").orElseThrow();

    AssistantCompleted completed = onlyObservation(succeeded, AssistantCompleted.class);
    assertEquals(1, completed.toolCallCount());
    assertEquals(ProviderStopReason.TOOL_CALLS, completed.stopReason());
    assertAssistantOnlyAfterPreparation(succeeded, succeeded.store.prepareAt);

    Fixture lostOwnership = toolFixture();
    lostOwnership.toolPreparation =
        (run, assistant, calls, bindings, workdir, environmentRoot, events, now) -> false;

    lostOwnership.worker().executeNext("worker-b").orElseThrow();

    assertTrue(observations(lostOwnership, AssistantCompleted.class).isEmpty());
    assertEquals(RunStatus.RUNNING, lostOwnership.store.current.status());
  }

  /** prepare cancel-wins：DB 返回 true 但不持久 Assistant、不进入 WAITING_TOOLS；只发 RunTerminated。 */
  @Test
  void publishesRunTerminalOnlyWhenPrepareLosesToCancelWins() {
    Fixture fixture = toolFixture();
    fixture.store.cancelOnPrepareAt = START.plusSeconds(1);

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertCancelWinsRunTermination(fixture, fixture.store.prepareAt);
  }

  /** Compaction observation 只在 compactAndRequeue 成功后发布并复用事务时间。 */
  @Test
  void publishesCompactionOnlyAfterDurableRequeue() {
    Fixture succeeded = overflowFixture();

    succeeded.worker().executeNext("worker-a").orElseThrow();

    CompactionCompleted completed = onlyObservation(succeeded, CompactionCompleted.class);
    assertEquals(10L, completed.firstKeptEntryId());
    assertEquals(succeeded.store.compactAt, completed.occurredAt());

    Fixture lostOwnership = overflowFixture();
    lostOwnership.store.compactResult = false;

    lostOwnership.worker().executeNext("worker-b").orElseThrow();

    assertTrue(observations(lostOwnership, CompactionCompleted.class).isEmpty());
    assertEquals(RunStatus.RUNNING, lostOwnership.store.current.status());
  }

  /** terminate=false 不能伪造 Run terminal observation。 */
  @Test
  void doesNotPublishRunTerminationWhenTerminalCasIsLost() {
    Fixture fixture = new Fixture();
    fixture.store.terminateResult = false;
    fixture.providers.add(
        RecordingProvider.fail(
            new ProviderException(ProviderErrorKind.AUTHENTICATION, "invalid credential")));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
    assertEquals(RunStatus.RUNNING, fixture.store.current.status());
  }

  /** terminal 已落库后 reload 缺失或异常只跳过 observation，不能破坏 durable FAILED。 */
  @Test
  void keepsDurableTerminationWhenReloadCannotObserveIt() {
    Fixture missing = terminalFailureFixture();
    missing.store.findMissing = true;

    missing.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, missing.store.current.status());
    assertTrue(observations(missing, RunTerminated.class).isEmpty());

    Fixture failedReload = terminalFailureFixture();
    failedReload.store.findFailure = new IllegalStateException("database read unavailable");

    failedReload.worker().executeNext("worker-b").orElseThrow();

    assertEquals(RunStatus.FAILED, failedReload.store.current.status());
    assertTrue(observations(failedReload, RunTerminated.class).isEmpty());
  }

  /** complete CAS 成功后 reload 缺失只跳过 observation，已持久 SUCCEEDED 不能回滚。 */
  @Test
  void keepsDurableCompletionWhenReloadReturnsMissing() {
    Fixture fixture = new Fixture();
    fixture.store.findMissing = true;
    fixture.providers.add(RecordingProvider.complete(response("answer", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
  }

  /** complete CAS 成功后 reload 抛错只跳过 observation，已持久 SUCCEEDED 不能回滚。 */
  @Test
  void keepsDurableCompletionWhenReloadThrows() {
    Fixture fixture = new Fixture();
    fixture.store.findFailure = new IllegalStateException("database read unavailable");
    fixture.providers.add(RecordingProvider.complete(response("answer", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
  }

  /** prepare CAS 成功后 reload 抛错只跳过 observation，已持久 WAITING_TOOLS 不能回滚。 */
  @Test
  void keepsDurablePreparationWhenReloadThrows() {
    Fixture fixture = toolFixture();
    fixture.store.findFailure = new IllegalStateException("database read unavailable");

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.WAITING_TOOLS, fixture.store.current.status());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
  }

  /** compactAndRequeue cancel-wins：真实 status 变 CANCELLED，observation 只发 RunTerminated。 */
  @Test
  void publishesRunTerminalOnlyWhenCompactionLosesToCancelWins() {
    Fixture fixture = overflowFixture();
    fixture.store.cancelOnCompactAt = START.plusSeconds(1);

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertTrue(fixture.store.compactions.isEmpty());
    assertTrue(observations(fixture, CompactionCompleted.class).isEmpty());
    RunTerminated terminated = onlyObservation(fixture, RunTerminated.class);
    assertEquals(RunStatus.CANCELLED, terminated.status());
    assertEquals(fixture.store.compactAt, terminated.occurredAt());
  }

  /** Assistant 文本为空且没有 tool call 时 Assistant entry 仍持久化一个空 TextMessageContent。 */
  @Test
  void persistsEmptyAssistantTextWithoutToolCalls() {
    Fixture fixture = new Fixture();
    fixture.providers.add(RecordingProvider.complete(response("", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(1, fixture.store.sessionMessages.size());
    MessageEntryPayload assistant = fixture.store.sessionMessages.get(0);
    List<AgentMessageContent> contents = assistant.message().contents();
    assertEquals(1, contents.size());
    assertTrue(contents.get(0) instanceof TextMessageContent);
    assertEquals("", ((TextMessageContent) contents.get(0)).text());
    assertAssistantThenTerminal(fixture, fixture.store.completeAt);
  }

  /** Assistant thinking 文本持久化为 ThinkingMessageContent，与 Text/ToolCall 并存。 */
  @Test
  void persistsAssistantThinkingAlongsideText() {
    Fixture fixture = new Fixture();
    fixture.providers.add(RecordingProvider.complete(thinkingResponse("answer", "reasoning")));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    MessageEntryPayload assistant = fixture.store.sessionMessages.get(0);
    List<AgentMessageContent> contents = assistant.message().contents();
    assertEquals(2, contents.size());
    assertTrue(contents.get(0) instanceof TextMessageContent);
    assertEquals("answer", ((TextMessageContent) contents.get(0)).text());
    assertTrue(contents.get(1) instanceof ThinkingMessageContent);
    assertEquals("reasoning", ((ThinkingMessageContent) contents.get(1)).text());
    assertAssistantThenTerminal(fixture, fixture.store.completeAt);
  }

  /** observer RuntimeException 由生产 dispatcher 隔离，不能改变已经成功的 transaction。 */
  @Test
  void isolatesObserverFailureFromSuccessfulTransaction() {
    Fixture fixture = new Fixture();
    fixture.providers.add(RecordingProvider.complete(response("answer", List.of())));
    fixture.lifecycleObservers =
        new HarnessLifecycleObservers(
            List.of(
                observation -> {
                  throw new IllegalStateException("observer failed");
                },
                fixture.observations::add));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(1, observations(fixture, AssistantCompleted.class).size());
  }

  /** 无工具 Turn 先落完整 Assistant，再 terminal 并清理 active run；Context 已投影给 Provider。 */
  @Test
  void completesNoToolTurnAndPersistsAssistantBeforeTerminal() {
    Fixture fixture = new Fixture();
    RecordingProvider provider = RecordingProvider.complete(response("answer", List.of()));
    fixture.providers.add(provider);

    AgentTurnWorker.ClaimedTurn turn = fixture.worker().executeNext("worker-a").orElseThrow();

    assertNotNull(turn.handle());
    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(List.of("assistant", "terminal:SUCCEEDED"), fixture.store.operations);
    assertEquals(1, fixture.store.sessionMessages.size());
    MessageEntryPayload assistant = fixture.store.sessionMessages.get(0);
    assertEquals("answer", text(assistant));
    assertEquals(ProviderStopReason.COMPLETED, assistant.assistantMetadata().stopReason());
    assertEquals(new ModelUsage(1, 1, 0, 0, 0, 0, 2), assistant.assistantMetadata().usage());
    assertEquals(zeroCost("USD"), assistant.assistantMetadata().cost());
    assertEquals(2, provider.request.messages().size());
    assertEquals(
        List.of(
            RunEventType.RUN_STARTED,
            RunEventType.TURN_STARTED,
            RunEventType.ASSISTANT_STARTED,
            RunEventType.ASSISTANT_DELTA_BATCH,
            RunEventType.ASSISTANT_COMPLETED,
            RunEventType.RUN_COMPLETED),
        fixture.store.events.stream().map(RunEvent::type).toList());
    assertTrue(
        fixture.store.events.stream()
            .allMatch(
                event ->
                    event.payloadJson().contains("\"attempt\":1")
                        && event.payloadJson().contains("\"turnIndex\":0")));
    RunEvent completed =
        fixture.store.events.stream()
            .filter(event -> event.type() == RunEventType.ASSISTANT_COMPLETED)
            .findFirst()
            .orElseThrow();
    assertTrue(completed.payloadJson().contains("\"stopReason\":\"COMPLETED\""));
    assertTrue(completed.payloadJson().contains("\"inputTokens\":1"));
    assertTrue(completed.payloadJson().contains("\"currency\":\"USD\""));
  }

  /** Tool barrier 观察到 Assistant 已持久化后才准备，并只进入 WAITING_TOOLS，不伪造 Invocation。 */
  @Test
  void persistsAssistantBeforeToolBarrier() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.complete(
            response(
                "", List.of(new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}")))));
    fixture.tools = List.of(tool());
    List<String> barrier = new ArrayList<>();
    fixture.toolPreparation =
        (run, assistant, calls, bindings, workdir, environmentRoot, assistantEvents, now) -> {
          fixture.store.sessionMessages.add(assistant);
          barrier.add("assistant");
          barrier.add("prepare:" + calls.get(0).id());
          List<RunEventDraft> events = new ArrayList<>(assistantEvents);
          events.add(
              new RunEventDraft(
                  RunEventType.TOOL_PREPARED,
                  RunEventPayloads.forAttempt(run, "toolCallCount", calls.size())));
          events.add(
              new RunEventDraft(
                  RunEventType.RUN_WAITING,
                  RunEventPayloads.forAttempt(run, "status", "WAITING_TOOLS")));
          fixture.store.appendDrafts(run.id(), events, now);
          fixture.store.current = fixture.store.waiting(run, now);
          return true;
        };

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(List.of("assistant", "prepare:call-1"), barrier);
    assertEquals(RunStatus.WAITING_TOOLS, fixture.store.current.status());
    assertEquals(1, fixture.store.current.turnIndex());
    assertEquals(
        1,
        fixture.store.events.stream()
            .filter(event -> event.type() == RunEventType.ASSISTANT_COMPLETED)
            .count());
    assertEquals(
        1,
        fixture.store.events.stream()
            .filter(event -> event.type() == RunEventType.TOOL_PREPARED)
            .count());
    assertEquals(
        1,
        fixture.store.events.stream()
            .filter(event -> event.type() == RunEventType.RUN_WAITING)
            .count());
  }

  /** beforeTool/schema/permission preparation 失败必须终结 Run，不能遗留到 lease reclaim。 */
  @Test
  void failsRunWhenToolPreparationThrows() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.complete(
            response(
                "", List.of(new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}")))));
    fixture.tools = List.of(tool());
    fixture.toolPreparation =
        (run, assistant, calls, bindings, workdir, environmentRoot, assistantEvents, now) -> {
          throw new IllegalArgumentException("interceptor rejected call");
        };

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertEquals(
        List.of(RunEventType.ASSISTANT_FAILED, RunEventType.RUN_FAILED),
        fixture.store.events.stream()
            .map(RunEvent::type)
            .filter(
                type -> type == RunEventType.ASSISTANT_FAILED || type == RunEventType.RUN_FAILED)
            .toList());
    assertTrue(
        fixture.store.events.stream()
            .filter(event -> event.type() == RunEventType.RUN_FAILED)
            .anyMatch(event -> event.payloadJson().contains("tool_preparation_failed")));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** 非领域型 preparation 故障保留 RUNNING lease，由数据库 reclaim 重试最后完整 Entry。 */
  @Test
  void leavesInfrastructurePreparationFailureForLeaseReclaim() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.complete(
            response(
                "", List.of(new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}")))));
    fixture.tools = List.of(tool());
    fixture.toolPreparation =
        (run, assistant, calls, bindings, workdir, environmentRoot, assistantEvents, now) -> {
          throw new IllegalStateException("database unavailable");
        };

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.RUNNING, fixture.store.current.status());
    assertFalse(
        fixture.store.events.stream().anyMatch(event -> event.type() == RunEventType.RUN_FAILED));
  }

  /** transient 错误只写 RunEvent，持久 backoff 后重试，失败 partial 不进入下一次 Context。 */
  @Test
  void retriesTransientFailureWithoutPollutingContext() {
    Fixture fixture = new Fixture();
    RecordingProvider failed =
        RecordingProvider.failAfterDelta(
            new ProviderStreamEvent.TextDelta("partial"),
            new ProviderException(ProviderErrorKind.TRANSIENT, "network"));
    RecordingProvider succeeded = RecordingProvider.complete(response("recovered", List.of()));
    fixture.providers.add(failed);
    fixture.providers.add(succeeded);

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.QUEUED, fixture.store.current.status());
    assertEquals(START.plusSeconds(1), fixture.store.current.nextAttemptAt());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertTrue(fixture.worker().executeNext("too-early").isEmpty());

    fixture.clock.advance(Duration.ofSeconds(1));
    fixture.worker().executeNext("worker-b").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(2, fixture.store.current.attempt());
    assertEquals(2, succeeded.request.messages().size());
    assertFalse(succeeded.request.toString().contains("partial"));
  }

  /** overflow 追加新的 Compaction Entry 后立即 requeue，旧语义 Entry 不变。 */
  @Test
  void compactsOverflowAndRequeues() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));
    fixture.compaction =
        (sessionId, context) -> Optional.of(new CompactionEntryPayload("summary", 10L, 100, "{}"));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.QUEUED, fixture.store.current.status());
    assertEquals(1, fixture.store.compactions.size());
    assertEquals("summary", fixture.store.compactions.get(0).summary());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertTrue(
        fixture.store.events.stream()
            .map(RunEvent::type)
            .toList()
            .contains(RunEventType.COMPACTION_COMPLETED));
  }

  /** 已无可压缩上下文时明确 FAILED，避免 overflow 无限 requeue。 */
  @Test
  void failsOverflowWhenNothingCanBeCompacted() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(
                event ->
                    event.type() == RunEventType.RUN_FAILED
                        && event.payloadJson().contains("no_compactable_context")));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** BeforeCompaction hook 异常必须通过持久 terminate 路径确定性终结 Run。 */
  @Test
  void failsRunWhenCompactionHookThrows() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));
    fixture.compaction =
        new InterceptingCompactionService(
            (sessionId, context) ->
                Optional.of(new CompactionEntryPayload("unused", 10L, 100, "{}")),
            List.of(
                context -> {
                  throw new IllegalStateException("hook failed");
                }));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertEquals(1, fixture.store.terminateCalls);
    assertEquals(1, terminalOperationCount(fixture));
    assertTrue(fixture.store.compactions.isEmpty());
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(
                event ->
                    event.type() == RunEventType.RUN_FAILED
                        && event.payloadJson().contains("compaction_failed")
                        && event.payloadJson().contains("hook failed")));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** Compaction delegate 异常与 hook 异常使用同一 FAILED 收敛路径，不能遗留 RUNNING lease。 */
  @Test
  void failsRunWhenCompactionDelegateThrows() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));
    fixture.compaction =
        new InterceptingCompactionService(
            (sessionId, context) -> {
              throw new IllegalArgumentException("delegate failed");
            },
            List.of(context -> context.context()));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertEquals(1, fixture.store.terminateCalls);
    assertEquals(1, terminalOperationCount(fixture));
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(
                event ->
                    event.type() == RunEventType.RUN_FAILED
                        && event.payloadJson().contains("compaction_failed")
                        && event.payloadJson().contains("delegate failed")));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** Compaction 失败与持久 cancel 竞争时由事务保持 cancel-wins，且只能写一个 terminal。 */
  @Test
  void keepsCancelWinsWhenCompactionFailureRacesWithCancel() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));
    fixture.compaction =
        new InterceptingCompactionService(
            (sessionId, context) -> Optional.empty(),
            List.of(
                context -> {
                  fixture.store.requestCancel(20L, START.plusSeconds(1));
                  throw new IllegalStateException("hook failed after cancel");
                }));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertEquals(1, fixture.store.terminateCalls);
    assertEquals(1, terminalOperationCount(fixture));
    assertEquals(
        1,
        fixture.store.events.stream()
            .filter(event -> event.type() == RunEventType.RUN_CANCELLED)
            .count());
    assertFalse(
        fixture.store.events.stream().anyMatch(event -> event.type() == RunEventType.RUN_FAILED));
    assertRunTermination(fixture, RunStatus.CANCELLED);
  }

  /** CompactionService 持续返回结果时也受持久 attempt 上限约束，达到上限直接 FAILED。 */
  @Test
  void stopsRepeatedOverflowCompactionAtAttemptLimit() {
    Fixture fixture = new Fixture();
    fixture.workerConfig =
        new RunWorkerConfig(
            Duration.ofSeconds(30), Duration.ofMillis(150), 8 * 1024, 3, Duration.ofSeconds(1));
    for (int i = 0; i < 3; i++) {
      fixture.providers.add(
          RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));
    }
    fixture.compaction =
        (sessionId, context) -> Optional.of(new CompactionEntryPayload("summary", 10L, 100, "{}"));

    fixture.worker().executeNext("worker-1").orElseThrow();
    fixture.worker().executeNext("worker-2").orElseThrow();
    fixture.worker().executeNext("worker-3").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertEquals(3, fixture.store.current.attempt());
    assertEquals(2, fixture.store.compactions.size());
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(
                event ->
                    event.type() == RunEventType.RUN_FAILED
                        && event.payloadJson().contains("compaction_attempts_exhausted")));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** Worker 未 finalize 时 lease 到期可由另一 worker reclaim，并从同一完整 Context 重新 Turn。 */
  @Test
  void reclaimsCrashedTurnFromLastCompleteEntry() {
    Fixture fixture = new Fixture();
    RecordingProvider crashed = RecordingProvider.manual();
    RecordingProvider recovered = RecordingProvider.complete(response("ok", List.of()));
    fixture.providers.add(crashed);
    fixture.providers.add(recovered);

    AgentTurnWorker.ClaimedTurn first = fixture.worker().executeNext("worker-a").orElseThrow();
    assertTrue(fixture.worker().heartbeat(first));
    assertEquals(RunStatus.RUNNING, fixture.store.current.status());

    fixture.clock.advance(Duration.ofSeconds(31));
    fixture.worker().executeNext("worker-b").orElseThrow();

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(2, fixture.store.current.attempt());
    assertEquals(2, recovered.request.messages().size());
  }

  /** reclaim 后旧 stream 晚到 Delta 仍标记旧 attempt，且不能追加 stale terminal event 或覆盖新终态。 */
  @Test
  void scopesLateAttemptDeltaWithoutOverwritingCurrentRun() {
    Fixture fixture = new Fixture();
    RecordingProvider stale = RecordingProvider.manual();
    fixture.providers.add(stale);
    fixture.providers.add(RecordingProvider.complete(response("current", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();
    fixture.clock.advance(Duration.ofSeconds(31));
    fixture.worker().executeNext("worker-b").orElseThrow();
    stale.deliverEvent(new ProviderStreamEvent.TextDelta("late"));
    stale.deliverFail(new ProviderException(ProviderErrorKind.TRANSIENT, "late failure"));

    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(1, fixture.store.sessionMessages.size());
    RunEvent lateDelta = fixture.store.events.get(fixture.store.events.size() - 1);
    assertEquals(RunEventType.ASSISTANT_DELTA_BATCH, lateDelta.type());
    assertTrue(lateDelta.payloadJson().contains("\"attempt\":1"));
    assertTrue(lateDelta.payloadJson().contains("\"turnIndex\":0"));
    assertTrue(lateDelta.payloadJson().contains("late"));
    assertFalse(
        fixture.store.events.stream()
            .anyMatch(event -> event.type() == RunEventType.ASSISTANT_FAILED));
  }

  /** 后续 Turn 即使 attempt 重置为 1 也只发 TURN_STARTED，不重复整个 Run 的 RUN_STARTED。 */
  @Test
  void doesNotRepeatRunStartedWhenLaterTurnAttemptResets() {
    Fixture fixture = new Fixture();
    fixture.store.current =
        fixture.store.copy(RunStatus.QUEUED, 1, 0, 0, null, null, START, null, START, null, START);
    fixture.providers.add(RecordingProvider.complete(response("next turn", List.of())));

    fixture.worker().executeNext("worker-next-turn").orElseThrow();

    assertEquals(1, fixture.store.current.attempt());
    assertEquals(2, fixture.store.current.turnIndex());
    assertFalse(
        fixture.store.events.stream()
            .map(RunEvent::type)
            .toList()
            .contains(RunEventType.RUN_STARTED));
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(
                event ->
                    event.type() == RunEventType.TURN_STARTED
                        && event.payloadJson().contains("\"turnIndex\":1")));
  }

  /** error/cancel 终态都先 flush Delta；auth 不重试，cancel 进入 CANCELLED。 */
  @Test
  void flushesDeltaOnPermanentErrorAndCancel() {
    for (ProviderErrorKind kind :
        List.of(ProviderErrorKind.AUTHENTICATION, ProviderErrorKind.CANCELLED)) {
      Fixture fixture = new Fixture();
      fixture.providers.add(
          RecordingProvider.failAfterDelta(
              new ProviderStreamEvent.TextDelta("partial"),
              new ProviderException(kind, kind.name())));

      fixture.worker().executeNext("worker-a").orElseThrow();

      assertTrue(
          fixture.store.events.stream()
              .map(RunEvent::type)
              .toList()
              .contains(RunEventType.ASSISTANT_DELTA_BATCH));
      assertEquals(
          kind == ProviderErrorKind.CANCELLED ? RunStatus.CANCELLED : RunStatus.FAILED,
          fixture.store.current.status());
      assertTrue(fixture.store.sessionMessages.isEmpty());
      assertRunTermination(
          fixture, kind == ProviderErrorKind.CANCELLED ? RunStatus.CANCELLED : RunStatus.FAILED);
    }
  }

  /** completion/error 并发竞争只允许一个 barrier/terminal side effect。 */
  @Test
  void finalizesCompletionRaceExactlyOnce() throws InterruptedException {
    Fixture fixture = new Fixture();
    RecordingProvider provider = RecordingProvider.manual();
    fixture.providers.add(provider);
    fixture.worker().executeNext("worker-a").orElseThrow();
    CountDownLatch start = new CountDownLatch(1);
    Thread complete =
        new Thread(
            () -> {
              await(start);
              provider.deliverComplete(response("ok", List.of()));
            });
    Thread fail =
        new Thread(
            () -> {
              await(start);
              provider.deliverFail(new ProviderException(ProviderErrorKind.INVALID_REQUEST, "bad"));
            });
    complete.start();
    fail.start();
    start.countDown();
    complete.join();
    fail.join();

    long terminalOperations =
        fixture.store.operations.stream().filter(value -> value.startsWith("terminal:")).count();
    assertEquals(1, terminalOperations);
    assertTrue(fixture.store.current.status().terminal());
  }

  /** 已持久化 cancelRequestedAt 的 Run claim 后不启动 Provider，直接清理为 CANCELLED。 */
  @Test
  void cancelsClaimedRunBeforeStartingProvider() {
    Fixture fixture = new Fixture();
    fixture.store.requestCancel(20L, START);

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertTrue(fixture.providers.isEmpty());
    assertTrue(
        fixture.store.events.stream()
            .map(RunEvent::type)
            .toList()
            .contains(RunEventType.RUN_CANCELLED));
    assertRunTermination(fixture, RunStatus.CANCELLED);
  }

  /** Context/资源解析失败属于不可重试 setup failure，且无 Provider stream 或 Session partial。 */
  @Test
  void failsWhenTurnResourcesCannotBeResolved() {
    Fixture fixture = new Fixture();
    fixture.resourceFailure = new IllegalArgumentException("unknown model");

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(event -> event.payloadJson().contains("turn_setup_failed")));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** transient 达到 attempt 上限后直接 FAILED，不再生成 retry_scheduled。 */
  @Test
  void stopsRetryingAtConfiguredAttemptLimit() {
    Fixture fixture = new Fixture();
    fixture.workerConfig =
        new RunWorkerConfig(
            Duration.ofSeconds(30), Duration.ofMillis(150), 8 * 1024, 1, Duration.ofSeconds(1));
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "network")));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.FAILED, fixture.store.current.status());
    assertFalse(
        fixture.store.events.stream()
            .map(RunEvent::type)
            .toList()
            .contains(RunEventType.RETRY_SCHEDULED));
    assertRunTermination(fixture, RunStatus.FAILED);
  }

  /** consumeSteering=false 时不启动 Provider。 */
  @Test
  void doesNotStartProviderWhenConsumeSteeringReturnsFalse() {
    Fixture fixture = new Fixture();
    fixture.store.current =
        fixture.store.copy(RunStatus.QUEUED, 0, 0, 0, null, null, START, null, null, null, START);
    fixture.store.steeringBlocked = true;
    RecordingProvider provider = RecordingProvider.complete(response("should-not-run", List.of()));
    fixture.providers.add(provider);

    AgentTurnWorker.ClaimedTurn turn = fixture.worker().executeNext("worker-a").orElseThrow();
    assertTrue(turn.handle().isCancelled());
    assertEquals(RunStatus.RUNNING, fixture.store.current.status());
    assertEquals(0, fixture.sessions.loadPathCalls);
    assertNull(provider.request);
    assertEquals(List.of("consumeSteering"), fixture.boundaryOperations);
    assertTrue(
        fixture.store.events.stream().noneMatch(e -> e.type() == RunEventType.ASSISTANT_STARTED));
  }

  /** consumeSteering 发生在 context build/provider resolve 前。 */
  @Test
  void consumeSteeringCalledBeforeContextAndProvider() {
    Fixture fixture = new Fixture();
    fixture.store.current =
        fixture.store.copy(RunStatus.QUEUED, 0, 0, 0, null, null, START, null, null, null, START);
    fixture.store.leaseOverride = "worker-a";
    fixture.providers.add(RecordingProvider.complete(response("ok", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(List.of("consumeSteering", "context", "provider"), fixture.boundaryOperations);
  }

  /** consumeSteering cancel-wins：事务内 cancelOwned → 真实 CANCELLED，发 RunTerminated，Provider 不调用。 */
  @Test
  void observesCancelWinsWhenConsumeSteeringLosesToCancel() {
    Fixture fixture = new Fixture();
    fixture.store.current =
        fixture.store.copy(RunStatus.QUEUED, 0, 0, 0, null, null, START, null, null, null, START);
    fixture.store.leaseOverride = "worker-a";
    fixture.store.cancelOnConsumeSteeringAt = START.plusSeconds(1);
    RecordingProvider provider = RecordingProvider.complete(response("ignored", List.of()));
    fixture.providers.add(provider);

    AgentTurnWorker.ClaimedTurn turn = fixture.worker().executeNext("worker-a").orElseThrow();

    assertTrue(turn.handle().isCancelled());
    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertNull(provider.request);
    assertEquals(List.of("consumeSteering"), fixture.boundaryOperations);
    assertTrue(
        fixture.store.events.stream().noneMatch(e -> e.type() == RunEventType.ASSISTANT_STARTED));
    RunTerminated terminated = onlyObservation(fixture, RunTerminated.class);
    assertEquals(RunStatus.CANCELLED, terminated.status());
    assertEquals(fixture.store.consumeSteeringAt, terminated.occurredAt());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
  }

  /** consumeSteering 返 false 但只是 ownership 丢失（lease/attempt 不匹配）：不发布，不打 WARNING。 */
  @Test
  void doesNotObserveWhenConsumeSteeringLosesOwnershipSilently() {
    Fixture fixture = new Fixture();
    fixture.store.current =
        fixture.store.copy(RunStatus.QUEUED, 0, 0, 0, null, null, START, null, null, null, START);
    fixture.store.steeringBlocked = true;
    RecordingProvider provider = RecordingProvider.complete(response("ignored", List.of()));
    fixture.providers.add(provider);

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.RUNNING, fixture.store.current.status());
    assertNull(provider.request);
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
  }

  /**
   * transient requeue cancel-wins：事务内 cancelOwned → 真实 CANCELLED，发 RunTerminated，不发
   * FAILED/Assistant。
   */
  @Test
  void observesCancelWinsWhenTransientRequeueLosesToCancel() {
    Fixture fixture = new Fixture();
    fixture.store.cancelOnRequeueAt = START.plusSeconds(1);
    fixture.providers.add(
        RecordingProvider.failAfterDelta(
            new ProviderStreamEvent.TextDelta("partial"),
            new ProviderException(ProviderErrorKind.TRANSIENT, "network")));

    fixture.worker().executeNext("worker-a").orElseThrow();

    assertEquals(RunStatus.CANCELLED, fixture.store.current.status());
    assertTrue(fixture.store.sessionMessages.isEmpty());
    assertFalse(
        fixture.store.events.stream().anyMatch(event -> event.type() == RunEventType.RUN_FAILED));
    assertFalse(
        fixture.store.events.stream()
            .anyMatch(event -> event.type() == RunEventType.ASSISTANT_COMPLETED));
    assertTrue(
        fixture.store.events.stream()
            .anyMatch(event -> event.type() == RunEventType.RUN_CANCELLED));
    RunTerminated terminated = onlyObservation(fixture, RunTerminated.class);
    assertEquals(RunStatus.CANCELLED, terminated.status());
    assertEquals(fixture.store.requeueAt, terminated.occurredAt());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
  }

  /** transient requeue 正常 QUEUED：不发任何 terminal 或 assistant observation。 */
  @Test
  void doesNotObserveWhenTransientRequeueReturnsQueued() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.failAfterDelta(
            new ProviderStreamEvent.TextDelta("partial"),
            new ProviderException(ProviderErrorKind.TRANSIENT, "network")));
    fixture.providers.add(RecordingProvider.complete(response("recovered", List.of())));

    fixture.worker().executeNext("worker-a").orElseThrow();
    assertEquals(RunStatus.QUEUED, fixture.store.current.status());
    assertEquals(START.plusSeconds(1), fixture.store.current.nextAttemptAt());
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());

    fixture.clock.advance(Duration.ofSeconds(1));
    fixture.worker().executeNext("worker-b").orElseThrow();
    assertEquals(RunStatus.SUCCEEDED, fixture.store.current.status());
    assertEquals(2, fixture.store.current.attempt());
  }

  /** heartbeat 因 cancel_requested 返回 false 时触发 handle.cancel()。 */
  @Test
  void heartbeatCancelsHandleWhenCancelRequested() {
    Fixture fixture = new Fixture();
    RecordingProvider manual = RecordingProvider.manual();
    fixture.providers.add(manual);
    AgentTurnWorker.ClaimedTurn turn = fixture.worker().executeNext("worker-a").orElseThrow();

    // Set cancel on current run
    fixture.store.requestCancel(20L, START.plusSeconds(1));

    assertFalse(fixture.worker().heartbeat(turn));
    assertTrue(turn.handle().isCancelled());
    assertTrue(manual.stream.isCancelled());
  }

  /** 无 due Run 返回 empty，空 worker id 在访问 Store 前被拒绝。 */
  @Test
  void validatesWorkerIdAndReturnsEmptyWhenNothingIsDue() {
    Fixture fixture = new Fixture();
    fixture.store.current =
        fixture.store.copy(
            RunStatus.QUEUED, 0, 0, 0, null, null, START.plusSeconds(10), null, null, null, START);

    assertTrue(fixture.worker().executeNext("worker-a").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> fixture.worker().executeNext(" "));
  }

  private static Fixture toolFixture() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.complete(
            response(
                "", List.of(new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}")))));
    fixture.tools = List.of(tool());
    return fixture;
  }

  private static Fixture overflowFixture() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(new ProviderException(ProviderErrorKind.OVERFLOW, "too long")));
    fixture.compaction =
        (sessionId, context) -> Optional.of(new CompactionEntryPayload("summary", 10L, 100, "{}"));
    return fixture;
  }

  private static Fixture terminalFailureFixture() {
    Fixture fixture = new Fixture();
    fixture.providers.add(
        RecordingProvider.fail(
            new ProviderException(ProviderErrorKind.AUTHENTICATION, "invalid credential")));
    return fixture;
  }

  private static void assertRunTermination(Fixture fixture, RunStatus status) {
    RunTerminated terminated = onlyObservation(fixture, RunTerminated.class);
    assertEquals(status, terminated.status());
    assertEquals(fixture.store.terminateAt, terminated.occurredAt());
  }

  /** RunTerminated 由 complete/prepare CAS cancel-wins 触发时使用与事务相同的 now。 */
  private static void assertCancelWinsRunTermination(Fixture fixture, Instant observedAt) {
    RunTerminated terminated = onlyObservation(fixture, RunTerminated.class);
    assertEquals(RunStatus.CANCELLED, terminated.status());
    assertEquals(observedAt, terminated.occurredAt());
    assertTrue(observations(fixture, AssistantCompleted.class).isEmpty());
  }

  /** natural SUCCEEDED 路径必须按 Assistant -> Run 顺序成对发布，observation 使用 complete CAS now。 */
  private static void assertAssistantThenTerminal(Fixture fixture, Instant observedAt) {
    AssistantCompleted assistant = onlyObservation(fixture, AssistantCompleted.class);
    List<RunTerminated> terminals = observations(fixture, RunTerminated.class);
    assertEquals(1, terminals.size());
    RunTerminated terminated = terminals.get(0);
    assertEquals(RunStatus.SUCCEEDED, terminated.status());
    assertEquals(observedAt, assistant.occurredAt());
    assertEquals(observedAt, terminated.occurredAt());
    int assistantIndex = fixture.observations.indexOf(assistant);
    int terminalIndex = fixture.observations.indexOf(terminated);
    assertTrue(
        assistantIndex >= 0 && terminalIndex > assistantIndex,
        "Assistant must be observed before terminal, but order was "
            + fixture.observations.stream().map(o -> o.getClass().getSimpleName()).toList());
  }

  /** control requeue QUEUED 路径只发 Assistant，observation 使用 complete CAS now。 */
  private static void assertAssistantOnlyAfterControlRequeue(Fixture fixture, Instant observedAt) {
    AssistantCompleted assistant = onlyObservation(fixture, AssistantCompleted.class);
    assertEquals(observedAt, assistant.occurredAt());
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
  }

  /** prepare 实际 WAITING_TOOLS 只发 Assistant，observation 使用 prepare CAS now。 */
  private static void assertAssistantOnlyAfterPreparation(Fixture fixture, Instant observedAt) {
    AssistantCompleted assistant = onlyObservation(fixture, AssistantCompleted.class);
    assertEquals(observedAt, assistant.occurredAt());
    assertTrue(observations(fixture, RunTerminated.class).isEmpty());
  }

  private static <T extends HarnessLifecycleObservation> T onlyObservation(
      Fixture fixture, Class<T> type) {
    List<T> observations = observations(fixture, type);
    assertEquals(1, observations.size());
    return observations.get(0);
  }

  private static <T extends HarnessLifecycleObservation> List<T> observations(
      Fixture fixture, Class<T> type) {
    return fixture.observations.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private static String text(MessageEntryPayload payload) {
    return ((TextMessageContent) payload.message().contents().get(0)).text();
  }

  private static long terminalOperationCount(Fixture fixture) {
    return fixture.store.operations.stream().filter(value -> value.startsWith("terminal:")).count();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private static ToolDescriptor tool() {
    return new ToolDescriptor(
        "read",
        "1",
        "read",
        null,
        new ToolParamsSchema("", Map.of(), Set.of(), true),
        ToolExecutionMode.CLOUD,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }

  private static ProviderResponse response(String text, List<ProviderToolCall> calls) {
    return new ProviderResponse(
        text,
        "",
        calls,
        calls.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        zeroCost("USD"),
        null,
        null,
        "{}");
  }

  private static ProviderResponse thinkingResponse(String text, String thinking) {
    return new ProviderResponse(
        text,
        thinking,
        List.of(),
        ProviderStopReason.COMPLETED,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        zeroCost("USD"),
        null,
        null,
        "{}");
  }

  private static ModelCost zeroCost(String currency) {
    return new ModelCost(
        currency,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static TurnResources resources(ModelProvider provider, List<ToolDescriptor> tools) {
    ModelVariant variant = new ModelVariant("default", null, null, null, null, List.of());
    ModelDescriptor model =
        new ModelDescriptor(
            1L,
            2L,
            ProviderType.OPENAI,
            "model",
            "Model",
            1024,
            256,
            Set.of(ModelInputModality.TEXT),
            Set.of(ModelCapability.TEXT, ModelCapability.TOOLS),
            List.of(variant),
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new TurnResources(
        provider,
        model,
        variant,
        tools.stream().map(ToolBinding::of).toList(),
        Path.of("/environment"),
        Path.of("/environment"));
  }

  private static final class Fixture {
    private final MutableClock clock = new MutableClock(START);
    private final List<String> boundaryOperations = new ArrayList<>();
    private final InMemoryRunState store = new InMemoryRunState(START, boundaryOperations);
    private final Queue<RecordingProvider> providers = new ArrayDeque<>();
    private final FixedSessionStore sessions = new FixedSessionStore(boundaryOperations);
    private List<ToolDescriptor> tools = List.of();
    private ToolPreparationPort toolPreparation = store::prepare;
    private CompactionService compaction = (sessionId, context) -> Optional.empty();
    private RunWorkerConfig workerConfig = RunWorkerConfig.DEFAULT;
    private RuntimeException resourceFailure;
    private ProviderRequestInterceptorChain providerRequestInterceptors =
        new ProviderRequestInterceptorChain(List.of());
    private final List<HarnessLifecycleObservation> observations = new ArrayList<>();
    private HarnessLifecycleObservers lifecycleObservers =
        new HarnessLifecycleObservers(List.of(observations::add));

    private AgentTurnWorker worker() {
      return new AgentTurnWorker(
          store,
          store,
          store,
          toolPreparation,
          new SessionContextBuilder(sessions, sessions, new DefaultContextTransform(), List.of()),
          new ProviderMessageProjector(),
          (sessionId, config) -> {
            if (resourceFailure != null) {
              throw resourceFailure;
            }
            boundaryOperations.add("provider");
            return resources(providers.remove(), tools);
          },
          compaction,
          workerConfig,
          clock,
          (delay, task) -> {},
          providerRequestInterceptors,
          lifecycleObservers);
    }
  }

  private static final class FixedSessionStore implements SessionStore, SessionEntryStore {
    private final Session session;
    private final List<SessionEntry> path;
    private final List<String> boundaryOperations;
    private int loadPathCalls;

    private FixedSessionStore(List<String> boundaryOperations) {
      this.boundaryOperations = boundaryOperations;
      AgentSnapshot snapshot =
          new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}");
      SessionEntry root =
          new SessionEntry(
              10L,
              1L,
              null,
              null,
              new AgentSnapshotEntryPayload(snapshot).type(),
              new AgentSnapshotEntryPayload(snapshot),
              START);
      MessageEntryPayload user =
          new MessageEntryPayload(
              new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("question"))));
      SessionEntry message =
          new SessionEntry(11L, 1L, 10L, 20L, user.type(), user, START.plusMillis(1));
      path = List.of(root, message);
      session =
          new Session(1L, 1L, "session", 11L, 20L, null, 1L, null, 0, false, 2L, START, START);
    }

    @Override
    public Optional<Session> find(long sessionId) {
      return sessionId == 1L ? Optional.of(session) : Optional.empty();
    }

    @Override
    public Optional<SessionEntry> find(long sessionId, long entryId) {
      return path.stream().filter(entry -> entry.id() == entryId).findFirst();
    }

    @Override
    public List<SessionEntry> loadPath(long sessionId, long leafEntryId) {
      loadPathCalls++;
      boundaryOperations.add("context");
      return path;
    }

    @Override
    public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
      return List.of();
    }

    @Override
    public void create(Session session) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void createFork(Session session, List<SessionEntry> entries) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void append(SessionEntry entry, Long expectedLeafEntryId, long expectedSessionVersion) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean compareAndSetLeaf(
        long sessionId,
        Long expectedLeafEntryId,
        long expectedSessionVersion,
        Long newLeafEntryId) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class InMemoryRunState implements RunStore, RunEventStore, RunTransactions {
    private AgentRun current;
    private long eventId = 100;
    private final List<RunEvent> events = new ArrayList<>();
    private final List<MessageEntryPayload> sessionMessages = new ArrayList<>();
    private final List<CompactionEntryPayload> compactions = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();
    private final List<String> boundaryOperations;
    private boolean steeringBlocked;
    private String leaseOverride;
    private int terminateCalls;
    private boolean completeResult = true;
    private boolean compactResult = true;
    private boolean terminateResult = true;
    private boolean findMissing;
    private RuntimeException findFailure;
    private boolean pendingSteer;
    private boolean pendingFollowUp;
    private Instant cancelOnConsumeSteeringAt;
    private Instant cancelOnRequeueAt;
    private Instant cancelOnCompleteAt;
    private Instant cancelOnPrepareAt;
    private Instant cancelOnCompactAt;
    private Instant consumeSteeringAt;
    private Instant requeueAt;
    private Instant completeAt;
    private Instant prepareAt;
    private Instant compactAt;
    private Instant terminateAt;

    private InMemoryRunState(Instant now, List<String> boundaryOperations) {
      this.boundaryOperations = boundaryOperations;
      current =
          new AgentRun(
              20L, 1L, 11L, RunStatus.QUEUED, 0, 0, 0, null, null, now, null, now, null, null, now);
    }

    @Override
    public synchronized Optional<AgentRun> find(long runId) {
      if (findFailure != null) {
        throw findFailure;
      }
      if (findMissing) {
        return Optional.empty();
      }
      return runId == current.id() ? Optional.of(current) : Optional.empty();
    }

    @Override
    public synchronized Optional<AgentRun> claimDue(
        String leaseOwner, Instant now, Duration leaseDuration) {
      boolean due = current.status() == RunStatus.QUEUED && !current.nextAttemptAt().isAfter(now);
      boolean expired = current.status() == RunStatus.RUNNING && !current.leaseUntil().isAfter(now);
      if (!due && !expired) {
        return Optional.empty();
      }
      current =
          copy(
              RunStatus.RUNNING,
              current.turnIndex(),
              current.attempt() + 1,
              current.eventSequence(),
              leaseOwner,
              now.plus(leaseDuration),
              current.nextAttemptAt(),
              current.cancelRequestedAt(),
              current.startedAt() == null ? now : current.startedAt(),
              null,
              now);
      return Optional.of(current);
    }

    @Override
    public synchronized boolean heartbeat(
        long runId, String leaseOwner, int attempt, Instant now, Duration leaseDuration) {
      if (!current.isOwnedBy(leaseOwner, attempt)
          || !current.leaseUntil().isAfter(now)
          || current.cancelRequestedAt() != null) {
        return false;
      }
      current =
          copy(
              current.status(),
              current.turnIndex(),
              current.attempt(),
              current.eventSequence(),
              leaseOwner,
              now.plus(leaseDuration),
              current.nextAttemptAt(),
              current.cancelRequestedAt(),
              current.startedAt(),
              current.finishedAt(),
              now);
      return true;
    }

    @Override
    public synchronized boolean requestCancel(long runId, Instant requestedAt) {
      current =
          copy(
              current.status(),
              current.turnIndex(),
              current.attempt(),
              current.eventSequence(),
              current.leaseOwner(),
              current.leaseUntil(),
              current.nextAttemptAt(),
              requestedAt,
              current.startedAt(),
              current.finishedAt(),
              requestedAt);
      return true;
    }

    @Override
    public synchronized RunEvent append(
        long runId, RunEventType type, String payloadJson, Instant createdAt) {
      long sequence = current.eventSequence() + 1;
      RunEvent event = new RunEvent(++eventId, runId, sequence, type, payloadJson, createdAt);
      events.add(event);
      current =
          copy(
              current.status(),
              current.turnIndex(),
              current.attempt(),
              sequence,
              current.leaseOwner(),
              current.leaseUntil(),
              current.nextAttemptAt(),
              current.cancelRequestedAt(),
              current.startedAt(),
              current.finishedAt(),
              createdAt);
      return event;
    }

    @Override
    public synchronized List<RunEvent> listAfter(long runId, long afterSequence, int limit) {
      return events.stream()
          .filter(event -> event.sequence() > afterSequence)
          .limit(limit)
          .toList();
    }

    @Override
    public AgentRun submitUserMessage(
        long sessionId, Long expectedLeafEntryId, AgentMessage userMessage, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public synchronized boolean consumeSteering(AgentRun claimedRun, Instant now) {
      boundaryOperations.add("consumeSteering");
      consumeSteeringAt = now;
      if (!owned(claimedRun)) {
        return false;
      }
      if (cancelOnConsumeSteeringAt != null && current.cancelRequestedAt() == null) {
        current =
            copy(
                current.status(),
                current.turnIndex(),
                current.attempt(),
                current.eventSequence(),
                current.leaseOwner(),
                current.leaseUntil(),
                current.nextAttemptAt(),
                cancelOnConsumeSteeringAt,
                current.startedAt(),
                current.finishedAt(),
                now);
      }
      if (current.cancelRequestedAt() != null) {
        // 模拟 DB cancel-wins：返 false 但 run 已 CANCELLED。
        List<RunEventDraft> cancelEvents = new ArrayList<>();
        cancelEvents.add(
            new RunEventDraft(
                RunEventType.RUN_CANCELLED,
                RunEventPayloads.forAttempt(claimedRun, "reason", "cancel_requested")));
        appendDrafts(claimedRun.id(), cancelEvents, now);
        current = terminal(claimedRun, RunStatus.CANCELLED, now, false);
        operations.add("terminal:CANCELLED");
        return false;
      }
      if (steeringBlocked) {
        return false;
      }
      return true;
    }

    @Override
    public synchronized boolean complete(
        AgentRun claimedRun,
        MessageEntryPayload assistant,
        RunEventDraft assistantCompleted,
        Instant now) {
      completeAt = now;
      if (!owned(claimedRun) || !completeResult) {
        return false;
      }
      if (cancelOnCompleteAt != null && current.cancelRequestedAt() == null) {
        // 模拟 Provider 完成 -> cancel 请求在 complete CAS 边界提交，DB 仍返回 true。
        current =
            copy(
                current.status(),
                current.turnIndex(),
                current.attempt(),
                current.eventSequence(),
                current.leaseOwner(),
                current.leaseUntil(),
                current.nextAttemptAt(),
                cancelOnCompleteAt,
                current.startedAt(),
                current.finishedAt(),
                now);
      }
      if (current.cancelRequestedAt() != null) {
        // 模拟 DB cancel-wins：返回 true 但不会持久 Assistant；真实 status 变成 CANCELLED。
        List<RunEventDraft> cancelEvents = new ArrayList<>();
        cancelEvents.add(
            new RunEventDraft(
                RunEventType.RUN_CANCELLED,
                RunEventPayloads.forAttempt(claimedRun, "reason", "cancel_requested")));
        appendDrafts(claimedRun.id(), cancelEvents, now);
        current = terminal(claimedRun, RunStatus.CANCELLED, now, false);
        operations.add("terminal:CANCELLED");
        return true;
      }
      if (pendingSteer || pendingFollowUp) {
        // 模拟 control requeue：Assistant 持久化、status 变 QUEUED、不发 terminal。
        sessionMessages.add(assistant);
        operations.add("assistant");
        appendDrafts(claimedRun.id(), List.of(assistantCompleted), now);
        appendDrafts(
            claimedRun.id(),
            List.of(
                new RunEventDraft(
                    RunEventType.RUN_REQUEUED,
                    RunEventPayloads.forAttempt(
                        claimedRun,
                        "reason",
                        pendingSteer ? "steering_pending" : "follow_up_consumed"))),
            now);
        current = requeued(claimedRun, now);
        operations.add("requeue:QUEUED");
        return true;
      }
      sessionMessages.add(assistant);
      operations.add("assistant");
      appendDrafts(claimedRun.id(), List.of(assistantCompleted), now);
      current = terminal(claimedRun, RunStatus.SUCCEEDED, now, true);
      appendDrafts(claimedRun.id(), List.of(completedEvent(claimedRun)), now);
      operations.add("terminal:SUCCEEDED");
      return true;
    }

    private RunEventDraft completedEvent(AgentRun run) {
      return new RunEventDraft(
          RunEventType.RUN_COMPLETED, RunEventPayloads.forAttempt(run, "status", "SUCCEEDED"));
    }

    private synchronized boolean prepare(
        AgentRun claimedRun,
        MessageEntryPayload assistant,
        List<ToolCall> calls,
        List<ToolBinding> bindings,
        Path workdir,
        Path environmentRoot,
        List<RunEventDraft> barrierEvents,
        Instant now) {
      prepareAt = now;
      if (!owned(claimedRun)) {
        return false;
      }
      if (cancelOnPrepareAt != null && current.cancelRequestedAt() == null) {
        current =
            copy(
                current.status(),
                current.turnIndex(),
                current.attempt(),
                current.eventSequence(),
                current.leaseOwner(),
                current.leaseUntil(),
                current.nextAttemptAt(),
                cancelOnPrepareAt,
                current.startedAt(),
                current.finishedAt(),
                now);
      }
      if (current.cancelRequestedAt() != null) {
        // 模拟 DB cancel-wins：返回 true 但不持久 Assistant、不进入 WAITING_TOOLS。
        List<RunEventDraft> cancelEvents = new ArrayList<>();
        cancelEvents.add(
            new RunEventDraft(
                RunEventType.RUN_CANCELLED,
                RunEventPayloads.forAttempt(claimedRun, "reason", "cancel_requested")));
        appendDrafts(claimedRun.id(), cancelEvents, now);
        current = terminal(claimedRun, RunStatus.CANCELLED, now, false);
        operations.add("terminal:CANCELLED");
        return true;
      }
      sessionMessages.add(assistant);
      appendDrafts(claimedRun.id(), barrierEvents, now);
      current = waiting(claimedRun, now);
      return true;
    }

    private AgentRun waiting(AgentRun claimedRun, Instant now) {
      return copy(
          RunStatus.WAITING_TOOLS,
          claimedRun.turnIndex() + 1,
          claimedRun.attempt(),
          current.eventSequence(),
          null,
          null,
          claimedRun.nextAttemptAt(),
          claimedRun.cancelRequestedAt(),
          claimedRun.startedAt(),
          null,
          now);
    }

    private AgentRun requeued(AgentRun claimedRun, Instant now) {
      return copy(
          RunStatus.QUEUED,
          claimedRun.turnIndex() + 1,
          claimedRun.attempt(),
          current.eventSequence(),
          null,
          null,
          now,
          claimedRun.cancelRequestedAt(),
          claimedRun.startedAt(),
          null,
          now);
    }

    @Override
    public synchronized boolean requeue(
        AgentRun claimedRun, Instant nextAttemptAt, List<RunEventDraft> retryEvents, Instant now) {
      requeueAt = now;
      if (!owned(claimedRun)) {
        return false;
      }
      if (cancelOnRequeueAt != null && current.cancelRequestedAt() == null) {
        current =
            copy(
                current.status(),
                current.turnIndex(),
                current.attempt(),
                current.eventSequence(),
                current.leaseOwner(),
                current.leaseUntil(),
                current.nextAttemptAt(),
                cancelOnRequeueAt,
                current.startedAt(),
                current.finishedAt(),
                now);
      }
      if (current.cancelRequestedAt() != null) {
        // 模拟 DB cancel-wins：返 true 但不持久 retry events，run 变 CANCELLED。
        List<RunEventDraft> cancelEvents = new ArrayList<>();
        cancelEvents.add(
            new RunEventDraft(
                RunEventType.RUN_CANCELLED,
                RunEventPayloads.forAttempt(claimedRun, "reason", "cancel_requested")));
        appendDrafts(claimedRun.id(), cancelEvents, now);
        current = terminal(claimedRun, RunStatus.CANCELLED, now, false);
        operations.add("terminal:CANCELLED");
        return true;
      }
      appendDrafts(claimedRun.id(), retryEvents, now);
      current =
          copy(
              RunStatus.QUEUED,
              claimedRun.turnIndex(),
              claimedRun.attempt(),
              current.eventSequence(),
              null,
              null,
              nextAttemptAt,
              claimedRun.cancelRequestedAt(),
              claimedRun.startedAt(),
              null,
              now);
      return true;
    }

    @Override
    public synchronized boolean compactAndRequeue(
        AgentRun claimedRun,
        CompactionEntryPayload compaction,
        Instant nextAttemptAt,
        List<RunEventDraft> compactionEvents,
        Instant now) {
      compactAt = now;
      if (!owned(claimedRun) || !compactResult) {
        return false;
      }
      if (cancelOnCompactAt != null && current.cancelRequestedAt() == null) {
        current =
            copy(
                current.status(),
                current.turnIndex(),
                current.attempt(),
                current.eventSequence(),
                current.leaseOwner(),
                current.leaseUntil(),
                current.nextAttemptAt(),
                cancelOnCompactAt,
                current.startedAt(),
                current.finishedAt(),
                now);
      }
      if (current.cancelRequestedAt() != null) {
        // 模拟 DB cancel-wins：返回 true 但不持久 Compaction，真实 status 变 CANCELLED。
        List<RunEventDraft> cancelEvents = new ArrayList<>();
        cancelEvents.add(
            new RunEventDraft(
                RunEventType.RUN_CANCELLED,
                RunEventPayloads.forAttempt(claimedRun, "reason", "cancel_requested")));
        appendDrafts(claimedRun.id(), cancelEvents, now);
        current = terminal(claimedRun, RunStatus.CANCELLED, now, false);
        operations.add("terminal:CANCELLED");
        return true;
      }
      compactions.add(compaction);
      return requeue(claimedRun, nextAttemptAt, compactionEvents, now);
    }

    @Override
    public synchronized boolean terminate(
        AgentRun claimedRun,
        RunStatus terminalStatus,
        List<RunEventDraft> terminalEvents,
        Instant now) {
      if (!owned(claimedRun)) {
        return false;
      }
      terminateCalls++;
      terminateAt = now;
      if (!terminateResult) {
        return false;
      }
      RunStatus resolvedStatus = terminalStatus;
      List<RunEventDraft> resolvedEvents = terminalEvents;
      if (current.cancelRequestedAt() != null) {
        resolvedStatus = RunStatus.CANCELLED;
        resolvedEvents = new ArrayList<>();
        boolean hasRunCancelled = false;
        for (RunEventDraft event : terminalEvents) {
          if (event.type() != RunEventType.RUN_FAILED) {
            resolvedEvents.add(event);
          }
          if (event.type() == RunEventType.RUN_CANCELLED) {
            hasRunCancelled = true;
          }
        }
        if (!hasRunCancelled) {
          resolvedEvents.add(
              new RunEventDraft(
                  RunEventType.RUN_CANCELLED,
                  RunEventPayloads.forAttempt(claimedRun, "reason", "cancel_requested")));
        }
      }
      appendDrafts(claimedRun.id(), resolvedEvents, now);
      current = terminal(claimedRun, resolvedStatus, now, false);
      operations.add("terminal:" + resolvedStatus.name());
      return true;
    }

    private void appendDrafts(long runId, List<RunEventDraft> drafts, Instant now) {
      for (RunEventDraft draft : drafts) {
        append(runId, draft.type(), draft.payloadJson(), now);
      }
    }

    private boolean owned(AgentRun claimedRun) {
      return current.isOwnedBy(claimedRun.leaseOwner(), claimedRun.attempt());
    }

    private AgentRun terminal(
        AgentRun claimedRun, RunStatus status, Instant now, boolean incrementTurn) {
      return copy(
          status,
          claimedRun.turnIndex() + (incrementTurn ? 1 : 0),
          claimedRun.attempt(),
          current.eventSequence(),
          null,
          null,
          claimedRun.nextAttemptAt(),
          claimedRun.cancelRequestedAt(),
          claimedRun.startedAt(),
          now,
          now);
    }

    private AgentRun copy(
        RunStatus status,
        int turnIndex,
        int attempt,
        long eventSequence,
        String leaseOwner,
        Instant leaseUntil,
        Instant nextAttemptAt,
        Instant cancelRequestedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {
      return new AgentRun(
          current.id(),
          current.sessionId(),
          current.triggerEntryId(),
          status,
          turnIndex,
          attempt,
          eventSequence,
          leaseOwner,
          leaseUntil,
          nextAttemptAt,
          cancelRequestedAt,
          current.createdAt(),
          startedAt,
          finishedAt,
          updatedAt);
    }
  }

  private static final class RecordingProvider implements ModelProvider {
    private final Delivery delivery;
    private ProviderRequest request;
    private ProviderStreamHandler handler;
    private final ProviderStream stream =
        new ProviderStream() {
          private boolean cancelled;

          @Override
          public void cancel() {
            cancelled = true;
          }

          @Override
          public boolean isCancelled() {
            return cancelled;
          }
        };

    private RecordingProvider(Delivery delivery) {
      this.delivery = delivery;
    }

    static RecordingProvider complete(ProviderResponse response) {
      return new RecordingProvider(
          (handler, stream) -> {
            if (!response.text().isEmpty()) {
              handler.onEvent(new ProviderStreamEvent.TextDelta(response.text()), stream);
            }
            handler.onComplete(response, stream);
          });
    }

    static RecordingProvider fail(ProviderException error) {
      return new RecordingProvider((handler, stream) -> handler.onError(error, stream));
    }

    static RecordingProvider failAfterDelta(ProviderStreamEvent event, ProviderException error) {
      return new RecordingProvider(
          (handler, stream) -> {
            handler.onEvent(event, stream);
            handler.onError(error, stream);
          });
    }

    static RecordingProvider manual() {
      return new RecordingProvider((handler, stream) -> {});
    }

    @Override
    public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
      this.request = request;
      this.handler = handler;
      delivery.deliver(handler, stream);
      return stream;
    }

    void deliverComplete(ProviderResponse response) {
      handler.onComplete(response, stream);
    }

    void deliverEvent(ProviderStreamEvent event) {
      handler.onEvent(event, stream);
    }

    void deliverFail(ProviderException error) {
      handler.onError(error, stream);
    }

    @FunctionalInterface
    private interface Delivery {
      void deliver(ProviderStreamHandler handler, ProviderStream stream);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
