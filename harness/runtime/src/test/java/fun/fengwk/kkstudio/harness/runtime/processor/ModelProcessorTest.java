package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ModelProcessor 端到端行为测试：InMemoryHarnessStore + fake ModelGateway / Realtime sink。
 *
 * <p>覆盖 READY admission（Started / Busy / 抛异常 / Rejected / Indeterminate）、callback-before-running
 * 门控、 stale lease 恢复、stream checkpoint 前缀与 tool fragment 排除、success terminal + THREAD
 * wake、transient retry 与 retry exhausted、UNKNOWN、duplicate terminal、deleteWork 后 late callback、new
 * wake 保留、sink 失败隔离、 cancel / close 与 wrong target。
 */
class ModelProcessorTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final EnvironmentId ENV_ID = new EnvironmentId(UUID.randomUUID().toString());
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  private static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(10);
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(7);
  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  private ScheduledExecutorService newScheduler() {
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "model-processor-test");
              thread.setDaemon(true);
              return thread;
            });
    schedulers.add(scheduler);
    return scheduler;
  }

  // ---------------------------------------------------------------------------------------------
  // admission
  // ---------------------------------------------------------------------------------------------

  @Test
  void rejectsNonModelClaim() {
    Fixture fixture = fixture();
    ClaimedWork threadClaim =
        fixture
            .store
            .<Optional<ClaimedWork>>transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, NOW, "thread-token", NOW.plusSeconds(60)))
            .orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(threadClaim));
  }

  /**
   * READY admission Started：短事务 DISPATCHING + revision+1，随后 markRunning + revision+1 并保留 heartbeat。
   */
  @Test
  void admissionStartedMarksRunningAndKeepsLocalExecution() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(1, fixture.gateway.startCalls);
    ModelGateway.Execution execution = fixture.gateway.executions.get(0);
    assertEquals(fixture.invocationId, execution.invocationId());
    assertEquals(1, execution.proposedAttempt());
    assertEquals(fixture.request, execution.request());
    assertFalse(handle.isCancelled());
    assertTrue(fixture.processor.hasActiveExecution());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals("token-" + fixture.invocationId, modelWork.leaseToken());
  }

  /**
   * start 刚返回（甚至同步）到达的 callback 必须先缓冲：callback 观察到的 durable 状态仍是 DISPATCHING，只有 markRunning commit
   * 后才按到达顺序重放（event -&gt; terminal），terminal 在激活期间落地因此 process 返回 TERMINATED。
   */
  @Test
  void gatesCallbacksUntilDurableRunningIsCommitted() {
    Fixture fixture = fixture();
    AtomicReference<ModelInvocationStatus> statusAtCallback = new AtomicReference<>();
    fixture.gateway.beforeReturn =
        listener -> {
          listener.onEvent(new ProviderStreamEvent.TextDelta("ans"));
          listener.onSucceeded(response("answer", ProviderStopReason.COMPLETED));
          statusAtCallback.set(model(fixture.store, fixture.invocationId).status());
        };
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    assertEquals(ModelInvocationStatus.DISPATCHING, statusAtCallback.get());

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertEquals("answer", model.result().text());
    assertNotNull(model.streamCheckpoint());
    assertEquals(1, model.streamCheckpoint().attempt());
    assertEquals(2, model.streamCheckpoint().sequence());
    assertEquals("answer", model.streamCheckpoint().text());
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("ans"), new ProviderStreamEvent.TextDelta("wer")),
        deltas(fixture.sink));
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Busy：DISPATCHING -&gt; READY + revision+1，按 retryAfter reschedule，attempt 不变。 */
  @Test
  void busyAdmissionBouncesToReadyWithoutAdvancingAttempt() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(0, model.attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** start 抛异常（契约：肯定未接受）：同样 bounce，使用构造注入的 fallback delay。 */
  @Test
  void startExceptionBouncesWithFallbackDelay() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new IllegalStateException("gateway down"));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Rejected：rejectDispatch FAILED + revision+1 + THREAD wake + complete MODEL Work，attempt 不变。 */
  @Test
  void rejectedAdmissionFailsInvocationAndWakesThread() {
    Fixture fixture = fixture();
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model rejected");
    fixture.gateway.queue(new ModelGateway.Rejected(error));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(0, model.attempt());
    assertEquals(error, model.error());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Indeterminate：UNKNOWN 并消费 proposed attempt（DISPATCHING attempt+1）。 */
  @Test
  void indeterminateAdmissionTerminatesUnknownConsumingProposedAttempt() {
    Fixture fixture = fixture();
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown");
    fixture.gateway.queue(new ModelGateway.Indeterminate(error));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(error, model.error());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  // ---------------------------------------------------------------------------------------------
  // prepare 恢复 / cleanup
  // ---------------------------------------------------------------------------------------------

  /**
   * 新 claim 遇到旧 lease 过期的 RUNNING：UNKNOWN 保留 attempt + revision+1 + THREAD wake + complete，绝不调用
   * Gateway。
   */
  @Test
  void staleRunningLeaseRecoveryTerminatesUnknownWithoutGateway() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    ClaimedWork firstClaim = claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 新 claim 遇到旧 lease 过期的 DISPATCHING：UNKNOWN 消费 proposed attempt（attempt+1）。 */
  @Test
  void staleDispatchingLeaseRecoveryConsumesProposedAttempt() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** terminal 行且 resultEntryId 仍 null：确保 THREAD wake 后 complete，不重复 bump revision。 */
  @Test
  void terminalCleanupWakesThreadWithoutRevisionBump() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", ProviderStopReason.COMPLETED), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.resultEntryId());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** terminal 行且 resultEntryId 已链接：只 complete MODEL Work，不再请求 THREAD wake。 */
  @Test
  void terminalCleanupSkipsThreadWakeWhenResultEntryIsLinked() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", ProviderStopReason.COMPLETED), NOW));
    long assistantEntryId =
        fixture.store.transaction(
            tx -> {
              long userId = tx.nextId();
              long id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      userId,
                      fixture.baseline.sessionId(),
                      fixture.baseline.turnStartEntryId(),
                      userMessagePayload(),
                      NOW.plusMillis(2)));
              tx.insertEntry(
                  new Entry(
                      id,
                      fixture.baseline.sessionId(),
                      userId,
                      assistantPayload(),
                      NOW.plusMillis(3)));
              return id;
            });
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.attachResultEntry(assistantEntryId, NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(claimed));

    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  // ---------------------------------------------------------------------------------------------
  // stream / terminal callbacks
  // ---------------------------------------------------------------------------------------------

  /** checkpoint 只累积 text/thinking；tool-call fragment 只推进 sequence，绝不进入 checkpoint。 */
  @Test
  void checkpointKeepsPrefixAndExcludesToolFragments() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("hel"));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{\"a\""));

    ModelInvocation mid = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, mid.status());
    assertEquals(1, mid.attempt());
    assertEquals(1, mid.streamCheckpoint().sequence());
    assertEquals("hel", mid.streamCheckpoint().text());
    assertFalse(mid.streamCheckpoint().text().contains("call_1"));

    listener.onEvent(new ProviderStreamEvent.TextDelta("lo"));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, null, null, ":1}"));
    listener.onSucceeded(
        toolResponse("hello", new ProviderToolCall("call_1", "bash", "{\"a\":1}")));

    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertEquals(1, terminal.attempt());
    assertNotNull(terminal.streamCheckpoint());
    assertEquals(4, terminal.streamCheckpoint().sequence());
    assertEquals("hello", terminal.streamCheckpoint().text());
    assertFalse(terminal.streamCheckpoint().text().contains("call_1"));
    List<ProviderStreamEvent> deltas = deltas(fixture.sink);
    assertEquals(4, deltas.size());
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("hel"), new ProviderStreamEvent.TextDelta("lo")),
        deltas.stream().filter(delta -> delta instanceof ProviderStreamEvent.TextDelta).toList());
  }

  /**
   * 成功终态：最终 reconcile 补发布 gap、写 terminal + revision+1 + THREAD wake + complete，并保留完整 safe
   * checkpoint。
   */
  @Test
  void successTerminalWakesThreadAndPublishesTrailingGap() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("ans"));
    listener.onSucceeded(response("answer", ProviderStopReason.COMPLETED));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertEquals("answer", model.result().text());
    assertNotNull(model.streamCheckpoint());
    assertEquals(2, model.streamCheckpoint().sequence());
    assertEquals("answer", model.streamCheckpoint().text());
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("ans"), new ProviderStreamEvent.TextDelta("wer")),
        deltas(fixture.sink));
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 成功终态在 THREAD Work 已被消费后仍按升序重建 THREAD Work，不能卡在 RUNNING。 */
  @Test
  void successTerminalRecreatesConsumedThreadWork() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    fixture.store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          assertTrue(
              tx.deleteWork(new WorkTarget(WorkTargetType.THREAD, thread.id())),
              "the thread work must be consumable before model terminal persistence");
          return null;
        });

    fixture
        .gateway
        .listener(fixture.invocationId)
        .onSucceeded(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /**
   * TRANSIENT + 策略允许：RUNNING -&gt; READY（retryReady）+ revision+1 + 按策略延迟 reschedule，不请求 THREAD
   * wake。
   */
  @Test
  void transientFailureRetriesWithPolicyDelay() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("partial"));
    listener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unavailable"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).revision());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());

    // retry 后旧 execution 的 late callback 必须 no-op。
    listener.onEvent(new ProviderStreamEvent.TextDelta("late"));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, deltas(fixture.sink).size());
  }

  /** retry budget 耗尽：第二次 TRANSIENT 失败转为 FAILED，attempt 保持已确认值。 */
  @Test
  void retryExhaustionTerminatesFailedAfterSecondTransientFailure() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "first"));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    ClaimedWork second = claim(fixture.store, fixture.invocationId, fixture.clock.instant());
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ModelGateway.Listener secondListener = fixture.gateway.listener(fixture.invocationId);

    secondListener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "second"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(6, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** onUnknown：RUNNING 保留 attempt 转 UNKNOWN，即使错误是 TRANSIENT 也不 retry。 */
  @Test
  void unknownCallbackTerminatesWithoutRetry() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onUnknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** terminal 只生效一次：duplicate / late terminal 与 terminal 后的 event 全部 no-op。 */
  @Test
  void duplicateTerminalAndLateEventsAreNoOps() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(response("answer", ProviderStopReason.COMPLETED));
    listener.onSucceeded(response("second", ProviderStopReason.COMPLETED));
    listener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "late"));
    listener.onUnknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "late"));
    listener.onEvent(new ProviderStreamEvent.TextDelta("late"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals("answer", model.result().text());
    assertEquals(1, model.attempt());
    assertEquals(List.of(new ProviderStreamEvent.TextDelta("answer")), deltas(fixture.sink));
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** Stop deleteWork 后到达的 late callback：ownership 校验失败，不写任何 durable 状态并关闭本地执行。 */
  @Test
  void lateCallbackAfterWorkDeletionIsNoOp() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    deleteModelWork(fixture);

    listener.onSucceeded(response("answer", ProviderStopReason.COMPLETED));
    listener.onEvent(new ProviderStreamEvent.TextDelta("late"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertTrue(fixture.sink.events.isEmpty());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late TRANSIENT 失败：retry 事务 ownership 校验失败，保持 RUNNING 并关闭本地执行。 */
  @Test
  void retryAfterWorkDeletionIsLost() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    deleteModelWork(fixture);

    listener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late UNKNOWN / 不可重试失败：terminal 事务 ownership 校验失败，保持 RUNNING。 */
  @Test
  void unknownAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    deleteModelWork(fixture);

    listener.onUnknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 事件管线失败发生在 ownership 已丢失之后：terminal 事务同样失败，仅本地关闭。 */
  @Test
  void eventFailureAfterWorkDeletionIsLost() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    deleteModelWork(fixture);

    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 首个事件即非法且 ownership 已丢失：terminal 事务失败，仅本地关闭，不写 durable 终态。 */
  @Test
  void nullEventAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    deleteModelWork(fixture);

    listener.onEvent(null);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** CANCELLED 失败且 ownership 已丢失：CANCELLED terminal 事务失败，仅本地关闭。 */
  @Test
  void cancelledAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    deleteModelWork(fixture);

    listener.onFailed(new ModelInvocationError(ProviderErrorKind.CANCELLED, "stopped"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** onFailed(CANCELLED)：invocation 转 CANCELLED terminal，不 retry。 */
  @Test
  void cancelledCallbackTerminatesCancelled() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onFailed(new ModelInvocationError(ProviderErrorKind.CANCELLED, "stopped"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.CANCELLED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.CANCELLED, model.error().kind());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** handle.cancel 抛异常：本地隔离，不传播也不影响 registry 释放。 */
  @Test
  void handleCancelFailureIsIsolated() {
    Fixture fixture = fixture();
    ModelGateway.Handle throwingHandle =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {
            throw new IllegalStateException("transport gone");
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(throwingHandle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertTrue(fixture.processor.cancel(fixture.invocationId));
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
  }

  /** handle.activate 抛异常（激活失败）：恰好一次 UNKNOWN terminal（RUNNING 保留 attempt），不重试、不重放。 */
  @Test
  void activationFailureProducesExactlyOneUnknown() {
    Fixture fixture = fixture();
    ModelGateway.Handle failingActivation =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {}

          @Override
          public void activate() {
            throw new IllegalStateException("provider gate broken");
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(failingActivation));
    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt(), "RUNNING keeps its confirmed attempt");
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.error().message().contains("activation failed"));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 恶意 handle：activate 内先同步投递 terminal 回调（gate 未开，只进缓冲）再抛异常——必须丢弃缓冲并强制恰好一次 UNKNOWN。 */
  @Test
  void activationFailureForcesUnknownEvenIfHandleEmitsTerminalThenThrows() {
    Fixture fixture = fixture();
    ModelGateway.Handle malicious =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {}

          @Override
          public void activate() {
            fixture
                .gateway
                .listener(fixture.invocationId)
                .onSucceeded(response("answer", ProviderStopReason.COMPLETED));
            throw new IllegalStateException("provider gate broken");
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(malicious));
    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(
        ModelInvocationStatus.UNKNOWN, model.status(), "buffered terminal must be dropped");
    assertEquals(1, model.attempt(), "RUNNING keeps its confirmed attempt");
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.error().message().contains("activation failed"));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 激活失败异常携带 adversarial toString：先收敛 durable UNKNOWN 再记录，异常渲染绝不 bypass 状态转换。 */
  @Test
  void activationFailureWithAdversarialExceptionToStringStillConvergesUnknown() {
    Fixture fixture = fixture();
    ModelGateway.Handle failingActivation =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {}

          @Override
          public void activate() {
            throw new RuntimeException("gate broken") {
              @Override
              public String toString() {
                throw new IllegalStateException("adversarial toString");
              }
            };
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(failingActivation));
    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt(), "RUNNING keeps its confirmed attempt");
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.error().message().contains("activation failed"));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 处理期间到达新 wake：terminal complete 只清 lease 保留行，新 wake 保持可见。 */
  @Test
  void newWakeSurvivesTerminalCompletion() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    requestModelWork(fixture);

    listener.onSucceeded(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(2, modelWork.wakeVersion());
    assertNull(modelWork.leaseToken());
    assertNull(modelWork.leaseUntil());
  }

  /** sink 失败只能降低实时体验：durable checkpoint / terminal 完全不受影响。 */
  @Test
  void sinkFailureDoesNotAffectDurableState() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    fixture.sink.failure = new IllegalStateException("redis down");

    listener.onEvent(new ProviderStreamEvent.TextDelta("ans"));
    listener.onSucceeded(response("answer", ProviderStopReason.COMPLETED));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals("answer", model.result().text());
    assertNotNull(model.streamCheckpoint());
    assertEquals("answer", model.streamCheckpoint().text());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    fixture.sink.failure = null;
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** 非法终态（stream 冲突 / 冻结 request 中不可见的工具）：转 FAILED(INVALID_REQUEST)。 */
  @Test
  void conflictingFinalResponseFailsWithInvalidRequest() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    listener.onSucceeded(response("world", ProviderStopReason.COMPLETED));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, model.error().kind());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertEquals(1, deltas(fixture.sink).size());
  }

  /** 冻结 request 中未声明的工具调用：reconcile 校验拒绝并 FAILED(INVALID_REQUEST)。 */
  @Test
  void undeclaredToolCallFailsWithInvalidRequest() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(
        new ProviderResponse(
            "",
            null,
            List.of(new ProviderToolCall("call_1", "undeclared", "{}")),
            ProviderStopReason.TOOL_CALLS,
            usage(),
            cost(),
            "req-1",
            null,
            null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, model.error().kind());
  }

  /** 缓冲的 TRANSIENT 失败在激活期间落地：走 retry 路径并返回 RESCHEDULED（attempt 已确认）。 */
  @Test
  void bufferedTransientFailureBeforeActivationReschedules() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.beforeReturn =
        listener ->
            listener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom"));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).revision());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 缓冲的不可重试失败在激活期间落地：走 terminal 路径并返回 TERMINATED。 */
  @Test
  void bufferedRejectionBeforeActivationTerminates() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn =
        listener ->
            listener.onFailed(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "nope"));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** admission 期间 Stop 获胜（work 行被删）：markRunning 校验失败，LOST_OWNERSHIP 且 cancel handle。 */
  @Test
  void stopWinningAdmissionReturnsLostAndCancelsHandle() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.beforeReturn = listener -> deleteModelWork(fixture);
    fixture.gateway.queue(new ModelGateway.Started(handle));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.DISPATCHING, model.status());
    assertEquals(0, model.attempt());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 事件管线异常（tool-call identity 冲突）：转 FAILED(INVALID_REQUEST)，已发布 delta 保持。 */
  @Test
  void conflictingToolIdentityEventFailsWithInvalidRequest() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, model.error().kind());
    assertEquals(1, deltas(fixture.sink).size());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
  }

  /** 无任何 streamed 内容且 final 为空：terminal 不写空 checkpoint。 */
  @Test
  void successWithoutStreamedContentKeepsNoCheckpoint() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(response("", ProviderStopReason.COMPLETED));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertNull(model.streamCheckpoint());
    assertTrue(fixture.sink.events.isEmpty());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** thinking delta 进 checkpoint；final thinking 补发布 gap。 */
  @Test
  void thinkingDeltasAreCheckpointedAndGapProjected() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("think"));
    listener.onSucceeded(
        new ProviderResponse(
            "",
            "thinking",
            List.of(),
            ProviderStopReason.COMPLETED,
            usage(),
            cost(),
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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("think"));
    listener.onSucceeded(response("", ProviderStopReason.COMPLETED));

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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call", null, null));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, null, "bas", null));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, null, null, "{\"a\""));
    listener.onSucceeded(toolResponse("", new ProviderToolCall("call_1", "bash", "{\"a\":1}")));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    List<ProviderStreamEvent> deltas = deltas(fixture.sink);
    assertEquals(4, deltas.size());
    assertEquals(new ProviderStreamEvent.ToolCallDelta(0, "_1", "h", ":1}"), deltas.get(3));
  }

  /** final response 遗漏 streamed 过的 tool call：reconcile 拒绝并 FAILED(INVALID_REQUEST)。 */
  @Test
  void finalResponseOmittingStreamedToolCallFails() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{}"));
    listener.onSucceeded(response("", ProviderStopReason.COMPLETED));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, model.error().kind());
  }

  /** 非 TOOL_CALLS stopReason 携带可执行工具调用：validator 拒绝。 */
  @Test
  void toolCallsWithNonToolCallStopReasonFail() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(
        new ProviderResponse(
            "",
            null,
            List.of(new ProviderToolCall("call_1", "bash", "{}")),
            ProviderStopReason.COMPLETED,
            usage(),
            cost(),
            "req-1",
            null,
            null));

    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        model(fixture.store, fixture.invocationId).error().kind());
  }

  /** TOOL_CALLS stopReason 但没有工具调用：validator 拒绝。 */
  @Test
  void toolCallStopReasonWithoutCallsFails() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(response("", ProviderStopReason.TOOL_CALLS));

    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        model(fixture.store, fixture.invocationId).error().kind());
  }

  /** 重复 tool call id：validator 拒绝。 */
  @Test
  void duplicateToolCallIdsFail() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(
        new ProviderResponse(
            "",
            null,
            List.of(
                new ProviderToolCall("call_1", "bash", "{}"),
                new ProviderToolCall("call_1", "bash", "{}")),
            ProviderStopReason.TOOL_CALLS,
            usage(),
            cost(),
            "req-1",
            null,
            null));

    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        model(fixture.store, fixture.invocationId).error().kind());
  }

  /** 非法 arguments JSON：validator 拒绝。 */
  @Test
  void malformedToolArgumentsFail() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(toolResponse("", new ProviderToolCall("call_1", "bash", "not-json")));

    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        model(fixture.store, fixture.invocationId).error().kind());
  }

  /** 同一 JVM 内旧 lease 过期后新 claim（不同 token）：supersede 本地旧 execution，恢复为 UNKNOWN 且不重放 Provider。 */
  @Test
  void sameJvmRecoverySupersedesStaleLocalExecution() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW, "token-old")));
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered =
        claim(fixture.store, fixture.invocationId, fixture.clock.instant(), "token-new");

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(1, fixture.gateway.startCalls);
    assertTrue(handle.isCancelled());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** admission 结果事务期间 ownership 丢失（Busy 且 work 已被删）：返回 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void busyWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = listener -> deleteModelWork(fixture);
    fixture.gateway.queue(new ModelGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.DISPATCHING, model.status());
    assertEquals(0, model.attempt());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** start 抛异常且期间 ownership 丢失：同样返回 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void startExceptionWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = listener -> deleteModelWork(fixture);
    fixture.gateway.queue(new IllegalStateException("gateway down"));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Rejected 且期间 ownership 丢失：LOST_OWNERSHIP。 */
  @Test
  void rejectedWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = listener -> deleteModelWork(fixture);
    fixture.gateway.queue(
        new ModelGateway.Rejected(
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "rejected")));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
  }

  /** Indeterminate 且期间 ownership 丢失：LOST_OWNERSHIP。 */
  @Test
  void indeterminateWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = listener -> deleteModelWork(fixture);
    fixture.gateway.queue(
        new ModelGateway.Indeterminate(
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unknown")));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
  }

  /** 恢复路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void recoveryWithDeletedWorkIsLost() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.DISPATCHING, model.status());
    assertEquals(0, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** terminal cleanup 路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void terminalCleanupWithDeletedWorkIsLost() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", ProviderStopReason.COMPLETED), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertEquals(0, fixture.gateway.startCalls);
  }

  // ---------------------------------------------------------------------------------------------
  // 主审修正：handle race / duplicate fencing / null start / lease margin / closed
  // ---------------------------------------------------------------------------------------------

  /**
   * Started handle 在 markRunning 之前安全 attach：execution 已被本地 cancel（heartbeat / close 竞态的确定性近似）时，
   * handle 必须被 best-effort cancel 且不得 markRunning（保持 DISPATCHING、无 mutation）。
   */
  @Test
  void activateAfterLocalCancelCancelsHandleWithoutMarkingRunning() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.beforeReturn =
        listener -> assertTrue(fixture.processor.cancel(fixture.invocationId));
    fixture.gateway.queue(new ModelGateway.Started(handle));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertTrue(handle.isCancelled());
    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.DISPATCHING, model.status());
    assertEquals(0, model.attempt());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 顺序重复投递同一 claim（同 token）：LOST no-op，不 cancel、不 mutation，合法 RUNNING 不受影响。 */
  @Test
  void duplicateSequentialProcessWithSameClaimIsLostNoOp() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    long revision = thread(fixture.store, fixture.baseline.threadId()).revision();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(revision, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(handle.isCancelled());
    assertEquals(1, fixture.gateway.startCalls);
    assertTrue(fixture.processor.hasActiveExecution());
  }

  /** 并发重复投递同一 claim：guard 覆盖 prepare 到 registry 插入，后到者 LOST no-op，绝不双发 Provider。 */
  @Test
  void duplicateConcurrentProcessWithSameClaimIsLostNoOp() throws Exception {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        listener -> {
          inStart.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    AtomicReference<ProcessResult> first = new AtomicReference<>();
    AtomicReference<ProcessResult> second = new AtomicReference<>();
    Thread firstThread = new Thread(() -> first.set(fixture.processor.process(claimed)));
    firstThread.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS));

    Thread secondThread = new Thread(() -> second.set(fixture.processor.process(claimed)));
    secondThread.start();
    secondThread.join(5000);
    assertEquals(ProcessResult.LOST_OWNERSHIP, second.get());

    release.countDown();
    firstThread.join(5000);
    assertEquals(ProcessResult.STARTED, first.get());
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertFalse(handle.isCancelled());
  }

  /** Gateway start 返回 null（契约违反）：acceptance 不确定，按 Indeterminate 语义收敛 UNKNOWN(attempt+1)。 */
  @Test
  void nullGatewayStartTreatsAsIndeterminate() {
    Fixture fixture = fixture();
    fixture.gateway.queueNullStart();

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * checkpoint 节流：首个 safe delta 立即 flush；interval 内只验证 ownership 并发布 realtime；tool fragment 永不
   * checkpoint；terminal success 总是 flush 完整 safe snapshot。
   */
  @Test
  void checkpointFlushIsThrottledAndTerminalAlwaysFlushes() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("a"));
    assertEquals(1, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());
    assertEquals("a", model(fixture.store, fixture.invocationId).streamCheckpoint().text());

    // interval 未到：不写 checkpoint，但仍验证 ownership 并发布 realtime。
    listener.onEvent(new ProviderStreamEvent.TextDelta("b"));
    assertEquals(1, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());
    assertEquals("a", model(fixture.store, fixture.invocationId).streamCheckpoint().text());
    assertEquals(2, deltas(fixture.sink).size());

    // 达到 interval：flush 累积文本（advance 保持在 claim lease 内）。
    fixture.clock.advance(Duration.ofSeconds(20));
    listener.onEvent(new ProviderStreamEvent.TextDelta("c"));
    assertEquals(3, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());
    assertEquals("abc", model(fixture.store, fixture.invocationId).streamCheckpoint().text());

    // tool fragment 永不 checkpoint（即使 interval 已到）；final 需携带该 tool call 才能 reconcile。
    fixture.clock.advance(Duration.ofSeconds(20));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    assertEquals(3, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());

    // terminal success 总是 flush 完整 safe snapshot（tool 的 name/args 差异聚合成 1 个 gap delta）。
    listener.onSucceeded(toolResponse("abc", new ProviderToolCall("call_1", "bash", "{}")));
    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertNotNull(terminal.streamCheckpoint());
    assertEquals("abc", terminal.streamCheckpoint().text());
    assertEquals(5, terminal.streamCheckpoint().sequence());
  }

  /** claim lease 剩余不足（首次 heartbeat 前会过期）：prepare READY 时立即 renew 出完整 margin。 */
  @Test
  void nearExpiryClaimGetsFullLeaseMarginBeforeDispatch() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.MODEL, NOW, "near-expiry", NOW.plusSeconds(1)))
            .orElseThrow();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(30), modelWork.leaseUntil());
    assertEquals("near-expiry", modelWork.leaseToken());
  }

  /** claim lease 剩余充足：不额外 renew，保持 dispatcher 写入的 lease。 */
  @Test
  void sufficientLeaseMarginIsNotRenewed() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(claimed.leaseUntil(), modelWork.leaseUntil());
  }

  /** close 后拒绝新 process；close 幂等且不 shutdown 注入的 scheduler。 */
  @Test
  void closedProcessorRejectsProcessAndCloseIsIdempotent() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    fixture.processor.close();
    fixture.processor.close();

    assertThrows(IllegalStateException.class, () -> fixture.processor.process(claimed));
    assertFalse(fixture.processor.hasActiveExecution());
    assertFalse(fixture.scheduler.isShutdown());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
  }

  // ---------------------------------------------------------------------------------------------
  // 最终并发收口：claimOwned 前置校验 / close-race / attachHandle 锁外 cancel
  // ---------------------------------------------------------------------------------------------

  /**
   * active RUNNING + 伪造不同 token 的 claim：Work-only 前置校验（claimOwned）必须先于 guard 替换 / supersede—— 伪造
   * token 与 stored leaseToken 不匹配 → LOST no-op，合法 active execution 不被 cancel、durable 不变。
   */
  @Test
  void forgedDifferentTokenWhileActiveRunningIsLostWithoutCancelling() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    ClaimedWork forged =
        new ClaimedWork(
            claimed.target(), claimed.claimedWakeVersion(), "forged-token", claimed.leaseUntil());
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(forged));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(1, fixture.gateway.startCalls);
    assertFalse(handle.isCancelled());
    assertTrue(fixture.processor.hasActiveExecution());
    assertEquals(
        "token-" + fixture.invocationId,
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .leaseToken());
  }

  /**
   * close 在 registry 插入之后执行（close snapshot 之前插入，快照含该 execution）：close 负责 abandon，Started handle
   * 到达时经 attachHandle 竞态被锁外 cancel，不留新 execution；durable 保持 DISPATCHING（markRunning 未发生），lease 过期后由
   * dispatcher 恢复。
   */
  @Test
  void closeAfterRegistryInsertCancelsHandleAndLeavesNoExecution() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.beforeReturn = listener -> fixture.processor.close();
    fixture.gateway.queue(new ModelGateway.Started(handle));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(1, fixture.gateway.startCalls);
    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(
        "token-" + fixture.invocationId,
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .leaseToken());
  }

  /**
   * close 在 process 通过入口检查之后、registry 插入之前完成（close snapshot 之后插入）：putIfAbsent 后的 closed 检查
   * 必须拦截——abandon 新 execution、把仍 owned 的 DISPATCHING 安全 bounce 回 READY + reschedule，绝不启动 Gateway。
   * 确定性同步：持 store monitor 使 process 阻塞在 claimOwned 事务上，期间执行 close。
   */
  @Test
  void closeBeforeRegistryInsertBouncesReadyWithoutStartingGateway() throws Exception {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle())); // 不应被消费
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);

    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holdStore =
        new Thread(
            () ->
                fixture.store.transaction(
                    ignored -> {
                      holding.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holdStore.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread = new Thread(() -> result.set(fixture.processor.process(claimed)));
    processThread.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (processThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(
        System.nanoTime() < deadline, "process must block on the store monitor (claimOwned)");

    fixture.processor.close(); // 此时 executions 为空：本 execution 尚未插入 registry
    release.countDown();
    holdStore.join(5000);
    processThread.join(5000);

    assertEquals(ProcessResult.RESCHEDULED, result.get());
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * attachHandle 的 handle.cancel 必须在 monitor 锁外：cancel 若同步触发 listener 回调（回调需要 monitor），锁内调用会 死锁。用
   * cancel 内启动回调线程并 join 的方式确定性验证无死锁（join 5s 超时即视为死锁）。
   */
  @Test
  void attachHandleCancelsOutsideMonitorSoCancelCallbacksCannotDeadlock() {
    Fixture fixture = fixture();
    FakeHandle handle =
        new FakeHandle() {
          @Override
          public void cancel() {
            super.cancel();
            Thread callback =
                new Thread(
                    () ->
                        fixture
                            .gateway
                            .listener(fixture.invocationId)
                            .onSucceeded(response("x", ProviderStopReason.COMPLETED)));
            callback.start();
            try {
              callback.join(5000);
              if (callback.isAlive()) {
                throw new AssertionError(
                    "handle.cancel synchronously waiting for listener callback deadlocked");
              }
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError(failure);
            }
          }
        };
    fixture.gateway.beforeReturn =
        listener -> assertTrue(fixture.processor.cancel(fixture.invocationId));
    fixture.gateway.queue(new ModelGateway.Started(handle));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertTrue(handle.isCancelled());
    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * close / 本地 cancel 在 heartbeat 启动窗口内获胜（startHeartbeat 返回后、Gateway start 前）：abandoned 检查必须 拦截，不启动
   * Gateway，把仍 owned 的 DISPATCHING 安全 bounce READY + reschedule。确定性注入：包装 scheduler， 在
   * scheduleAtFixedRate 提交期间取消本地 execution。
   */
  @Test
  void abandonDuringHeartbeatStartupBouncesWithoutStartingGateway() {
    AtomicReference<ModelProcessor> processorRef = new AtomicReference<>();
    AtomicLong cancelId = new AtomicLong();
    ScheduledExecutorService base = newScheduler();
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    // startHeartbeat 提交期间取消：execution 已在 registry（putIfAbsent 先于 startHeartbeat）。
                    processorRef.get().cancel(cancelId.get());
                  }
                  return method.invoke(base, args);
                });
    Fixture fixture = fixture(NO_RETRY, request(), hooked);
    cancelId.set(fixture.invocationId);
    processorRef.set(fixture.processor);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle())); // 不应被消费

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * 并发不同 token：新 claim 已真实 owned（旧 lease 过期被 dispatcher 重新 claim）时通过 claimOwned 前置校验并抢占 guard
   * （replace 循环），supersede 旧本地 execution 后按 durable DISPATCHING 恢复 UNKNOWN（消费 proposed attempt）； 旧
   * process 的 Started handle 到达时被锁外 cancel。
   */
  @Test
  void concurrentNewTokenPreemptsGuardAndRecoversUnknown() throws Exception {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        listener -> {
          inStart.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimedA = claim(fixture.store, fixture.invocationId, NOW, "token-A");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixture.processor.process(claimedA)));
    threadA.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS)); // A 已通过 guard / putIfAbsent，阻塞在 gateway.start

    // 旧 lease 被 dispatcher 重新 claim：删旧行、重建 wake 并以新 token 重新领取。
    replaceModelWork(fixture);
    ClaimedWork claimedB = claim(fixture.store, fixture.invocationId, NOW, "token-B");

    AtomicReference<ProcessResult> resultB = new AtomicReference<>();
    Thread threadB = new Thread(() -> resultB.set(fixture.processor.process(claimedB)));
    threadB.start();
    threadB.join(5000);
    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.TERMINATED, resultB.get());
    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(1, fixture.gateway.startCalls);
    assertTrue(handle.isCancelled());
    assertEquals(
        ModelInvocationStatus.UNKNOWN, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * stale-start fence：A（token-1）在 heartbeat 启动后暂停；lease 过期后由**另一实例**（模拟另一 JVM 的 processor， 本地
   * registry / guard 独立，A 的本地 abandoned 检查不可见）以 token-2 recover UNKNOWN；A 恢复时 fence 校验 durable 已非
   * DISPATCHING → 立即 abandon 并 LOST，绝不调用 Gateway。
   */
  @Test
  void staleStartAfterCrossInstanceRecoveryNeverCallsGateway() throws Exception {
    CountDownLatch inHeartbeat = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ScheduledExecutorService base = newScheduler();
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  Object result = method.invoke(base, args);
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    inHeartbeat.countDown();
                    release.await(5, TimeUnit.SECONDS);
                  }
                  return result;
                });
    Fixture fixtureA = fixture(NO_RETRY, request(), hooked);
    fixtureA.gateway.queue(new ModelGateway.Started(new FakeHandle())); // 不应被消费
    ClaimedWork claimedA = claim(fixtureA.store, fixtureA.invocationId, NOW, "token-1");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixtureA.processor.process(claimedA)));
    threadA.start();
    assertTrue(
        inHeartbeat.await(5, TimeUnit.SECONDS)); // A 已 putIfAbsent + prepare，暂停在 startHeartbeat

    // lease 过期，dispatcher 以 token-2 重新 claim；另一实例 recover UNKNOWN（跨实例，A 的 abandoned 检查不可见）。
    fixtureA.clock.advance(Duration.ofSeconds(61));
    replaceModelWork(fixtureA);
    ClaimedWork claimedB =
        claim(fixtureA.store, fixtureA.invocationId, fixtureA.clock.instant(), "token-2");
    ModelProcessor processorB =
        new ModelProcessor(
            fixtureA.store,
            new FakeGateway(),
            fixtureA.sink,
            new ModelProcessorConfig(LEASE_CONFIG, CHECKPOINT_INTERVAL, NO_RETRY, FALLBACK_DELAY),
            fixtureA.clock,
            newScheduler());
    assertEquals(ProcessResult.TERMINATED, processorB.process(claimedB));

    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(0, fixtureA.gateway.startCalls, "stale start must never call the gateway");
    assertFalse(fixtureA.processor.hasActiveExecution());
    assertFalse(processorB.hasActiveExecution());
    ModelInvocation model = model(fixtureA.store, fixtureA.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(2, thread(fixtureA.store, fixtureA.baseline.threadId()).revision());
    assertEquals(
        2,
        work(fixtureA.store, new WorkTarget(WorkTargetType.THREAD, fixtureA.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixtureA.store, new WorkTarget(WorkTargetType.MODEL, fixtureA.invocationId)));
  }

  /**
   * scheduler 拒绝 heartbeat 且期间 ownership 已丢（bounce 前 work 行被删）：按 bounce 实际结果返回 LOST_OWNERSHIP，
   * 不得无条件 RESCHEDULED；不调用 Gateway。
   */
  @Test
  void heartbeatSchedulerRejectionWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    ScheduledExecutorService base = newScheduler();
    ScheduledExecutorService hooked =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scheduleAtFixedRate")) {
                    // prepare 之后、bounce 之前删除 work 行：bounce 无法恢复，必须 LOST。
                    deleteModelWork(fixture);
                    throw new RejectedExecutionException("shutdown");
                  }
                  return method.invoke(base, args);
                });
    ModelProcessor processor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(LEASE_CONFIG, CHECKPOINT_INTERVAL, NO_RETRY, FALLBACK_DELAY),
            fixture.clock,
            hooked);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle())); // 不应被消费

    assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claimed));

    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertFalse(processor.hasActiveExecution());
  }

  /** lease 剩余 15s（< leaseDuration 30s）：prepare READY 仍 renew 出完整 margin。 */
  @Test
  void partialLeaseMarginIsRenewedToFullDuration() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.MODEL, NOW, "partial", NOW.plusSeconds(15)))
            .orElseThrow();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(30), modelWork.leaseUntil());
    assertEquals("partial", modelWork.leaseToken());
  }

  /** lease 剩余恰好等于 leaseDuration：相等不 renew（renew 要求严格延展，等值会违反）。 */
  @Test
  void exactFullLeaseMarginIsNotRenewed() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.MODEL, NOW, "exact", NOW.plusSeconds(30)))
            .orElseThrow();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plusSeconds(30), modelWork.leaseUntil());
  }

  /** heartbeat 一旦 stop 就不能重启（scheduler 拒绝路径之外的防御）。 */
  @Test
  void heartbeatCannotRestartAfterStop() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline baseline = seedBaseline(store, NOW);
    long invocationId = seedInvocation(store, baseline, request(), NOW);
    ClaimedWork claimed = claim(store, invocationId, NOW);
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(store, newScheduler(), LEASE_CONFIG, Clock.systemUTC(), () -> {});
    assertTrue(heartbeat.start(claimed));
    heartbeat.stop();
    assertFalse(heartbeat.start(claimed));
  }

  /** ModelProcessorConfig：checkpointFlushInterval 与 fallback delay 必须为正且至少 1ms。 */
  @Test
  void processorConfigValidatesDurations() {
    Fixture fixture = fixture();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelProcessorConfig(LEASE_CONFIG, Duration.ZERO, NO_RETRY, FALLBACK_DELAY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelProcessorConfig(
                LEASE_CONFIG, Duration.ofNanos(500), NO_RETRY, FALLBACK_DELAY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelProcessorConfig(LEASE_CONFIG, CHECKPOINT_INTERVAL, NO_RETRY, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelProcessorConfig(
                LEASE_CONFIG, CHECKPOINT_INTERVAL, NO_RETRY, Duration.ofNanos(500)));
    new ModelProcessorConfig(LEASE_CONFIG, CHECKPOINT_INTERVAL, NO_RETRY, FALLBACK_DELAY);
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store, fixture.gateway, fixture.sink, null, fixture.clock, newScheduler()));
  }

  /** ProcessorLeaseConfig：interval 必须为正且严格小于 leaseDuration。 */
  @Test
  void processorLeaseConfigValidatesIntervals() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofNanos(500)));
    new ProcessorLeaseConfig(Duration.ofSeconds(1), Duration.ofMillis(500));
  }

  // ---------------------------------------------------------------------------------------------
  // registry / cancel / close / heartbeat
  // ---------------------------------------------------------------------------------------------

  /** cancel 只关闭本地执行（handle + heartbeat），不反写任何 durable 状态。 */
  @Test
  void cancelStopsLocalExecutionWithoutDurableWrite() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    assertTrue(fixture.processor.cancel(fixture.invocationId));
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .leaseToken());
    listener.onEvent(new ProviderStreamEvent.TextDelta("late"));
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertNull(model(fixture.store, fixture.invocationId).streamCheckpoint());
    assertTrue(fixture.sink.events.isEmpty());

    assertFalse(fixture.processor.cancel(fixture.invocationId));
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.cancel(0));
  }

  /** close 取消全部本地 execution；durable 行保持 RUNNING 等待 lease 恢复。 */
  @Test
  void closeCancelsAllLocalExecutions() {
    Fixture fixture = fixture();
    Baseline secondBaseline = seedBaseline(fixture.store, NOW);
    long secondInvocationId = seedInvocation(fixture.store, secondBaseline, request(), NOW);
    FakeHandle firstHandle = new FakeHandle();
    FakeHandle secondHandle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(firstHandle));
    fixture.gateway.queue(new ModelGateway.Started(secondHandle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, secondInvocationId, NOW)));

    fixture.processor.close();

    assertFalse(fixture.processor.hasActiveExecution());
    assertTrue(firstHandle.isCancelled());
    assertTrue(secondHandle.isCancelled());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(ModelInvocationStatus.RUNNING, model(fixture.store, secondInvocationId).status());
  }

  /** heartbeat 只 renew 当前 Work lease；lease 丢失后立即关 gate / cancel handle，不反写 durable。 */
  @Test
  void heartbeatRenewsLeaseAndStopsWhenOwnershipIsLost() throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Baseline baseline = seedBaseline(store, now);
    long invocationId = seedInvocation(store, baseline, request(), now);
    FakeGateway gateway = new FakeGateway();
    RecordingSink sink = new RecordingSink();
    FakeHandle handle = new FakeHandle();
    ModelProcessor processor =
        new ModelProcessor(
            store,
            gateway,
            sink,
            new ModelProcessorConfig(
                new ProcessorLeaseConfig(Duration.ofMillis(400), Duration.ofMillis(100)),
                CHECKPOINT_INTERVAL,
                NO_RETRY,
                FALLBACK_DELAY),
            Clock.systemUTC(),
            newScheduler());
    gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, now, "token-" + invocationId, now.plusMillis(400)))
            .orElseThrow();

    assertEquals(ProcessResult.STARTED, processor.process(claimed));

    awaitTrue(
        () ->
            work(store, new WorkTarget(WorkTargetType.MODEL, invocationId))
                .leaseUntil()
                .isAfter(claimed.leaseUntil()),
        Duration.ofSeconds(2));
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.deleteWork(new WorkTarget(WorkTargetType.MODEL, invocationId));
          return null;
        });
    awaitTrue(handle::isCancelled, Duration.ofSeconds(2));

    assertFalse(processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.RUNNING, model(store, invocationId).status());
  }

  /** scheduler 拒绝 heartbeat 提交：视为无法维持 lease，bounce 为 RESCHEDULED 且不调用 Gateway。 */
  @Test
  void heartbeatSchedulerRejectionBouncesWithoutGatewayCall() {
    Fixture fixture = fixture();
    ScheduledExecutorService dead = newScheduler();
    dead.shutdownNow();
    ModelProcessor deadProcessor =
        new ModelProcessor(
            fixture.store,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(LEASE_CONFIG, CHECKPOINT_INTERVAL, NO_RETRY, FALLBACK_DELAY),
            fixture.clock,
            dead);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  // ---------------------------------------------------------------------------------------------
  // lost ownership at prepare
  // ---------------------------------------------------------------------------------------------

  /** claim lease 已过期：typed LOST_OWNERSHIP，无任何 mutation。 */
  @Test
  void expiredClaimIsLostOwnershipWithoutMutation() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(0, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals("token-" + fixture.invocationId, modelWork.leaseToken());
  }

  /** Stop deleteWork 后：claim 行缺失同样 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void deletedWorkClaimIsLostOwnershipWithoutMutation() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(0, fixture.gateway.startCalls);
  }

  // ---------------------------------------------------------------------------------------------
  // fixture / helpers
  // ---------------------------------------------------------------------------------------------

  private void deleteModelWork(Fixture fixture) {
    deleteModelWork(fixture, fixture.invocationId);
  }

  private void deleteModelWork(Fixture fixture, long invocationId) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          tx.deleteWork(new WorkTarget(WorkTargetType.MODEL, invocationId));
          return null;
        });
  }

  private void replaceModelWork(Fixture fixture) {
    replaceModelWork(fixture, fixture.invocationId);
  }

  private void replaceModelWork(Fixture fixture, long invocationId) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          WorkTarget target = new WorkTarget(WorkTargetType.MODEL, invocationId);
          tx.deleteWork(target);
          tx.requestWork(target, NOW);
          return null;
        });
  }

  private void requestModelWork(Fixture fixture) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, fixture.invocationId), NOW);
          return null;
        });
  }

  private Fixture fixture() {
    return fixture(NO_RETRY, request());
  }

  private Fixture fixture(InvocationRetryPolicy retryPolicy) {
    return fixture(retryPolicy, request());
  }

  private Fixture fixture(InvocationRetryPolicy retryPolicy, ModelInvocationRequest request) {
    return new Fixture(retryPolicy, request, newScheduler());
  }

  private Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ModelInvocationRequest request,
      ScheduledExecutorService scheduler) {
    return new Fixture(retryPolicy, request, scheduler);
  }

  private final class Fixture {
    final MutableClock clock = new MutableClock(NOW);
    final InMemoryHarnessStore store = new InMemoryHarnessStore();
    final FakeGateway gateway = new FakeGateway();
    final RecordingSink sink = new RecordingSink();
    final ScheduledExecutorService scheduler;
    final ModelInvocationRequest request;
    final Baseline baseline;
    final long invocationId;
    final ModelProcessor processor;

    Fixture(InvocationRetryPolicy retryPolicy, ModelInvocationRequest request) {
      this(retryPolicy, request, newScheduler());
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelInvocationRequest request,
        ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
      this.request = request;
      this.baseline = seedBaseline(store, NOW);
      this.invocationId = seedInvocation(store, baseline, request, NOW);
      this.processor =
          new ModelProcessor(
              store,
              gateway,
              sink,
              new ModelProcessorConfig(
                  LEASE_CONFIG, CHECKPOINT_INTERVAL, retryPolicy, FALLBACK_DELAY),
              clock,
              scheduler);
    }
  }

  private record Baseline(long sessionId, long rootEntryId, long turnStartEntryId, long threadId) {}

  private static Baseline seedBaseline(InMemoryHarnessStore store, Instant now) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, now));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, branchSettings()),
                  now.plusMillis(1)));
          tx.insertThread(new ThreadState(threadId, turnStartEntryId, false, 1, 0, now, now));
          return new Baseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  private static long seedInvocation(
      InMemoryHarnessStore store, Baseline baseline, ModelInvocationRequest request, Instant now) {
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          long id = tx.nextId();
          tx.insertModelInvocation(
              new ModelInvocation(
                  id,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  request,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  now,
                  now));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), now);
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id), now);
          return id;
        });
  }

  private static ClaimedWork claim(InMemoryHarnessStore store, long invocationId, Instant now) {
    return claim(store, invocationId, now, "token-" + invocationId);
  }

  private static ClaimedWork claim(
      InMemoryHarnessStore store, long invocationId, Instant now, String token) {
    return store
        .transaction(tx -> tx.claimNextWork(WorkTargetType.MODEL, now, token, now.plusSeconds(60)))
        .orElseThrow();
  }

  private static void transition(
      InMemoryHarnessStore store,
      long invocationId,
      Function<ModelInvocation, ModelInvocation> transition) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
          tx.updateModelInvocation(transition.apply(model));
          return null;
        });
  }

  private static ModelInvocation model(InMemoryHarnessStore store, long invocationId) {
    return store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
  }

  private static ThreadState thread(InMemoryHarnessStore store, long threadId) {
    return store.transaction(tx -> tx.findThread(threadId)).orElseThrow();
  }

  private static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target)).orElse(null);
  }

  private static List<ProviderStreamEvent> deltas(RecordingSink sink) {
    return sink.events.stream()
        .filter(event -> event instanceof RealtimeEvent.ModelDelta)
        .map(event -> (RealtimeEvent.ModelDelta) event)
        .map(RealtimeEvent.ModelDelta::delta)
        .toList();
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    fail("condition not met within " + timeout);
  }

  private static ModelInvocationRequest request() {
    return new ModelInvocationRequest(
        ENV_ID, providerRequest(List.of()), List.of(), List.of(), false);
  }

  private static ModelInvocationRequest requestWithTool() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "bash",
            "1.0",
            ToolType.PLATFORM,
            "run bash commands",
            null,
            new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolBinding binding = new ToolBinding(descriptor, ToolType.PLATFORM, null);
    ProviderRequest provider =
        providerRequest(List.of(new ProviderToolDefinition("bash", "run bash commands", "{}")));
    return new ModelInvocationRequest(ENV_ID, provider, List.of(binding), List.of(), false);
  }

  private static ProviderRequest providerRequest(List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        tools,
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static ProviderResponse response(String text, ProviderStopReason stopReason) {
    return new ProviderResponse(
        text, null, List.of(), stopReason, usage(), cost(), "req-1", null, null);
  }

  private static ProviderResponse toolResponse(String text, ProviderToolCall call) {
    return new ProviderResponse(
        text,
        null,
        List.of(call),
        ProviderStopReason.TOOL_CALLS,
        usage(),
        cost(),
        "req-1",
        null,
        null);
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

  private static EntryPayload userMessagePayload() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  private static EntryPayload assistantPayload() {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("assistant reply"))),
        new AssistantMessageMetadata(ProviderStopReason.COMPLETED, usage(), cost()),
        null);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings(
        ENV_ID, "agent", new ModelSelection("provider", "model", "v1"), "low", List.of());
  }

  static final class FakeGateway implements ModelGateway {
    private static final Object NULL_START = new Object();
    final LinkedList<Object> results = new LinkedList<>();
    final List<Execution> executions = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<Long, Listener> listeners = new ConcurrentHashMap<>();
    volatile Consumer<Listener> beforeReturn;
    volatile int startCalls;

    void queue(Object result) {
      results.add(result);
    }

    /** queue 一次返回 null 的 start（契约违反场景）。 */
    void queueNullStart() {
      results.add(NULL_START);
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      startCalls++;
      executions.add(execution);
      listeners.put(execution.invocationId(), listener);
      Object result = results.poll();
      if (result == null) {
        throw new IllegalStateException("no queued gateway result");
      }
      Consumer<Listener> hook = beforeReturn;
      if (hook != null) {
        hook.accept(listener);
      }
      if (result == NULL_START) {
        return null;
      }
      if (result instanceof RuntimeException failure) {
        throw failure;
      }
      return (StartResult) result;
    }

    Listener listener(long invocationId) {
      return listeners.get(invocationId);
    }
  }

  static class FakeHandle implements ModelGateway.Handle {
    final AtomicInteger cancels = new AtomicInteger();

    @Override
    public void cancel() {
      cancels.incrementAndGet();
    }

    boolean isCancelled() {
      return cancels.get() > 0;
    }
  }

  static final class RecordingSink implements RealtimeEventSink {
    final List<RealtimeEvent> events = new CopyOnWriteArrayList<>();
    volatile RuntimeException failure;

    @Override
    public void append(RealtimeEvent event) {
      RuntimeException current = failure;
      if (current != null) {
        throw current;
      }
      events.add(event);
    }
  }

  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
