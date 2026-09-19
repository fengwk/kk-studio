package fun.fengwk.kkstudio.harness.infra.dispatch;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * HarnessWorkDispatcher 测试基座：InMemoryHarnessStore 种子、MutableClock、可控制 executor / scheduler / store
 * gate 与等待 helper。
 *
 * <p>种子 helper 只建立最小合法链（Session + ROOT [+ TURN_START [+ USER + ASSISTANT]] + Thread [+
 * ModelInvocation [+ ToolInvocation]]）并原子写入对应 Work 行，requestedAt 全部为 {@link #NOW}。
 */
final class DispatcherTestSupport {

  static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);
  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

  /** 测试种子线程的合法 64 位小写 SHA-256 creation request hash。 */
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  static final Duration THREAD_LEASE = Duration.ofSeconds(30);
  static final Duration MODEL_LEASE = Duration.ofSeconds(45);
  static final Duration TOOL_LEASE = Duration.ofSeconds(60);
  static final Duration POLL_INTERVAL = Duration.ofMillis(20);
  static final Duration REJECTION_DELAY = Duration.ofSeconds(2);
  static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private DispatcherTestSupport() {}

  static HarnessWorkDispatcherConfig config() {
    return config(1);
  }

  static HarnessWorkDispatcherConfig config(int maxDispatchTasks) {
    return new HarnessWorkDispatcherConfig(
        THREAD_LEASE, MODEL_LEASE, TOOL_LEASE, POLL_INTERVAL, REJECTION_DELAY, maxDispatchTasks);
  }

  static MutableClock clock() {
    return new MutableClock(NOW);
  }

  /** 一次最小 THREAD 链：Session + ROOT + Thread(head=ROOT) + THREAD Work。 */
  record ThreadSeed(UUID threadId) {}

  static ThreadSeed seedThread(HarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertThread(thread(threadId, sessionId, rootEntryId));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), NOW);
          return new ThreadSeed(threadId);
        });
  }

  /** THREAD 链 + TURN_START + READY ModelInvocation + MODEL Work。 */
  record ModelSeed(UUID threadId, UUID modelInvocationId) {}

  static ModelSeed seedModel(HarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID modelId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, threadId));
          tx.insertThread(thread(threadId, sessionId, turnStartEntryId));
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  threadId,
                  turnStartEntryId,
                  turnStartEntryId,
                  modelRequest(),
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  NOW,
                  NOW));
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, modelId), NOW);
          return new ModelSeed(threadId, modelId);
        });
  }

  /**
   * MODEL 链 + USER/ASSISTANT(call-1) + terminal ModelInvocation + READY ToolInvocation + TOOL Work。
   */
  record ToolSeed(UUID threadId, UUID modelInvocationId, UUID toolInvocationId) {}

  static ToolSeed seedTool(HarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID modelId = tx.nextId();
          UUID toolId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, threadId));
          tx.insertEntry(userEntry(userEntryId, sessionId, turnStartEntryId));
          // SUCCEEDED assistant 由同一 request/response 经 mapper 派生（strict attach 校验要求全等）：请求带 bash
          // binding，使 assistant ToolCall renderer 与 ToolInvocation binding 全等。
          ModelRequestSpec requestSpec = tooledRequest();
          ProviderResponse response = toolResponse();
          tx.insertEntry(
              assistantEntry(assistantEntryId, sessionId, userEntryId, requestSpec, response));
          tx.insertThread(thread(threadId, sessionId, turnStartEntryId));
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  threadId,
                  turnStartEntryId,
                  turnStartEntryId,
                  requestSpec,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  NOW,
                  NOW));
          ModelInvocation current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.beginDispatch(NOW));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.markRunning(NOW));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.succeed(response, NOW));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.attachResultEntry(assistantEntryId, NOW));
          tx.insertToolInvocations(
              List.of(
                  new ToolInvocation(
                      toolId,
                      modelId,
                      assistantEntryId,
                      0,
                      toolRequest().call(),
                      toolRequest().binding(),
                      ToolInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      NOW,
                      NOW)));
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, toolId), NOW, ENV_ID);
          return new ToolSeed(threadId, modelId, toolId);
        });
  }

  static Work work(HarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target).orElse(null));
  }

  /** 测试可控时钟：默认始终返回整毫秒 {@link Instant}。 */
  static final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant now) {
      this.now = now;
    }

    Instant get() {
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

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** 包装 executor：统计 execute 调用、可编程拒绝，并可把 execute 钉住直到测试放行。 */
  static final class ControlledExecutor implements Executor {

    private final Executor delegate;
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicBoolean reject = new AtomicBoolean();
    private final AtomicBoolean blocking = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch blockGate = new CountDownLatch(1);

    ControlledExecutor(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      submissions.incrementAndGet();
      if (blocking.get()) {
        try {
          blockGate.await();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new RejectedExecutionException("interrupted while waiting for the executor gate");
        }
      }
      if (reject.get()) {
        throw new RejectedExecutionException("controlled rejection");
      }
      Throwable configuredFailure = failure.get();
      if (configuredFailure instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (configuredFailure instanceof Error error) {
        throw error;
      }
      delegate.execute(command);
    }

    int submissions() {
      return submissions.get();
    }

    void setReject(boolean reject) {
      this.reject.set(reject);
    }

    void setFailure(Throwable failure) {
      this.failure.set(failure);
    }

    void setBlocking(boolean blocking) {
      this.blocking.set(blocking);
    }

    void releaseBlocking() {
      blockGate.countDown();
    }
  }

  /**
   * 可编程阻塞 store：armed 后阻塞所有事务（except arming 线程）直到 release，用于确定性地把 drain 线程钉在 claim / return 事务边界上。
   */
  static final class BlockingStore implements HarnessStore {

    private final HarnessStore delegate;
    private final AtomicBoolean armed = new AtomicBoolean();
    private volatile Thread armingThread;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    BlockingStore(HarnessStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      if (armed.get() && Thread.currentThread() != armingThread) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while waiting for the store gate", error);
        }
      }
      return delegate.transaction(callback);
    }

    /** Arming 线程自身的事务不被阻塞（测试需要在其间读写 store）。 */
    void arm() {
      armingThread = Thread.currentThread();
      armed.set(true);
    }

    void awaitEntered() throws InterruptedException {
      if (!entered.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new AssertionError("store gate was not entered");
      }
    }

    void release() {
      armed.set(false);
      armingThread = null;
      release.countDown();
    }
  }

  /** 记录 scheduleWithFixedDelay 参数与 future 的 fake scheduler；poll 可手动触发。 */
  static final class RecordingScheduler implements ScheduledExecutorService {

    private final CopyOnWriteArrayList<ScheduledTask> tasks = new CopyOnWriteArrayList<>();

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      ScheduledTask task = new ScheduledTask(command, initialDelay, delay, unit);
      tasks.add(task);
      return task.future;
    }

    List<ScheduledTask> tasks() {
      return tasks;
    }

    void runPoll(int index) {
      tasks.get(index).command.run();
    }

    @Override
    public void execute(Runnable command) {
      throw unsupported();
    }

    @Override
    public void shutdown() {
      throw unsupported();
    }

    @Override
    public List<Runnable> shutdownNow() {
      throw unsupported();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return false;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw unsupported();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw unsupported();
    }

    @Override
    public Future<?> submit(Runnable task) {
      throw unsupported();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      throw unsupported();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      throw unsupported();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      throw unsupported();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException {
      throw unsupported();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw unsupported();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw unsupported();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
      return new UnsupportedOperationException("not used by the dispatcher");
    }

    static final class ScheduledTask {
      final Runnable command;
      final long initialDelay;
      final long delay;
      final TimeUnit unit;
      final RecordingFuture future = new RecordingFuture();

      ScheduledTask(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        this.command = command;
        this.initialDelay = initialDelay;
        this.delay = delay;
        this.unit = unit;
      }
    }

    static final class RecordingFuture implements ScheduledFuture<Object> {

      private final AtomicBoolean cancelled = new AtomicBoolean();
      private final AtomicInteger cancelCalls = new AtomicInteger();

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        cancelCalls.incrementAndGet();
        return !cancelled.getAndSet(true);
      }

      @Override
      public boolean isCancelled() {
        return cancelled.get();
      }

      int cancelCalls() {
        return cancelCalls.get();
      }

      @Override
      public boolean isDone() {
        return cancelled.get();
      }

      @Override
      public Object get() {
        return null;
      }

      @Override
      public Object get(long timeout, TimeUnit unit) {
        return null;
      }

      @Override
      public long getDelay(TimeUnit unit) {
        return 0;
      }

      @Override
      public int compareTo(Delayed other) {
        return 0;
      }
    }
  }

  static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("condition not met within " + AWAIT_TIMEOUT);
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
  }

  private static Session session(UUID id) {
    return new Session(id, "session-" + id, NOW);
  }

  private static Entry rootEntry(UUID id, UUID sessionId) {
    return new Entry(id, sessionId, null, new RootPayload(branchSettings()), NOW);
  }

  private static Entry turnStartEntry(UUID id, UUID sessionId, UUID parentId, UUID ownerThreadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.INPUT, branchSettings(), ownerThreadId, 100_000, 16_384, null),
        NOW);
  }

  private static Entry userEntry(UUID id, UUID sessionId, UUID parentId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null),
        NOW);
  }

  private static Entry assistantEntry(
      UUID id,
      UUID sessionId,
      UUID parentId,
      ModelRequestSpec requestSpec,
      ProviderResponse response) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new HistoryPayloadMapper().assistantPayload(response, requestSpec.toolBindings()),
        NOW);
  }

  private static ThreadState thread(UUID id, UUID sessionId, UUID headEntryId) {
    return new ThreadState(
        id, sessionId, headEntryId, CREATION_REQUEST_HASH, "main", false, 1, 0, NOW, NOW);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
  }

  private static ModelRequestSpec modelRequest() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 带 bash binding 的机械请求（与 {@link #toolRequest()} 的 binding renderer 全等）。 */
  private static ModelRequestSpec tooledRequest() {
    ModelRequestSpec base = modelRequest();
    return new ModelRequestSpec(
        base.providerType(),
        base.providerConnectionGenerationId(),
        base.model(),
        base.variant(),
        1024,
        "Test system instruction.",
        List.of(toolRequest().binding()),
        List.of(),
        List.of(),
        base.cacheControl());
  }

  private static ProviderResponse toolResponse() {
    return new ProviderResponse(
        "assistant reply",
        "",
        List.of(new ProviderToolCall("call-1", "bash", "{}")),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        "{}");
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        "model",
        Set.of(ModelInputModality.TEXT),
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

  private static ToolInvocationRequest toolRequest() {
    return new ToolInvocationRequest(
        new ToolCall("call-1", "bash", "{}"),
        new ToolBinding(
            new AgentToolDefinition(
                new ToolDescriptor(
                    "bash",
                    "description of bash",
                    "bash",
                    new InputSchema("arguments", Map.of(), Set.of(), false),
                    ToolSideEffect.READ_ONLY,
                    Duration.ofSeconds(30)),
                ToolVisibility.SELECTABLE),
            new ContributorBinding("test", "bash", List.of()),
            true,
            ENV_ID));
  }
}
