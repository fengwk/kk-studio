package fun.fengwk.kkstudio.harness.runtime.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
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
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
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
    assertEquals(new ModelUsage(1, 1, 0, 0, 0), assistant.assistantMetadata().usage());
    assertEquals(new ModelCost("USD", BigDecimal.ZERO), assistant.assistantMetadata().cost());
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
        (run, assistant, calls, bindings, workdir, workspaceRoot, assistantEvents, now) -> {
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
        (run, assistant, calls, bindings, workdir, workspaceRoot, assistantEvents, now) -> {
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
        (run, assistant, calls, bindings, workdir, workspaceRoot, assistantEvents, now) -> {
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

  private static String text(MessageEntryPayload payload) {
    return ((TextMessageContent) payload.message().contents().get(0)).text();
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
        new ModelUsage(1, 1, 0, 0, 0),
        new ModelCost("USD", BigDecimal.ZERO));
  }

  private static TurnResources resources(ModelProvider provider, List<ToolDescriptor> tools) {
    ModelVariant variant = new ModelVariant("default", null, null, null, null, List.of());
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            "model",
            "Model",
            1024,
            256,
            Set.of(ModelInputModality.TEXT),
            Set.of(ModelCapability.TEXT, ModelCapability.TOOLS),
            List.of(variant),
            new ModelPricing(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    return new TurnResources(
        provider,
        model,
        variant,
        tools.stream().map(ToolBinding::of).toList(),
        Path.of("/workspace"),
        Path.of("/workspace"));
  }

  private static final class Fixture {
    private final MutableClock clock = new MutableClock(START);
    private final InMemoryRunState store = new InMemoryRunState(START);
    private final Queue<RecordingProvider> providers = new ArrayDeque<>();
    private final FixedSessionStore sessions = new FixedSessionStore();
    private List<ToolDescriptor> tools = List.of();
    private ToolPreparationPort toolPreparation = store::prepare;
    private CompactionService compaction = (sessionId, context) -> Optional.empty();
    private RunWorkerConfig workerConfig = RunWorkerConfig.DEFAULT;
    private RuntimeException resourceFailure;

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
            return resources(providers.remove(), tools);
          },
          compaction,
          workerConfig,
          clock,
          (delay, task) -> {});
    }
  }

  private static final class FixedSessionStore implements SessionStore, SessionEntryStore {
    private final Session session;
    private final List<SessionEntry> path;

    private FixedSessionStore() {
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

    private InMemoryRunState(Instant now) {
      current =
          new AgentRun(
              20L, 1L, 11L, RunStatus.QUEUED, 0, 0, 0, null, null, now, null, now, null, null, now);
    }

    @Override
    public synchronized Optional<AgentRun> find(long runId) {
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
      if (!current.isOwnedBy(leaseOwner, attempt) || !current.leaseUntil().isAfter(now)) {
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
    public synchronized boolean complete(
        AgentRun claimedRun,
        MessageEntryPayload assistant,
        List<RunEventDraft> terminalEvents,
        Instant now) {
      if (!owned(claimedRun)) {
        return false;
      }
      sessionMessages.add(assistant);
      operations.add("assistant");
      appendDrafts(claimedRun.id(), terminalEvents, now);
      current = terminal(claimedRun, RunStatus.SUCCEEDED, now, true);
      operations.add("terminal:SUCCEEDED");
      return true;
    }

    private synchronized boolean prepare(
        AgentRun claimedRun,
        MessageEntryPayload assistant,
        List<ToolCall> calls,
        List<ToolBinding> bindings,
        Path workdir,
        Path workspaceRoot,
        List<RunEventDraft> barrierEvents,
        Instant now) {
      if (!owned(claimedRun)) {
        return false;
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

    @Override
    public synchronized boolean requeue(
        AgentRun claimedRun, Instant nextAttemptAt, List<RunEventDraft> retryEvents, Instant now) {
      if (!owned(claimedRun)) {
        return false;
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
      if (!owned(claimedRun)) {
        return false;
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
      appendDrafts(claimedRun.id(), terminalEvents, now);
      current = terminal(claimedRun, terminalStatus, now, false);
      operations.add("terminal:" + terminalStatus.name());
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
