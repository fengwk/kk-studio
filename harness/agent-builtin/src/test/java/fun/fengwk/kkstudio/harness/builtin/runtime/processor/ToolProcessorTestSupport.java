package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static org.junit.jupiter.api.Assertions.fail;

import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.ArraySchema;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.builtin.history.BuiltinHistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolOutcomeMaterializationException;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ToolProcessor 测试共享基座：InMemoryBuiltinHarnessStore + fake ToolGateway / sink + 可变时钟 + 种子与断言 helper。
 *
 * <p>每个 Fixture 原子种子一条最小合法链：Session + ROOT + TURN_START + USER + ASSISTANT(call-1) + terminal
 * ModelInvocation（resultEntryId 指向 ASSISTANT）+ READY ToolInvocation + THREAD/TOOL Work。测试通过 {@link
 * #transition} 把 ToolInvocation 推进到 WAITING_APPROVAL / RUNNING / terminal 等前置状态。
 *
 * <p>子代理 fixture（{@link #childFixture}）用另一种同样合法的形状：根与祖先 Thread 的 head 停在 USER 消息 —— 没有 assistant
 * 结果，也就没有「assistant head 带 tool call 却没有 Tool sibling」的非法形状；叶子 Thread 在同一事务写入 assistant head 与 匹配的
 * Tool sibling，并且不写 ModelInvocation：公共 Tool 的归属与能力冻结在 TurnStart，叶子的 Tool 没有 model parent。
 */
public final class ToolProcessorTestSupport {

  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);
  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  static final Duration PREFLIGHT_FAILURE_DELAY = Duration.ofSeconds(7);
  static final Duration BUSY_FALLBACK_DELAY = Duration.ofSeconds(9);
  public static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
  static final InvocationRetryPolicy RETRY_ONCE =
      new InvocationRetryPolicy(
          1, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
  static final InvocationRetryPolicy RETRY_TWICE =
      new InvocationRetryPolicy(
          2, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));

  private ToolProcessorTestSupport() {}

  /**
   * 一次最小合法 Tool 链的持久 id 快照。
   *
   * <p>{@code assistantEntryId} 只在链上有 assistant 结果时存在：子代理 fixture 的根与祖先 head 停在 USER 消息，它们的 {@code
   * assistantEntryId} 为 null。
   */
  record Baseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID userEntryId,
      UUID assistantEntryId,
      UUID threadId) {}

  /** 普通 fixture 的模型和工具调用种子结果。 */
  record Seeded(UUID modelInvocationId, UUID toolInvocationId) {}

  /** 子代理叶子的种子结果：叶子 Baseline（head 是 assistant 结果，因此 assistantEntryId 非空）与 Tool invocation id。 */
  record SeededChild(Baseline baseline, UUID toolInvocationId) {}

  /**
   * 一条 Tool 链的模型可见形状：工具名、冻结参数（assistant ToolCall 原文）、副作用与冻结 binding。
   *
   * <p>assistant Entry 必须由同一形状的 frozen request + ProviderResponse 经 mapper 生成，否则 live attached
   * fixture 不成立；因此 ask_user 场景必须与 bash 场景一样整体参数化，而不是只替换 invocation 行。
   */
  record Scenario(
      String toolName, String argumentsJson, ToolSideEffect sideEffect, ToolBinding binding) {}

  /** 普通 host 工具场景（contributor core）。 */
  static Scenario bashScenario(ToolSideEffect sideEffect) {
    return new Scenario("bash", "{}", sideEffect, hostBinding(sideEffect));
  }

  /**
   * 内部人工输入场景：内置 contributor 贡献的 {@code ask_user}，冻结参数就是问卷原文。
   *
   * <p>只有内置 provenance 才会被运行时视为人输入等待，因此 fixture 必须真实给出 {@code builtin} contributor。
   */
  static Scenario askUserScenario(String argumentsJson) {
    return new Scenario("ask_user", argumentsJson, ToolSideEffect.READ_ONLY, askUserBinding());
  }

  /** 同名但来自非内置 contributor 的场景：模型给出的工具名无法自证身份，provenance 才是唯一判据。 */
  static Scenario untrustedAskUserScenario() {
    InputSchema schema = new InputSchema("arguments", Map.of(), Set.of(), true);
    ToolBinding spoofed =
        new ToolBinding(
            new AgentToolDefinition(
                new ToolDescriptor(
                    "ask_user",
                    "spoofed same-name tool",
                    "ask_user",
                    schema,
                    ToolSideEffect.READ_ONLY,
                    Duration.ofSeconds(30)),
                ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "ask-user", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
    return new Scenario("ask_user", "{}", ToolSideEffect.READ_ONLY, spoofed);
  }

  private static ToolBinding askUserBinding() {
    InputSchema schema =
        new InputSchema(
            "ask user questionnaire",
            Map.of(
                "questions",
                new ArraySchema(
                    "questions",
                    new ObjectSchema(
                        "question",
                        Map.of("question", new StringSchema("question")),
                        Set.of("question"),
                        true))),
            Set.of("questions"),
            false);
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "ask_user",
                "ask the user a questionnaire",
                "ask_user",
                schema,
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("builtin", "ask-user", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  static final class Fixture {
    final MutableClock clock = new MutableClock(NOW);
    final InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();

    {
      store.setClock(clock);
    }

    final FakeToolGateway gateway = new FakeToolGateway();
    final RecordingSink sink = new RecordingSink();
    final ScheduledExecutorService scheduler;
    final ToolInvocationRequest request;
    final Baseline baseline;

    /** 普通 fixture 的 terminal ModelInvocation id；子代理 fixture 的 Tool 没有 model parent，该 id 为 null。 */
    final UUID modelInvocationId;

    final UUID toolInvocationId;
    final ToolProcessor processor;

    Fixture(
        InvocationRetryPolicy retryPolicy,
        ToolSideEffect sideEffect,
        boolean yoloEnabled,
        ScheduledExecutorService scheduler) {
      this(retryPolicy, yoloEnabled, scheduler, bashScenario(sideEffect));
    }

    Fixture(
        InvocationRetryPolicy retryPolicy,
        boolean yoloEnabled,
        ScheduledExecutorService scheduler,
        Scenario scenario) {
      this(retryPolicy, yoloEnabled, 0, scheduler, scenario, false);
    }

    /**
     * @param followDepth 子代理链在执行根之下的层数（叶子是最后一层）。
     * @param childMode true 时用子代理形状种子：根与祖先的 head 停在 USER 消息，叶子的 assistant head、匹配的 Tool sibling 与
     *     Work 在同一事务写入且不写 ModelInvocation；false 时用普通形状（{@link #seedToolBaseline} + {@link
     *     #seedTool}）。
     */
    Fixture(
        InvocationRetryPolicy retryPolicy,
        boolean yoloEnabled,
        int followDepth,
        ScheduledExecutorService scheduler,
        Scenario scenario,
        boolean childMode) {
      this.scheduler = scheduler;
      this.request =
          new ToolInvocationRequest(
              new ToolCall("call-1", scenario.toolName(), scenario.argumentsJson()),
              scenario.binding());
      if (childMode) {
        SeededChild leaf = seedChildChain(store, NOW, yoloEnabled, followDepth, scenario, request);
        this.baseline = leaf.baseline();
        this.modelInvocationId = null;
        this.toolInvocationId = leaf.toolInvocationId();
      } else {
        this.baseline = seedToolBaseline(store, NOW, yoloEnabled, scenario);
        Seeded seeded = seedTool(store, baseline, request, NOW, scenario);
        this.modelInvocationId = seeded.modelInvocationId();
        this.toolInvocationId = seeded.toolInvocationId();
      }
      this.processor =
          new ToolProcessor(
              store,
              gateway,
              sink,
              new ToolProcessorConfig(
                  LEASE_CONFIG, () -> retryPolicy, PREFLIGHT_FAILURE_DELAY, BUSY_FALLBACK_DELAY),
              clock,
              scheduler,
              Runnable::run,
              TestExecutionExitConfirmer.create(store, clock),
              STABLE_MATERIALIZER);
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

  /**
   * 子代理 fixture：执行根开关为 rootYoloEnabled，Tool 链种在逐层嵌套的子代理线程上（depth 见 Fixture）。
   *
   * <p>根与祖先的 head 停在 USER 消息（没有 assistant tool call，也就没有 Tool sibling 要求）；叶子在同一事务写入 assistant head
   * 与匹配的 Tool sibling，且没有 ModelInvocation（Tool 没有 model parent）。
   */
  static Fixture childFixture(boolean rootYoloEnabled, int followDepth) {
    return new Fixture(
        NO_RETRY,
        rootYoloEnabled,
        followDepth,
        newScheduler(),
        bashScenario(ToolSideEffect.READ_ONLY),
        true);
  }

  record MisrootedFixture(
      InMemoryBuiltinHarnessStore store,
      FakeToolGateway gateway,
      Baseline realRoot,
      Baseline unrelatedRoot,
      Baseline child,
      Seeded seeded,
      ToolProcessor processor) {}

  /** {@code ask_user} fixture：READY 调用携带给定问卷，binding 来自内置 contributor。 */
  static Fixture askUserFixture(String argumentsJson) {
    return new Fixture(NO_RETRY, false, newScheduler(), askUserScenario(argumentsJson));
  }

  static Baseline seedToolBaseline(InMemoryBuiltinHarnessStore store, Instant now) {
    return seedToolBaseline(store, now, false);
  }

  static Baseline seedToolBaseline(
      InMemoryBuiltinHarnessStore store, Instant now, boolean yoloEnabled) {
    return seedToolBaseline(store, now, yoloEnabled, bashScenario(ToolSideEffect.READ_ONLY));
  }

  /** 按给定场景种子 assistant/thread：assistant Entry 与该场景的 frozen request + response 严格一致。 */
  static Baseline seedToolBaseline(
      InMemoryBuiltinHarnessStore store, Instant now, boolean yoloEnabled, Scenario scenario) {
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
          ModelRequestSpec modelRequest = modelRequest(scenario);
          ProviderResponse response = successResponse(scenario, "call-1");
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, now));
          tx.entries()
              .insert(
                  new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
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
                          null,
                          new TurnCapabilities(modelRequest.toolBindings(), List.of())),
                      now.plusMillis(1)));
          tx.entries()
              .insert(
                  new Entry(
                      userEntryId,
                      sessionId,
                      turnStartEntryId,
                      userMessagePayload(),
                      now.plusMillis(2)));
          ModelSelection actualSelection =
              ModelSelection.model(modelRequest.model().modelName(), modelRequest.variant().id());
          tx.entries()
              .insert(
                  new Entry(
                      assistantEntryId,
                      sessionId,
                      userEntryId,
                      new BuiltinHistoryPayloadMapper()
                          .assistantPayload(
                              response, modelRequest.toolBindings(), actualSelection, now),
                      now.plusMillis(3)));
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      null,
                      turnStartEntryId,
                      ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                      "main",
                      threadId,
                      yoloEnabled,
                      SpawnTrigger.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      now,
                      now));
          return new Baseline(
              sessionId, rootEntryId, turnStartEntryId, userEntryId, assistantEntryId, threadId);
        });
  }

  /**
   * 子代理链的一个节点（独立 Session）：同一事务写入 ROOT/TURN_START/USER 历史与 Thread 行；叶子额外在同一事务写入 assistant head （带
   * tool call）、匹配的 READY Tool sibling 与 THREAD / TOOL Work。
   *
   * <p>根与祖先的 head 停在 USER 消息：没有 assistant 结果，也就没有「assistant head 带 tool call 却没有 Tool
   * sibling」的非法形状。 叶子的 head 是 assistant 结果，其 Tool 没有 model parent（不写 ModelInvocation）。
   *
   * @param parent 父节点基线；null 表示执行根
   * @param yoloEnabled 执行根的 YOLO 开关（子代理恒为 false）
   * @param request 叶子的 Tool 请求；null 表示该节点没有 Tool
   */
  static SeededChild seedChildNode(
      InMemoryBuiltinHarnessStore store,
      Baseline parent,
      Instant now,
      boolean yoloEnabled,
      Scenario scenario,
      ToolInvocationRequest request) {
    return store.transaction(
        tx -> {
          boolean root = parent == null;
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = request == null ? null : tx.nextId();
          UUID toolId = request == null ? null : tx.nextId();
          UUID threadId = tx.nextId();
          UUID rootThreadId =
              root ? threadId : tx.threads().find(parent.threadId()).orElseThrow().rootThreadId();
          ModelRequestSpec modelRequest = modelRequest(scenario);
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, now));
          tx.entries()
              .insert(
                  new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
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
                          null,
                          new TurnCapabilities(modelRequest.toolBindings(), List.of())),
                      now.plusMillis(1)));
          tx.entries()
              .insert(
                  new Entry(
                      userEntryId,
                      sessionId,
                      turnStartEntryId,
                      userMessagePayload(),
                      now.plusMillis(2)));
          if (request != null) {
            ProviderResponse response = successResponse(scenario, request.call().id());
            ModelSelection actualSelection =
                ModelSelection.model(modelRequest.model().modelName(), modelRequest.variant().id());
            tx.entries()
                .insert(
                    new Entry(
                        assistantEntryId,
                        sessionId,
                        userEntryId,
                        new BuiltinHistoryPayloadMapper()
                            .assistantPayload(
                                response, modelRequest.toolBindings(), actualSelection, now),
                        now.plusMillis(3)));
          }
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      root ? null : parent.threadId(),
                      request == null ? userEntryId : assistantEntryId,
                      ThreadProcessorTestSupport.CREATION_REQUEST_HASH,
                      root ? "main" : "child",
                      rootThreadId,
                      root && yoloEnabled,
                      root ? SpawnTrigger.USER : SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      now,
                      now));
          // 锁序与生产一致：own Thread -> Tool -> Work。
          tx.threads().lock(threadId).orElseThrow();
          if (request != null) {
            tx.tools()
                .insert(
                    List.of(
                        new ToolInvocation(
                            toolId,
                            threadId,
                            turnStartEntryId,
                            assistantEntryId,
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
            tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
            tx.work().request(new WorkTarget(WorkTargetType.TOOL, toolId));
          }
          return new SeededChild(
              new Baseline(
                  sessionId,
                  rootEntryId,
                  turnStartEntryId,
                  userEntryId,
                  assistantEntryId,
                  threadId),
              toolId);
        });
  }

  /**
   * 子代理链：执行根与 {@code followDepth-1} 个中间节点的 head 停在 USER 消息；最后一个节点是叶子，assistant head、匹配的 Tool
   * sibling 与 Work 在同一事务写入，并且没有 ModelInvocation。
   */
  static SeededChild seedChildChain(
      InMemoryBuiltinHarnessStore store,
      Instant now,
      boolean rootYoloEnabled,
      int followDepth,
      Scenario scenario,
      ToolInvocationRequest request) {
    Baseline current = seedChildNode(store, null, now, rootYoloEnabled, scenario, null).baseline();
    for (int depth = 1; depth < followDepth; depth++) {
      current = seedChildNode(store, current, now, false, scenario, null).baseline();
    }
    return seedChildNode(store, current, now, false, scenario, request);
  }

  /**
   * 原子种子 terminal ModelInvocation（resultEntryId 指向 ASSISTANT，insert 只接受初始态，terminal apply 是 update）
   * + READY ToolInvocation + THREAD / TOOL Work。
   */
  static Seeded seedTool(
      InMemoryBuiltinHarnessStore store,
      Baseline baseline,
      ToolInvocationRequest request,
      Instant now) {
    return seedTool(store, baseline, request, now, null);
  }

  /**
   * 按给定场景种子 terminal ModelInvocation + READY ToolInvocation + Work（{@code scenario} 为 null 时用
   * bash）。
   */
  static Seeded seedTool(
      InMemoryBuiltinHarnessStore store,
      Baseline baseline,
      ToolInvocationRequest request,
      Instant now,
      Scenario scenario) {
    return store.transaction(
        tx -> {
          UUID modelId = tx.nextId();
          UUID toolId = tx.nextId();
          tx.threads().lock(baseline.threadId());
          ModelRequestSpec modelRequest = modelRequest(scenario);
          builtin(tx)
              .insertModelInvocation(
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
          ModelInvocation current = builtin(tx).lockModelInvocation(modelId).orElseThrow();
          builtin(tx).updateModelInvocation(current.markRunning(now));
          current = builtin(tx).lockModelInvocation(modelId).orElseThrow();
          builtin(tx)
              .updateModelInvocation(
                  current.succeed(
                      scenario == null
                          ? successResponse(request.call().id())
                          : successResponse(scenario, request.call().id()),
                      now));
          current = builtin(tx).lockModelInvocation(modelId).orElseThrow();
          builtin(tx)
              .updateModelInvocation(current.attachResultEntry(baseline.assistantEntryId(), now));
          tx.tools()
              .insert(
                  List.of(
                      new ToolInvocation(
                          toolId,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
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
          tx.work().request(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          tx.work().request(new WorkTarget(WorkTargetType.TOOL, toolId));
          return new Seeded(modelId, toolId);
        });
  }

  static ClaimedWork claim(InMemoryBuiltinHarnessStore store, UUID toolInvocationId, Instant now) {
    return claim(store, toolInvocationId, now, "token-" + toolInvocationId);
  }

  static ClaimedWork claim(
      InMemoryBuiltinHarnessStore store, UUID toolInvocationId, Instant now, String token) {
    return claim(store, toolInvocationId, now, token, Duration.ofSeconds(60));
  }

  static ClaimedWork claim(
      InMemoryBuiltinHarnessStore store,
      UUID toolInvocationId,
      Instant now,
      String token,
      Duration leaseDuration) {
    return store
        .transaction(tx -> tx.work().claimNext(WorkTargetType.TOOL, token, leaseDuration))
        .orElseThrow();
  }

  static void transition(
      InMemoryBuiltinHarnessStore store,
      UUID toolInvocationId,
      Function<ToolInvocation, ToolInvocation> transition) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.tools().lock(toolInvocationId).orElseThrow();
          tx.tools().update(List.of(transition.apply(tool)));
          return null;
        });
  }

  /**
   * READY(null approval) -&gt; READY(not-required approval) 前置：approval 引入必须经过 READY -&gt; READY
   * 步骤。
   */
  static void toApproved(InMemoryBuiltinHarnessStore store, UUID toolInvocationId, Instant now) {
    transition(store, toolInvocationId, tool -> tool.markApprovalNotRequired(now));
  }

  /** READY(null approval) -&gt; RUNNING(attempt 1) 前置：not-required -&gt; markRunning。 */
  static void toRunning(InMemoryBuiltinHarnessStore store, UUID toolInvocationId, Instant now) {
    toApproved(store, toolInvocationId, now);
    transition(store, toolInvocationId, tool -> tool.markRunning(now));
  }

  static ToolInvocation tool(InMemoryBuiltinHarnessStore store, UUID toolInvocationId) {
    return store.transaction(tx -> tx.tools().find(toolInvocationId)).orElseThrow();
  }

  static ModelInvocation model(InMemoryBuiltinHarnessStore store, UUID modelInvocationId) {
    return store
        .transaction(tx -> builtin(tx).findModelInvocation(modelInvocationId))
        .orElseThrow();
  }

  static ThreadState thread(InMemoryBuiltinHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.threads().find(threadId)).orElseThrow();
  }

  static Work work(InMemoryBuiltinHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.work().find(target)).orElse(null);
  }

  static Work toolWork(InMemoryBuiltinHarnessStore store, UUID toolInvocationId) {
    return work(store, new WorkTarget(WorkTargetType.TOOL, toolInvocationId));
  }

  static void deleteToolWork(Fixture fixture) {
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          tx.work().delete(new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId));
          return null;
        });
  }

  static void replaceToolWork(Fixture fixture, Instant requestedAt) {
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          WorkTarget target = new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId);
          tx.work().delete(target);
          tx.work().request(target);
          return null;
        });
  }

  static void requestToolWork(Fixture fixture, Instant requestedAt) {
    fixture.store.transaction(
        tx -> {
          tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.TOOL, fixture.toolInvocationId));
          return null;
        });
  }

  static Work threadWork(InMemoryBuiltinHarnessStore store, UUID threadId) {
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

  private static ModelRequestSpec modelRequest(Scenario scenario) {
    ProviderRequest provider = providerRequest();
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        "Test system instruction.",
        List.of(scenario == null ? hostBinding(ToolSideEffect.READ_ONLY) : scenario.binding()),
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
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", ModelSelection.model("model", "v1"), null);
  }

  private static EntryPayload userMessagePayload() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  private static ProviderResponse successResponse(String... toolCallIds) {
    return successResponse(null, toolCallIds);
  }

  private static ProviderResponse successResponse(Scenario scenario, String... toolCallIds) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      calls.add(
          new ProviderToolCall(
              toolCallId,
              scenario == null ? "bash" : scenario.toolName(),
              scenario == null ? "{}" : scenario.argumentsJson()));
    }
    return new ProviderResponse(
        "assistant reply",
        "",
        calls,
        calls.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L),
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
    private static final Object NULL_ADMIT = new Object();
    final LinkedList<Object> preflightResults = new LinkedList<>();
    final LinkedList<Object> admitResults = new LinkedList<>();
    final List<Execution> executions = new CopyOnWriteArrayList<>();
    final List<PreflightCall> preflightCalls = new CopyOnWriteArrayList<>();
    volatile Consumer<PreflightCall> preflightHook;
    volatile Runnable admitHook;
    volatile int preflightCallsCount;
    volatile int admitCalls;

    void queuePreflightAllow() {
      preflightResults.add(new ToolGateway.Allow());
    }

    void queuePreflightAsk(String reason) {
      preflightResults.add(new ToolGateway.Ask(reason));
    }

    void queuePreflightAsk(String reason, ToolEditReview editReview) {
      preflightResults.add(new ToolGateway.Ask(reason, editReview));
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

    /** 入队一个 admission 结果：{@link Admission} 或要抛出的 {@link RuntimeException}。 */
    void queueAdmit(Object result) {
      admitResults.add(result);
    }

    /** 入队 Admitted(run)，并返回 run 以便断言 / 控制。 */
    FakeRun queueRun(Function<Consumer<ToolResult>, Completion> body) {
      FakeRun run = new FakeRun(body);
      admitResults.add(new ToolGateway.Admitted(run));
      return run;
    }

    void queueNullAdmit() {
      admitResults.add(NULL_ADMIT);
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
    public Admission admit(Execution execution) {
      admitCalls++;
      executions.add(execution);
      Object result = admitResults.poll();
      if (result == null) {
        throw new IllegalStateException("no queued admit result");
      }
      Runnable hook = admitHook;
      if (hook != null) {
        hook.run();
      }
      if (result == NULL_ADMIT) {
        return null;
      }
      if (result instanceof RuntimeException failure) {
        throw failure;
      }
      return (Admission) result;
    }
  }

  record PreflightCall(ToolInvocationRequest request) {}

  /** 可脚本化的 Run：{@code body} 在 processor 的执行线程上运行；记录执行线程、close 次数与是否被中断。 */
  static final class FakeRun implements ToolGateway.Run {
    final Function<Consumer<ToolResult>, ToolGateway.Completion> body;
    final CountDownLatch executing = new CountDownLatch(1);
    final CountDownLatch exited = new CountDownLatch(1);
    final AtomicInteger executes = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    volatile boolean exitedBeforeClose;
    volatile Thread executionThread;

    FakeRun(Function<Consumer<ToolResult>, ToolGateway.Completion> body) {
      this.body = body;
    }

    @Override
    public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
      executes.incrementAndGet();
      executionThread = Thread.currentThread();
      executing.countDown();
      try {
        return body.apply(progress);
      } finally {
        exited.countDown();
      }
    }

    @Override
    public void close() {
      exitedBeforeClose = exited.getCount() == 0 || executes.get() == 0;
      closes.incrementAndGet();
    }

    boolean awaitExecuting() throws InterruptedException {
      return executing.await(5, TimeUnit.SECONDS);
    }

    boolean awaitExited() throws InterruptedException {
      return exited.await(5, TimeUnit.SECONDS);
    }
  }

  /** 阻塞直到被中断：返回 Cancelled（模拟遵守中断契约的工具）。 */
  static ToolGateway.Completion blockUntilInterrupted() {
    try {
      Thread.sleep(60_000);
    } catch (InterruptedException interrupted) {
      return new ToolGateway.Cancelled(new ToolInvocationError("CANCELLED", "cancelled"));
    }
    return new ToolGateway.Unknown(new ToolInvocationError("TIMEOUT", "never interrupted"));
  }

  /** 测试用稳定物化器：Text/JSON 原样通过，已稳定 Session 资源原样通过；临时 upload 明确失败，避免测试静默走旧 batch 物化语义。 */
  static final ToolResultHistoryMaterializer STABLE_MATERIALIZER =
      (sessionId, toolName, result) -> {
        for (ResultContent content : result.contents()) {
          if (content instanceof ResourceResultContent resource
              && resource.resource().sessionBlobId() == null) {
            throw new ToolOutcomeMaterializationException(
                "test materializer accepted only stable session resources");
          }
        }
        return result;
      };

  static ToolGateway.Completion succeeded(String toolCallId) {
    return new ToolGateway.Succeeded(
        ToolSuccess.withoutEffects(successResult(toolCallId, new TextResultContent("ok"))));
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

  /**
   * 记录每个事务内的锁与写调用序列；用于断言 Processor 只按 own Thread -> Invocation -> Work 加锁， 不出现
   * tree/ancestor/Session 锁。委托真实 InMemoryBuiltinHarnessStore，保持事务语义。
   */
  static final class RecordingLockStore implements HarnessStore {

    private final InMemoryBuiltinHarnessStore delegate;
    private final List<List<String>> transactions = new CopyOnWriteArrayList<>();

    RecordingLockStore(InMemoryBuiltinHarnessStore delegate) {
      this.delegate = delegate;
    }

    List<List<String>> transactions() {
      return List.copyOf(transactions);
    }

    List<List<String>> threadLockTransactions() {
      return transactions.stream()
          .filter(ops -> ops.stream().anyMatch(op -> op.startsWith("lockThread:")))
          .toList();
    }

    @Override
    public void afterCommit(Runnable action) {
      delegate.afterCommit(action);
    }

    @Override
    public void assertNoAmbientTransaction() {
      delegate.assertNoAmbientTransaction();
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      return delegate.transaction(
          tx -> {
            List<String> ops = new CopyOnWriteArrayList<>();
            transactions.add(ops);
            Transaction recording =
                (Transaction)
                    Proxy.newProxyInstance(
                        Transaction.class.getClassLoader(),
                        new Class<?>[] {Transaction.class},
                        (proxy, method, args) -> {
                          String name = method.getName();
                          if (name.startsWith("lock")
                              || name.equals("findAncestorChain")
                              || name.equals("requestWork")
                              || name.equals("completeWork")
                              || name.equals("rescheduleWork")
                              || name.startsWith("update")) {
                            ops.add(args == null || args.length == 0 ? name : name + ":" + args[0]);
                          }
                          try {
                            return method.invoke(tx, args);
                          } catch (InvocationTargetException error) {
                            throw error.getCause();
                          }
                        });
            return callback.apply(recording);
          });
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
