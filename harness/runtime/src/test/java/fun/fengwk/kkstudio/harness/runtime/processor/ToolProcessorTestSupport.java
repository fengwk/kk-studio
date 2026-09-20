package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.fail;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ToolProcessor 测试共享基座：InMemoryHarnessStore + fake ToolGateway / sink + 可变时钟 + 种子与断言 helper。
 *
 * <p>每个 Fixture 原子种子一条最小合法链：Session + ROOT + TURN_START + USER + ASSISTANT(call-1) + terminal
 * ModelInvocation（resultEntryId 指向 ASSISTANT）+ READY ToolInvocation + THREAD/TOOL Work。测试通过 {@link
 * #transition} 把 ToolInvocation 推进到 WAITING_APPROVAL / DISPATCHING / RUNNING / terminal 等前置状态。
 */
final class ToolProcessorTestSupport {

  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);
  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  static final Duration PREFLIGHT_FAILURE_DELAY = Duration.ofSeconds(7);
  static final Duration BUSY_FALLBACK_DELAY = Duration.ofSeconds(9);
  static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
  static final InvocationRetryPolicy RETRY_ONCE =
      new InvocationRetryPolicy(
          1, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
  static final InvocationRetryPolicy RETRY_TWICE =
      new InvocationRetryPolicy(
          2, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));

  private ToolProcessorTestSupport() {}

  /** 一次最小合法 Tool 链的持久 id 快照。 */
  record Baseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID userEntryId,
      UUID assistantEntryId,
      UUID threadId) {}

  record Seeded(UUID modelInvocationId, UUID toolInvocationId) {}

  static final class Fixture {
    final MutableClock clock = new MutableClock(NOW);
    final InMemoryHarnessStore store = new InMemoryHarnessStore();
    final FakeToolGateway gateway = new FakeToolGateway();
    final RecordingSink sink = new RecordingSink();
    final ScheduledExecutorService scheduler;
    final ToolInvocationRequest request;
    final Baseline baseline;
    final UUID modelInvocationId;
    final UUID toolInvocationId;
    final ToolProcessor processor;

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ToolSideEffect sideEffect,
        boolean yoloEnabled,
        ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
      this.request = toolRequest("call-1", sideEffect);
      this.baseline = seedToolBaseline(store, NOW, yoloEnabled);
      Seeded seeded = seedTool(store, baseline, request, NOW);
      this.modelInvocationId = seeded.modelInvocationId();
      this.toolInvocationId = seeded.toolInvocationId();
      this.processor =
          new ToolProcessor(
              store,
              gateway,
              sink,
              new ToolProcessorConfig(
                  LEASE_CONFIG, () -> retryPolicy, PREFLIGHT_FAILURE_DELAY, BUSY_FALLBACK_DELAY),
              clock,
              scheduler,
              Runnable::run);
    }

    /** 额外种子第二条完整 Tool 链（复用同一 request 的 call-1）。 */
    Seeded seedExtraTool() {
      Baseline extraBaseline = seedToolBaseline(store, NOW);
      return seedTool(store, extraBaseline, request, NOW);
    }
  }

  static Fixture fixture() {
    return fixture(NO_RETRY, ToolSideEffect.READ_ONLY);
  }

  static Fixture fixture(InvocationRetryPolicy retryPolicy) {
    return fixture(retryPolicy, ToolSideEffect.READ_ONLY);
  }

  static Fixture fixture(InvocationRetryPolicy retryPolicy, ToolSideEffect sideEffect) {
    return fixture(retryPolicy, sideEffect, false);
  }

  static Fixture fixture(
      InvocationRetryPolicy retryPolicy, ToolSideEffect sideEffect, boolean yoloEnabled) {
    return fixture(retryPolicy, sideEffect, yoloEnabled, newScheduler());
  }

  static Fixture fixture(
      InvocationRetryPolicy retryPolicy,
      ToolSideEffect sideEffect,
      boolean yoloEnabled,
      ScheduledExecutorService scheduler) {
    return new Fixture(retryPolicy, sideEffect, yoloEnabled, scheduler);
  }

  static Baseline seedToolBaseline(InMemoryHarnessStore store, Instant now) {
    return seedToolBaseline(store, now, false);
  }

  static Baseline seedToolBaseline(InMemoryHarnessStore store, Instant now, boolean yoloEnabled) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          // live attached tool baseline：assistant 由同一 frozen request + response 经 mapper 生成，与
          // seedTool 的
          // model request/successResponse 严格一致（renderer bash 来自 model request 的 bash binding）。
          ModelRequestSpec modelRequest = modelRequest();
          ProviderResponse response = successResponse("call-1");
          tx.insertSession(new Session(sessionId, "session-" + sessionId, now));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), threadId, 100_000, 16_384, null),
                  now.plusMillis(1)));
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  sessionId,
                  turnStartEntryId,
                  userMessagePayload(),
                  now.plusMillis(2)));
          tx.insertEntry(
              new Entry(
                  assistantEntryId,
                  sessionId,
                  userEntryId,
                  new HistoryPayloadMapper()
                      .assistantPayload(response, modelRequest.toolBindings()),
                  now.plusMillis(3)));
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  turnStartEntryId,
                  ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                  "main",
                  yoloEnabled,
                  1L,
                  0L,
                  now,
                  now));
          return new Baseline(
              sessionId, rootEntryId, turnStartEntryId, userEntryId, assistantEntryId, threadId);
        });
  }

  /**
   * 原子种子 terminal ModelInvocation（resultEntryId 指向 ASSISTANT，insert 只接受初始态，terminal apply 是 update）
   * + READY ToolInvocation + THREAD / TOOL Work。
   */
  static Seeded seedTool(
      InMemoryHarnessStore store, Baseline baseline, ToolInvocationRequest request, Instant now) {
    return store.transaction(
        tx -> {
          UUID modelId = tx.nextId();
          UUID toolId = tx.nextId();
          tx.lockThread(baseline.threadId());
          ModelRequestSpec modelRequest = modelRequest();
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  modelRequest,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  now,
                  now));
          ModelInvocation current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.beginDispatch(now));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.markRunning(now));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.succeed(successResponse(request.call().id()), now));
          current = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(current.attachResultEntry(baseline.assistantEntryId(), now));
          tx.insertToolInvocations(
              List.of(
                  new ToolInvocation(
                      toolId,
                      modelId,
                      baseline.assistantEntryId(),
                      0,
                      request.call(),
                      request.binding(),
                      ToolInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      now,
                      now)));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), now);
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, toolId), now);
          return new Seeded(modelId, toolId);
        });
  }

  static ClaimedWork claim(InMemoryHarnessStore store, UUID toolInvocationId, Instant now) {
    return claim(store, toolInvocationId, now, "token-" + toolInvocationId);
  }

  static ClaimedWork claim(
      InMemoryHarnessStore store, UUID toolInvocationId, Instant now, String token) {
    return claim(store, toolInvocationId, now, token, now.plusSeconds(60));
  }

  static ClaimedWork claim(
      InMemoryHarnessStore store,
      UUID toolInvocationId,
      Instant now,
      String token,
      Instant leaseUntil) {
    return store
        .transaction(tx -> tx.claimNextWork(WorkTargetType.TOOL, now, token, leaseUntil))
        .orElseThrow();
  }

  static void transition(
      InMemoryHarnessStore store,
      UUID toolInvocationId,
      Function<ToolInvocation, ToolInvocation> transition) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolInvocationId).orElseThrow();
          tx.updateToolInvocations(List.of(transition.apply(tool)));
          return null;
        });
  }

  /**
   * READY(null approval) -&gt; DISPATCHING 前置：approval 引入必须经过 READY -&gt; READY 的 not-required
   * 步骤（aggregate transition rule），因此分两步写。
   */
  static void toDispatched(InMemoryHarnessStore store, UUID toolInvocationId, Instant now) {
    transition(store, toolInvocationId, tool -> tool.markApprovalNotRequired(now));
    transition(store, toolInvocationId, tool -> tool.beginDispatch(now));
  }

  /**
   * READY(null approval) -&gt; RUNNING(attempt 1) 前置：not-required -&gt; beginDispatch -&gt;
   * markRunning。
   */
  static void toRunning(InMemoryHarnessStore store, UUID toolInvocationId, Instant now) {
    toDispatched(store, toolInvocationId, now);
    transition(store, toolInvocationId, tool -> tool.markRunning(now));
  }

  static ToolInvocation tool(InMemoryHarnessStore store, UUID toolInvocationId) {
    return store.transaction(tx -> tx.findToolInvocation(toolInvocationId)).orElseThrow();
  }

  static ModelInvocation model(InMemoryHarnessStore store, UUID modelInvocationId) {
    return store.transaction(tx -> tx.findModelInvocation(modelInvocationId)).orElseThrow();
  }

  static ThreadState thread(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId)).orElseThrow();
  }

  static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target)).orElse(null);
  }

  static Work toolWork(InMemoryHarnessStore store, UUID toolInvocationId) {
    return work(store, new WorkTarget(WorkTargetType.TOOL, toolInvocationId));
  }

  static void deleteToolWork(Fixture fixture) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          tx.deleteWork(new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId));
          return null;
        });
  }

  static void replaceToolWork(Fixture fixture, Instant requestedAt) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          WorkTarget target = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
          tx.deleteWork(target);
          tx.requestWork(target, requestedAt);
          return null;
        });
  }

  static void requestToolWork(Fixture fixture, Instant requestedAt) {
    fixture.store.transaction(
        tx -> {
          tx.lockThread(fixture.baseline.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId), requestedAt);
          return null;
        });
  }

  static Work threadWork(InMemoryHarnessStore store, UUID threadId) {
    return work(store, new WorkTarget(WorkTargetType.THREAD, threadId));
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

  static ToolInvocationRequest toolRequest(String toolCallId, ToolSideEffect sideEffect) {
    return new ToolInvocationRequest(
        new ToolCall(toolCallId, "bash", "{}"), hostBinding(sideEffect));
  }

  static ToolResult partialResult(String toolCallId) {
    return new ToolResult(toolCallId, List.of(new TextResultContent("partial")), false, "{}");
  }

  static ToolResult successResult(String toolCallId, ResultContent... contents) {
    return new ToolResult(toolCallId, List.of(contents), false, "{}");
  }

  private static ToolBinding hostBinding(ToolSideEffect sideEffect) {
    return new ToolBinding(
        new AgentToolDefinition(toolDescriptor(sideEffect), ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ToolDescriptor toolDescriptor(ToolSideEffect sideEffect) {
    return new ToolDescriptor(
        "bash",
        "description of bash",
        "bash",
        new InputSchema("arguments", Map.of(), Set.of(), false),
        sideEffect,
        Duration.ofSeconds(30));
  }

  private static ModelRequestSpec modelRequest() {
    ProviderRequest provider = providerRequest();
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        "Test system instruction.",
        List.of(hostBinding(ToolSideEffect.READ_ONLY)),
        List.of(),
        provider.cacheControl());
  }

  private static ProviderRequest providerRequest() {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
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

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
  }

  private static EntryPayload userMessagePayload() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  private static ProviderResponse successResponse(String... toolCallIds) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      calls.add(new ProviderToolCall(toolCallId, "bash", "{}"));
    }
    return new ProviderResponse(
        "assistant reply",
        "",
        calls,
        calls.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE,
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

  /** 从 READY / approval null 构造 required undecided approval（WAITING_APPROVAL 前置）。 */
  static ToolInvocation waitingApproval(ToolInvocation tool, String reason, Instant now) {
    return tool.requestApproval(reason, now);
  }

  /** 从 WAITING_APPROVAL 推进为 READY + ALLOWED decision（completed approval 前置）。 */
  static ToolInvocation decideAllowed(ToolInvocation tool, Instant now) {
    return tool.decideApproval(
        ToolApprovalDecision.ALLOWED, new UUID(0L, 1L), "actor", "ok", now, now);
  }

  static ScheduledExecutorService newScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "tool-processor-test");
          thread.setDaemon(true);
          return thread;
        });
  }

  static final class FakeToolGateway implements ToolGateway {
    private static final Object NULL_PREFLIGHT = new Object();
    private static final Object NULL_START = new Object();
    final LinkedList<Object> preflightResults = new LinkedList<>();
    final LinkedList<Object> startResults = new LinkedList<>();
    final List<Execution> executions = new CopyOnWriteArrayList<>();
    final List<PreflightCall> preflightCalls = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<UUID, Listener> listeners = new ConcurrentHashMap<>();
    volatile Consumer<Listener> beforeStartReturn;
    volatile Consumer<PreflightCall> preflightHook;
    volatile int preflightCallsCount;
    volatile int startCalls;

    void queuePreflightAllow() {
      preflightResults.add(new ToolGateway.Allow());
    }

    void queuePreflightAsk(String reason) {
      preflightResults.add(new ToolGateway.Ask(reason));
    }

    void queuePreflightDeny(ToolInvocationError error) {
      preflightResults.add(new ToolGateway.Deny(error));
    }

    void queuePreflightFailure(RuntimeException failure) {
      preflightResults.add(failure);
    }

    void queueNullPreflight() {
      preflightResults.add(NULL_PREFLIGHT);
    }

    void queueStart(Object result) {
      startResults.add(result);
    }

    void queueNullStart() {
      startResults.add(NULL_START);
    }

    @Override
    public PreflightResult preflight(ToolInvocationRequest request) {
      preflightCallsCount++;
      PreflightCall call = new PreflightCall(request);
      preflightCalls.add(call);
      Object result = preflightResults.poll();
      if (result == null) {
        throw new IllegalStateException("no queued preflight result");
      }
      Consumer<PreflightCall> hook = preflightHook;
      if (hook != null) {
        hook.accept(call);
      }
      if (result == NULL_PREFLIGHT) {
        return null;
      }
      if (result instanceof RuntimeException failure) {
        throw failure;
      }
      return (PreflightResult) result;
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      startCalls++;
      executions.add(execution);
      listeners.put(execution.invocationId(), listener);
      Object result = startResults.poll();
      if (result == null) {
        throw new IllegalStateException("no queued start result");
      }
      Consumer<Listener> hook = beforeStartReturn;
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

  record PreflightCall(ToolInvocationRequest request) {}

  static class FakeHandle implements ToolGateway.Handle {
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

    /** 测试钩子：在事件真正入列之前执行，用于精确制造并发窗口。 */
    volatile Runnable beforeAppend;

    @Override
    public void append(RealtimeEvent event) {
      RuntimeException current = failure;
      if (current != null) {
        throw current;
      }
      Runnable hook = beforeAppend;
      if (hook != null) {
        hook.run();
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
