package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptFailurePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
  private static final EnvironmentId ENV_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
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
  // 准入
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
                        WorkTargetType.THREAD, NOW, "thread-token", Duration.ofSeconds(60)))
            .orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(threadClaim));
  }

  /**
   * READY admission Started：短事务 DISPATCHING + version+1，随后 markRunning + version+1 并保留 heartbeat。
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.startCalls);
    ModelGateway.Execution execution = fixture.gateway.executions.get(0);
    assertEquals(fixture.invocationId, execution.invocationId());
    assertEquals(1, execution.proposedAttempt());
    assertEquals(fixture.requestSpec.providerType(), execution.providerType());
    assertEquals(materialized(fixture), execution.request());
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
          listener.onSucceeded(response("answer", GenerationStopReason.COMPLETE));
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
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Busy：DISPATCHING -&gt; READY + version+1，按 retryAfter reschedule，attempt 不变。 */
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
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
    fixture.gateway.queue(new RuntimeException("gateway down"));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  /** Rejected：rejectDispatch FAILED + version+1 + THREAD wake + complete MODEL Work，attempt 不变。 */
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 历史物化遇到被移除的工具与 opaque replay：放弃不兼容 replay、按合法语义降级投影，网关收到该请求并 start。 */
  @Test
  void incompatibleOpaqueReplayProjectsSemanticFallbackThenStarts() {
    ReplayHistory history = seedReplayHistory();
    InMemoryHarnessStore store = history.store();
    UserBasis basis = history.basis();
    UUID invocationId = history.invocationId();
    FakeGateway gateway = new FakeGateway();
    gateway.queue(new ModelGateway.Started(new FakeHandle()));
    ModelProcessor processor = projectionProcessor(store, gateway);

    assertEquals(ProcessResult.STARTED, processor.process(claim(store, invocationId, NOW)));

    assertEquals(1, gateway.startCalls);
    ModelGateway.Execution execution = gateway.executions.get(0);
    assertEquals(invocationId, execution.invocationId());
    assertEquals(1, execution.proposedAttempt());
    // 降级语义投影：保留 assistant 文本，原 bash 调用并入 USER 上下文并逐字保留 arguments，不再有 native replay。
    List<ProviderMessage> messages = execution.request().messages();
    assertEquals(3, messages.size());
    assertEquals(
        List.of(ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT, ProviderMessageRole.USER),
        messages.stream().map(ProviderMessage::role).toList());
    assertEquals("answer", ((ProviderTextBlock) messages.get(1).contents().get(0)).text());
    String fallback = ((ProviderTextBlock) messages.get(2).contents().get(0)).text();
    assertTrue(fallback.startsWith("Previous context:"));
    assertTrue(fallback.contains("{\"secret\":1}"));
    assertTrue(messages.stream().noneMatch(ProviderMessage::hasReplayState));

    ModelInvocation running = model(store, invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, running.status());
    assertEquals(1, running.attempt());
    assertNull(running.error());
    assertEquals(3, thread(store, basis.threadId()).version());
    assertEquals(
        1, work(store, new WorkTarget(WorkTargetType.THREAD, basis.threadId())).wakeVersion());
    assertTrue(processor.hasActiveExecution());
  }

  /** 加载历史后丢失 claim：即使随后投影要放弃 replay，也不得把别人的执行标记为 FAILED，零 gateway 启动、零 mutation。 */
  @Test
  void ownershipLossDuringMaterializationDoesNotWriteOrDispatch() {
    ReplayHistory history = seedReplayHistory();
    InMemoryHarnessStore store = history.store();
    WorkTarget target = new WorkTarget(WorkTargetType.MODEL, history.invocationId());
    FakeGateway gateway = new FakeGateway();
    HarnessStore hooked =
        afterEntryPathLoad(
            store,
            () ->
                store.transaction(
                    tx -> {
                      tx.lockThread(history.basis().threadId()).orElseThrow();
                      tx.deleteWork(target);
                      return null;
                    }));
    ModelProcessor processor = projectionProcessor(hooked, gateway);

    assertEquals(
        ProcessResult.LOST_OWNERSHIP, processor.process(claim(store, history.invocationId(), NOW)));
    assertEquals(ModelInvocationStatus.DISPATCHING, model(store, history.invocationId()).status());
    assertEquals(0, model(store, history.invocationId()).attempt());
    assertEquals(2, thread(store, history.basis().threadId()).version());
    assertNull(work(store, target));
    assertEquals(0, gateway.startCalls);
    assertFalse(processor.hasActiveExecution());
  }

  /** Store 物化阶段报告无效历史：归类 INVALID_REQUEST 并清理本地执行，而非毒化工作循环。 */
  @Test
  void invalidHistoryDuringMaterializationRejectsWithoutGateway() {
    Fixture fixture = fixture();
    HarnessStore hooked =
        afterEntryPathLoad(
            fixture.store,
            () -> {
              throw new IllegalStateException("invalid entry path");
            });
    ModelProcessor processor = projectionProcessor(hooked, fixture.gateway);

    assertEquals(
        ProcessResult.TERMINATED,
        processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failed.error().kind());
    assertEquals("invalid entry path", failed.error().message());
    assertEquals(0, failed.attempt());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(processor.hasActiveExecution());
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
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
   * 新 claim 遇到旧 lease 过期的 RUNNING：接入既有重试策略记录一次失败 attempt 后回到 READY 并按策略延迟重排 MODEL Work，attempt
   * 保持已确认值、旧 partial 只留作尝试审计，绝不调用 Gateway，也不 request THREAD wake。
   */
  @Test
  void staleRunningLeaseRecoveryRetriesWithExistingPolicy() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    StreamCheckpoint checkpoint = new StreamCheckpoint(1, 7, "durable partial", "thinking");
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.checkpoint(checkpoint, NOW.plusSeconds(1)));
    claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertNull(model.error());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(1, model.failedAttempts().getFirst().attempt());
    assertEquals(7L, model.failedAttempts().getFirst().sequence());
    assertEquals("durable partial", model.failedAttempts().getFirst().text());
    assertEquals(ProviderErrorKind.TRANSIENT, model.failedAttempts().getFirst().error().kind());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(fixture.clock.instant().plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * 新 claim 遇到旧 lease 过期的 DISPATCHING：把可能已发出的启动计为一个已消耗 attempt（attempt+1）后按策略回到 READY， 绝不重放
   * Provider。DISPATCHING 无 checkpoint，审计快照的 sequence/text 为空。
   */
  @Test
  void staleDispatchingLeaseRecoveryChargesUnconfirmedAttemptAndRetries() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(1, model.failedAttempts().getFirst().attempt());
    assertEquals(0L, model.failedAttempts().getFirst().sequence());
    assertEquals("", model.failedAttempts().getFirst().text());
    assertEquals(ProviderErrorKind.TRANSIENT, model.failedAttempts().getFirst().error().kind());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(fixture.clock.instant().plusSeconds(5), modelWork.availableAt());
  }

  /**
   * 新 claim 遇到旧 lease 过期的 RUNNING 且重试预算耗尽：FAILED 保留已确认 attempt，唤醒 THREAD 并 complete MODEL
   * Work，明确报告失败而不是无限重试。
   */
  @Test
  void staleRunningLeaseRecoveryExhaustedTerminatesFailed() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.failedAttempts().isEmpty());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /**
   * 新 claim 遇到旧 lease 过期的 DISPATCHING 且重试预算耗尽：未确认的启动仍计为 attempt+1 后 FAILED，唤醒 THREAD 并 complete
   * MODEL Work。
   */
  @Test
  void staleDispatchingLeaseRecoveryExhaustedTerminatesFailed() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.failedAttempts().isEmpty());
    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** 恢复预检后丢失 claim：重试与预算耗尽两条路径的 tentative 写入都必须回滚，不能推进 attempt 或唤醒 THREAD。 */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void recoveryLosingClaimAfterPrecheckRollsBackAllMutations(int maxRetries) {
    InvocationRetryPolicy policy =
        new InvocationRetryPolicy(
            maxRetries,
            InvocationRetryBackoffStrategy.FIXED,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
    Fixture fixture = fixture(policy);
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId, NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, fixture.clock.instant());
    ModelInvocation beforeModel = model(fixture.store, fixture.invocationId);
    ThreadState beforeThread = thread(fixture.store, fixture.baseline.threadId());
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId());
    Work beforeWake = work(fixture.store, threadTarget);
    AtomicInteger transactions = new AtomicInteger();
    HarnessStore interleaved =
        (HarnessStore)
            Proxy.newProxyInstance(
                HarnessStore.class.getClassLoader(),
                new Class<?>[] {HarnessStore.class},
                (proxy, method, args) -> {
                  Object result = invokeUnchecked(fixture.store, method, args);
                  if ("transaction".equals(method.getName())
                      && transactions.incrementAndGet() == 1) {
                    // 确定性交错：claimOwned 已读到有效租约，在真正恢复事务前由另一个事务删除 Work。
                    deleteModelWork(fixture);
                  }
                  return result;
                });
    try (ModelProcessor processor =
        new ModelProcessor(
            interleaved,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> policy, FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            Runnable::run)) {
      assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(recovered));
      assertEquals(beforeModel, model(fixture.store, fixture.invocationId));
      assertEquals(beforeThread, thread(fixture.store, fixture.baseline.threadId()));
      assertEquals(beforeWake, work(fixture.store, threadTarget));
      assertNull(work(fixture.store, recovered.target()));
      assertEquals(0, fixture.gateway.startCalls);
      assertFalse(processor.hasActiveExecution());
    }
  }

  /** terminal 行且 resultEntryId 仍 null：确保 THREAD wake 后 complete，不重复 bump version。 */
  @Test
  void terminalCleanupWakesThreadWithoutVersionBump() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.beginDispatch(NOW));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.resultEntryId());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
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
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    UUID assistantEntryId =
        fixture.store.transaction(
            tx -> {
              UUID userId = tx.nextId();
              UUID id = tx.nextId();
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

    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  // ---------------------------------------------------------------------------------------------
  // stream / terminal 回调
  // ---------------------------------------------------------------------------------------------

  /** checkpoint 只累积 text/thinking；tool-call fragment 只推进 sequence，绝不进入 checkpoint。 */
  @Test
  void checkpointKeepsPrefixAndExcludesToolFragments() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool(), StreamFlushConfig.IMMEDIATE);
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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(toolResponse("hello", new ProviderToolCall("call_1", "bash", "{}")));

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
    failing.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        failing.processor.process(claim(failing.store, failing.invocationId, NOW)));
    failing
        .gateway
        .listener(failing.invocationId)
        .onSucceeded(toolResponse("hello", new ProviderToolCall("call_1", "bash", "{}")));

    ModelInvocation failedRender = model(failing.store, failing.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, failedRender.status());
    assertNull(failedRender.result().toolCalls().getFirst().historyAction());
  }

  /** 意图：Tool-owned renderer 阻塞时不得占用 execution monitor；close/lease-loss 必须仍可立即 abandon。 */
  @Test
  void toolHistoryRenderingDoesNotHoldExecutionMonitor() throws Exception {
    CountDownLatch rendererEntered = new CountDownLatch(1);
    CountDownLatch releaseRenderer = new CountDownLatch(1);
    Fixture fixture =
        new Fixture(
            NO_RETRY,
            requestWithTool(),
            (binding, call) -> {
              rendererEntered.countDown();
              try {
                if (!releaseRenderer.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("renderer release timed out");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("renderer interrupted", interrupted);
              }
              return Optional.of("run bash");
            });
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    Thread terminalThread =
        new Thread(
            () ->
                fixture
                    .gateway
                    .listener(fixture.invocationId)
                    .onSucceeded(
                        toolResponse("hello", new ProviderToolCall("call_1", "bash", "{}"))),
            "blocked-history-renderer");
    terminalThread.start();
    assertTrue(rendererEntered.await(2, TimeUnit.SECONDS));

    Thread closeThread = new Thread(fixture.processor::close, "model-processor-close");
    closeThread.start();
    closeThread.join(2_000);
    boolean closeCompletedWithoutRenderer = !closeThread.isAlive();

    releaseRenderer.countDown();
    terminalThread.join(2_000);
    closeThread.join(2_000);

    assertTrue(closeCompletedWithoutRenderer, "close must not wait for the history renderer");
    assertFalse(terminalThread.isAlive());
    assertFalse(closeThread.isAlive());
    assertTrue(handle.isCancelled());
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
            StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("truncated partial"));
    listener.onSucceeded(
        new ProviderCompletion(
            response("truncated partial", GenerationStopReason.LENGTH), sampleReplayState()));

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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    ClaimedWork second = claim(fixture.store, fixture.invocationId, fixture.clock.instant());
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ModelGateway.Listener secondListener = fixture.gateway.listener(fixture.invocationId);
    secondListener.onSucceeded(
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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    listener.onEvent(new ProviderStreamEvent.TextDelta("partial one"));
    listener.onSucceeded(
        new ProviderCompletion(
            response("partial one", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    ClaimedWork second = claim(fixture.store, fixture.invocationId, fixture.clock.instant());
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ModelGateway.Listener secondListener = fixture.gateway.listener(fixture.invocationId);
    secondListener.onEvent(new ProviderStreamEvent.TextDelta("partial two"));
    secondListener.onSucceeded(
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
                    .loadEntryPath(
                        tx.findThread(fixture.baseline.threadId()).orElseThrow().headEntryId())
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
    Fixture fixture = compactionFixture(NO_RETRY, StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(
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
            StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(
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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            claim(fixture.store, fixture.invocationId, fixture.clock.instant())));
    ModelGateway.Listener secondListener = fixture.gateway.listener(fixture.invocationId);
    secondListener.onSucceeded(
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
            StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    fixture
        .gateway
        .listener(fixture.invocationId)
        .onSucceeded(
            new ProviderCompletion(
                response(" \n", GenerationStopReason.COMPLETE), sampleReplayState()));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            claim(fixture.store, fixture.invocationId, fixture.clock.instant())));
    fixture
        .gateway
        .listener(fixture.invocationId)
        .onSucceeded(
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
            StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(
        new ProviderCompletion(
            response("filtered text", GenerationStopReason.FILTERED), sampleReplayState()));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(1, failed.attempt());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, failed.error().kind());
    assertEquals(
        "compaction model response was filtered and cannot be used as a summary",
        failed.error().message());
    assertEquals(1, fixture.gateway.startCalls, "a filtered result must never be replayed");
    assertNull(failed.resultEntryId());
  }

  /** 配置了 tools 的压缩子合法 tool 回合（COMPLETE + tool calls）按普通 Runtime 推进，且不再抑制 replay 与实时 delta 发布。 */
  @Test
  void compactionChildToolTurnProceedsLikeOrdinaryRuntimeAndPublishesDeltas() {
    Fixture fixture = compactionFixture(NO_RETRY, StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("calling tool"));
    listener.onSucceeded(
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
    Fixture fixture = compactionFixture(NO_RETRY, StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("truncated call"));
    listener.onSucceeded(
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
                    .loadEntryPath(
                        tx.findThread(fixture.baseline.threadId()).orElseThrow().headEntryId())
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
    truncated.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        truncated.processor.process(claim(truncated.store, truncated.invocationId, NOW)));
    truncated
        .gateway
        .listener(truncated.invocationId)
        .onSucceeded(
            new ProviderCompletion(
                response("partial answer", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(truncated.store, truncated.invocationId).status(),
        "ordinary LENGTH semantics must not change");

    Fixture empty = fixture();
    empty.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        empty.processor.process(claim(empty.store, empty.invocationId, NOW)));
    empty
        .gateway
        .listener(empty.invocationId)
        .onSucceeded(
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

    // TASK Join 的 child 与普通 Thread 一样，不是压缩子：截断 final 不做摘要完整性校验。
    Fixture taskChild = fixture();
    seedJoin(taskChild.store, taskChild.baseline.threadId(), JoinPurpose.TASK);
    taskChild.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        taskChild.processor.process(claim(taskChild.store, taskChild.invocationId, NOW)));
    taskChild
        .gateway
        .listener(taskChild.invocationId)
        .onSucceeded(
            new ProviderCompletion(
                response("task partial", GenerationStopReason.LENGTH), sampleReplayState()));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED,
        model(taskChild.store, taskChild.invocationId).status(),
        "only COMPACTION purpose joins make a thread a compaction child");
  }

  /**
   * 验证 ModelExecution 完成路径：普通非 compaction 的 COMPLETE 与 LENGTH 在无 toolCallDiagnostics 时持久化
   * replayState。
   */
  @Test
  void modelExecutionPersistsReplayStateOnCompleteAndLengthWithoutDiagnostics() {
    // 1. COMPLETE 场景
    Fixture fixtureComplete = fixture();
    fixtureComplete.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixtureComplete.processor.process(
            claim(fixtureComplete.store, fixtureComplete.invocationId, NOW)));
    ModelGateway.Listener listenerComplete =
        fixtureComplete.gateway.listener(fixtureComplete.invocationId);

    ProviderReplayState replayState1 = sampleReplayState();
    listenerComplete.onSucceeded(
        new ProviderCompletion(response("hello", GenerationStopReason.COMPLETE), replayState1));

    ModelInvocation modelComplete = model(fixtureComplete.store, fixtureComplete.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, modelComplete.status());
    assertEquals(
        replayState1,
        modelComplete.providerReplayState(),
        "COMPLETE without diagnostics must persist replayState");

    // 2. LENGTH 场景
    Fixture fixtureLength = fixture();
    fixtureLength.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixtureLength.processor.process(
            claim(fixtureLength.store, fixtureLength.invocationId, NOW)));
    ModelGateway.Listener listenerLength =
        fixtureLength.gateway.listener(fixtureLength.invocationId);

    ProviderReplayState replayState2 = sampleReplayState();
    listenerLength.onSucceeded(
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
    fixtureFiltered.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixtureFiltered.processor.process(
            claim(fixtureFiltered.store, fixtureFiltered.invocationId, NOW)));
    ModelGateway.Listener listenerFiltered =
        fixtureFiltered.gateway.listener(fixtureFiltered.invocationId);

    listenerFiltered.onSucceeded(
        new ProviderCompletion(
            response("filtered output", GenerationStopReason.FILTERED), sampleReplayState()));

    ModelInvocation modelFiltered = model(fixtureFiltered.store, fixtureFiltered.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, modelFiltered.status());
    assertNull(modelFiltered.providerReplayState(), "FILTERED must not persist replayState");

    // 2. 含 incomplete tool diagnostics 场景
    Fixture fixtureDiag = fixture();
    fixtureDiag.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixtureDiag.processor.process(claim(fixtureDiag.store, fixtureDiag.invocationId, NOW)));
    ModelGateway.Listener listenerDiag = fixtureDiag.gateway.listener(fixtureDiag.invocationId);

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

    listenerDiag.onSucceeded(new ProviderCompletion(responseWithDiagnostics, sampleReplayState()));

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
    fixtureFailed.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixtureFailed.processor.process(
            claim(fixtureFailed.store, fixtureFailed.invocationId, NOW)));
    ModelGateway.Listener listenerFailed =
        fixtureFailed.gateway.listener(fixtureFailed.invocationId);

    listenerFailed.onFailed(
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "terminal failure"));

    ModelInvocation modelFailed = model(fixtureFailed.store, fixtureFailed.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, modelFailed.status());
    assertNull(modelFailed.providerReplayState(), "Failed invocation must not retain replayState");

    // 2. UNKNOWN 终态
    Fixture fixtureUnknown = fixture();
    fixtureUnknown.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixtureUnknown.processor.process(
            claim(fixtureUnknown.store, fixtureUnknown.invocationId, NOW)));
    ModelGateway.Listener listenerUnknown =
        fixtureUnknown.gateway.listener(fixtureUnknown.invocationId);

    listenerUnknown.onUnknown(
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
  void successTerminalWakesThreadAndPublishesTrailingGap() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("ans"));
    listener.onSucceeded(response("answer", GenerationStopReason.COMPLETE));

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
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * 真实故障回归：RUNNING 后本地 wall clock 回拨到 invocation 创建时间之前，terminal 仍必须落地并 complete MODEL Work，不能遗留给
   * lease-expiry recovery。
   */
  @Test
  void successTerminalSurvivesClockRollbackAndCompletesModelWork() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    fixture.clock.set(NOW.minusSeconds(1));

    fixture
        .gateway
        .listener(fixture.invocationId)
        .onSucceeded(response("answer", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(NOW, model.updatedAt());
    assertEquals(NOW, thread(fixture.store, fixture.baseline.threadId()).updatedAt());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
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
        .onSucceeded(response("answer", GenerationStopReason.COMPLETE));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /**
   * TRANSIENT + 策略允许：RUNNING -&gt; READY（retryReady）+ version+1 + 按策略延迟 reschedule，不请求 THREAD wake。
   */
  @Test
  void transientFailureRetriesWithPolicyDelay() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            requestSpec(),
            StreamFlushConfig.IMMEDIATE);
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("partial"));
    listener.onEvent(new ProviderStreamEvent.TextDelta("-tail"));
    listener.onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unavailable"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(1, model.failedAttempts().getFirst().attempt());
    assertEquals(2, model.failedAttempts().getFirst().sequence());
    assertEquals("partial-tail", model.failedAttempts().getFirst().text());
    assertEquals("", model.failedAttempts().getFirst().thinking());
    assertEquals(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unavailable"),
        model.failedAttempts().getFirst().error());
    assertEquals(NOW, model.failedAttempts().getFirst().failedAt());
    assertEquals(NOW.plusSeconds(5), model.failedAttempts().getFirst().retryAt());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(2, deltas(fixture.sink).size());
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
    assertEquals(1, model.failedAttempts().size());
    assertEquals("first", model.failedAttempts().getFirst().error().message());
    assertEquals(NOW.plusSeconds(5), model.failedAttempts().getFirst().retryAt());
    assertEquals(6, thread(fixture.store, fixture.baseline.threadId()).version());
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

    listener.onSucceeded(response("answer", GenerationStopReason.COMPLETE));
    listener.onSucceeded(response("second", GenerationStopReason.COMPLETE));
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

    listener.onSucceeded(response("answer", GenerationStopReason.COMPLETE));
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

  /**
   * retry 的持久化 failedAt 以当前已锁定 durable 事实为下界（leaseNow / thread.updatedAt / model.updatedAt / 上一
   * retryAt 的最大值）：attempt 执行期间 clock 回拨时，第二次 MODEL_ATTEMPT_FAILURE 的 failedAt 仍单调推进， terminal
   * materialization 的 failedAttempts 不变量与 Entry 链时间顺序都能通过，retry -&gt; terminal -&gt; THREAD
   * materialization 全链路一次跑通。
   */
  @Test
  void retryFailedAtUsesDurableFloorAcrossClockRollbackAndMaterializesMonotonically() {
    InvocationRetryPolicy retryPolicy =
        new InvocationRetryPolicy(
            2, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
    MutableClock clock = new MutableClock(NOW);
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    FakeGateway gateway = new FakeGateway();
    RecordingSink sink = new RecordingSink();
    ModelProcessor modelProcessor =
        new ModelProcessor(
            store,
            gateway,
            sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> retryPolicy, FALLBACK_DELAY),
            clock,
            newScheduler(),
            Runnable::run,
            Runnable::run);
    // 与生产一致的最小链：ROOT + TURN_START(INPUT) + USER，Thread head 与 invocation requestHead 指向 USER。
    UserBasis baseline = seedUserBasis(store);
    UUID invocationId = seedUserBasisInvocation(store, baseline, requestSpec(), NOW);

    // attempt 1：N 启动，N+2 失败 -> retry（failure#1 failedAt=N+2 / retryAt=N+7）。
    gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(ProcessResult.STARTED, modelProcessor.process(claim(store, invocationId, NOW)));
    clock.advance(Duration.ofSeconds(2));
    gateway
        .listener(invocationId)
        .onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "first"));
    ModelInvocation afterFirst = model(store, invocationId);
    assertEquals(ModelInvocationStatus.READY, afterFirst.status());
    assertEquals(1, afterFirst.failedAttempts().size());
    assertEquals(NOW.plusSeconds(2), afterFirst.failedAttempts().getFirst().failedAt());
    assertEquals(NOW.plusSeconds(7), afterFirst.failedAttempts().getFirst().retryAt());
    // Work 的 in-memory due 等于业务 retryAt：剩余 delay 基于同一 JVM 采样 leaseNow 计算，业务语义未被改动。
    assertEquals(
        NOW.plusSeconds(7),
        work(store, new WorkTarget(WorkTargetType.MODEL, invocationId)).availableAt());

    // attempt 2：N+10 启动（durable 抬升到 N+10），随后把 clock 回拨到 N+4（低于上一 retryAt）再失败。
    clock.advance(Duration.ofSeconds(8));
    gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED, modelProcessor.process(claim(store, invocationId, clock.instant())));
    clock.set(NOW.plusSeconds(4));
    gateway
        .listener(invocationId)
        .onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "second"));

    ModelInvocation afterSecond = model(store, invocationId);
    assertEquals(ModelInvocationStatus.READY, afterSecond.status(), "回拨后的 retry 必须仍然成功落地");
    assertEquals(2, afterSecond.failedAttempts().size());
    assertEquals(
        NOW.plusSeconds(10),
        afterSecond.failedAttempts().get(1).failedAt(),
        "failedAt 必须抬升到 N+10（thread/model durable 下界，而非回拨的 N+4）");
    assertEquals(NOW.plusSeconds(15), afterSecond.failedAttempts().get(1).retryAt());
    // clock 回拨时仍以 durable 下界为准：Work due = 回拨后 leaseNow + (retryAt - leaseNow) = 业务 retryAt。
    assertEquals(
        NOW.plusSeconds(15),
        work(store, new WorkTarget(WorkTargetType.MODEL, invocationId)).availableAt());

    // attempt 3：N+16 启动，retry 预算耗尽 -> FAILED terminal + THREAD wake。
    clock.advance(Duration.ofSeconds(12));
    gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED, modelProcessor.process(claim(store, invocationId, clock.instant())));
    gateway
        .listener(invocationId)
        .onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "third"));
    assertEquals(ModelInvocationStatus.FAILED, model(store, invocationId).status());
    assertNull(work(store, new WorkTarget(WorkTargetType.MODEL, invocationId)));

    // 同一 store 上的 ThreadProcessor 消费 THREAD claim：MODEL_ATTEMPT_FAILURE 按单调 failedAt 物化、
    // TURN_END 关闭 turn、Model 行严格校验后物理删除。
    ThreadProcessor threadProcessor =
        new ThreadProcessor(
            store,
            (threadId, path, preparation) -> null,
            new ThreadProcessorConfig(
                LEASE_CONFIG, FALLBACK_DELAY, () -> new CompactionConfig(20_000)),
            clock,
            newScheduler(),
            Runnable::run);
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(
            ThreadProcessorTestSupport.claimThreadWork(
                store, baseline.threadId(), clock.instant())));

    ThreadState thread = thread(store, baseline.threadId());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    assertEquals(
        7, path.entries().size(), "ROOT+TURN_START+USER+2xFAILURE+AssistantError+TURN_END");
    assertEquals(NOW, path.entries().get(0).createdAt());
    assertEquals(NOW.plusMillis(1), path.entries().get(1).createdAt());
    assertEquals(NOW.plusMillis(2), path.entries().get(2).createdAt());
    assertTrue(path.entries().get(2).payload() instanceof MessagePayload);
    ModelAttemptFailurePayload firstFailure =
        (ModelAttemptFailurePayload) path.entries().get(3).payload();
    assertEquals(1, firstFailure.attempt().attempt());
    assertEquals(NOW.plusSeconds(2), path.entries().get(3).createdAt());
    ModelAttemptFailurePayload secondFailure =
        (ModelAttemptFailurePayload) path.entries().get(4).payload();
    assertEquals(2, secondFailure.attempt().attempt());
    assertEquals(NOW.plusSeconds(10), path.entries().get(4).createdAt());
    assertTrue(
        path.entries().get(3).createdAt().isBefore(path.entries().get(4).createdAt()),
        "MODEL_ATTEMPT_FAILURE 时间必须单调推进");
    assertEquals(NOW.plusSeconds(16), path.entries().get(5).createdAt());
    assertTrue(path.entries().get(5).payload() instanceof AssistantErrorPayload);
    assertEquals(NOW.plusSeconds(16), path.entries().get(6).createdAt());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(6).payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(TurnEndReason.TURN_FAILED, end.reason());
    assertEquals(
        path.entries().get(6).id(),
        thread(store, baseline.threadId()).headEntryId(),
        "TURN_END 必须关闭 turn 并成为新 head");
    assertEquals(10, thread(store, baseline.threadId()).version());
    assertNull(
        store.transaction(tx -> tx.findModelInvocation(invocationId).orElse(null)),
        "closed turn 的 ModelInvocation 必须被物理删除");
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

          @Override
          public void activate() {}
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
                .onSucceeded(response("answer", GenerationStopReason.COMPLETE));
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

    listener.onSucceeded(response("answer", GenerationStopReason.COMPLETE));

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
    fixture.sink.failure = new IllegalStateException("notification channel unavailable");

    listener.onEvent(new ProviderStreamEvent.TextDelta("ans"));
    listener.onSucceeded(response("answer", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals("answer", model.result().text());
    assertNotNull(model.streamCheckpoint());
    assertEquals("answer", model.streamCheckpoint().text());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    fixture.sink.failure = null;
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** 非法终态（stream 冲突 / canonical 不变量违反）：转 FAILED(INVALID_RESPONSE)（重试策略允许时先 retry）。 */
  @Test
  void conflictingFinalResponseFailsWithInvalidResponse() {
    Fixture fixture = fixture(StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    listener.onSucceeded(response("world", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
    assertNotNull(model.streamCheckpoint());
    assertEquals(1, model.streamCheckpoint().sequence());
    assertEquals("hello", model.streamCheckpoint().text());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertEquals(1, deltas(fixture.sink).size());
  }

  /**
   * 未声明（unknown）工具调用不再是 canonical 校验错误：validator 不做 binding 可见性校验，响应合法并 SUCCEEDED； unknown tool 由
   * {@link ModelResponsePlanner} 在 Thread 边界规划为 FAILED(UNKNOWN_TOOL) 槽位（见 ThreadProcessor 测试）。
   */
  @Test
  void undeclaredToolCallIsAcceptedByModelProcessor() {
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
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
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

  /** 事件管线异常（tool-call identity 冲突）：转 FAILED(INVALID_RESPONSE)，已发布 delta 保持。 */
  @Test
  void conflictingToolIdentityEventFailsWithInvalidResponse() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool(), StreamFlushConfig.IMMEDIATE);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
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

    listener.onSucceeded(response("", GenerationStopReason.COMPLETE));

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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("think"));
    listener.onSucceeded(response("", GenerationStopReason.COMPLETE));

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

  /** final response 遗漏 streamed 过的 tool call：reconcile 拒绝并 FAILED(INVALID_RESPONSE)。 */
  @Test
  void finalResponseOmittingStreamedToolCallFails() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{}"));
    listener.onSucceeded(response("", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
  }

  /** 重复 tool call id：canonical 校验拒绝并 FAILED(INVALID_RESPONSE)。 */
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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    listener.onSucceeded(toolResponse("", new ProviderToolCall("call_1", "bash", "not-json")));

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
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);

    // 第一次 canonical 校验失败：INVALID_RESPONSE 走 retry 路径（不是 FAILED）。
    listener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    listener.onSucceeded(response("world", GenerationStopReason.COMPLETE));

    ModelInvocation retried = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(1, retried.attempt());
    assertEquals(1, retried.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, retried.failedAttempts().getFirst().error().kind());
    assertEquals(
        NOW.plusSeconds(5),
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .availableAt());

    // 重试耗尽：第二次 INVALID_RESPONSE 转 FAILED。
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW.plusSeconds(5))));
    ModelGateway.Listener retriedListener = fixture.gateway.listener(fixture.invocationId);
    retriedListener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    retriedListener.onSucceeded(response("world", GenerationStopReason.COMPLETE));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failed.error().kind());
  }

  /**
   * 同一 JVM 内旧 lease 过期后新 claim（不同 token）：supersede 本地旧 execution，按既有重试策略恢复 durable RUNNING（保留已确认
   * attempt / 取消旧 handle / 绝不重放 Provider）；无重试预算时如实终止 FAILED。
   */
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
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(1, fixture.gateway.startCalls);
    assertTrue(handle.isCancelled());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** start 抛异常且期间 ownership 丢失：同样返回 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void startExceptionWithLostWorkReturnsLostOwnership() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = listener -> deleteModelWork(fixture);
    fixture.gateway.queue(new RuntimeException("gateway down"));

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
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
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
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
  // Handle fencing / duplicate admission / null start / lease margin / close
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
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * abandon 在 activation 开始前获胜（本地 cancel 抢占 markRunning 事务，markRunning 仍 commit）：durable RUNNING
   * 保持，但 {@code handle.activate()} 绝不调用。
   */
  @Test
  void cancelWinningDuringMarkRunningSkipsActivateAndCancelsHandle() throws Exception {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    CountDownLatch inStart = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        listener -> {
          inStart.countDown();
          try {
            proceed.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(handle));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread =
        new Thread(
            () ->
                result.set(
                    fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW))));
    processThread.start();
    assertTrue(inStart.await(5, TimeUnit.SECONDS), "process must be inside gateway.start");

    // 测试线程抢 store monitor：让 process 的 markRunning 事务阻塞在其上。
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch releaseMonitor = new CountDownLatch(1);
    Thread holdStore =
        new Thread(
            () ->
                fixture.store.transaction(
                    ignored -> {
                      holding.countDown();
                      try {
                        releaseMonitor.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holdStore.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS));

    proceed.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (processThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(
        System.nanoTime() < deadline, "process must block on the store monitor (markRunning)");
    assertTrue(fixture.processor.cancel(fixture.invocationId));

    releaseMonitor.countDown();
    holdStore.join(5000);
    processThread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(0, handle.activates.get(), "abandon 先获胜时 handle.activate 绝不调用");
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  /**
   * abandon 在 activation 期间获胜（{@code handle.activate()} 已开始）：abandon 必须推迟 handle cancel 直到 activate
   * 返回，外部调用序恒为 ACTIVATE -&gt; CANCEL，cancel 绝不丢失。
   */
  @Test
  void cancelWinningDuringActivationDefersCancelUntilActivateReturns() throws Exception {
    Fixture fixture = fixture();
    List<String> order = new CopyOnWriteArrayList<>();
    CountDownLatch inActivate = new CountDownLatch(1);
    CountDownLatch releaseActivate = new CountDownLatch(1);
    ModelGateway.Handle blockingHandle =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {
            order.add("cancel");
          }

          @Override
          public void activate() {
            order.add("activate");
            inActivate.countDown();
            try {
              releaseActivate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
          }
        };
    fixture.gateway.queue(new ModelGateway.Started(blockingHandle));

    AtomicReference<ProcessResult> result = new AtomicReference<>();
    Thread processThread =
        new Thread(
            () ->
                result.set(
                    fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW))));
    processThread.start();
    assertTrue(inActivate.await(5, TimeUnit.SECONDS), "handle.activate must have begun");

    assertTrue(fixture.processor.cancel(fixture.invocationId));
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(List.of("activate"), order, "activation 中的 abandon 必须推迟 cancel");

    releaseActivate.countDown();
    processThread.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, result.get());
    assertEquals(List.of("activate", "cancel"), order, "外部调用序必须保持 ACTIVATE -> CANCEL");
    assertFalse(fixture.processor.hasActiveExecution());
    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
  }

  /** 顺序重复投递同一 claim（同 token）：LOST no-op，不 cancel、不 mutation，合法 RUNNING 不受影响。 */
  @Test
  void duplicateSequentialProcessWithSameClaimIsLostNoOp() {
    Fixture fixture = fixture();
    FakeHandle handle = new FakeHandle();
    fixture.gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId, NOW);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    long version = thread(fixture.store, fixture.baseline.threadId()).version();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(version, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 每个 safe delta 在发布 realtime 前先持久化完整 checkpoint；tool fragment 永不 checkpoint。 */
  @Test
  void everySafeDeltaPersistsCheckpointBeforeRealtimePublication() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool(), StreamFlushConfig.IMMEDIATE);
    HarnessRuntime runtime =
        new HarnessRuntime(
            fixture.store,
            fixture.clock,
            (threadId, path, preparation) -> null,
            () -> CompactionConfig.DEFAULT);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, NOW)));
    ModelGateway.Listener listener = fixture.gateway.listener(fixture.invocationId);
    long runningVersion = thread(fixture.store, fixture.baseline.threadId()).version();
    assertNull(runtime.getThreadSnapshot(fixture.baseline.threadId()).model().streamCheckpoint());

    listener.onEvent(new ProviderStreamEvent.TextDelta("a"));
    var snapshotAfterFirstDelta = runtime.getThreadSnapshot(fixture.baseline.threadId());
    assertEquals(runningVersion, snapshotAfterFirstDelta.thread().version());
    assertEquals(1, snapshotAfterFirstDelta.model().streamCheckpoint().sequence());
    assertEquals("a", snapshotAfterFirstDelta.model().streamCheckpoint().text());

    listener.onEvent(new ProviderStreamEvent.TextDelta("b"));
    assertEquals(2, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());
    assertEquals("ab", model(fixture.store, fixture.invocationId).streamCheckpoint().text());
    assertEquals(2, deltas(fixture.sink).size());

    listener.onEvent(new ProviderStreamEvent.TextDelta("c"));
    assertEquals(3, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());
    assertEquals("abc", model(fixture.store, fixture.invocationId).streamCheckpoint().text());

    // tool fragment 永不 checkpoint；final 需携带该 tool call 才能 reconcile。
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    assertEquals(3, model(fixture.store, fixture.invocationId).streamCheckpoint().sequence());

    // terminal success 总是 flush 完整 safe snapshot（tool 的 name/args 差异聚合成 1 个 gap delta）。
    listener.onSucceeded(toolResponse("abc", new ProviderToolCall("call_1", "bash", "{}")));
    ModelInvocation terminal = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, terminal.status());
    assertNotNull(terminal.streamCheckpoint());
    assertEquals("abc", terminal.streamCheckpoint().text());
    assertEquals(3, terminal.streamCheckpoint().sequence());
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
                    tx.claimNextWork(
                        WorkTargetType.MODEL, NOW, "near-expiry", Duration.ofSeconds(1)))
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
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
                            .onSucceeded(response("x", GenerationStopReason.COMPLETE)));
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
    AtomicReference<UUID> cancelId = new AtomicReference<>();
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
    Fixture fixture = fixture(NO_RETRY, requestSpec(), hooked);
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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * 并发不同 token：新 claim 已真实 owned（旧 lease 过期被 dispatcher 重新 claim）时通过 claimOwned 前置校验并抢占 guard
   * （replace 循环），supersede 旧本地 execution 后按 durable DISPATCHING 把未确认启动计为 attempt+1（无重试预算时 FAILED）；
   * 旧 process 的 Started handle 到达时被锁外 cancel。
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
    // 新 token 接管 durable DISPATCHING：把可能已发出的启动计为 attempt+1，无重试预算时如实终止 FAILED。
    assertEquals(ModelInvocationStatus.FAILED, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /**
   * stale-start fence：A（token-1）在 heartbeat 启动后暂停；lease 过期后由**另一实例**（模拟另一 JVM 的 processor， 本地
   * registry / guard 独立，A 的本地 abandoned 检查不可见）以 token-2 接管 durable DISPATCHING；A 恢复时 fence 校验
   * durable 已非 DISPATCHING → 立即 abandon 并 LOST，绝不调用 Gateway。
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
    Fixture fixtureA = fixture(NO_RETRY, requestSpec(), hooked);
    fixtureA.gateway.queue(new ModelGateway.Started(new FakeHandle())); // 不应被消费
    ClaimedWork claimedA = claim(fixtureA.store, fixtureA.invocationId, NOW, "token-1");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixtureA.processor.process(claimedA)));
    threadA.start();
    assertTrue(
        inHeartbeat.await(5, TimeUnit.SECONDS)); // A 已 putIfAbsent + prepare，暂停在 startHeartbeat

    // lease 过期，dispatcher 以 token-2 重新 claim；另一实例接管 durable DISPATCHING（跨实例，A 的 abandoned 检查不可见）。
    fixtureA.clock.advance(Duration.ofSeconds(61));
    replaceModelWork(fixtureA);
    ClaimedWork claimedB =
        claim(fixtureA.store, fixtureA.invocationId, fixtureA.clock.instant(), "token-2");
    ModelProcessor processorB =
        new ModelProcessor(
            fixtureA.store,
            new FakeGateway(),
            fixtureA.sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
            fixtureA.clock,
            newScheduler(),
            Runnable::run,
            Runnable::run);
    assertEquals(ProcessResult.TERMINATED, processorB.process(claimedB));

    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(0, fixtureA.gateway.startCalls, "stale start must never call the gateway");
    assertFalse(fixtureA.processor.hasActiveExecution());
    assertFalse(processorB.hasActiveExecution());
    ModelInvocation model = model(fixtureA.store, fixtureA.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(2, thread(fixtureA.store, fixtureA.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixtureA.store, new WorkTarget(WorkTargetType.THREAD, fixtureA.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixtureA.store, new WorkTarget(WorkTargetType.MODEL, fixtureA.invocationId)));
  }

  /**
   * materialize 完成后再做 start fence：Stop / recovery 在加载 EntryPath 之后删除 MODEL work 时，不得再调用 Gateway。
   */
  @Test
  void staleStartAfterMaterializationNeverCallsGateway() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));
    HarnessStore hooked = afterEntryPathLoad(fixture.store, () -> deleteModelWork(fixture));
    ModelProcessor processor =
        new ModelProcessor(
            hooked,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            Runnable::run);

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(0, fixture.gateway.startCalls, "post-materialization stale start must not start");
    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertFalse(processor.hasActiveExecution());
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
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
            fixture.clock,
            hooked,
            Runnable::run,
            Runnable::run);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle())); // 不应被消费

    assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claimed));

    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(
        ModelInvocationStatus.DISPATCHING, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
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
                tx ->
                    tx.claimNextWork(WorkTargetType.MODEL, NOW, "partial", Duration.ofSeconds(15)))
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
                tx -> tx.claimNextWork(WorkTargetType.MODEL, NOW, "exact", Duration.ofSeconds(30)))
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
    UUID invocationId = seedInvocation(store, baseline, requestSpec(), NOW);
    ClaimedWork claimed = claim(store, invocationId, NOW);
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(
            store, newScheduler(), Runnable::run, LEASE_CONFIG, Clock.systemUTC(), () -> {});
    assertTrue(heartbeat.start(claimed));
    heartbeat.stop();
    assertFalse(heartbeat.start(claimed));
  }

  /** ModelProcessorConfig：fallback delay 必须为正的整毫秒。 */
  @Test
  void processorConfigValidatesDurations() {
    Fixture fixture = fixture();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, Duration.ofNanos(500)));
    assertEquals(
        FALLBACK_DELAY,
        new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY)
            .dispatchBusyFallbackDelay());
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                null,
                fixture.clock,
                newScheduler(),
                Runnable::run,
                Runnable::run));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
                fixture.clock,
                newScheduler(),
                null,
                Runnable::run));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelProcessor(
                fixture.store,
                fixture.gateway,
                fixture.sink,
                new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
                fixture.clock,
                newScheduler(),
                Runnable::run,
                null));
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
  // registry / cancel / close / heartbeat（注册表 / 取消 / 关闭 / 心跳）
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
    assertThrows(NullPointerException.class, () -> fixture.processor.cancel(null));
  }

  /** close 取消全部本地 execution；durable 行保持 RUNNING 等待 lease 恢复。 */
  @Test
  void closeCancelsAllLocalExecutions() {
    Fixture fixture = fixture();
    Baseline secondBaseline = seedBaseline(fixture.store, NOW);
    UUID secondInvocationId = seedInvocation(fixture.store, secondBaseline, requestSpec(), NOW);
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
    UUID invocationId = seedInvocation(store, baseline, requestSpec(), now);
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
                () -> NO_RETRY,
                FALLBACK_DELAY),
            Clock.systemUTC(),
            newScheduler(),
            Runnable::run,
            Runnable::run);
    gateway.queue(new ModelGateway.Started(handle));
    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, now, "token-" + invocationId, Duration.ofMillis(400)))
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
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
            fixture.clock,
            dead,
            Runnable::run,
            Runnable::run);
    fixture.gateway.queue(new ModelGateway.Started(new FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(claim(fixture.store, fixture.invocationId, NOW)));

    assertEquals(0, fixture.gateway.startCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  // ---------------------------------------------------------------------------------------------
  // prepare 期间丢失 ownership
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
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
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
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.startCalls);
  }

  // ---------------------------------------------------------------------------------------------
  // fixture / 辅助方法
  // ---------------------------------------------------------------------------------------------

  /** 在一次 transaction 加载完不可变 EntryPath 并提交后执行 {@code afterLoad}，用于证明 materialize 之后的 start fence。 */
  private static HarnessStore afterEntryPathLoad(HarnessStore delegate, Runnable afterLoad) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if ("transaction".equals(method.getName()) && args != null && args.length == 1) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, ?> callback =
                    (Function<HarnessStore.Transaction, ?>) args[0];
                AtomicBoolean loaded = new AtomicBoolean();
                Object result =
                    delegate.transaction(
                        tx ->
                            callback.apply(
                                (HarnessStore.Transaction)
                                    Proxy.newProxyInstance(
                                        HarnessStore.Transaction.class.getClassLoader(),
                                        new Class<?>[] {HarnessStore.Transaction.class},
                                        (ignored, txMethod, txArgs) -> {
                                          Object value = invokeUnchecked(tx, txMethod, txArgs);
                                          if ("loadEntryPath".equals(txMethod.getName())) {
                                            loaded.set(true);
                                          }
                                          return value;
                                        })));
                if (loaded.get()) {
                  afterLoad.run();
                }
                return result;
              }
              return invokeUnchecked(delegate, method, args);
            });
  }

  private static Object invokeUnchecked(Object target, Method method, Object[] args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }

  private void deleteModelWork(Fixture fixture) {
    deleteModelWork(fixture, fixture.invocationId);
  }

  private void deleteModelWork(Fixture fixture, UUID invocationId) {
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

  private void replaceModelWork(Fixture fixture, UUID invocationId) {
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
    return fixture(NO_RETRY, requestSpec());
  }

  private Fixture fixture(InvocationRetryPolicy retryPolicy) {
    return fixture(retryPolicy, requestSpec());
  }

  private Fixture fixture(StreamFlushConfig flushConfig) {
    return new Fixture(NO_RETRY, requestSpec(), newScheduler(), false, flushConfig);
  }

  /** 压缩子 fixture：fixture Thread 是本 fixture 自己创建的 COMPACTION Join 的 child。 */
  private Fixture compactionFixture(InvocationRetryPolicy retryPolicy) {
    return compactionFixture(retryPolicy, StreamFlushConfig.DEFAULT);
  }

  private Fixture compactionFixture(
      InvocationRetryPolicy retryPolicy, StreamFlushConfig flushConfig) {
    return new Fixture(retryPolicy, requestSpec(), newScheduler(), true, flushConfig);
  }

  private Fixture fixture(InvocationRetryPolicy retryPolicy, ModelRequestSpec requestSpec) {
    return new Fixture(retryPolicy, requestSpec, newScheduler());
  }

  private Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec,
      StreamFlushConfig flushConfig) {
    return new Fixture(retryPolicy, requestSpec, newScheduler(), false, flushConfig);
  }

  private Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec,
      ScheduledExecutorService scheduler) {
    return new Fixture(retryPolicy, requestSpec, scheduler);
  }

  private final class Fixture {
    final MutableClock clock = new MutableClock(NOW);
    final InMemoryHarnessStore store = new InMemoryHarnessStore();
    final FakeGateway gateway = new FakeGateway();
    final RecordingSink sink = new RecordingSink();
    final ScheduledExecutorService scheduler;
    final ModelRequestSpec requestSpec;
    final Baseline baseline;
    final UUID invocationId;
    final ModelProcessor processor;

    Fixture(InvocationRetryPolicy retryPolicy, ModelRequestSpec requestSpec) {
      this(retryPolicy, requestSpec, newScheduler());
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler) {
      this(retryPolicy, requestSpec, scheduler, false, StreamFlushConfig.DEFAULT);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        boolean compactionChild,
        StreamFlushConfig flushConfig) {
      this(retryPolicy, requestSpec, scheduler, compactionChild, flushConfig, Runnable::run);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        boolean compactionChild,
        StreamFlushConfig flushConfig,
        Executor flushExecutor) {
      this(retryPolicy, requestSpec, scheduler, compactionChild, flushConfig, flushExecutor, null);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ToolHistoryActionResolver toolHistoryActionResolver) {
      this(
          retryPolicy,
          requestSpec,
          newScheduler(),
          false,
          StreamFlushConfig.DEFAULT,
          Runnable::run,
          toolHistoryActionResolver);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        boolean compactionChild,
        StreamFlushConfig flushConfig,
        Executor flushExecutor,
        ToolHistoryActionResolver toolHistoryActionResolver) {
      this.scheduler = scheduler;
      this.requestSpec = requestSpec;
      this.baseline = seedBaseline(store, NOW);
      if (compactionChild) {
        seedJoin(store, baseline.threadId(), JoinPurpose.COMPACTION);
      }
      this.invocationId = seedInvocation(store, baseline, requestSpec, NOW);
      this.processor =
          new ModelProcessor(
              store,
              gateway,
              sink,
              new ModelProcessorConfig(
                  LEASE_CONFIG,
                  () -> retryPolicy,
                  FALLBACK_DELAY,
                  flushConfig,
                  toolHistoryActionResolver),
              clock,
              scheduler,
              Runnable::run,
              flushExecutor);
    }
  }

  private record Baseline(UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID threadId) {}

  /** 带 USER 输入的 open turn 基线：Thread head 与 invocation requestHead 指向 USER。 */
  private record UserBasis(
      UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID userEntryId, UUID threadId) {}

  private record ReplayHistory(InMemoryHarnessStore store, UserBasis basis, UUID invocationId) {}

  /** 使用真实 Entry 与 Work 初始化含工具调用及 opaque replay 的待投影历史。 */
  private static ReplayHistory seedReplayHistory() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    UserBasis basis = seedUserBasis(store);
    UUID invocationId =
        store.transaction(
            tx -> {
              UUID assistantId = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      assistantId,
                      basis.sessionId(),
                      basis.userEntryId(),
                      new HistoryPayloadMapper()
                          .assistantPayload(
                              toolResponse(
                                  "answer",
                                  new ProviderToolCall("call-1", "bash", "{\"secret\":1}")),
                              requestWithTool().toolBindings()),
                      NOW.plusMillis(3),
                      sampleReplayState()));
              tx.updateThread(
                  tx.lockThread(basis.threadId()).orElseThrow().advanceHead(assistantId, NOW));
              UUID id = tx.nextId();
              tx.insertModelInvocation(
                  new ModelInvocation(
                      id,
                      basis.threadId(),
                      basis.turnStartEntryId(),
                      assistantId,
                      requestSpec(),
                      ModelInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      null,
                      List.of(),
                      NOW,
                      NOW));
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, basis.threadId()), NOW);
              tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id), NOW);
              return id;
            });
    return new ReplayHistory(store, basis, invocationId);
  }

  private ModelProcessor projectionProcessor(HarnessStore store, FakeGateway gateway) {
    return new ModelProcessor(
        store,
        gateway,
        new RecordingSink(),
        new ModelProcessorConfig(
            LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, StreamFlushConfig.DEFAULT, null),
        Clock.fixed(NOW, ZoneOffset.UTC),
        newScheduler(),
        Runnable::run,
        Runnable::run);
  }

  /** Session + ROOT + TURN_START(INPUT) + USER；Thread head 指向 USER。 */
  private static UserBasis seedUserBasis(HarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), threadId, 100_000, 16_384, null),
                  NOW.plusMillis(1)));
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  sessionId,
                  turnStartEntryId,
                  userMessagePayload(),
                  NOW.plusMillis(2)));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(threadId, sessionId, userEntryId, NOW));
          return new UserBasis(sessionId, rootEntryId, turnStartEntryId, userEntryId, threadId);
        });
  }

  /** 以 USER 为 requestHead 插入 READY ModelInvocation + THREAD / MODEL Work（materialization 全链测试用）。 */
  private static UUID seedUserBasisInvocation(
      InMemoryHarnessStore store, UserBasis baseline, ModelRequestSpec requestSpec, Instant now) {
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID id = tx.nextId();
          tx.insertModelInvocation(
              new ModelInvocation(
                  id,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.userEntryId(),
                  requestSpec,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  now,
                  now));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), now);
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id), now);
          return id;
        });
  }

  private static Baseline seedBaseline(HarnessStore store, Instant now) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, now));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
          UUID turnStartEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), threadId, 100_000, 16_384, null),
                  now.plusMillis(1)));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(threadId, sessionId, turnStartEntryId, now));
          return new Baseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  /**
   * 给已存在的 Thread 插入一个 purpose 指定的未结算 Join（连同父 Thread 与子 source command），构造真实的 child 侧身份事实。
   *
   * <p>压缩子身份只能由 COMPACTION purpose 的未结算 Join 决定：TASK Join 的 child 与无 Join 的普通 Thread 都不是压缩子。
   */
  private static void seedJoin(
      InMemoryHarnessStore store, UUID childThreadId, JoinPurpose purpose) {
    store.transaction(
        tx -> {
          tx.lockThread(childThreadId);
          UUID parentThreadId = tx.nextId();
          UUID parentSessionId = tx.nextId();
          UUID parentRootEntryId = tx.nextId();
          tx.insertSession(new Session(parentSessionId, "parent-" + parentSessionId, NOW));
          tx.insertEntry(
              new Entry(
                  parentRootEntryId,
                  parentSessionId,
                  null,
                  new RootPayload(branchSettings()),
                  NOW));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(
                  parentThreadId, parentSessionId, parentRootEntryId, NOW));
          UUID commandId = tx.nextId();
          CustomMessageCommandPayload payload =
              new CustomMessageCommandPayload(AgentMessage.user("compaction instruction"));
          String requestHash = ThreadCommandPayloadJsonCodec.requestHash(payload);
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      childThreadId, 1L, payload, commandId, requestHash, null, null, null, NOW)));
          tx.insertJoin(
              new ThreadJoin(
                  commandId,
                  requestHash,
                  parentThreadId,
                  childThreadId,
                  1L,
                  "compaction",
                  null,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  purpose,
                  null));
          return null;
        });
  }

  /** 已提交增量中的文本 delta（顺序即发布顺序）。 */
  private static List<String> deltaTexts(RecordingSink sink) {
    return deltas(sink).stream()
        .filter(event -> event instanceof ProviderStreamEvent.TextDelta)
        .map(event -> ((ProviderStreamEvent.TextDelta) event).text())
        .toList();
  }

  private static UUID seedInvocation(
      HarnessStore store, Baseline baseline, ModelRequestSpec requestSpec, Instant now) {
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID id = tx.nextId();
          tx.insertModelInvocation(
              new ModelInvocation(
                  id,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  requestSpec,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  now,
                  now));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), now);
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id), now);
          return id;
        });
  }

  private static ClaimedWork claim(HarnessStore store, UUID invocationId, Instant now) {
    return claim(store, invocationId, now, "token-" + invocationId);
  }

  private static ClaimedWork claim(
      HarnessStore store, UUID invocationId, Instant now, String token) {
    return store
        .transaction(
            tx -> tx.claimNextWork(WorkTargetType.MODEL, now, token, Duration.ofSeconds(60)))
        .orElseThrow();
  }

  private static void transition(
      HarnessStore store,
      UUID invocationId,
      Function<ModelInvocation, ModelInvocation> transition) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
          tx.updateModelInvocation(transition.apply(model));
          return null;
        });
  }

  private static ModelInvocation model(HarnessStore store, UUID invocationId) {
    return store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
  }

  private static ProviderRequest materialized(Fixture fixture) {
    ModelInvocation invocation = model(fixture.store, fixture.invocationId);
    EntryPath path =
        fixture.store.transaction(tx -> tx.loadEntryPath(invocation.requestHeadEntryId()));
    return new ModelRequestMaterializer().materialize(path, fixture.requestSpec);
  }

  private static ThreadState thread(HarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId)).orElseThrow();
  }

  private static Work work(HarnessStore store, WorkTarget target) {
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

  private static ModelRequestSpec requestSpec() {
    return spec(List.of());
  }

  private static ModelRequestSpec requestWithTool() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "bash",
            "run bash commands",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "bash", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
    return spec(List.of(binding));
  }

  private static ModelRequestSpec spec(List<ToolBinding> bindings) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        bindings,
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderRequest providerRequest(List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        tools,
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ProviderResponse response(String text, GenerationStopReason stopReason) {
    return new ProviderResponse(text, null, List.of(), stopReason, usage(), "req-1", null, null);
  }

  private static ProviderResponse toolResponse(String text, ProviderToolCall call) {
    return new ProviderResponse(
        text, null, List.of(call), GenerationStopReason.COMPLETE, usage(), "req-1", null, null);
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
    // live attached 场景的 assistant 由 fixture 的 canonical request/response 经 mapper 派生，与 strict
    // attach 校验一致；
    // 历史（无 active model）场景复用同一 payload 不影响 attach 校验。
    return new HistoryPayloadMapper()
        .assistantPayload(
            response("ok", GenerationStopReason.COMPLETE), requestWithTool().toolBindings());
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
  }

  private static ProviderReplayState sampleReplayState() {
    return new ProviderReplayState(
        ProviderReplayFormat.OPENAI_RESPONSES,
        new ProviderReplayAffinity(
            ProviderType.OPENAI_RESPONSES, "provider-a", UUID.randomUUID(), "model-a"),
        JsonNodeFactory.instance.objectNode().put("token", 42));
  }

  static final class FakeGateway implements ModelGateway {
    private static final Object NULL_START = new Object();
    final LinkedList<Object> results = new LinkedList<>();
    final List<Execution> executions = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<UUID, Listener> listeners = new ConcurrentHashMap<>();
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

    Listener listener(UUID invocationId) {
      return listeners.get(invocationId);
    }
  }

  static class FakeHandle implements ModelGateway.Handle {
    final AtomicInteger cancels = new AtomicInteger();
    final AtomicInteger activates = new AtomicInteger();

    @Override
    public void cancel() {
      cancels.incrementAndGet();
    }

    @Override
    public void activate() {
      activates.incrementAndGet();
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

    void set(Instant now) {
      this.now = now;
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
