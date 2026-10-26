package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 真实临时 PostgreSQL 端到端集成测试：覆盖压缩子 Thread 创建、父 durable wait、子真实执行到终态、父消费并按摘要提交。 */
class PostgresqlCompactionChildTest {

  private static final ModelUsage OVER_THRESHOLD_USAGE =
      new ModelUsage(90_000L, 2L, 0L, 0L, Map.of(), 0L, 90_002L);
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;

  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
  private static final InvocationRetryPolicy RETRY_ONCE =
      new InvocationRetryPolicy(
          1, InvocationRetryBackoffStrategy.FIXED, Duration.ofMillis(1), Duration.ofMillis(1));

  private HarnessStore store;
  private HarnessRuntime runtime;
  private ThreadProcessor processor;
  private ScheduledExecutorService scheduler;
  private ScriptedChildGateway gateway;
  private ModelProcessor modelProcessor;
  private TurnResolver resolver;
  private volatile InvocationRetryPolicy retryPolicy = NO_RETRY;

  /** 在已 spawn 的 owner 上补两段越过压缩阈值的闭合历史，并把 spawn 源命令标记为第一段的实际输入。 */
  private void seedOwnerCompactionHistory(UUID ownerThreadId) {
    store.transaction(
        tx -> {
          ThreadState owner = ThreadLifecycleCoordinator.lockThreadWithParent(tx, ownerThreadId);
          BranchSettings settings = tx.loadEntryPath(owner.headEntryId()).baseSettings();
          ThreadCommand source =
              tx.loadQueuedCommands(ownerThreadId).stream()
                  .filter(command -> command.sequence() == 1L)
                  .findFirst()
                  .orElseThrow();
          UserMessageCommandPayload sourcePayload = (UserMessageCommandPayload) source.payload();
          UUID turn1StartId = tx.nextId();
          UUID turn1UserId = tx.nextId();
          UUID turn1AssistantId = tx.nextId();
          UUID turn1EndId = tx.nextId();
          UUID turn2StartId = tx.nextId();
          UUID turn2UserId = tx.nextId();
          UUID turn2AssistantId = tx.nextId();
          UUID turn2EndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turn1StartId,
                  owner.sessionId(),
                  owner.headEntryId(),
                  inputTurnStart(settings, ownerThreadId),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1UserId,
                  owner.sessionId(),
                  turn1StartId,
                  new MessagePayload(sourcePayload.message(), null, null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1AssistantId,
                  owner.sessionId(),
                  turn1UserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new TextMessageContent(
                                  "historical assistant " + "a".repeat(50_000)))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE,
                          new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1EndId,
                  owner.sessionId(),
                  turn1AssistantId,
                  new TurnEndPayload(turn1StartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2StartId,
                  owner.sessionId(),
                  turn1EndId,
                  inputTurnStart(settings, ownerThreadId),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2UserId,
                  owner.sessionId(),
                  turn2StartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(
                              new TextMessageContent(
                                  "user asks a very long question" + "x".repeat(90_000)))),
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2AssistantId,
                  owner.sessionId(),
                  turn2UserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("historical reply"))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE, OVER_THRESHOLD_USAGE),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2EndId,
                  owner.sessionId(),
                  turn2AssistantId,
                  new TurnEndPayload(turn2StartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));
          tx.updateCommands(List.of(source.markApplied(turn1StartId)));
          tx.updateThread(owner.advanceHeadAndInputThroughSequence(turn2EndId, 1L, T1));
          return null;
        });
  }

  private static TurnStartPayload inputTurnStart(BranchSettings settings, UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.INPUT,
        settings,
        ownerThreadId,
        CONTEXT_WINDOW,
        MAX_OUTPUT_TOKENS,
        null,
        null);
  }

  /** 测试只需 Stop 的真实 Model 取消语义，不执行任何工具。 */
  private ToolProcessor noOpToolProcessor() {
    ToolGateway gateway =
        new ToolGateway() {
          @Override
          public ToolGateway.PreflightResult preflight(ToolInvocationRequest request) {
            throw new UnsupportedOperationException("no tools in this test");
          }

          @Override
          public ToolGateway.Admission admit(ToolGateway.Execution execution) {
            throw new UnsupportedOperationException("no tools in this test");
          }
        };
    return new ToolProcessor(
        store,
        gateway,
        event -> {},
        new ToolProcessorConfig(
            new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
            () -> NO_RETRY,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5)),
        Clock.fixed(T1, ZoneOffset.UTC),
        scheduler,
        Runnable::run,
        ExecutionExitTestSupport.confirmer(store, Clock.fixed(T1, ZoneOffset.UTC)));
  }

  /** 处理 THREAD Work 直到目标 owner 被处理；Stop 提前投递的父通知 Work 一并 drain，避免污染目标断言。 */
  private boolean processThreadWorkUntil(UUID expectedThreadId) {
    for (int i = 0; i < 8; i++) {
      Optional<ClaimedWork> claim = claimNextThreadWork();
      if (claim.isEmpty()) {
        return false;
      }
      if (processor.process(claim.get()) != ThreadProcessResult.COMPLETED) {
        return false;
      }
      if (expectedThreadId.equals(claim.get().target().id())) {
        return true;
      }
    }
    return false;
  }

  private Optional<ClaimedWork> claimNextThreadWork() {
    return store.transaction(
        tx ->
            tx.claimNextWork(
                WorkTargetType.THREAD, UUID.randomUUID().toString(), Duration.ofSeconds(30)));
  }

  private List<ExecutionExitFact> loadUnconfirmedExecutions(UUID threadId) {
    return store.transaction(tx -> tx.loadUnconfirmedExecutions(threadId));
  }

  private int countIncompleteChildJoins(UUID parentThreadId) {
    return store.transaction(tx -> tx.countIncompleteChildJoins(parentThreadId));
  }

  private long version(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().version());
  }

  /** 外层父 Thread 上按 notificationId 去重的通知数量（含已物化与仍为命令的交付）。 */
  private int notificationCount(UUID parentThreadId) {
    return store.transaction(
        tx -> {
          Set<UUID> notificationIds = new HashSet<>();
          for (ThreadCommand command : tx.loadCommandsByThread(parentThreadId)) {
            if (command.type().isNotification()) {
              notificationIds.add(
                  ((NotificationCommandPayload) command.payload()).notificationId());
            }
          }
          EntryPath path =
              tx.loadEntryPath(tx.findThread(parentThreadId).orElseThrow().headEntryId());
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload notification) {
              notificationIds.add(notification.notificationId());
            }
          }
          return notificationIds.size();
        });
  }

  /** release 后独立 join、关闭 processor 与 scheduler；所有清理失败均返回给调用方保留。 */
  private List<Throwable> cleanup(
      HoldingModelGateway holdingGateway,
      ModelProcessor stoppingModelProcessor,
      ToolProcessor toolProcessor,
      ExecutorService executionExecutor,
      Thread executionThread) {
    List<Throwable> failures = new ArrayList<>();
    holdingGateway.release();
    if (executionThread != null) {
      try {
        if (!executionThread.join(Duration.ofSeconds(5))) {
          failures.add(new AssertionError("model execution thread must exit after release"));
        }
      } catch (Throwable failure) {
        failures.add(failure);
      }
    }
    if (stoppingModelProcessor != null) {
      try {
        stoppingModelProcessor.close();
      } catch (Throwable failure) {
        failures.add(failure);
      }
    }
    if (toolProcessor != null) {
      try {
        toolProcessor.close();
      } catch (Throwable failure) {
        failures.add(failure);
      }
    }
    if (executionExecutor != null) {
      try {
        executionExecutor.shutdownNow();
        if (!executionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          failures.add(new AssertionError("model execution executor must terminate"));
        }
      } catch (Throwable failure) {
        failures.add(failure);
      }
    }
    try {
      scheduler.shutdownNow();
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        failures.add(new AssertionError("scheduler must terminate"));
      }
    } catch (Throwable failure) {
      failures.add(failure);
    }
    return failures;
  }

  /** 普通 Turn 解析出的输出预算与 cache 覆盖：仅在本测试内模拟“当前配置给压缩子的预算/缓存档位”。 */
  private Integer resolvedOutputTokens;

  private ProviderCacheControl resolvedCacheControl;

  /** 记录每次普通 Turn 解析出的 spec：用于在断言收紧结果的同时证明“收紧前确实更大 / 带 cache”。 */
  private final List<ModelRequestSpec> observedResolvedSpecs = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    Clock clock = Clock.fixed(T1, ZoneOffset.UTC);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    resolver =
        (threadId, path, preparation) -> {
          if (preparation != null) {
            ModelSelection compactorModel = ModelSelection.model("compactor-model", "v1");
            return new TurnResolver.CompactionResolved(
                compactorModel,
                4096L,
                CONTEXT_WINDOW,
                MAX_OUTPUT_TOKENS,
                new BranchSettings(
                    "compaction", compactorModel, path.baseSettings().environmentName(), null));
          }
          ModelRequestSpec spec = resolvedSpec(path.baseSettings());
          observedResolvedSpecs.add(spec);
          return new TurnResolver.Resolved(
              spec,
              CONTEXT_WINDOW,
              resolvedOutputTokens != null ? resolvedOutputTokens : MAX_OUTPUT_TOKENS);
        };
    runtime = new HarnessRuntime(store, resolver, () -> CompactionConfig.DEFAULT);
    processor =
        new ThreadProcessor(
            store,
            resolver,
            new ThreadProcessorConfig(Duration.ofSeconds(5), () -> CompactionConfig.DEFAULT));
    gateway = new ScriptedChildGateway();
    modelProcessor =
        new ModelProcessor(
            store,
            gateway,
            event -> {},
            new ModelProcessorConfig(
                new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                () -> retryPolicy,
                Duration.ofSeconds(5)),
            clock,
            scheduler,
            Runnable::run,
            Runnable::run,
            ExecutionExitTestSupport.confirmer(store, clock));
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  @Test
  void compactionChildLifecycleEndToEnd() {
    // a. seed 一个父 Session/ROOT + 历史，使自动压缩在 threshold 下被规划
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    assertTrue(findModelInvocationByTurn(parentId, baseline.turnStartEntryId()).isEmpty());

    UUID userCommandId = seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);

    // b. claim 父线程直到规划出压缩
    processNext(parentId);

    EntryPath parentPath1 = path(parentId);
    assertEquals(10, parentPath1.entries().size());
    Entry compactionStartEntry = parentPath1.head();
    assertInstanceOf(TurnStartPayload.class, compactionStartEntry.payload());
    TurnStartPayload startPayload = (TurnStartPayload) compactionStartEntry.payload();
    assertEquals(TurnStartReason.COMPACTION, startPayload.reason());
    assertEquals(parentId, startPayload.ownerThreadId());

    // 父 TURN_START.compaction 的 run group 完整
    CompactionStart compaction = startPayload.compaction();
    assertNotNull(compaction);
    assertNotNull(compaction.executionModel());
    assertEquals("compactor-model", compaction.executionModel().modelName());
    assertNotNull(compaction.outputBudget());
    assertTrue(compaction.outputBudget() > 0);
    assertNotNull(compaction.childThreadId());
    assertNotNull(compaction.joinInvocationId());
    assertEquals(CompactionPhase.FULL, compaction.phase());
    assertEquals(CompactionTrigger.THRESHOLD, compaction.trigger());

    UUID childThreadId = compaction.childThreadId();
    UUID joinInvocationId = compaction.joinInvocationId();

    // 父线程没有 ModelInvocation
    assertTrue(findModelInvocationByTurn(parentId, compactionStartEntry.id()).isEmpty());

    // 新建了子 Session/ROOT Entry/子 Thread（parentThreadId = 父）
    store.transaction(
        tx -> {
          ThreadState childThread = tx.lockThread(childThreadId).orElseThrow();
          assertEquals(parentId, childThread.parentThreadId());
          Session childSession = tx.findSession(childThread.sessionId()).orElseThrow();
          assertNotNull(childSession);
          Entry rootEntry = tx.findEntry(childThread.headEntryId()).orElseThrow();
          assertInstanceOf(RootPayload.class, rootEntry.payload());

          // 子第一条 command 的 payload 是 CustomMessageCommandPayload（摘要 prompt，非空）
          List<ThreadCommand> childCommands = tx.loadCommandsByThread(childThreadId);
          assertEquals(1, childCommands.size());
          ThreadCommand childFirstCmd = childCommands.getFirst();
          assertEquals(1L, childFirstCmd.sequence());
          assertEquals(ThreadCommandType.CUSTOM_MESSAGE, childFirstCmd.type());
          assertInstanceOf(CustomMessageCommandPayload.class, childFirstCmd.payload());
          CustomMessageCommandPayload promptPayload =
              (CustomMessageCommandPayload) childFirstCmd.payload();
          TextMessageContent textContent =
              (TextMessageContent) promptPayload.message().contents().getFirst();
          assertFalse(textContent.text().isBlank());

          // 存在 delivery == SETTLE_OWNER_TURN、childThreadId = 子、未 matched 的 join
          ThreadJoin join = tx.findJoin(joinInvocationId).orElseThrow();
          assertEquals(JoinDelivery.SETTLE_OWNER_TURN, join.delivery());
          // 压缩子的创建来源与约束经 PostgreSQL 持久化后原样读回。
          assertEquals(SpawnTrigger.RUNTIME, childThread.spawnTrigger());
          assertEquals(false, childThread.constraints().allowAutoCompaction());
          assertEquals(true, childThread.constraints().promptCacheDisabled());
          assertEquals(
              compaction.outputBudget().intValue(), childThread.constraints().maxOutputTokens());
          assertEquals(parentId, join.parentThreadId());
          assertEquals(childThreadId, join.childThreadId());
          assertFalse(join.matched());
          assertNull(join.terminalEntryId());
          assertNull(join.finalAnswerEntryId());
          return null;
        });

    // 子 THREAD Work 被请求
    assertTrue(hasWork(WorkTargetType.THREAD, childThreadId));

    // c. 父 durable wait：父没有待处理 Work（除子之外没有父自身的 THREAD/MODEL Work）、父历史没有新的 TURN_END
    assertFalse(hasWork(WorkTargetType.THREAD, parentId));
    assertEquals(compactionStartEntry.id(), path(parentId).head().id());
    assertFalse(path(parentId).head().payload() instanceof TurnEndPayload);

    // 用户原始输入仍然在父队列中未被消费
    List<ThreadCommand> parentQueued =
        store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(1, parentQueued.size());
    assertEquals(userCommandId, parentQueued.getFirst().idempotencyKey());

    // d. 真实执行子 Thread：claim 子线程（注入的 TurnResolver 对子返回普通 Resolved）
    processNext(childThreadId);

    // 子线程产生了 ModelInvocation，状态为 READY
    ModelInvocation childModel =
        store.transaction(
            tx -> {
              ThreadState childThread = tx.lockThread(childThreadId).orElseThrow();
              UUID childTurn =
                  tx.loadEntryPath(childThread.headEntryId()).openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(childThreadId, childTurn).orElseThrow();
            });
    assertEquals(ModelInvocationStatus.READY, childModel.status());

    // 把它的 ModelInvocation 推进到 SUCCEEDED
    String childSummaryText = "compacted summary of history";
    finishModel(childThreadId, childSummaryText);

    // 使子 turn 走完并结算
    processNext(childThreadId);

    // 断言 COMPACTION join 已 matched（terminalEntryId/finalAnswerEntryId 非空）
    ThreadJoin settledJoin = findJoin(joinInvocationId);
    assertTrue(settledJoin.matched());
    assertNotNull(settledJoin.terminalEntryId());
    assertNotNull(settledJoin.finalAnswerEntryId());

    // 父 THREAD Work 被重新请求
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));

    // e. 父恢复并消费：claim 父线程
    processNext(parentId);

    // 断言父历史新增 CompactionPayload（summaryText 等于子最终 assistant 文本）+ TurnEndPayload(COMPLETED)
    EntryPath parentPathFinal = path(parentId);
    assertEquals(12, parentPathFinal.entries().size());
    Entry compactionResultEntry = parentPathFinal.entries().get(10);
    assertInstanceOf(CompactionPayload.class, compactionResultEntry.payload());
    CompactionPayload compactionPayload = (CompactionPayload) compactionResultEntry.payload();
    assertEquals(childSummaryText, compactionPayload.summaryText());
    assertNotNull(compactionPayload.assistantMetadata());
    assertEquals(GenerationStopReason.COMPLETE, compactionPayload.assistantMetadata().stopReason());
    assertEquals(
        new ModelUsage(100L, 20L, 0L, 0L, Map.of(), 0L, 120L),
        compactionPayload.assistantMetadata().usage());

    Entry turnEndEntry = parentPathFinal.entries().get(11);
    assertEquals(parentPathFinal.head().id(), turnEndEntry.id());
    assertInstanceOf(TurnEndPayload.class, turnEndEntry.payload());
    TurnEndPayload endPayload = (TurnEndPayload) turnEndEntry.payload();
    assertEquals(compactionStartEntry.id(), endPayload.turnStartEntryId());
    assertEquals(TurnEndOutcome.COMPLETED, endPayload.outcome());
    assertFalse(endPayload.continueModel());

    // 父 head 推进
    ThreadState finalParentThread = store.transaction(tx -> tx.findThread(parentId).orElseThrow());
    assertEquals(turnEndEntry.id(), finalParentThread.headEntryId());

    // 父无 ModelInvocation
    assertTrue(findModelInvocationByTurn(parentId, compactionStartEntry.id()).isEmpty());
  }

  /**
   * 子压缩调用真实经 ModelProcessor 收到不可重试的 OVERFLOW：子执行失败、父压缩 turn FAILED、不递归创建压缩子、不留残留 Work，
   * 且原历史上下文不变。压缩子身份只由 durable COMPACTION Join 事实决定。
   */
  @Test
  void compactionChildOverflowFailsParentWithoutRecursionOrResidualWork() {
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    int parentEntryCountBefore = path(parentId).entries().size();
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    processNext(child.childThreadId());

    // 子的模型调用真实经 ModelProcessor 发出，provider 报上下文超限（OVERFLOW 不可重试）。
    UUID childModelId = openChildModel(child.childThreadId());
    gateway.queue(
        new ModelGateway.Failed(
            new ModelInvocationError(ProviderErrorKind.OVERFLOW, "context window exceeded")));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    assertEquals(ModelInvocationStatus.FAILED, findModel(childModelId).status());
    assertFalse(hasWork(WorkTargetType.MODEL, childModelId));

    // 子结算失败回合：绝不因超限递归规划压缩子，也不残留 Work。
    processNext(child.childThreadId());
    assertNoRecursiveCompactionOnChild(child.childThreadId());
    assertFalse(hasWork(WorkTargetType.THREAD, child.childThreadId()));

    // JOIN 以失败回执冻结，父 THREAD 被唤醒。
    assertTrue(findJoin(child.joinInvocationId()).matched());
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));

    // 父结算：COMPACTION FAILED，不交付任何摘要，原历史上下文保持不变。
    processNext(parentId);
    EntryPath parentPath = path(parentId);
    assertEquals(parentEntryCountBefore + 3, parentPath.entries().size());
    assertEquals(child.turnStartEntryId(), parentPath.entries().get(parentEntryCountBefore).id());
    Entry failureEntry = parentPath.entries().get(parentEntryCountBefore + 1);
    assertInstanceOf(AssistantErrorPayload.class, failureEntry.payload());
    assertEquals(
        "COMPACTION_FAILED", ((AssistantErrorPayload) failureEntry.payload()).error().code());
    Entry endEntry = parentPath.head();
    assertInstanceOf(TurnEndPayload.class, endEntry.payload());
    TurnEndPayload endPayload = (TurnEndPayload) endEntry.payload();
    assertEquals(TurnEndOutcome.FAILED, endPayload.outcome());
    assertEquals(child.turnStartEntryId(), endPayload.turnStartEntryId());
    assertTrue(
        parentPath.entries().stream()
            .noneMatch(entry -> entry.payload() instanceof CompactionPayload));
    assertTrue(findModelInvocationByTurn(parentId, child.turnStartEntryId()).isEmpty());
    // 压缩失败不消费用户输入：父线程仍被唤醒去处理未消费的排队命令。
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));
    List<ThreadCommand> stillQueued =
        store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(1, stillQueued.size());
  }

  /** 子第一次调用被输出上限截断：截断结果绝不冻结为成功回执，走同一调用的既有重试（同一 attempt 预算、计数不重置）；第二次合法 摘要才由父一次提交，且父只有一条压缩摘要。 */
  @Test
  void compactionChildTruncatedAttemptRetriesThenParentCommitsOneSummary() {
    retryPolicy = RETRY_ONCE;
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    processNext(child.childThreadId());
    UUID childModelId = openChildModel(child.childThreadId());

    // attempt 1：截断 final（LENGTH）→ INVALID_RESPONSE，绝不产生结果，也不交付父。
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(
                providerResponse("truncated partial summary", GenerationStopReason.LENGTH), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    ModelInvocation afterFirstAttempt = findModel(childModelId);
    assertEquals(ModelInvocationStatus.READY, afterFirstAttempt.status());
    assertEquals(1, afterFirstAttempt.attempt());
    assertNull(afterFirstAttempt.resultEntryId());
    assertEquals(1, afterFirstAttempt.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        afterFirstAttempt.failedAttempts().getFirst().error().kind());
    assertFalse(hasWork(WorkTargetType.THREAD, parentId));

    // attempt 2：合法摘要，attempt 计数不重置。
    String summary = "durable compacted summary";
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(
                providerResponse(summary, GenerationStopReason.COMPLETE), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    ModelInvocation afterSecondAttempt = findModel(childModelId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, afterSecondAttempt.status());
    assertEquals(2, afterSecondAttempt.attempt());

    processNext(child.childThreadId());
    assertTrue(findJoin(child.joinInvocationId()).matched());
    processNext(parentId);

    EntryPath parentPath = path(parentId);
    assertInstanceOf(TurnEndPayload.class, parentPath.head().payload());
    assertEquals(
        TurnEndOutcome.COMPLETED, ((TurnEndPayload) parentPath.head().payload()).outcome());
    Entry summaryEntry = parentPath.entries().get(parentPath.entries().size() - 2);
    assertInstanceOf(CompactionPayload.class, summaryEntry.payload());
    assertEquals(summary, ((CompactionPayload) summaryEntry.payload()).summaryText());
    assertEquals(
        1,
        parentPath.entries().stream()
            .filter(entry -> entry.payload() instanceof CompactionPayload)
            .count());
    // 压缩成功不消费用户输入：父线程仍被唤醒去处理排队命令。
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));
    assertEquals(1, queuedCommandCount(parentId));
  }

  /** 截断重试耗尽：子 invocation FAILED、子回合 FAILED、父压缩 FAILED；旧的部分输出绝不作为摘要或部分报告交付，attempt 不被重置。 */
  @Test
  void compactionChildTruncationExhaustionFailsParentWithoutPartialSummary() {
    retryPolicy = RETRY_ONCE;
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    processNext(child.childThreadId());
    UUID childModelId = openChildModel(child.childThreadId());

    String partial = "partial text that must never surface";
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(providerResponse(partial, GenerationStopReason.LENGTH), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(providerResponse(partial, GenerationStopReason.LENGTH), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));

    ModelInvocation exhausted = findModel(childModelId);
    assertEquals(ModelInvocationStatus.FAILED, exhausted.status());
    assertEquals(2, exhausted.attempt());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exhausted.error().kind());
    assertEquals(1, exhausted.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, exhausted.failedAttempts().getFirst().error().kind());

    processNext(child.childThreadId());
    assertNoRecursiveCompactionOnChild(child.childThreadId());
    processNext(parentId);

    EntryPath parentPath = path(parentId);
    assertInstanceOf(TurnEndPayload.class, parentPath.head().payload());
    assertEquals(TurnEndOutcome.FAILED, ((TurnEndPayload) parentPath.head().payload()).outcome());
    assertTrue(
        parentPath.entries().stream()
            .noneMatch(entry -> entry.payload() instanceof CompactionPayload));
    assertFalse(containsText(parentPath, partial));
    assertFalse(hasWork(WorkTargetType.THREAD, child.childThreadId()));
    // 压缩失败不消费用户输入：父线程仍被唤醒去处理排队命令，且旧的部分输出绝不作为摘要交付。
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));
    assertEquals(1, queuedCommandCount(parentId));
  }

  /**
   * 意图：父 COMPACTION TURN_START 冻结的 outputBudget 是压缩子每一次请求的权威上限。子自身解析出的更大预算被收紧到冻结值（min 语义）、cache 强制
   * NONE、TURN_START 冻结的实际预算与落库 spec 一致；身份只来自 durable COMPACTION Join，而非 Agent name。
   */
  @Test
  void compactionChildFirstRequestBudgetIsCappedAndCacheForcedNone() {
    ProviderCacheControl cacheControl =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "child-session");
    resolvedOutputTokens = 9000;
    resolvedCacheControl = cacheControl;
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    assertEquals(4096L, frozenOutputBudget(parentId));

    processNext(child.childThreadId());
    // 反证前提：resolver 确实给压缩子提出了更大的预算与会话级 cache（否则本用例不构成收紧证据）。
    assertTrue(observedResolvedSpecs.stream().anyMatch(item -> item.outputTokens() == 9000));
    assertTrue(
        observedResolvedSpecs.stream().anyMatch(item -> cacheControl.equals(item.cacheControl())));
    ModelRequestSpec spec = findModel(openChildModel(child.childThreadId())).requestSpec();
    assertEquals(4096, spec.outputTokens());
    assertEquals(ProviderCacheControl.none(), spec.cacheControl());
    assertEquals("Test system instruction.", spec.systemInstruction());
    // TURN_START 冻结的 maxOutputTokens 必须等于实际请求预算，绝不残留 resolver 提出的 9000。
    assertEquals(4096, childTurnStartMaxOutputTokens(child.childThreadId()));
  }

  /**
   * 意图：压缩子的工具 loop 续作回合（工具回合闭合后的 CONTINUATION 模型轮）同样只由 durable 冻结事实收紧——resolver 给出的预算低于父 冻结预算时保持
   * min 语义（绝不抬高到父预算），cache 一律 NONE，TURN_START 与实际 spec 一致。
   */
  @Test
  void compactionChildToolLoopContinuationTurnIsCappedAndCacheForcedNone() {
    resolvedOutputTokens = 1024;
    resolvedCacheControl =
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "child-session");
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    // 子第一条输入回合：真实工具调用 + 工具结果 + continueModel=true，形成工具 loop 的续作义务（不依赖工具执行器）。
    seedClosedToolLoopTurn(child.childThreadId());
    processNext(child.childThreadId());

    TurnStartPayload continuationStart =
        (TurnStartPayload) path(child.childThreadId()).openTurnStart().orElseThrow().payload();
    assertEquals(TurnStartReason.CONTINUATION, continuationStart.reason());
    ModelRequestSpec spec = findModel(openChildModel(child.childThreadId())).requestSpec();
    assertEquals(1024, spec.outputTokens());
    assertEquals(1024, continuationStart.maxOutputTokens());
    assertEquals(ProviderCacheControl.none(), spec.cacheControl());
  }

  /** 纯空白 final（COMPLETE）同样不是可用摘要：绝不冻结成父 receipt，走同一调用的既有重试，第二次合法摘要才由父一次提交。 */
  @Test
  void compactionChildWhitespaceFinalRetriesThenParentCommitsOneSummary() {
    retryPolicy = RETRY_ONCE;
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    processNext(child.childThreadId());
    UUID childModelId = openChildModel(child.childThreadId());

    // attempt 1：纯空白 final → INVALID_RESPONSE，绝不产生结果，也不交付父。
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(
                providerResponse("  \n ", GenerationStopReason.COMPLETE), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    ModelInvocation afterFirstAttempt = findModel(childModelId);
    assertEquals(ModelInvocationStatus.READY, afterFirstAttempt.status());
    assertEquals(1, afterFirstAttempt.attempt());
    assertNull(afterFirstAttempt.resultEntryId());
    assertEquals(1, afterFirstAttempt.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE,
        afterFirstAttempt.failedAttempts().getFirst().error().kind());
    assertFalse(hasWork(WorkTargetType.THREAD, parentId));

    // attempt 2：合法摘要，attempt 计数不重置。
    String summary = "durable compacted summary";
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(
                providerResponse(summary, GenerationStopReason.COMPLETE), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    assertEquals(ModelInvocationStatus.SUCCEEDED, findModel(childModelId).status());

    processNext(child.childThreadId());
    assertTrue(findJoin(child.joinInvocationId()).matched());
    processNext(parentId);

    EntryPath parentPath = path(parentId);
    assertInstanceOf(TurnEndPayload.class, parentPath.head().payload());
    assertEquals(
        TurnEndOutcome.COMPLETED, ((TurnEndPayload) parentPath.head().payload()).outcome());
    Entry summaryEntry = parentPath.entries().get(parentPath.entries().size() - 2);
    assertInstanceOf(CompactionPayload.class, summaryEntry.payload());
    assertEquals(summary, ((CompactionPayload) summaryEntry.payload()).summaryText());
    assertEquals(
        1,
        parentPath.entries().stream()
            .filter(entry -> entry.payload() instanceof CompactionPayload)
            .count());
  }

  /** 纯空白 final 重试耗尽：子 invocation / 子回合 / 父压缩全部 FAILED，绝不交付空白或部分摘要，也不残留子 Work。 */
  @Test
  void compactionChildWhitespaceFinalExhaustionFailsParentWithoutPartialSummary() {
    retryPolicy = RETRY_ONCE;
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store, OVER_THRESHOLD_USAGE);
    UUID parentId = baseline.threadId();
    seedUserCommand(parentId, "queued input");
    requestThreadWork(parentId);
    processNext(parentId);

    CompactionChild child = compactionChildOf(parentId);
    processNext(child.childThreadId());
    UUID childModelId = openChildModel(child.childThreadId());

    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(providerResponse("   ", GenerationStopReason.COMPLETE), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
    gateway.queue(
        new ModelGateway.Succeeded(
            new ProviderCompletion(providerResponse("\n\n", GenerationStopReason.COMPLETE), null)));
    assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));

    ModelInvocation exhausted = findModel(childModelId);
    assertEquals(ModelInvocationStatus.FAILED, exhausted.status());
    assertEquals(2, exhausted.attempt());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, exhausted.error().kind());
    assertEquals(1, exhausted.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.INVALID_RESPONSE, exhausted.failedAttempts().getFirst().error().kind());

    processNext(child.childThreadId());
    assertNoRecursiveCompactionOnChild(child.childThreadId());
    processNext(parentId);

    EntryPath parentPath = path(parentId);
    assertInstanceOf(TurnEndPayload.class, parentPath.head().payload());
    assertEquals(TurnEndOutcome.FAILED, ((TurnEndPayload) parentPath.head().payload()).outcome());
    assertTrue(
        parentPath.entries().stream()
            .noneMatch(entry -> entry.payload() instanceof CompactionPayload));
    assertFalse(hasWork(WorkTargetType.THREAD, child.childThreadId()));
    // 压缩失败不消费用户输入：父线程仍被唤醒去处理未消费的排队命令。
    assertTrue(hasWork(WorkTargetType.THREAD, parentId));
    assertEquals(1, queuedCommandCount(parentId));
  }

  /**
   * Stop 关闭 owner 自有 COMPACTION turn 后，仍未收敛的 SETTLE_OWNER_TURN 压缩子必须继续把 owner 留给 RUNNING；外层
   * NOTIFY_PARENT 只能等压缩子真实退出确认后收敛，且 owner 的派生 cursor 必须推进，随后同一恢复输入才可接受。
   */
  @Test
  void stoppedCompactionOwnerWaitsForSummaryExitBeforeSettlingAndResuming() throws Throwable {
    HoldingModelGateway holdingGateway = new HoldingModelGateway();
    ExecutorService executionExecutor = null;
    ToolProcessor toolProcessor = null;
    ModelProcessor stoppingModelProcessor = null;
    Thread executionThread = null;
    Throwable primary = null;
    try {
      executionExecutor = Executors.newVirtualThreadPerTaskExecutor();
      toolProcessor = noOpToolProcessor();
      stoppingModelProcessor =
          new ModelProcessor(
              store,
              holdingGateway,
              event -> {},
              new ModelProcessorConfig(
                  new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                  () -> NO_RETRY,
                  Duration.ofSeconds(5)),
              Clock.fixed(T1, ZoneOffset.UTC),
              scheduler,
              Runnable::run,
              executionExecutor,
              ExecutionExitTestSupport.confirmer(store, Clock.fixed(T1, ZoneOffset.UTC)));
      HarnessRuntime stoppingRuntime =
          new HarnessRuntime(
              store,
              resolver,
              () -> CompactionConfig.DEFAULT,
              null,
              stoppingModelProcessor,
              toolProcessor);
      runtime = stoppingRuntime;
      modelProcessor = stoppingModelProcessor;
      StoreTestSupport.Baseline outer = seedThreadBaseline(store);
      UUID outerThreadId = outer.threadId();
      UUID ownerThreadId = UUID.randomUUID();
      UUID outerJoinId = UUID.randomUUID();
      NewThreadCommand ownerSource =
          new NewThreadCommand(
              new UserMessageCommandPayload(AgentMessage.user("outer task")), UUID.randomUUID());
      stoppingRuntime.spawn(
          new SpawnRequest(
              ownerThreadId,
              UUID.randomUUID(),
              branchSettings(),
              List.of(ownerSource),
              AcceptancePreflight.IDENTITY,
              outerThreadId,
              outer.rootEntryId(),
              null,
              false,
              ThreadConstraints.NONE,
              SpawnTrigger.CONTROLLER,
              JoinDelivery.NOTIFY_PARENT,
              SpawnRequest.Budget.unlimited(),
              outerJoinId,
              ownerSource.requestHash()));

      seedOwnerCompactionHistory(ownerThreadId);
      seedUserCommand(ownerThreadId, "queued input");
      requestThreadWork(ownerThreadId);
      processNext(ownerThreadId);

      CompactionChild child = compactionChildOf(ownerThreadId);
      assertTrue(hasWork(WorkTargetType.THREAD, child.childThreadId()));
      processNext(child.childThreadId());
      UUID childModelId = openChildModel(child.childThreadId());
      assertEquals(ProcessResult.STARTED, processModel(childModelId, T1));
      assertTrue(holdingGateway.entered.await(5, TimeUnit.SECONDS), "model body must be entered");
      executionThread = holdingGateway.runner();
      assertNotNull(executionThread);

      long ownerVersionAtStop = version(ownerThreadId);
      stoppingRuntime.stop(new StopCommand(ownerThreadId, UUID.randomUUID(), ownerVersionAtStop));
      boolean stopInterruptedBody = holdingGateway.interrupted.await(5, TimeUnit.SECONDS);

      ThreadSnapshot childHeld = snapshot(child.childThreadId());
      ThreadSnapshot ownerHeld = snapshot(ownerThreadId);
      ThreadJoin childJoinHeld = findJoin(child.joinInvocationId());
      ThreadJoin outerJoinHeld = stoppingRuntime.findJoin(outerJoinId).orElseThrow();
      int outerNotificationsHeld = notificationCount(outerThreadId);
      int incompleteChildJoinsHeld = countIncompleteChildJoins(ownerThreadId);
      List<ExecutionExitFact> unconfirmedHeld = loadUnconfirmedExecutions(child.childThreadId());
      UUID childExecutionId =
          unconfirmedHeld.isEmpty() ? null : unconfirmedHeld.getFirst().executionId();

      NewThreadCommand resumeCommand =
          new NewThreadCommand(
              new UserMessageCommandPayload(AgentMessage.user("resume")), UUID.randomUUID());
      AcceptCommandsCommand resumeRequest =
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(ownerThreadId), List.of(resumeCommand));
      HarnessRuntimeConflictException.Reason resumeReason = null;
      boolean resumeAcceptedWhileHeld = false;
      try {
        stoppingRuntime.acceptCommands(resumeRequest, AcceptancePreflight.IDENTITY);
        resumeAcceptedWhileHeld = true;
      } catch (HarnessRuntimeConflictException conflict) {
        resumeReason = conflict.reason();
      }

      ThreadSnapshot ownerBeforeSettings = snapshot(ownerThreadId);
      NewThreadCommand settingsCommand =
          new NewThreadCommand(new SetAgentCommandPayload("assistant"), UUID.randomUUID());
      AcceptCommandsCommand settingsRequest =
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  ownerThreadId,
                  ownerBeforeSettings.thread().headEntryId(),
                  ownerBeforeSettings.thread().nextCommandSequence()),
              List.of(settingsCommand));
      boolean settingsAccepted = false;
      Throwable settingsAcceptFailure = null;
      try {
        stoppingRuntime.acceptCommands(settingsRequest, AcceptancePreflight.IDENTITY);
        settingsAccepted = true;
      } catch (Throwable failure) {
        settingsAcceptFailure = failure;
      }
      boolean ownerSettingsWorkProcessed = false;
      Throwable settingsProcessFailure = null;
      if (settingsAccepted) {
        try {
          ownerSettingsWorkProcessed = processThreadWorkUntil(ownerThreadId);
        } catch (Throwable failure) {
          settingsProcessFailure = failure;
        }
      }
      ThreadSnapshot ownerAfterSettings = snapshot(ownerThreadId);
      boolean ownerStillStopped =
          ownerAfterSettings.thread().executionControl() == ThreadExecutionControl.STOPPED;
      boolean ownerWorkAfterSettings = hasWork(WorkTargetType.THREAD, ownerThreadId);
      long ownerVersionAfterSettings = ownerAfterSettings.thread().version();

      holdingGateway.release();
      boolean executionExited = executionThread.join(Duration.ofSeconds(5));
      ExecutionExitFact childExecutionAfter =
          childExecutionId == null
              ? null
              : store.transaction(tx -> tx.findExecution(childExecutionId).orElse(null));
      ThreadJoin childJoinAfter = findJoin(child.joinInvocationId());
      ThreadJoin outerJoinAfter = stoppingRuntime.findJoin(outerJoinId).orElseThrow();
      int outerNotificationsAfter = notificationCount(outerThreadId);
      ThreadSnapshot ownerAfterExit = snapshot(ownerThreadId);
      long ownerVersionAfterExit = ownerAfterExit.thread().version();
      boolean ownerWorkAfterExit = hasWork(WorkTargetType.THREAD, ownerThreadId);

      boolean resumeAcceptedAfterExit = false;
      Throwable resumeAfterExitFailure = null;
      ThreadExecutionControl controlAfterResume = ownerAfterExit.thread().executionControl();
      try {
        stoppingRuntime.acceptCommands(resumeRequest, AcceptancePreflight.IDENTITY);
        resumeAcceptedAfterExit = true;
        controlAfterResume = snapshot(ownerThreadId).thread().executionControl();
      } catch (Throwable failure) {
        resumeAfterExitFailure = failure;
      }

      HarnessRuntimeConflictException.Reason observedResumeReason = resumeReason;
      boolean observedResumeAcceptedWhileHeld = resumeAcceptedWhileHeld;
      boolean observedSettingsAccepted = settingsAccepted;
      boolean observedResumeAcceptedAfterExit = resumeAcceptedAfterExit;
      ThreadExecutionControl observedControlAfterResume = controlAfterResume;
      Throwable observedSettingsAcceptFailure = settingsAcceptFailure;
      Throwable observedSettingsProcessFailure = settingsProcessFailure;
      Throwable observedResumeAfterExitFailure = resumeAfterExitFailure;
      boolean observedOwnerSettingsWorkProcessed = ownerSettingsWorkProcessed;

      assertAll(
          () -> assertTrue(stopInterruptedBody, "Stop must interrupt the held model body"),
          () -> assertEquals(1, unconfirmedHeld.size()),
          () -> assertEquals(ExecutionExitState.UNCONFIRMED, unconfirmedHeld.getFirst().state()),
          () -> assertTrue(childHeld.hasUnconfirmedExecution()),
          () -> assertEquals(ThreadRuntimeStatus.RUNNING, childHeld.runtimeStatus()),
          () -> assertFalse(childJoinHeld.matched()),
          () ->
              assertEquals(
                  ThreadRuntimeStatus.RUNNING,
                  ownerHeld.runtimeStatus(),
                  "owner keeps a held summary obligation and must stay RUNNING"),
          () ->
              assertFalse(
                  outerJoinHeld.matched(),
                  "outer NOTIFY_PARENT must stay unmatched while the summary body is held"),
          () -> assertEquals(0, outerNotificationsHeld),
          () -> assertEquals(0, incompleteChildJoinsHeld),
          () ->
              assertEquals(
                  HarnessRuntimeConflictException.Reason.EXIT_UNCONFIRMED, observedResumeReason),
          () -> assertFalse(observedResumeAcceptedWhileHeld),
          () -> assertTrue(observedSettingsAccepted),
          () ->
              assertNull(
                  observedSettingsAcceptFailure,
                  () -> "SET accept failed: " + observedSettingsAcceptFailure),
          () ->
              assertNull(
                  observedSettingsProcessFailure,
                  () -> "SET processing failed: " + observedSettingsProcessFailure),
          () -> assertTrue(observedOwnerSettingsWorkProcessed),
          () -> assertTrue(ownerStillStopped),
          () -> assertFalse(ownerWorkAfterSettings),
          () -> assertTrue(executionExited),
          () ->
              assertTrue(
                  holdingGateway.releasedNormally,
                  "held body must finish after explicit release, not the gate timeout"),
          () -> assertNotNull(childExecutionAfter),
          () -> assertEquals(ExecutionExitState.STUDIO_EXITED, childExecutionAfter.state()),
          () -> assertTrue(childJoinAfter.matched()),
          () -> assertTrue(outerJoinAfter.matched()),
          () -> assertEquals(1, outerNotificationsAfter),
          () -> assertEquals(ThreadRuntimeStatus.IDLE, ownerAfterExit.runtimeStatus()),
          () -> assertFalse(ownerWorkAfterExit),
          () ->
              assertEquals(
                  ownerVersionAfterSettings + 1,
                  ownerVersionAfterExit,
                  "settling the held summary must advance the owner version cursor exactly once"),
          () -> assertTrue(observedResumeAcceptedAfterExit),
          () ->
              assertNull(
                  observedResumeAfterExitFailure,
                  () -> "post-exit resume failed: " + observedResumeAfterExitFailure),
          () -> assertEquals(ThreadExecutionControl.RUNNABLE, observedControlAfterResume));
    } catch (Throwable failure) {
      primary = failure;
    } finally {
      List<Throwable> cleanupFailures =
          cleanup(
              holdingGateway,
              stoppingModelProcessor,
              toolProcessor,
              executionExecutor,
              executionThread);
      if (primary != null) {
        cleanupFailures.forEach(primary::addSuppressed);
        throw primary;
      }
      if (!cleanupFailures.isEmpty()) {
        Throwable first = cleanupFailures.getFirst();
        cleanupFailures.stream().skip(1).forEach(first::addSuppressed);
        throw first;
      }
    }
  }

  // ===== 压缩子驱动的真实模型执行辅助 =====

  private record CompactionChild(
      UUID childThreadId, UUID joinInvocationId, UUID turnStartEntryId) {}

  /** 读取父 Thread 上刚提交的压缩子事实：子 Thread、durable COMPACTION Join 与父压缩 TURN_START。 */
  private CompactionChild compactionChildOf(UUID parentId) {
    return store.transaction(
        tx -> {
          ThreadState parent = tx.findThread(parentId).orElseThrow();
          EntryPath parentPath = tx.loadEntryPath(parent.headEntryId());
          Entry startEntry = parentPath.head();
          assertInstanceOf(TurnStartPayload.class, startEntry.payload());
          TurnStartPayload startPayload = (TurnStartPayload) startEntry.payload();
          assertNotNull(startPayload.compaction(), "compaction turn must own a compaction child");
          UUID childThreadId = startPayload.compaction().childThreadId();
          ThreadJoin join = tx.findJoin(startPayload.compaction().joinInvocationId()).orElseThrow();
          assertEquals(JoinDelivery.SETTLE_OWNER_TURN, join.delivery());
          return new CompactionChild(childThreadId, join.invocationId(), startEntry.id());
        });
  }

  /** 子 Thread 规划输入回合后 READY 的模型调用，全部经真实 ModelProcessor 执行。 */
  private UUID openChildModel(UUID childThreadId) {
    return store.transaction(
        tx -> {
          ThreadState child = tx.findThread(childThreadId).orElseThrow();
          UUID childTurn = tx.loadEntryPath(child.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation invocation =
              tx.findModelInvocationByTurn(childThreadId, childTurn).orElseThrow();
          assertEquals(ModelInvocationStatus.READY, invocation.status());
          return invocation.id();
        });
  }

  /** 普通 Turn 解析：默认沿用 branch settings 的默认请求，测试可覆盖预算与 cache 以观察压缩子的收紧。 */
  private ModelRequestSpec resolvedSpec(BranchSettings settings) {
    ModelRequestSpec base = requestFor(settings);
    if (resolvedOutputTokens == null && resolvedCacheControl == null) {
      return base;
    }
    return new ModelRequestSpec(
        base.providerType(),
        base.providerConnectionGenerationId(),
        base.model(),
        base.variant(),
        resolvedOutputTokens != null ? resolvedOutputTokens : base.outputTokens(),
        base.systemInstruction(),
        base.toolBindings(),
        base.subagentBindings(),
        resolvedCacheControl != null ? resolvedCacheControl : base.cacheControl());
  }

  /** 父路径上冻结的压缩输出预算。 */
  private long frozenOutputBudget(UUID parentId) {
    return store.transaction(
        tx -> {
          ThreadState parent = tx.findThread(parentId).orElseThrow();
          TurnStartPayload start =
              (TurnStartPayload) tx.findEntry(parent.headEntryId()).orElseThrow().payload();
          assertNotNull(start.compaction(), "parent head must be a compaction turn start");
          return start.compaction().outputBudget();
        });
  }

  /** 子当前 open Turn 的 TURN_START 冻结输出预算。 */
  private int childTurnStartMaxOutputTokens(UUID childThreadId) {
    return store.transaction(
        tx -> {
          ThreadState child = tx.findThread(childThreadId).orElseThrow();
          TurnStartPayload start =
              (TurnStartPayload)
                  tx.loadEntryPath(child.headEntryId()).openTurnStart().orElseThrow().payload();
          assertNotNull(start.maxOutputTokens(), "open child turn must freeze maxOutputTokens");
          return start.maxOutputTokens();
        });
  }

  /**
   * 在压缩子上种一个已闭合的工具 loop 输入回合：真实工具调用 + 匹配的工具结果 + {@code continueModel=true}，并消费子第一条
   * CUSTOM_MESSAGE、推进输入水位，使下一次 claim 规划出工具 loop 的 CONTINUATION 模型轮。
   */
  private void seedClosedToolLoopTurn(UUID childThreadId) {
    store.transaction(
        tx -> {
          ThreadState child = ThreadLifecycleCoordinator.lockThreadWithParent(tx, childThreadId);
          BranchSettings settings = tx.loadEntryPath(child.headEntryId()).baseSettings();
          String callId = "call-1";
          UUID startId = tx.nextId();
          UUID inputId = tx.nextId();
          UUID assistantId = tx.nextId();
          UUID toolResultId = tx.nextId();
          UUID endId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  startId,
                  child.sessionId(),
                  child.headEntryId(),
                  new TurnStartPayload(
                      TurnStartReason.INPUT,
                      settings,
                      childThreadId,
                      CONTEXT_WINDOW,
                      MAX_OUTPUT_TOKENS,
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  inputId,
                  child.sessionId(),
                  startId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("compaction instruction"))),
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  assistantId,
                  child.sessionId(),
                  inputId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new ToolCallMessageContent(callId, "bash", "bash", "{}"),
                              new TextMessageContent("working"))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE,
                          new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  toolResultId,
                  child.sessionId(),
                  assistantId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.TOOL,
                          List.of(
                              new ToolResultMessageContent(
                                  callId,
                                  "bash",
                                  "bash",
                                  List.of(new TextMessageContent("tool output")),
                                  false,
                                  "{}"))),
                      null,
                      new ToolResultMetadata(
                          new UUID(0L, 8888L),
                          assistantId,
                          callId,
                          0,
                          ToolResultStatus.SUCCEEDED,
                          false,
                          null,
                          null,
                          null)),
                  T1));
          tx.insertEntry(
              new Entry(
                  endId,
                  child.sessionId(),
                  toolResultId,
                  new TurnEndPayload(startId, TurnEndOutcome.COMPLETED, true, null, null),
                  T1));
          ThreadCommand childCommand =
              tx.loadQueuedCommands(childThreadId).stream()
                  .filter(command -> command.sequence() == 1L)
                  .findFirst()
                  .orElseThrow();
          tx.updateCommands(List.of(childCommand.markApplied(startId)));
          tx.updateThread(child.advanceHeadAndInputThroughSequence(endId, 1L, T1));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, childThreadId));
          return null;
        });
  }

  /** claim 一次 MODEL Work 并交给真实 ModelProcessor：模型调用按 provider 回调推进。 */
  private ProcessResult processModel(UUID invocationId, Instant now) {
    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, UUID.randomUUID().toString(), Duration.ofSeconds(30)))
            .orElseThrow();
    assertEquals(invocationId, claim.target().id());
    return modelProcessor.process(claim);
  }

  private ModelInvocation findModel(UUID invocationId) {
    return store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
  }

  /** 压缩子在 OVERFLOW 后不得递归创建压缩子，也不得出现第二条压缩 TURN_START。 */
  private void assertNoRecursiveCompactionOnChild(UUID childThreadId) {
    assertEquals(0, countJoinsByParent(childThreadId));
    assertTrue(
        path(childThreadId).entries().stream()
            .noneMatch(
                entry ->
                    entry.payload() instanceof TurnStartPayload start
                        && start.reason() == TurnStartReason.COMPACTION));
  }

  private long countJoinsByParent(UUID parentThreadId) {
    try (Connection connection = PostgresqlHarnessStoreFixture.dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from harness_thread_join where parent_thread_id = ?")) {
      statement.setObject(1, parentThreadId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("cannot count joins by parent", error);
    }
  }

  private static boolean containsText(EntryPath path, String text) {
    return path.entries().stream()
        .filter(entry -> entry.payload() instanceof MessagePayload)
        .map(entry -> (MessagePayload) entry.payload())
        .flatMap(payload -> payload.message().contents().stream())
        .anyMatch(
            content ->
                content instanceof TextMessageContent textContent
                    && textContent.text().contains(text));
  }

  private static ProviderResponse providerResponse(String text, GenerationStopReason stopReason) {
    return new ProviderResponse(
        text,
        "",
        List.of(),
        stopReason,
        new ModelUsage(100L, 20L, 0L, 0L, Map.of(), 0L, 120L),
        "req-compaction-child",
        null,
        "{}");
  }

  /** body 进入后保持显式 gate；Stop 的 interrupt 只计数，不释放 gate，也不重置清理超时。 */
  private static final class HoldingModelGateway implements ModelGateway {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile Thread runner;
    private volatile boolean releasedNormally;

    @Override
    public Admission admit(Execution execution) {
      return new Admitted() {
        @Override
        public Outcome run(Sink sink) {
          runner = Thread.currentThread();
          entered.countDown();
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
          while (true) {
            try {
              if (!release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                throw new AssertionError("held model body gate was not released");
              }
              releasedNormally = true;
              break;
            } catch (InterruptedException ignored) {
              interrupted.countDown();
            }
          }
          Thread.interrupted();
          return new Succeeded(
              new ProviderCompletion(
                  providerResponse("held summary", GenerationStopReason.COMPLETE), null));
        }

        @Override
        public void close() {}
      };
    }

    Thread runner() {
      return runner;
    }

    void release() {
      release.countDown();
    }
  }

  /** 脚本化子压缩 model gateway：按序返回排入的终态，覆盖截断 / 超限的真实 processor 路径（执行线程内联运行）。 */
  private static final class ScriptedChildGateway implements ModelGateway {
    private final Deque<Outcome> outcomes = new ArrayDeque<>();

    void queue(Outcome outcome) {
      outcomes.add(outcome);
    }

    @Override
    public Admission admit(Execution execution) {
      Outcome outcome = outcomes.poll();
      if (outcome == null) {
        throw new IllegalStateException("no queued gateway outcome");
      }
      return new Admitted() {
        @Override
        public Outcome run(Sink sink) {
          return outcome;
        }

        @Override
        public void close() {}
      };
    }
  }

  private record ClosedTurnBaseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID userEntryId,
      UUID assistantEntryId,
      UUID turnEndEntryId,
      UUID threadId) {}

  private ClosedTurnBaseline seedCompactionReadyClosedTurn(HarnessStore store, ModelUsage usage) {
    ProviderResponse response =
        new ProviderResponse(
            "assistant reply",
            "",
            List.of(),
            GenerationStopReason.COMPLETE,
            usage,
            "req-1",
            null,
            "{}");
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turn1StartId = tx.nextId();
          UUID turn1UserId = tx.nextId();
          UUID turn1AssistantId = tx.nextId();
          UUID turn1EndId = tx.nextId();
          UUID turn2StartId = tx.nextId();
          UUID turn2UserId = tx.nextId();
          UUID turn2AssistantId = tx.nextId();
          UUID turn2EndId = tx.nextId();
          UUID threadId = tx.nextId();

          tx.insertSession(new Session(sessionId, "session-" + sessionId, T1));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), T1));

          // turn1：可摘要历史显著大于测试摘要 wrapper，确保成功摘要有真实 gain
          tx.insertEntry(
              new Entry(
                  turn1StartId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), T1));
          tx.insertEntry(
              new Entry(
                  turn1UserId,
                  sessionId,
                  turn1StartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("historical user " + "h".repeat(50_000)))),
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1AssistantId,
                  sessionId,
                  turn1UserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new TextMessageContent(
                                  "historical assistant " + "a".repeat(50_000)))),
                      new AssistantMessageMetadata(
                          GenerationStopReason.COMPLETE,
                          new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn1EndId,
                  sessionId,
                  turn1AssistantId,
                  new TurnEndPayload(turn1StartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));

          // turn2：长 USER + 短 ASSISTANT（携带越过阈值的 usage）
          tx.insertEntry(
              new Entry(turn2StartId, sessionId, turn1EndId, resolvedInputTurnStart(threadId), T1));
          tx.insertEntry(
              new Entry(
                  turn2UserId,
                  sessionId,
                  turn2StartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(
                              new TextMessageContent(
                                  "user asks a very long question" + "x".repeat(90_000)))),
                      null,
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2AssistantId,
                  sessionId,
                  turn2UserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent(response.text()))),
                      new AssistantMessageMetadata(response.stopReason(), response.usage()),
                      null),
                  T1));
          tx.insertEntry(
              new Entry(
                  turn2EndId,
                  sessionId,
                  turn2AssistantId,
                  new TurnEndPayload(turn2StartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));

          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  null,
                  turn2EndId,
                  CREATION_REQUEST_HASH,
                  "main",
                  threadId,
                  false,
                  SpawnTrigger.USER,
                  ThreadConstraints.NONE,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  T1,
                  T1));

          return new ClosedTurnBaseline(
              sessionId,
              rootEntryId,
              turn2StartId,
              turn2UserId,
              turn2AssistantId,
              turn2EndId,
              threadId);
        });
  }

  private static TurnStartPayload resolvedInputTurnStart(UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.INPUT,
        branchSettings(),
        ownerThreadId,
        CONTEXT_WINDOW,
        MAX_OUTPUT_TOKENS,
        null,
        null);
  }

  private UUID seedUserCommand(UUID threadId, String text) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          UUID idempotencyKey = tx.nextId();
          long seq = thread.nextCommandSequence();
          UserMessageCommandPayload payload =
              new UserMessageCommandPayload(AgentMessage.user(text));
          ThreadCommand command =
              new ThreadCommand(
                  threadId,
                  seq,
                  payload,
                  idempotencyKey,
                  CREATION_REQUEST_HASH,
                  null,
                  null,
                  null,
                  T1);
          tx.insertCommands(List.of(command));
          tx.updateThread(thread.reserveCommandSequences(1, T1));
          return idempotencyKey;
        });
  }

  private void requestThreadWork(UUID threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
          return null;
        });
  }

  private ClaimedWork processNext(UUID expectedThreadId) {
    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD,
                        UUID.randomUUID().toString(),
                        Duration.ofSeconds(30)))
            .orElseThrow();
    assertEquals(expectedThreadId, claim.target().id());
    assertEquals(ThreadProcessResult.COMPLETED, processor.process(claim));
    return claim;
  }

  private void startModel(UUID threadId) {
    store.transaction(
        tx -> {
          ThreadState thread = ThreadLifecycleCoordinator.lockThreadWithParent(tx, threadId);
          UUID turn = tx.loadEntryPath(thread.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation model = tx.findModelInvocationByTurn(threadId, turn).orElseThrow();
          assertEquals(ModelInvocationStatus.READY, model.status());
          tx.lockModelInvocation(model.id());
          tx.updateModelInvocation(model.markRunning(T1));
          return null;
        });
  }

  private void finishModel(UUID threadId, String text) {
    ThreadSnapshot current = snapshot(threadId);
    if (current.model() != null && current.model().status() == ModelInvocationStatus.READY) {
      startModel(threadId);
    }
    store.transaction(
        tx -> {
          ThreadState thread = ThreadLifecycleCoordinator.lockThreadWithParent(tx, threadId);
          UUID turn = tx.loadEntryPath(thread.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation model = tx.findModelInvocationByTurn(threadId, turn).orElseThrow();
          assertEquals(ModelInvocationStatus.RUNNING, model.status());
          tx.lockModelInvocation(model.id());
          ProviderResponse response =
              new ProviderResponse(
                  text,
                  "",
                  List.of(),
                  GenerationStopReason.COMPLETE,
                  new ModelUsage(100L, 20L, 0L, 0L, Map.of(), 0L, 120L),
                  "req-compaction-child",
                  null,
                  "{}");
          tx.updateModelInvocation(model.succeed(response, T1));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
          return null;
        });
  }

  private ThreadSnapshot snapshot(UUID threadId) {
    return runtime.getThreadSnapshot(threadId);
  }

  private EntryPath path(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.findThread(threadId).orElseThrow();
          return tx.loadEntryPath(thread.headEntryId());
        });
  }

  private int queuedCommandCount(UUID threadId) {
    return store.transaction(
        tx -> {
          tx.lockThread(threadId);
          return tx.loadQueuedCommands(threadId).size();
        });
  }

  private boolean hasWork(WorkTargetType type, UUID targetId) {
    return store.<Boolean>transaction(
        tx -> tx.findWork(new WorkTarget(type, targetId)).isPresent());
  }

  private ThreadJoin findJoin(UUID invocationId) {
    return store.transaction(tx -> tx.findJoin(invocationId)).orElseThrow();
  }

  private Optional<ModelInvocation> findModelInvocationByTurn(UUID threadId, UUID turnStartId) {
    return store.transaction(tx -> tx.findModelInvocationByTurn(threadId, turnStartId));
  }

  private static ModelRequestSpec requestFor(BranchSettings settings) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor(
            "provider",
            settings.model().modelName(),
            settings.model().modelName(),
            Set.of(ModelInputModality.TEXT),
            true,
            true),
        new ModelVariant(settings.model().variant()),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }
}
