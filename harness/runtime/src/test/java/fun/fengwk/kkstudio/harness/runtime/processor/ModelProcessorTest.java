package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.JsonNodeFactory;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
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
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
 * <p>覆盖 READY 准入（Admitted / Busy / 抛异常 / Rejected）、RUNNING 先于运行持久化的陈旧启动围栏、stale lease 恢复、stream
 * checkpoint 前缀与 tool fragment 排除、success terminal + THREAD wake、transient retry 与 retry
 * exhausted、UNKNOWN、 deleteWork 后的 terminal、new wake 保留、sink 失败隔离、cancel / close 与 wrong target。
 */
class ModelProcessorTest {
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final EnvironmentId ENV_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(7);

  /** 逐事件即时推送、1ms 后 checkpoint 的测试配置。 */
  private static final ModelStreamConfig IMMEDIATE_STREAM =
      new ModelStreamConfig(Duration.ofMillis(1), 1, 1, Duration.ofMillis(1));

  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
    executors.forEach(ExecutorService::shutdownNow);
  }

  /** 每个任务一个虚拟线程的执行 executor，与生产装配一致。 */
  private ExecutorService newExecutionExecutor() {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    executors.add(executor);
    return executor;
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
                    tx.claimNextWork(WorkTargetType.THREAD, "thread-token", Duration.ofSeconds(60)))
            .orElseThrow();
    assertThrows(IllegalArgumentException.class, () -> fixture.processor.process(threadClaim));
  }

  /** READY admission Admitted：一次短事务 READY -&gt; RUNNING + version+1，保留 heartbeat 与本地执行线程。 */
  @Test
  void admissionStartedMarksRunningAndKeepsLocalExecution() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    ModelGateway.Execution execution = fixture.gateway.executions.get(0);
    assertEquals(fixture.invocationId, execution.invocationId());
    assertEquals(1, execution.proposedAttempt());
    assertEquals(fixture.requestSpec.providerType(), execution.providerType());
    assertEquals(materialized(fixture), execution.request());
    assertFalse(fixture.gateway.controlled(fixture.invocationId).interrupted.get());
    assertTrue(fixture.processor.hasActiveExecution());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals("token-" + fixture.invocationId, modelWork.leaseToken());
  }

  /** Busy：肯定未发出请求，invocation 保持 READY 且不写 Thread，按 retryAfter reschedule，attempt 不变。 */
  @Test
  void busyAdmissionBouncesToReadyWithoutAdvancingAttempt() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new ModelGateway.Busy(Duration.ofSeconds(5)));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(0, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** admit 抛异常（契约：肯定未接受）：同样只重排 Work，使用构造注入的 fallback delay。 */
  @Test
  void startExceptionBouncesWithFallbackDelay() {
    Fixture fixture = fixture();
    fixture.gateway.queue(new RuntimeException("gateway down"));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  /** Rejected：READY -&gt; FAILED + version+1 + THREAD wake + complete MODEL Work，attempt 不变。 */
  @Test
  void rejectedAdmissionFailsInvocationAndWakesThread() {
    Fixture fixture = fixture();
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model rejected");
    fixture.gateway.queue(new ModelGateway.Rejected(error));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(0, model.attempt());
    assertEquals(error, model.error());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
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
    gateway.queueControlled();
    ModelProcessor processor = projectionProcessor(store, gateway);

    assertEquals(ProcessResult.STARTED, processor.process(claim(store, invocationId)));

    assertEquals(1, gateway.admitCalls);
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
    assertEquals(2, thread(store, basis.threadId()).version());
    assertEquals(
        1, work(store, new WorkTarget(WorkTargetType.THREAD, basis.threadId())).wakeVersion());
    assertTrue(processor.hasActiveExecution());
  }

  /** 加载历史后丢失 claim：即使随后投影要放弃 replay，也不得把别人的执行标记为 FAILED；准入名额被释放，零运行、零 mutation。 */
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
    gateway.queueControlled();

    assertEquals(
        ProcessResult.LOST_OWNERSHIP, processor.process(claim(store, history.invocationId())));
    assertEquals(ModelInvocationStatus.READY, model(store, history.invocationId()).status());
    assertEquals(0, model(store, history.invocationId()).attempt());
    assertEquals(1, thread(store, history.basis().threadId()).version());
    assertNull(work(store, target));
    assertEquals(0, gateway.runCalls.get());
    assertTrue(gateway.runs.get(history.invocationId()).closedWithoutRun.get());
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
        ProcessResult.TERMINATED, processor.process(claim(fixture.store, fixture.invocationId)));
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
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(processor.hasActiveExecution());
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
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    StreamCheckpoint checkpoint = new StreamCheckpoint(1, 7, "durable partial", "thinking");
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.checkpoint(checkpoint, NOW.plusSeconds(1)));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);

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
    assertEquals(0, fixture.gateway.admitCalls);
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
   * 新 claim 遇到旧 lease 过期的 RUNNING 且重试预算耗尽：FAILED 保留已确认 attempt，唤醒 THREAD 并 complete MODEL
   * Work，明确报告失败而不是无限重试。
   */
  @Test
  void staleRunningLeaseRecoveryExhaustedTerminatesFailed() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertTrue(model.failedAttempts().isEmpty());
    assertEquals(0, fixture.gateway.admitCalls);
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
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);
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
      assertEquals(0, fixture.gateway.admitCalls);
      assertFalse(processor.hasActiveExecution());
    }
  }

  /** terminal 行且 resultEntryId 仍 null：确保 THREAD wake 后 complete，不重复 bump version。 */
  @Test
  void terminalCleanupWakesThreadWithoutVersionBump() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

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
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** terminal 行且 resultEntryId 已链接：只 complete MODEL Work，不再请求 THREAD wake。 */
  @Test
  void terminalCleanupSkipsThreadWakeWhenResultEntryIsLinked() {
    Fixture fixture = fixture();
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
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

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
            ModelHttpErrorPolicyProvider.DEFAULT);
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
            ModelHttpErrorPolicyProvider.DEFAULT);
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
  void successTerminalWakesThreadAndPublishesTrailingGap() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("ans"));
    listener.succeed(response("answer", GenerationStopReason.COMPLETE));

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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 成功终态在 THREAD Work 已被消费后仍按升序重建 THREAD Work，不能卡在 RUNNING。 */
  @Test
  void successTerminalRecreatesConsumedThreadWork() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
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
        .controlled(fixture.invocationId)
        .succeed(response("answer", GenerationStopReason.COMPLETE));

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
            IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("partial"));
    listener.event(new ProviderStreamEvent.TextDelta("-tail"));
    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unavailable"));

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
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
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
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "first"));
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);

    secondListener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "second"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(1, model.failedAttempts().size());
    assertEquals("first", model.failedAttempts().getFirst().error().message());
    assertEquals(NOW.plusSeconds(5), model.failedAttempts().getFirst().retryAt());
    assertEquals(4, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** 白名单命中的 HTTP 失败进入既有重试预算，且诊断 kind 不被改写（429 仍是 BILLING）。 */
  @Test
  void httpWhitelistedStatusRetriesKeepingDiagnosticKind() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(408, 429)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.BILLING, "quota exceeded", 429));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.failedAttempts().size());
    ModelInvocationError recorded = model.failedAttempts().getFirst().error();
    assertEquals(ProviderErrorKind.BILLING, recorded.kind());
    assertEquals(429, recorded.httpStatus());
  }

  /** 未列入白名单的 HTTP 失败立即失败，不消耗重试预算、不新增 failed attempt。 */
  @Test
  void httpUnlistedStatusFailsImmediately() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(429)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "server", 500));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertTrue(model.failedAttempts().isEmpty());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(500, model.error().httpStatus());
  }

  /** 空白名单禁用 HTTP 重试：即使还有预算也直接失败。 */
  @Test
  void emptyWhitelistDisablesHttpRetry() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of()));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "overloaded", 503));

    assertEquals(ModelInvocationStatus.FAILED, model(fixture.store, fixture.invocationId).status());
  }

  /** 非预期 3xx 保留实际状态并明确失败，不能丢掉状态后当作破损响应自动重试。 */
  @Test
  void unexpectedRedirectStatusFailsInsteadOfBrokenResponseRetry() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(408, 429, 500, 502, 503, 504)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(
        new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, "unexpected redirect", 301));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(301, model.error().httpStatus());
    assertTrue(model.failedAttempts().isEmpty());
  }

  /** 重试判定使用当前 Invocation 冻结的 Provider 身份读取白名单，且现读最新策略快照。 */
  @Test
  void httpRetryReadsFrozenProviderIdentityAndLivePolicy() {
    AtomicReference<List<Integer>> live = new AtomicReference<>(List.of(503));
    AtomicReference<String> observedProvider = new AtomicReference<>();
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> {
              observedProvider.set(providerName);
              return new ModelHttpErrorPolicy(live.get());
            });
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "down", 503));

    assertEquals("provider", observedProvider.get());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    // 现读快照：把白名单改为空后，下一次失败立即终止，不重写已派发的 attempt。
    live.set(List.of());
    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);

    secondListener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "down", 503));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(1, model.failedAttempts().size());
  }

  /** 白名单命中但重试预算耗尽：记录一次 failed attempt 后明确失败，不因诊断 kind 被领域层拒绝而挂起。 */
  @Test
  void httpWhitelistedRetryExhaustionTerminatesFailed() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(401, 429, 500, 502, 503, 504)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "auth", 401));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);

    secondListener.fail(new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "auth", 401));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.AUTHENTICATION, model.failedAttempts().getFirst().error().kind());
    assertEquals(401, model.failedAttempts().getFirst().error().httpStatus());
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
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.unknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
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
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late UNKNOWN / 不可重试失败：terminal 事务 ownership 校验失败，保持 RUNNING。 */
  @Test
  void unknownAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.unknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 事件管线失败发生在 ownership 已丢失之后：terminal 事务同样失败，仅本地关闭。 */
  @Test
  void eventFailureAfterWorkDeletionIsLost() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 首个事件即非法且 ownership 已丢失：terminal 事务失败，仅本地关闭，不写 durable 终态。 */
  @Test
  void nullEventAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.event(null);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** CANCELLED 失败且 ownership 已丢失：CANCELLED terminal 事务失败，仅本地关闭。 */
  @Test
  void cancelledAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.fail(new ModelInvocationError(ProviderErrorKind.CANCELLED, "stopped"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
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
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.CANCELLED, "stopped"));

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

  /** 处理期间到达新 wake：terminal complete 只清 lease 保留行，新 wake 保持可见。 */
  @Test
  void newWakeSurvivesTerminalCompletion() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    requestModelWork(fixture);

    listener.succeed(response("answer", GenerationStopReason.COMPLETE));

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
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    fixture.sink.failure = new IllegalStateException("notification channel unavailable");

    listener.event(new ProviderStreamEvent.TextDelta("ans"));
    listener.succeed(response("answer", GenerationStopReason.COMPLETE));

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
    Fixture fixture = fixture(IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("hello"));
    listener.succeed(response("world", GenerationStopReason.COMPLETE));

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
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
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
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
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
   */
  @Test
  void sameJvmRecoverySupersedesStaleLocalExecution() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId, "token-old")));
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId, "token-new");

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(recovered));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 恢复路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void recoveryWithDeletedWorkIsLost() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** terminal cleanup 路径中 work 行已被删除：LOST_OWNERSHIP，无 mutation。 */
  @Test
  void terminalCleanupWithDeletedWorkIsLost() {
    Fixture fixture = fixture();
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    transition(
        fixture.store,
        fixture.invocationId,
        model -> model.succeed(response("ok", GenerationStopReason.COMPLETE), NOW));
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  // ---------------------------------------------------------------------------------------------
  // Handle fencing / duplicate admission / null start / lease margin / close
  // ---------------------------------------------------------------------------------------------

  /** 顺序重复投递同一 claim（同 token）：LOST no-op，不 cancel、不 mutation，合法 RUNNING 不受影响。 */
  @Test
  void duplicateSequentialProcessWithSameClaimIsLostNoOp() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    long version = thread(fixture.store, fixture.baseline.threadId()).version();
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(version, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    assertTrue(fixture.processor.hasActiveExecution());
  }

  /** 首个 process 的执行线程仍在准入时重复投递同一 claim：后到者 LOST no-op，绝不二次准入或双发 Provider。 */
  @Test
  void duplicateConcurrentProcessWithSameClaimIsLostNoOp() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch inAdmit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        execution -> {
          inAdmit.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        };
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));
    assertTrue(inAdmit.await(5, TimeUnit.SECONDS));
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    release.countDown();
    ControlledRun run = fixture.gateway.controlled(fixture.invocationId);
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertFalse(run.interrupted.get());
  }

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

  /** claim lease 剩余不足（首次 heartbeat 前会过期）：prepare READY 时立即 renew 出完整 margin。 */
  @Test
  void nearExpiryClaimGetsFullLeaseMarginBeforeDispatch() {
    Fixture fixture = fixture();
    ClaimedWork claimed =
        fixture
            .store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.MODEL, "near-expiry", Duration.ofSeconds(1)))
            .orElseThrow();
    fixture.gateway.queueControlled();

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
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    fixture.gateway.queueControlled();

    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(claimed.leaseUntil(), modelWork.leaseUntil());
  }

  /** close 后拒绝新 process；close 幂等且不 shutdown 注入的 scheduler。 */
  @Test
  void closedProcessorRejectsProcessAndCloseIsIdempotent() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
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
  // 最终并发收口：claimOwned 前置校验 / close-race / 准入竞态
  // ---------------------------------------------------------------------------------------------

  /**
   * active RUNNING + 伪造不同 token 的 claim：Work-only 前置校验（claimOwned）必须先于 guard 替换 / supersede—— 伪造
   * token 与 stored leaseToken 不匹配 → LOST no-op，合法 active execution 不被 cancel、durable 不变。
   */
  @Test
  void forgedDifferentTokenWhileActiveRunningIsLostWithoutCancelling() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimed));

    ClaimedWork forged =
        new ClaimedWork(
            claimed.target(), claimed.claimedWakeVersion(), "forged-token", claimed.leaseUntil());
    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(forged));

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(1, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1, fixture.gateway.admitCalls);
    assertTrue(fixture.processor.hasActiveExecution());
    assertEquals(
        "token-" + fixture.invocationId,
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .leaseToken());
  }

  /**
   * admit 期间 close：execution 已被 abandon，准入返回后释放名额、绝不持久化 RUNNING 也不运行；invocation 保持 READY 并按兜底延迟重排。
   */
  @Test
  void closeDuringAdmissionReleasesTheSlotWithoutRunning() {
    Fixture fixture = fixture();
    fixture.gateway.beforeReturn = execution -> fixture.processor.close();
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertTrue(fixture.gateway.runs.get(fixture.invocationId).closedWithoutRun.get());
    assertEquals(0, fixture.gateway.runCalls.get());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(1, fixture.gateway.admitCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * close 在 process 通过入口检查之后、registry 插入之前完成（close snapshot 之后插入）：putIfAbsent 后的 closed 检查
   * 必须拦截——abandon 新 execution、保持 READY 并 reschedule 仍 owned 的 Work，绝不调用 Gateway。 确定性同步：持 store
   * monitor 使 process 阻塞在 claimOwned 事务上，期间执行 close。
   */
  @Test
  void closeBeforeRegistryInsertBouncesReadyWithoutStartingGateway() throws Exception {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled(); // 不应被消费
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);

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
    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * close / 本地 cancel 在 heartbeat 启动窗口内获胜（startHeartbeat 返回后、准入前）：abandoned 检查必须拦截，不调用 Gateway，保持
   * READY 并 reschedule 仍 owned 的 Work。确定性注入：包装 scheduler，在 scheduleAtFixedRate 提交期间取消本地 execution。
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
    fixture.gateway.queueControlled(); // 不应被消费

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
  }

  /**
   * 旧 claim 仍阻塞在准入时 lease 过期并被新 token 重新领取：新 claim supersede 旧本地 execution 并完成准入与运行；旧 claim
   * 准入返回后发现已被 abandon，释放名额、绝不运行，reschedule 也因 ownership 已转移而 LOST。
   */
  @Test
  void newTokenSupersedesExecutionStillInAdmission() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch inAdmit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.gateway.beforeReturn =
        execution -> {
          if (inAdmit.getCount() > 0) {
            inAdmit.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
          }
        };
    ClaimedWork claimedA = claim(fixture.store, fixture.invocationId, "token-A");
    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixture.processor.process(claimedA)));
    threadA.start();
    assertTrue(inAdmit.await(5, TimeUnit.SECONDS));

    // 旧 lease 被 dispatcher 重新 claim：删旧行、重建 wake 并以新 token 重新领取。
    replaceModelWork(fixture);
    ClaimedWork claimedB = claim(fixture.store, fixture.invocationId, "token-B");
    fixture.gateway.queueControlled();
    assertEquals(ProcessResult.STARTED, fixture.processor.process(claimedB));
    ControlledRun fresh = fixture.gateway.controlled(fixture.invocationId);

    ControlledRun stale = new ControlledRun();
    fixture.gateway.queue(stale);
    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertTrue(stale.closedWithoutRun.get());
    assertEquals(2, fixture.gateway.admitCalls);
    assertEquals(1, fixture.gateway.runCalls.get());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(1, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(
        "token-B",
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .leaseToken());
    fresh.succeed(response("done", GenerationStopReason.COMPLETE));
    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
  }

  /**
   * stale-start fence：A（token-1）在 heartbeat 启动后暂停；lease 过期后由**另一实例**（模拟另一 JVM 的 processor，本地
   * registry / guard 独立，A 的本地 abandoned 检查不可见）以 token-2 接管并完成运行；A 恢复时物化事务的 claim 校验失败 → 立即 abandon
   * 并 LOST，绝不调用 Gateway。
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
    fixtureA.gateway.queueControlled(); // 不应被消费
    ClaimedWork claimedA = claim(fixtureA.store, fixtureA.invocationId, "token-1");

    AtomicReference<ProcessResult> resultA = new AtomicReference<>();
    Thread threadA = new Thread(() -> resultA.set(fixtureA.processor.process(claimedA)));
    threadA.start();
    assertTrue(
        inHeartbeat.await(5, TimeUnit.SECONDS)); // A 已 putIfAbsent + prepare，暂停在 startHeartbeat

    // lease 过期，dispatcher 以 token-2 重新 claim；另一实例接管并完成运行（跨实例，A 的 abandoned 检查不可见）。
    fixtureA.clock.advance(Duration.ofSeconds(61));
    replaceModelWork(fixtureA);
    ClaimedWork claimedB = claim(fixtureA.store, fixtureA.invocationId, "token-2");
    FakeGateway gatewayB = new FakeGateway();
    gatewayB.queueScript(
        new ModelGateway.Succeeded(
            new ProviderCompletion(response("done", GenerationStopReason.COMPLETE), null)));
    ModelProcessor processorB =
        new ModelProcessor(
            fixtureA.store,
            gatewayB,
            fixtureA.sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
            fixtureA.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor());
    assertEquals(ProcessResult.STARTED, processorB.process(claimedB));
    awaitTrue(() -> !processorB.hasActiveExecution());

    release.countDown();
    threadA.join(5000);

    assertEquals(ProcessResult.LOST_OWNERSHIP, resultA.get());
    assertEquals(0, fixtureA.gateway.admitCalls, "stale start must never call the gateway");
    assertFalse(fixtureA.processor.hasActiveExecution());
    ModelInvocation model = model(fixtureA.store, fixtureA.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(2, thread(fixtureA.store, fixtureA.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixtureA.store, new WorkTarget(WorkTargetType.THREAD, fixtureA.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixtureA.store, new WorkTarget(WorkTargetType.MODEL, fixtureA.invocationId)));
  }

  /**
   * materialize 完成后 Stop / recovery 删除 MODEL work：准入只做本地检查可以发生，但 markRunning 围栏拒绝，释放名额且绝不运行，durable
   * 保持 READY。
   */
  @Test
  void staleStartAfterMaterializationNeverCallsGateway() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
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
            newExecutionExecutor());

    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(
        0, fixture.gateway.runCalls.get(), "post-materialization stale start must not run");
    assertTrue(fixture.gateway.runs.get(fixture.invocationId).closedWithoutRun.get());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
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
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
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
            newExecutionExecutor());
    fixture.gateway.queueControlled(); // 不应被消费

    assertEquals(ProcessResult.LOST_OWNERSHIP, processor.process(claimed));

    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, model(fixture.store, fixture.invocationId).attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
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
                tx -> tx.claimNextWork(WorkTargetType.MODEL, "partial", Duration.ofSeconds(15)))
            .orElseThrow();
    fixture.gateway.queueControlled();

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
                tx -> tx.claimNextWork(WorkTargetType.MODEL, "exact", Duration.ofSeconds(30)))
            .orElseThrow();
    fixture.gateway.queueControlled();

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
    ClaimedWork claimed = claim(store, invocationId);
    WorkHeartbeat heartbeat =
        new WorkHeartbeat(store, newScheduler(), Runnable::run, LEASE_CONFIG, () -> {});
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
        new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY).admitFailureDelay());
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

  /** cancel 只中断本地执行线程（连接关闭、heartbeat 停止），不反写任何 durable 状态，之后的增量全部丢弃。 */
  @Test
  void cancelStopsLocalExecutionWithoutDurableWrite() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun run = fixture.gateway.controlled(fixture.invocationId);

    assertTrue(fixture.processor.cancel(fixture.invocationId));
    awaitTrue(run.interrupted::get);
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId))
            .leaseToken());
    assertNull(model(fixture.store, fixture.invocationId).streamCheckpoint());
    assertTrue(fixture.sink.events.isEmpty());

    assertFalse(fixture.processor.cancel(fixture.invocationId));
    assertThrows(NullPointerException.class, () -> fixture.processor.cancel(null));
  }

  /** close 中断全部本地执行线程；durable 行保持 RUNNING 等待 lease 恢复。 */
  @Test
  void closeCancelsAllLocalExecutions() {
    Fixture fixture = fixture();
    Baseline secondBaseline = seedBaseline(fixture.store, NOW);
    UUID secondInvocationId = seedInvocation(fixture.store, secondBaseline, requestSpec(), NOW);
    fixture.gateway.queueControlled();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun first = fixture.gateway.controlled(fixture.invocationId);
    assertEquals(
        ProcessResult.STARTED, fixture.processor.process(claim(fixture.store, secondInvocationId)));
    ControlledRun second = fixture.gateway.controlled(secondInvocationId);

    fixture.processor.close();

    awaitTrue(() -> first.interrupted.get() && second.interrupted.get());
    assertFalse(fixture.processor.hasActiveExecution());
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
            newExecutionExecutor());
    ControlledRun run = new ControlledRun();
    gateway.queue(run);
    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, "token-" + invocationId, Duration.ofMillis(400)))
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
    awaitTrue(run.interrupted::get, Duration.ofSeconds(2));

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
            newExecutionExecutor());
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.RESCHEDULED,
        deadProcessor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(0, fixture.gateway.admitCalls);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals(NOW.plus(FALLBACK_DELAY), modelWork.availableAt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
  }

  // ---------------------------------------------------------------------------------------------
  // prepare 期间丢失 ownership
  // ---------------------------------------------------------------------------------------------

  /** claim lease 已过期：typed LOST_OWNERSHIP，无任何 mutation。 */
  @Test
  void expiredClaimIsLostOwnershipWithoutMutation() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(0, model.attempt());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
    Work modelWork =
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
    assertEquals("token-" + fixture.invocationId, modelWork.leaseToken());
  }

  /** Stop deleteWork 后：claim 行缺失同样 LOST_OWNERSHIP，无 mutation。 */
  @Test
  void deletedWorkClaimIsLostOwnershipWithoutMutation() {
    Fixture fixture = fixture();
    ClaimedWork claimed = claim(fixture.store, fixture.invocationId);
    deleteModelWork(fixture);

    assertEquals(ProcessResult.LOST_OWNERSHIP, fixture.processor.process(claimed));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());
    assertEquals(0, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /**
   * 真实 prepare + 拒绝终态路径：每次写事务只先锁 own Thread，再锁 Model/Work，不取 tree、Session 或祖先锁。
   * 拒绝回写与准入共用同一锁边界，覆盖失败终态路径。
   */
  @Test
  void prepareAndRejectedDispatchLockOwnThreadOnly() {
    Fixture fixture = fixture();
    fixture.gateway.queue(
        new ModelGateway.Rejected(
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "model rejected")));
    ToolProcessorTestSupport.RecordingLockStore recording =
        new ToolProcessorTestSupport.RecordingLockStore(fixture.store);
    ModelProcessor processor =
        new ModelProcessor(
            recording,
            fixture.gateway,
            fixture.sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY),
            fixture.clock,
            newScheduler(),
            Runnable::run,
            newExecutionExecutor());

    assertEquals(
        ProcessResult.TERMINATED, processor.process(claim(fixture.store, fixture.invocationId)));

    List<List<String>> locked = recording.threadLockTransactions();
    assertEquals(2, locked.size(), "prepare and rejected dispatch each lock the own thread");
    for (List<String> ops : locked) {
      assertFalse(
          ops.stream()
              .anyMatch(
                  op ->
                      op.startsWith("lockTree")
                          || op.startsWith("findAncestorChain")
                          || op.startsWith("lockSession")),
          "processor must not take tree/session/ancestor locks: " + ops);
      assertEquals("lockThread:" + fixture.baseline.threadId(), ops.get(0));
      assertTrue(
          ops.stream()
              .filter(op -> op.startsWith("lockThread:"))
              .allMatch(op -> op.equals("lockThread:" + fixture.baseline.threadId())));
      int model = indexOfPrefix(ops, "lockModelInvocation:");
      int work = indexOfPrefix(ops, "lockClaimedWork");
      assertTrue(model > 0, "own Thread must precede Model: " + ops);
      assertTrue(work > model, "Model must precede Work: " + ops);
    }
    assertTrue(
        locked.get(1).stream().anyMatch(op -> op.startsWith("requestWork")),
        "rejected dispatch must request THREAD wake before terminal write");
  }

  private static int indexOfPrefix(List<String> ops, String prefix) {
    for (int i = 0; i < ops.size(); i++) {
      if (ops.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
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
          tx.requestWork(target);
          return null;
        });
  }

  private void requestModelWork(Fixture fixture) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
          return null;
        });
  }

  private Fixture fixture() {
    return fixture(NO_RETRY, requestSpec());
  }

  private Fixture fixture(InvocationRetryPolicy retryPolicy) {
    return fixture(retryPolicy, requestSpec());
  }

  private Fixture fixture(ModelStreamConfig flushConfig) {
    return new Fixture(NO_RETRY, requestSpec(), newScheduler(), false, flushConfig);
  }

  /** 压缩子 fixture：fixture Thread 是本 fixture 自己创建的 COMPACTION Join 的 child。 */
  private Fixture compactionFixture(InvocationRetryPolicy retryPolicy) {
    return compactionFixture(retryPolicy, ModelStreamConfig.DEFAULT);
  }

  private Fixture compactionFixture(
      InvocationRetryPolicy retryPolicy, ModelStreamConfig flushConfig) {
    return new Fixture(retryPolicy, requestSpec(), newScheduler(), true, flushConfig);
  }

  private Fixture fixture(InvocationRetryPolicy retryPolicy, ModelRequestSpec requestSpec) {
    return new Fixture(retryPolicy, requestSpec, newScheduler());
  }

  private Fixture fixture(
      InvocationRetryPolicy retryPolicy, ModelHttpErrorPolicyProvider httpErrorPolicyProvider) {
    return new Fixture(
        retryPolicy,
        requestSpec(),
        newScheduler(),
        false,
        ModelStreamConfig.DEFAULT,
        null,
        httpErrorPolicyProvider);
  }

  private Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec,
      ModelStreamConfig flushConfig) {
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

    {
      store.setClock(clock);
    }

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
      this(retryPolicy, requestSpec, scheduler, false, ModelStreamConfig.DEFAULT);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        boolean compactionChild,
        ModelStreamConfig flushConfig) {
      this(
          retryPolicy,
          requestSpec,
          scheduler,
          compactionChild,
          flushConfig,
          null,
          ModelHttpErrorPolicyProvider.DEFAULT);
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
          ModelStreamConfig.DEFAULT,
          toolHistoryActionResolver,
          ModelHttpErrorPolicyProvider.DEFAULT);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        boolean compactionChild,
        ModelStreamConfig flushConfig,
        ToolHistoryActionResolver toolHistoryActionResolver,
        ModelHttpErrorPolicyProvider httpErrorPolicyProvider) {
      this(
          retryPolicy,
          requestSpec,
          scheduler,
          compactionChild ? new ThreadConstraints(4096, true, false) : ThreadConstraints.NONE,
          compactionChild,
          flushConfig,
          toolHistoryActionResolver,
          httpErrorPolicyProvider);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        ThreadConstraints constraints,
        boolean ownerSettledSummary,
        ModelStreamConfig flushConfig,
        ToolHistoryActionResolver toolHistoryActionResolver,
        ModelHttpErrorPolicyProvider httpErrorPolicyProvider) {
      this.scheduler = scheduler;
      this.requestSpec = requestSpec;
      this.baseline = seedBaseline(store, NOW, constraints);
      if (ownerSettledSummary) {
        seedOwnerSettledTicket(
            store, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId());
      }
      this.invocationId = seedInvocation(store, baseline, requestSpec, NOW);
      Executor executionExecutor = newExecutionExecutor();
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
                  toolHistoryActionResolver,
                  httpErrorPolicyProvider),
              clock,
              scheduler,
              Runnable::run,
              executionExecutor);
      gateway.owner = processor;
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
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, basis.threadId()));
              tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id));
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
            LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, ModelStreamConfig.DEFAULT, null),
        Clock.fixed(NOW, ZoneOffset.UTC),
        newScheduler(),
        Runnable::run,
        newExecutionExecutor());
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
                      TurnStartReason.INPUT,
                      branchSettings(),
                      threadId,
                      100_000,
                      16_384,
                      null,
                      null),
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
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id));
          return id;
        });
  }

  /** 真正由父 Turn owner 结算的摘要任务：直接票据 delivery=SETTLE_OWNER_TURN，而不是靠执行约束推断。 */
  private static void seedOwnerSettledTicket(
      InMemoryHarnessStore store, UUID sessionId, UUID rootEntryId, UUID threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          String hash = "a".repeat(64);
          UUID parentThreadId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  parentThreadId,
                  sessionId,
                  null,
                  rootEntryId,
                  hash,
                  "compaction-owner",
                  parentThreadId,
                  false,
                  SpawnTrigger.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  NOW,
                  NOW));
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      threadId,
                      1,
                      new CustomMessageCommandPayload(AgentMessage.user("summarize")),
                      UUID.randomUUID(),
                      hash,
                      null,
                      null,
                      null,
                      NOW)));
          tx.insertJoin(
              new ThreadJoin(
                  UUID.randomUUID(),
                  hash,
                  parentThreadId,
                  threadId,
                  1,
                  "Compaction",
                  null,
                  0,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  JoinDelivery.SETTLE_OWNER_TURN,
                  false,
                  null));
          return null;
        });
  }

  private static Baseline seedBaseline(HarnessStore store, Instant now) {
    return seedBaseline(store, now, ThreadConstraints.NONE);
  }

  private static Baseline seedBaseline(
      HarnessStore store, Instant now, ThreadConstraints constraints) {
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
                      TurnStartReason.INPUT,
                      branchSettings(),
                      threadId,
                      100_000,
                      16_384,
                      null,
                      null),
                  now.plusMillis(1)));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(
                  threadId, sessionId, turnStartEntryId, now, constraints));
          return new Baseline(sessionId, rootEntryId, turnStartEntryId, threadId);
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
          ModelGroupExecution group = requestSpec.groupExecution();
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
                  now,
                  null,
                  group == null
                      ? null
                      : new ModelGroupProgress(group.groupName(), group.groupVersion(), 0, 0)));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id));
          return id;
        });
  }

  private static ClaimedWork claim(HarnessStore store, UUID invocationId) {
    return claim(store, invocationId, "token-" + invocationId);
  }

  private static ClaimedWork claim(HarnessStore store, UUID invocationId, String token) {
    return store
        .transaction(tx -> tx.claimNextWork(WorkTargetType.MODEL, token, Duration.ofSeconds(60)))
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

  private static List<Long> checkpoints(RecordingSink sink) {
    return sink.events.stream()
        .filter(event -> event instanceof RealtimeEvent.ModelCheckpoint)
        .map(event -> (RealtimeEvent.ModelCheckpoint) event)
        .map(RealtimeEvent.ModelCheckpoint::sequence)
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
    return new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
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

  /** 两成员模型组冻结请求：成员逻辑名用于断言真实 gateway 身份，而非仅顶层组名。 */
  private static ModelRequestSpec groupSpec(Set<Integer> fallbackStatusCodes, int cooldownSeconds) {
    List<ModelGroupMemberExecution> members = List.of(groupMember("model"), groupMember("model2"));
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor("group", "group", "group", Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none(),
        new ModelGroupExecution("group", 7L, members, fallbackStatusCodes, cooldownSeconds));
  }

  private static ModelGroupMemberExecution groupMember(String name) {
    return new ModelGroupMemberExecution(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor("provider", name, name, Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("v1"));
  }

  private static void freezeGroupMember(Fixture fixture, String member, Instant until) {
    fixture.store.transaction(
        tx -> {
          tx.freezeGroupMember("group", 7L, member, "v1", until);
          return null;
        });
  }

  /** 在 fixture 当前时钟下该成员是否仍处于组冷却。 */
  private static boolean cooling(Fixture fixture, String member) {
    return fixture.store.transaction(tx -> tx.isGroupMemberCooling("group", 7L, member, "v1"));
  }

  private static ModelGroupProgress groupProgress(ModelInvocation invocation) {
    assertNotNull(invocation.groupProgress());
    return invocation.groupProgress();
  }

  /** 组首成员冷却时在运行前推进 durable 游标，真实启动第二成员。 */
  @Test
  void groupSkipsCoolingFirstMemberAndStartsSecond() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(), 30));
    freezeGroupMember(fixture, "model", NOW.plusSeconds(30));
    fixture.gateway.queueControlled();

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals("model2", fixture.gateway.executions.getFirst().request().model().modelName());
    ModelInvocation running = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, running.status());
    assertEquals(1, running.attempt());
    assertEquals(1, groupProgress(running).memberIndex());
  }

  /** 全成员冷却是无可用候选的本地专用失败：不准入 gateway，也不伪装成普通上游重试。 */
  @Test
  void groupAllMembersCoolingFailsLocallyWithoutGatewayOrRetry() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(), 30));
    freezeGroupMember(fixture, "model", NOW.plusSeconds(30));
    freezeGroupMember(fixture, "model2", NOW.plusSeconds(30));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    assertEquals(0, fixture.gateway.admitCalls);
    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.NO_AVAILABLE_MODEL, failed.error().kind());
    assertNull(failed.error().httpStatus());
    assertFalse(failed.error().retryCandidate());
    assertTrue(failed.error().message().contains("no available model in group"));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)));
  }

  /** 命中 fallback 码立即冻结并推进，不消耗普通重试预算；失败审计带实际成员与请求时间，下一成员立即可执行。 */
  @Test
  void groupFallbackStatusFreezesAndAdvancesToNextMemberWithoutOrdinaryRetry() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(429), 30));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "limited", 429));

    ModelInvocation advanced = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, advanced.status());
    assertEquals(1, advanced.attempt());
    assertEquals(1, groupProgress(advanced).memberIndex());
    assertEquals(0, groupProgress(advanced).memberAttempts());
    assertEquals(ModelSelection.model("model", "v1"), advanced.failedAttempts().getFirst().model());
    assertEquals(NOW, advanced.failedAttempts().getFirst().requestedAt());
    assertTrue(cooling(fixture, "model"));
    assertFalse(cooling(fixture, "model2"));

    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    assertEquals("model2", fixture.gateway.executions.get(1).request().model().modelName());
  }

  /** 非 fallback 的可重试错误先消耗当前成员预算；预算耗尽后才冻结并推进，失败审计逐次指向真实成员。 */
  @Test
  void groupNonFallbackFailureRetriesCurrentMemberThenFreezesAndAdvances() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            groupSpec(Set.of(429), 30));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "server", 500));

    ModelInvocation retried = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(0, groupProgress(retried).memberIndex());
    assertEquals(1, groupProgress(retried).memberAttempts());
    assertFalse(cooling(fixture, "model"));

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "server2", 500));

    ModelInvocation advanced = model(fixture.store, fixture.invocationId);
    assertEquals(1, groupProgress(advanced).memberIndex());
    assertEquals(2, advanced.failedAttempts().size());
    assertEquals(ModelSelection.model("model", "v1"), advanced.failedAttempts().getLast().model());
    assertTrue(cooling(fixture, "model"));
  }

  /** cooldown=0 只推进不写跨调用冷却；所有成员失败后保留最后一次真实上游错误。 */
  @Test
  void groupZeroCooldownAdvancesWithoutFreezingAndKeepsLastError() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(429), 0));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "limited", 429));

    ModelInvocation advanced = model(fixture.store, fixture.invocationId);
    assertEquals(1, groupProgress(advanced).memberIndex());
    assertFalse(cooling(fixture, "model"));

    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "denied", 401));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.AUTHENTICATION, failed.error().kind());
    assertEquals(401, failed.error().httpStatus());
    assertEquals(2, failed.failedAttempts().size());
    assertEquals(ModelSelection.model("model2", "v1"), failed.failedAttempts().getLast().model());
  }

  /** 上下文溢出在组内仍走专用终止：不冻结、不推进成员、不追加降级审计，原样交给压缩路径。 */
  @Test
  void groupOverflowFailureKeepsDedicatedTerminalSemantics() {
    Fixture fixture = fixture(NO_RETRY, groupSpec(Set.of(), 30));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));

    fixture
        .gateway
        .controlled(fixture.invocationId)
        .fail(new ModelInvocationError(ProviderErrorKind.OVERFLOW, "context too long"));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(ProviderErrorKind.OVERFLOW, failed.error().kind());
    assertTrue(failed.failedAttempts().isEmpty());
    assertEquals(0, groupProgress(failed).memberIndex());
    assertFalse(cooling(fixture, "model"));
  }

  /** 租约丢失恢复只消耗当前成员预算：允许时留在原成员，耗尽时原成员失败，绝不冻结或切换成员。 */
  @Test
  void groupLeaseRecoveryConsumesCurrentMemberBudgetWithoutFreezeOrFallback() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            groupSpec(Set.of(), 30));
    transition(fixture.store, fixture.invocationId, model -> model.markRunning(NOW));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.RESCHEDULED, fixture.processor.process(recovered));

    ModelInvocation retried = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(0, groupProgress(retried).memberIndex());
    assertEquals(1, groupProgress(retried).memberAttempts());
    assertEquals(ModelSelection.model("model", "v1"), retried.failedAttempts().getLast().model());
    assertFalse(cooling(fixture, "model"));

    // 同一成员第二次租约丢失：成员预算耗尽，直接 FAILED，仍不冻结、不切到 model2。
    fixture.clock.advance(Duration.ofSeconds(5));
    transition(
        fixture.store, fixture.invocationId, model -> model.markRunning(fixture.clock.instant()));
    claim(fixture.store, fixture.invocationId);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork exhausted = claim(fixture.store, fixture.invocationId);

    assertEquals(ProcessResult.TERMINATED, fixture.processor.process(exhausted));

    ModelInvocation failed = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(0, groupProgress(failed).memberIndex());
    assertEquals(ProviderErrorKind.TRANSIENT, failed.error().kind());
    assertEquals(2, failed.failedAttempts().size());
    assertFalse(cooling(fixture, "model"));
    assertEquals(0, fixture.gateway.admitCalls);
  }

  private static EntryPayload assistantPayload() {
    // live attached 场景的 assistant 由 fixture 的 canonical request/response 经 mapper 派生，与 strict
    // attach 校验一致；
    // 历史（无 active model）场景复用同一 payload 不影响 attach 校验。
    return new HistoryPayloadMapper()
        .assistantPayload(
            response("ok", GenerationStopReason.COMPLETE),
            requestWithTool().toolBindings(),
            ModelSelection.model("model", "v1"),
            NOW);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", ModelSelection.model("model", "v1"), null);
  }

  private static ProviderReplayState sampleReplayState() {
    return new ProviderReplayState(
        ProviderReplayFormat.OPENAI_RESPONSES,
        new ProviderReplayAffinity(
            ProviderType.OPENAI_RESPONSES, "provider-a", UUID.randomUUID(), "model-a"),
        JsonNodeFactory.instance.objectNode().put("token", 42));
  }

  /**
   * 可编排的 fake Gateway：每次 admit 取队列头的一个结果。{@link #queueControlled} 排入一次受测试线程控制的阻塞运行——测试通过 {@link
   * ControlledRun} 逐个推送增量并给出终态，运行线程在 {@code run} 内阻塞等待。
   */
  static final class FakeGateway implements ModelGateway {
    final LinkedList<Object> results = new LinkedList<>();
    final List<Execution> executions = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<UUID, ControlledRun> runs = new ConcurrentHashMap<>();
    final AtomicInteger runCalls = new AtomicInteger();
    final AtomicInteger closedUnused = new AtomicInteger();
    volatile int admitCalls;
    volatile ModelProcessor owner;

    /** 在 admit 返回结果之前于执行线程上运行，用于确定性地制造准入与本地状态变化的竞态窗口。 */
    volatile Consumer<Execution> beforeReturn;

    /** 排入一个 Admission、一次抛出的 RuntimeException 或 {@link Script}。 */
    void queue(Object result) {
      results.add(result);
    }

    /** 排入一次由测试控制的阻塞运行。 */
    void queueControlled() {
      results.add(new ControlledRun());
    }

    /** 排入一次立即完成的运行：先按序推送 events，再返回 outcome。 */
    void queueScript(Outcome outcome, ProviderStreamEvent... events) {
      results.add(
          (Script)
              sink -> {
                for (ProviderStreamEvent event : events) {
                  sink.onEvent(event);
                }
                return outcome;
              });
    }

    @Override
    public Admission admit(Execution execution) {
      admitCalls++;
      executions.add(execution);
      Consumer<Execution> hook = beforeReturn;
      if (hook != null) {
        hook.accept(execution);
      }
      Object result = results.poll();
      if (result == null) {
        throw new IllegalStateException("no queued gateway result");
      }
      if (result instanceof RuntimeException failure) {
        throw failure;
      }
      if (result instanceof ControlledRun controlled) {
        controlled.gateway = this;
        runs.put(execution.invocationId(), controlled);
        return controlled;
      }
      if (result instanceof Script script) {
        return new ScriptedAdmitted(script, this);
      }
      return (Admission) result;
    }

    /** 等待运行线程进入 {@code run}（此时 RUNNING 已持久化）并返回控制器。 */
    ControlledRun controlled(UUID invocationId) {
      awaitTrue(
          () -> runs.get(invocationId) != null && runs.get(invocationId).entered.getCount() == 0);
      return runs.get(invocationId);
    }
  }

  /** 运行线程在 {@link Admitted#run} 内执行的脚本。 */
  interface Script {
    ModelGateway.Outcome run(ModelGateway.Sink sink);
  }

  static final class ScriptedAdmitted implements ModelGateway.Admitted {
    private final Script script;
    private final FakeGateway gateway;

    ScriptedAdmitted(Script script, FakeGateway gateway) {
      this.script = script;
      this.gateway = gateway;
    }

    @Override
    public ModelGateway.Outcome run(ModelGateway.Sink sink) {
      gateway.runCalls.incrementAndGet();
      return script.run(sink);
    }

    @Override
    public void close() {
      gateway.closedUnused.incrementAndGet();
    }
  }

  /** 测试线程驱动的阻塞运行：推送增量 / 终态都会等待运行线程处理完毕。 */
  static final class ControlledRun implements ModelGateway.Admitted {
    private final LinkedBlockingQueue<Object[]> signals = new LinkedBlockingQueue<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicBoolean interrupted = new AtomicBoolean();
    final AtomicBoolean closedWithoutRun = new AtomicBoolean();
    volatile FakeGateway gateway;
    volatile RuntimeException sinkFailure;

    @Override
    public ModelGateway.Outcome run(ModelGateway.Sink sink) {
      gateway.runCalls.incrementAndGet();
      entered.countDown();
      try {
        while (true) {
          Object[] signal = signals.take();
          if (signal[0] instanceof ProviderStreamEvent event) {
            try {
              sink.onEvent(event);
            } catch (RuntimeException failure) {
              sinkFailure = failure;
              throw failure;
            } finally {
              ((CountDownLatch) signal[1]).countDown();
            }
          } else {
            ((CountDownLatch) signal[1]).countDown();
            return (ModelGateway.Outcome) signal[0];
          }
        }
      } catch (InterruptedException cancelled) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
        return new ModelGateway.Failed(
            new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled"));
      }
    }

    @Override
    public void close() {
      closedWithoutRun.set(true);
    }

    void event(ProviderStreamEvent event) {
      CountDownLatch done = new CountDownLatch(1);
      signals.add(new Object[] {event, done});
      await(done);
      if (sinkFailure != null) {
        // sink 拒绝事件后 run 随即结束，终态提交发生在 run 返回之后：等待 execution 释放。
        awaitTrue(() -> gateway.owner == null || !gateway.owner.hasActiveExecution());
      }
    }

    void succeed(ProviderCompletion completion) {
      finish(new ModelGateway.Succeeded(completion));
    }

    void succeed(ProviderResponse response) {
      succeed(new ProviderCompletion(response, null));
    }

    void fail(ModelInvocationError error) {
      finish(new ModelGateway.Failed(error));
    }

    void unknown(ModelInvocationError error) {
      finish(new ModelGateway.Unknown(error));
    }

    private void finish(ModelGateway.Outcome outcome) {
      CountDownLatch done = new CountDownLatch(1);
      signals.add(new Object[] {outcome, done});
      await(done);
      // 终态提交发生在 run 返回之后：等待 execution 释放。
      awaitTrue(() -> gateway.owner == null || !gateway.owner.hasActiveExecution());
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          failTest("run thread did not process the signal");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }

  static void failTest(String message) {
    fail(message);
  }

  static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        failTest("condition not reached in time");
      }
      try {
        Thread.sleep(2);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
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
