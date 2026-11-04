package fun.fengwk.kkstudio.harness.builtin.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreCallProbe.wrap;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.node.JsonNodeFactory;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ThreadProcessorTestSupport;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryModelGateway;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ModelProcessor 行为测试的共享基座：InMemoryBuiltinHarnessStore + fake ModelGateway / Realtime sink。
 *
 * <p>测试按关注点分为准入与物化恢复、流式 / checkpoint、终态与重试、进程生命周期与所有权围栏、模型组五个测试类；本类只提供它们共用的 fixture、Seed
 * 与断言辅助，不直接声明测试。覆盖 READY 准入（Admitted / Busy / 抛异常 / Rejected）、RUNNING 先于运行持久化的陈旧启动围栏、 stale lease
 * 恢复、stream checkpoint 前缀与 tool fragment 排除、success terminal + THREAD wake、transient retry 与 retry
 * exhausted、UNKNOWN、 deleteWork 后的 terminal、new wake 保留、sink 失败隔离、cancel / close 与 wrong target。
 */
abstract class ModelProcessorTestSupport {
  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  static final Duration FALLBACK_DELAY = Duration.ofSeconds(7);

  /** 逐事件即时推送、1ms 后 checkpoint 的测试配置。 */
  static final ModelStreamConfig IMMEDIATE_STREAM =
      new ModelStreamConfig(Duration.ofMillis(1), 1, 1, Duration.ofMillis(1));

  static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));

  final List<ScheduledExecutorService> schedulers = new ArrayList<>();
  final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
    executors.forEach(ExecutorService::shutdownNow);
  }

  /** 每个任务一个虚拟线程的执行 executor，与生产装配一致。 */
  ExecutorService newExecutionExecutor() {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    executors.add(executor);
    return executor;
  }

  ScheduledExecutorService newScheduler() {
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
  // fixture / 辅助方法
  // ---------------------------------------------------------------------------------------------

  /** 在一次 transaction 加载完不可变 EntryPath 并提交后执行 {@code afterLoad}，用于证明 materialize 之后的 start fence。 */
  static HarnessStore afterEntryPathLoad(HarnessStore delegate, Runnable afterLoad) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class, BuiltinStore.class},
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
                                wrap(
                                    tx,
                                    (call, next) -> {
                                      Object value = next.invoke();
                                      if ("entries.loadPath".equals(call.operation())) {
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

  /**
   * 在第二次 Work-only ownership 复核（admission guard verifier）提交后执行 {@code afterAdmissionCheck}：模拟另一事务在
   * 准入复核通过之后、prepare 事务之前删除 claim，用于验证 prepare / 恢复 / 终态清理的最终 fence 完整回滚。
   */
  static HarnessStore afterAdmissionCheck(HarnessStore delegate, Runnable afterAdmissionCheck) {
    AtomicInteger ownedLocks = new AtomicInteger();
    AtomicBoolean fired = new AtomicBoolean();
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class, BuiltinStore.class},
            (proxy, method, args) -> {
              if ("transaction".equals(method.getName()) && args != null && args.length == 1) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, ?> callback =
                    (Function<HarnessStore.Transaction, ?>) args[0];
                AtomicInteger ownedInTransaction = new AtomicInteger();
                Object result =
                    delegate.transaction(
                        tx ->
                            callback.apply(
                                wrap(
                                    tx,
                                    (call, next) -> {
                                      Object value = next.invoke();
                                      if ("work.lockClaimed".equals(call.operation())
                                          && value instanceof Optional<?> owned
                                          && owned.isPresent()) {
                                        ownedInTransaction.incrementAndGet();
                                      }
                                      return value;
                                    })));
                if (ownedLocks.addAndGet(ownedInTransaction.get()) >= 2
                    && fired.compareAndSet(false, true)) {
                  afterAdmissionCheck.run();
                }
                return result;
              }
              return invokeUnchecked(delegate, method, args);
            });
  }

  static Object invokeUnchecked(Object target, Method method, Object[] args) {
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

  void deleteModelWork(Fixture fixture) {
    deleteModelWork(fixture, fixture.invocationId);
  }

  void deleteModelWork(Fixture fixture, UUID invocationId) {
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          tx.work().delete(new WorkTarget(BuiltinWorkType.MODEL, invocationId));
          return null;
        });
  }

  void replaceModelWork(Fixture fixture) {
    replaceModelWork(fixture, fixture.invocationId);
  }

  void replaceModelWork(Fixture fixture, UUID invocationId) {
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          WorkTarget target = new WorkTarget(BuiltinWorkType.MODEL, invocationId);
          tx.work().delete(target);
          tx.work().request(target);
          return null;
        });
  }

  void requestModelWork(Fixture fixture) {
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
          return null;
        });
  }

  Fixture fixture() {
    return fixture(NO_RETRY, requestSpec());
  }

  Fixture fixture(InvocationRetryPolicy retryPolicy) {
    return fixture(retryPolicy, requestSpec());
  }

  Fixture fixture(ModelStreamConfig flushConfig) {
    return new Fixture(NO_RETRY, requestSpec(), newScheduler(), false, flushConfig);
  }

  /** 压缩子 fixture：fixture Thread 是本 fixture 自己创建的 COMPACTION Join 的 child。 */
  Fixture compactionFixture(InvocationRetryPolicy retryPolicy) {
    return compactionFixture(retryPolicy, ModelStreamConfig.DEFAULT);
  }

  Fixture compactionFixture(InvocationRetryPolicy retryPolicy, ModelStreamConfig flushConfig) {
    return new Fixture(retryPolicy, requestSpec(), newScheduler(), true, flushConfig);
  }

  Fixture fixture(InvocationRetryPolicy retryPolicy, ModelRequestSpec requestSpec) {
    return new Fixture(retryPolicy, requestSpec, newScheduler());
  }

  Fixture fixture(
      InvocationRetryPolicy retryPolicy, Function<String, ModelHttpErrorPolicy> httpErrorPolicyProvider) {
    return new Fixture(
        retryPolicy,
        requestSpec(),
        newScheduler(),
        false,
        ModelStreamConfig.DEFAULT,
        null,
        httpErrorPolicyProvider);
  }

  Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec,
      ModelStreamConfig flushConfig) {
    return new Fixture(retryPolicy, requestSpec, newScheduler(), false, flushConfig);
  }

  Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec,
      ScheduledExecutorService scheduler) {
    return new Fixture(retryPolicy, requestSpec, scheduler);
  }

  final class Fixture {
    final MutableClock clock = new MutableClock(NOW);
    final InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();

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
          providerName -> ModelHttpErrorPolicy.DEFAULT);
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
          providerName -> ModelHttpErrorPolicy.DEFAULT);
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ModelRequestSpec requestSpec,
        ScheduledExecutorService scheduler,
        boolean compactionChild,
        ModelStreamConfig flushConfig,
        ToolHistoryActionResolver toolHistoryActionResolver,
        Function<String, ModelHttpErrorPolicy> httpErrorPolicyProvider) {
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
        Function<String, ModelHttpErrorPolicy> httpErrorPolicyProvider) {
      this.scheduler = scheduler;
      this.requestSpec = requestSpec;
      if (requestSpec.groupExecution() != null) {
        store.transaction(
            tx -> {
              gateway.setGroupVersion(
                  tx,
                  requestSpec.groupExecution().groupName(),
                  requestSpec.groupExecution().groupVersion());
              return null;
            });
      }
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
              executionExecutor,
              TestExecutionExitConfirmer.create(store, clock));
      gateway.owner = processor;
    }
  }

  record Baseline(UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID threadId) {}

  /** 带 USER 输入的 open turn 基线：Thread head 与 invocation requestHead 指向 USER。 */
  record UserBasis(
      UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID userEntryId, UUID threadId) {}

  record ReplayHistory(InMemoryBuiltinHarnessStore store, UserBasis basis, UUID invocationId) {}

  /** 使用真实 Entry 与 Work 初始化含工具调用及 opaque replay 的待投影历史。 */
  static ReplayHistory seedReplayHistory() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    UserBasis basis = seedUserBasis(store);
    UUID invocationId =
        store.transaction(
            tx -> {
              UUID assistantId = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          assistantId,
                          basis.sessionId(),
                          basis.userEntryId(),
                          new BuiltinHistoryPayloadMapper()
                              .assistantPayload(
                                  toolResponse(
                                      "answer",
                                      new ProviderToolCall("call-1", "bash", "{\"secret\":1}")),
                                  requestWithTool().toolBindings()),
                          NOW.plusMillis(3),
                          sampleReplayState()));
              tx.threads()
                  .update(
                      tx.threads()
                          .lock(basis.threadId())
                          .orElseThrow()
                          .advanceHead(assistantId, NOW));
              UUID id = tx.nextId();
              builtin(store, tx)
                  .insertModelInvocation(
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
              tx.work().request(new WorkTarget(WorkTargetType.THREAD, basis.threadId()));
              tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, id));
              return id;
            });
    return new ReplayHistory(store, basis, invocationId);
  }

  ModelProcessor projectionProcessor(HarnessStore store, FakeGateway gateway) {
    return new ModelProcessor(
        store,
        gateway,
        new RecordingSink(),
        new ModelProcessorConfig(
            LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, ModelStreamConfig.DEFAULT, null),
        Clock.fixed(NOW, ZoneOffset.UTC),
        newScheduler(),
        Runnable::run,
        newExecutionExecutor(),
        TestExecutionExitConfirmer.create(store, Clock.fixed(NOW, ZoneOffset.UTC)));
  }

  /** Session + ROOT + TURN_START(INPUT) + USER；Thread head 指向 USER。 */
  static UserBasis seedUserBasis(HarnessStore store) {
    return seedUserBasis(store, null);
  }

  /**
   * 同树子 Thread 的 USER basis：{@code parentThreadId} 非空时先建合法父 root，再建 parent/root 指向该父的子 Thread，使
   * Join 与祖先树锁一致。
   */
  static UserBasis seedUserBasis(HarnessStore store, UUID parentThreadId) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
          tx.entries()
              .insert(
                  new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          if (parentThreadId != null) {
            // 先锁树再写父/子 Thread，子 Thread 才能作为 joinedChild 沿用父树锁。
            tx.threads().lockTree(parentThreadId);
            tx.threads()
                .insert(
                    ThreadProcessorTestSupport.threadState(
                        parentThreadId, sessionId, rootEntryId, "parent-" + parentThreadId, NOW));
          }
          tx.entries()
              .insert(
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
          tx.entries()
              .insert(
                  new Entry(
                      userEntryId,
                      sessionId,
                      turnStartEntryId,
                      userMessagePayload(),
                      NOW.plusMillis(2)));
          tx.threads()
              .insert(
                  parentThreadId == null
                      ? ThreadProcessorTestSupport.threadState(
                          threadId, sessionId, userEntryId, NOW)
                      : new ThreadState(
                          threadId,
                          sessionId,
                          parentThreadId,
                          userEntryId,
                          ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                          "child-" + threadId,
                          parentThreadId,
                          false,
                          SpawnTrigger.MODEL_TOOL,
                          ThreadConstraints.NONE,
                          ThreadExecutionControl.RUNNABLE,
                          0L,
                          1L,
                          0L,
                          NOW,
                          NOW));
          return new UserBasis(sessionId, rootEntryId, turnStartEntryId, userEntryId, threadId);
        });
  }

  /** 以 USER 为 requestHead 插入 READY ModelInvocation + THREAD / MODEL Work（materialization 全链测试用）。 */
  static UUID seedUserBasisInvocation(
      InMemoryBuiltinHarnessStore store,
      UserBasis baseline,
      ModelRequestSpec requestSpec,
      Instant now) {
    return store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId());
          UUID id = tx.nextId();
          builtin(store, tx)
              .insertModelInvocation(
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
          tx.work().request(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, id));
          return id;
        });
  }

  /** 真正由父 Turn owner 结算的摘要任务：直接票据 delivery=SETTLE_OWNER_TURN，而不是靠执行约束推断。 */
  static void seedOwnerSettledTicket(
      InMemoryBuiltinHarnessStore store, UUID sessionId, UUID rootEntryId, UUID threadId) {
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          String hash = "a".repeat(64);
          UUID parentThreadId = tx.nextId();
          tx.threads()
              .insert(
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
          tx.commands()
              .insert(
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
          tx.joins()
              .insert(
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
                      null));
          return null;
        });
  }

  static Baseline seedBaseline(HarnessStore store, Instant now) {
    return seedBaseline(store, now, ThreadConstraints.NONE);
  }

  static Baseline seedBaseline(HarnessStore store, Instant now, ThreadConstraints constraints) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, now));
          tx.entries()
              .insert(
                  new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
          UUID turnStartEntryId = tx.nextId();
          tx.entries()
              .insert(
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
          tx.threads()
              .insert(
                  ThreadProcessorTestSupport.threadState(
                      threadId, sessionId, turnStartEntryId, now, constraints));
          return new Baseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  /** 已提交增量中的文本 delta（顺序即发布顺序）。 */
  static List<String> deltaTexts(RecordingSink sink) {
    return deltas(sink).stream()
        .filter(event -> event instanceof ProviderStreamEvent.TextDelta)
        .map(event -> ((ProviderStreamEvent.TextDelta) event).text())
        .toList();
  }

  static UUID seedInvocation(
      HarnessStore store, Baseline baseline, ModelRequestSpec requestSpec, Instant now) {
    return store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId());
          UUID id = tx.nextId();
          ModelGroupExecution group = requestSpec.groupExecution();
          builtin(store, tx)
              .insertModelInvocation(
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
          tx.work().request(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, id));
          return id;
        });
  }

  static ClaimedWork claim(HarnessStore store, UUID invocationId) {
    return claim(store, invocationId, "token-" + invocationId);
  }

  static ClaimedWork claim(HarnessStore store, UUID invocationId, String token) {
    return store
        .transaction(
            tx -> tx.work().claimNext(BuiltinWorkType.MODEL, token, Duration.ofSeconds(60)))
        .orElseThrow();
  }

  static void transition(
      HarnessStore store,
      UUID invocationId,
      Function<ModelInvocation, ModelInvocation> transition) {
    store.transaction(
        tx -> {
          ModelInvocation model =
              builtin(store, tx).lockModelInvocation(invocationId).orElseThrow();
          builtin(store, tx).updateModelInvocation(transition.apply(model));
          return null;
        });
  }

  static ModelInvocation model(HarnessStore store, UUID invocationId) {
    return store
        .transaction(tx -> builtin(store, tx).findModelInvocation(invocationId))
        .orElseThrow();
  }

  static ProviderRequest materialized(Fixture fixture) {
    ModelInvocation invocation = model(fixture.store, fixture.invocationId);
    EntryPath path =
        fixture.store.transaction(tx -> tx.entries().loadPath(invocation.requestHeadEntryId()));
    return new ModelRequestMaterializer().materialize(path, fixture.requestSpec);
  }

  static ThreadState thread(HarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.threads().find(threadId)).orElseThrow();
  }

  static Work work(HarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.work().find(target)).orElse(null);
  }

  static List<ProviderStreamEvent> deltas(RecordingSink sink) {
    return sink.events.stream()
        .filter(event -> event instanceof RealtimeEvent.ModelDelta)
        .map(event -> (RealtimeEvent.ModelDelta) event)
        .map(RealtimeEvent.ModelDelta::delta)
        .toList();
  }

  static List<Long> checkpoints(RecordingSink sink) {
    return sink.events.stream()
        .filter(event -> event instanceof RealtimeEvent.ModelCheckpoint)
        .map(event -> (RealtimeEvent.ModelCheckpoint) event)
        .map(RealtimeEvent.ModelCheckpoint::sequence)
        .toList();
  }

  static void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    fail("condition not met within " + timeout);
  }

  /** 等待被观测 gateway 的真实执行线程退出后，再断言该 Thread 没有未确认执行事实。执行线程 join 是退出证据； registry 清空与轮询都不是。 */
  static void awaitExecutionExit(Fixture fixture) {
    awaitExecutionExit(fixture, fixture.gateway);
  }

  static void awaitExecutionExit(Fixture fixture, FakeGateway gateway) {
    List<Thread> executionThreads = List.copyOf(gateway.executionThreads);
    assertFalse(executionThreads.isEmpty(), "gateway must have started an execution body");
    for (Thread executionThread : executionThreads) {
      try {
        assertTrue(executionThread.join(Duration.ofSeconds(5)), "model execution thread must exit");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        fail("interrupted while waiting for model execution thread", interrupted);
      }
    }
    boolean noUnconfirmed =
        fixture.store.transaction(
            tx -> tx.exits().loadUnconfirmed(fixture.baseline.threadId()).isEmpty());
    assertTrue(noUnconfirmed, "confirmed exit must leave no unconfirmed execution fact");
  }

  static ModelRequestSpec requestSpec() {
    return spec(List.of());
  }

  static ModelRequestSpec requestWithTool() {
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

  static ModelRequestSpec spec(List<ToolBinding> bindings) {
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

  static ProviderRequest providerRequest(List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        tools,
        ProviderCacheControl.none());
  }

  static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  static ProviderResponse response(String text, GenerationStopReason stopReason) {
    return new ProviderResponse(text, null, List.of(), stopReason, usage(), "req-1", null, null);
  }

  static ProviderResponse toolResponse(String text, ProviderToolCall call) {
    return new ProviderResponse(
        text, null, List.of(call), GenerationStopReason.COMPLETE, usage(), "req-1", null, null);
  }

  static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L);
  }

  static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  static EntryPayload userMessagePayload() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  static EntryPayload assistantPayload() {
    // live attached 场景的 assistant 由 fixture 的 canonical request/response 经 mapper 派生，与 strict
    // attach 校验一致；
    // 历史（无 active model）场景复用同一 payload 不影响 attach 校验。
    return new BuiltinHistoryPayloadMapper()
        .assistantPayload(
            response("ok", GenerationStopReason.COMPLETE),
            requestWithTool().toolBindings(),
            ModelSelection.model("model", "v1"),
            NOW);
  }

  static BranchSettings branchSettings() {
    return new BranchSettings(
        "agent", BuiltinExecution.of(ModelSelection.model("model", "v1")), null);
  }

  static ProviderReplayState sampleReplayState() {
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
  static final class FakeGateway extends InMemoryModelGateway {
    final LinkedList<Object> results = new LinkedList<>();
    final List<Execution> executions = new CopyOnWriteArrayList<>();
    final List<Thread> executionThreads = new CopyOnWriteArrayList<>();
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
      gateway.executionThreads.add(Thread.currentThread());
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
      gateway.executionThreads.add(Thread.currentThread());
      gateway.runCalls.incrementAndGet();
      entered.countDown();
      try {
        while (true) {
          Object[] signal = signals.take();
          if ("EVENT".equals(signal[0])) {
            try {
              sink.onEvent((ProviderStreamEvent) signal[1]);
            } catch (RuntimeException failure) {
              sinkFailure = failure;
              throw failure;
            } finally {
              ((CountDownLatch) signal[2]).countDown();
            }
          } else {
            ((CountDownLatch) signal[2]).countDown();
            return (ModelGateway.Outcome) signal[1];
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
      signals.add(new Object[] {"EVENT", event, done});
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
      signals.add(new Object[] {"OUTCOME", outcome, done});
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
