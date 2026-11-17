package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinRegistry;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.ToolSuccess;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 真实 PostgreSQL 上的 Tool 租约失联恢复与模型循环续跑验收：失联工具不执行，只生成如实的错误 tool_result；ThreadProcessor 应用该 batch 并要求
 * {@code continueModel = true}；下一个 claim 真正创建下一轮 ModelInvocation。
 */
class PostgresqlToolLeaseRecoveryContinuationTest {

  private static final UUID OLD_LEASE = UUID.randomUUID();

  private static final Duration LEASE = Duration.ofSeconds(30);

  private HarnessStore store;
  private ScheduledExecutorService scheduler;
  private CountingToolGateway toolGateway;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    toolGateway = new CountingToolGateway();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  /**
   * This narrow persistence probe checks only that status and Work rows survive Store recreation.
   */
  @Test
  void waitingChildStatusAndToolWorkRowsSurviveStoreRecreation() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    String argumentsJson = "{\"subagent_type\":\"alpha\",\"prompt\":\"work\"}";
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "task",
            "delegate work",
            "task",
            new InputSchema(
                "task arguments",
                Map.of(
                    "subagent_type",
                    new StringSchema("type"),
                    "prompt",
                    new StringSchema("prompt")),
                Set.of("subagent_type", "prompt"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ZERO);
    ToolBinding taskBinding =
        new ToolBinding(
            new AgentToolDefinition(descriptor, ToolVisibility.INTERNAL),
            new ContributorBinding("builtin", "task"),
            EnvironmentSupport.NONE,
            null,
            null);
    ToolCall call = new ToolCall("call-task", "task", argumentsJson);
    UUID toolId = UUID.randomUUID();
    store.transaction(
        tx -> {
          var thread = tx.threads().lock(turn.threadId()).orElseThrow();
          UUID userId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      userId,
                      turn.sessionId(),
                      turn.turnStartEntryId(),
                      StoreTestSupport.userMessagePayload(),
                      T2));
          UUID assistantId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistantId,
                      turn.sessionId(),
                      userId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.ASSISTANT,
                              List.of(
                                  new ToolCallMessageContent(
                                      "call-task", "task", "task", argumentsJson),
                                  new TextMessageContent("assistant"))),
                          new AssistantMessageMetadata(
                              GenerationStopReason.COMPLETE,
                              new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                          null),
                      T3));
          tx.threads().update(thread.advanceHead(assistantId, T3));
          ToolInvocation ready =
              new ToolInvocation(
                  toolId,
                  turn.threadId(),
                  turn.turnStartEntryId(),
                  assistantId,
                  0,
                  call,
                  taskBinding,
                  ToolInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  T3,
                  T3);
          tx.tools().insert(List.of(ready));
          ToolInvocation allowed = ready.markApprovalNotRequired(T3);
          tx.tools().update(List.of(allowed));
          ToolInvocation running = allowed.markRunning(T3);
          tx.tools().update(List.of(running));
          tx.tools().update(List.of(running.waitForChild(T3)));
          return null;
        });

    assertEquals(ToolInvocationStatus.WAITING_CHILD, findTool(toolId).status());
    requestWork(new WorkTarget(WorkTargetType.TOOL, toolId), turn.threadId());

    HarnessStore restartedStore = PostgresqlHarnessStoreFixture.create();
    assertEquals(
        ToolInvocationStatus.WAITING_CHILD,
        restartedStore.transaction(tx -> tx.tools().find(toolId).orElseThrow().status()));
    ClaimedWork recovered =
        restartedStore
            .transaction(tx -> tx.work().claimNext(WorkTargetType.TOOL, UUID.randomUUID(), LEASE))
            .orElseThrow();
    assertEquals(new WorkTarget(WorkTargetType.TOOL, toolId), recovered.target());
  }

  @Test
  void matchedTaskJoinIsDeliveredExactlyOnceByRecreatedProcessor() {
    AcceptedTask task = acceptWaitingTask();
    HarnessRuntime parentRuntime = runtimeFor(store);

    // A real child Stop freezes its accepted SETTLE_TOOL_RESULT Join and requests the parent's TOOL
    // work.
    UUID stopRequestId = UUID.randomUUID();
    var child = store.transaction(tx -> tx.threads().find(task.childThreadId()).orElseThrow());
    parentRuntime.stop(new StopCommand(task.childThreadId(), stopRequestId, child.version()));
    ThreadJoin matched = store.transaction(tx -> tx.joins().find(task.toolId()).orElseThrow());
    assertTrue(matched.matched());
    assertEquals(JoinDelivery.SETTLE_TOOL_RESULT, matched.delivery());
    assertEquals(ToolInvocationStatus.WAITING_CHILD, findTool(task.toolId()).status());
    assertTrue(findWork(new WorkTarget(WorkTargetType.TOOL, task.toolId())).isPresent());

    // Recreate both store and processor; the frozen Join receipt, not a replayed task execution,
    // settles the original invocation.
    store = PostgresqlHarnessStoreFixture.create();
    ToolProcessor recreatedProcessor = newToolProcessor();
    ClaimedWork recovered = claim(WorkTargetType.TOOL, UUID.randomUUID()).orElseThrow();
    assertEquals(ProcessResult.TERMINATED, recreatedProcessor.process(recovered));
    assertEquals(ToolInvocationStatus.SUCCEEDED, findTool(task.toolId()).status());
    assertTrue(findWork(new WorkTarget(WorkTargetType.TOOL, task.toolId())).isEmpty());
    assertEquals(
        task.childThreadId(),
        store.transaction(tx -> tx.joins().find(task.toolId()).orElseThrow().childThreadId()));
    assertEquals(0, toolGateway.startCalls(), "Join settlement must not replay external tool work");

    // A duplicate TOOL wake after terminal settlement is cleanup-only and cannot append another
    // result.
    requestWork(new WorkTarget(WorkTargetType.TOOL, task.toolId()), task.parentThreadId());
    assertEquals(
        ProcessResult.TERMINATED,
        recreatedProcessor.process(claim(WorkTargetType.TOOL, UUID.randomUUID()).orElseThrow()));
    assertEquals(ToolInvocationStatus.SUCCEEDED, findTool(task.toolId()).status());
    assertTrue(findWork(new WorkTarget(WorkTargetType.TOOL, task.toolId())).isEmpty());

    ThreadProcessor threadProcessor = newThreadProcessor();
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(claim(WorkTargetType.THREAD, UUID.randomUUID()).orElseThrow()));
    List<MessagePayload> results = toolResults(threadHead(task.parentThreadId()), "call-task");
    assertEquals(
        1, results.size(), "the original task invocation materializes exactly one tool_result");
    ToolResultMetadata result = results.getFirst().toolResultMetadata();
    assertEquals(0, result.callIndex());
    assertEquals(ToolResultStatus.SUCCEEDED, result.status());
    assertTrue(findWork(new WorkTarget(WorkTargetType.THREAD, task.parentThreadId())).isPresent());
    assertEquals(0, toolGateway.startCalls(), "result application must not re-execute the task");
  }

  @Test
  void joinMatchWhileOldToolClaimIsHeldSurvivesOldClaimCompletion() {
    AcceptedTask task = acceptWaitingTask();
    requestWork(new WorkTarget(WorkTargetType.TOOL, task.toolId()), task.parentThreadId());
    ClaimedWork oldClaim = claim(WorkTargetType.TOOL, UUID.randomUUID()).orElseThrow();

    HarnessRuntime parentRuntime = runtimeFor(store);
    var child = store.transaction(tx -> tx.threads().find(task.childThreadId()).orElseThrow());
    parentRuntime.stop(new StopCommand(task.childThreadId(), UUID.randomUUID(), child.version()));
    ThreadJoin matched = store.transaction(tx -> tx.joins().find(task.toolId()).orElseThrow());
    assertTrue(matched.matched());

    store.transaction(
        tx -> {
          tx.threads().lock(task.parentThreadId());
          tx.work().complete(oldClaim);
          return null;
        });
    Work newer = findWork(new WorkTarget(WorkTargetType.TOOL, task.toolId())).orElseThrow();
    assertEquals(2L, newer.wakeVersion());
    assertTrue(newer.leaseToken() == null, "completing the stale claim must release its lease");
    ClaimedWork retry = claim(WorkTargetType.TOOL, UUID.randomUUID()).orElseThrow();
    assertEquals(2L, retry.claimedWakeVersion());
  }

  @Test
  void parentStopRacesTaskChildTerminalWithoutLeavingToolWork() throws Exception {
    AcceptedTask task = acceptWaitingTask();
    ClaimedWork childClaim = claim(WorkTargetType.THREAD, UUID.randomUUID()).orElseThrow();
    assertEquals(task.childThreadId(), childClaim.target().id());

    ThreadState parent =
        store.transaction(tx -> tx.threads().find(task.parentThreadId()).orElseThrow());
    ThreadProcessor childProcessor = newThreadProcessor();
    HarnessRuntime stopRuntime = runtimeFor(store);
    UUID stopRequestId = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<StopResult> parentStop =
          executor.submit(
              () -> {
                start.await();
                return stopRuntime.stop(
                    new StopCommand(task.parentThreadId(), stopRequestId, parent.version()));
              });
      Future<ThreadProcessResult> childTerminal =
          executor.submit(
              () -> {
                start.await();
                return childProcessor.process(childClaim);
              });
      start.countDown();

      try {
        assertNotNull(parentStop.get(10, TimeUnit.SECONDS));
      } catch (ExecutionException failure) {
        DomainException conflict = assertInstanceOf(DomainException.class, failure.getCause());
        assertEquals(DomainErrorCode.CONFLICT, conflict.code());
        assertEquals(HarnessConflictReason.STALE_VERSION.name(), conflict.reason());
        ThreadState current =
            store.transaction(tx -> tx.threads().find(task.parentThreadId()).orElseThrow());
        assertNotNull(
            stopRuntime.stop(
                new StopCommand(task.parentThreadId(), stopRequestId, current.version())));
      }
      assertNotNull(childTerminal.get(10, TimeUnit.SECONDS));
    }

    ThreadState stoppedParent =
        store.transaction(tx -> tx.threads().find(task.parentThreadId()).orElseThrow());
    assertEquals(ThreadExecutionControl.STOPPED, stoppedParent.executionControl());
    assertTrue(
        store.<Boolean>transaction(tx -> tx.joins().find(task.toolId()).orElseThrow().matched()));
    assertTrue(store.<Boolean>transaction(tx -> tx.tools().find(task.toolId()).isEmpty()));
    assertTrue(findWork(new WorkTarget(WorkTargetType.TOOL, task.toolId())).isEmpty());
    assertTrue(findWork(new WorkTarget(WorkTargetType.THREAD, task.parentThreadId())).isEmpty());
    assertEquals(
        1,
        toolResults(stoppedParent.headEntryId(), "call-task").size(),
        "Stop/child settlement race must materialize the task result exactly once");
    assertEquals(0, toolGateway.startCalls(), "the non-idempotent task must not be replayed");
  }

  @Test
  void expiredToolRecoveryFeedsHonestResultAndContinuesModelLoop() {
    StoreTestSupport.TurnBaseline turn =
        StoreTestSupport.seedTurnBaseline(store, succeededRequest().toolBindings());
    UUID userId = seedUserEntry(turn);
    ModelRequestSpec request = succeededRequest();
    ProviderResponse response = StoreTestSupport.assistantResponse("call-0");
    UUID modelId = seedRunningModelWithToolCall(turn, userId, request, response);
    UUID assistantId =
        store.transaction(
            tx -> builtin(store, tx).findModelInvocation(modelId).orElseThrow().resultEntryId());
    UUID toolId =
        store.transaction(tx -> tx.tools().loadByAssistantEntry(assistantId).getFirst().id());
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, toolId);
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, turn.threadId());
    requestWork(toolTarget, turn.threadId());

    // 工具执行持有者失联：不重放，只把 invocation 如实转为 UNKNOWN 并唤醒 ThreadProcessor。
    ToolProcessor toolProcessor = newToolProcessor();
    assertEquals(
        ProcessResult.TERMINATED,
        toolProcessor.process(claim(WorkTargetType.TOOL, UUID.randomUUID()).orElseThrow()));

    ToolInvocation recovered = findTool(toolId);
    assertEquals(ToolInvocationStatus.UNKNOWN, recovered.status());
    assertEquals("LEASE_EXPIRED", recovered.error().kind());
    assertTrue(recovered.error().message().contains("cannot be confirmed"));
    assertTrue(recovered.error().message().contains("RUNNING"));
    assertEquals(0, toolGateway.startCalls(), "recovery must not re-execute the tool");
    assertTrue(findWork(toolTarget).isEmpty());
    assertTrue(findWork(threadTarget).isPresent());

    // ThreadProcessor 应用 UNKNOWN batch：写入唯一一条如实错误 tool_result 并要求继续模型循环。
    ThreadProcessor threadProcessor = newThreadProcessor();
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(claim(WorkTargetType.THREAD, UUID.randomUUID()).orElseThrow()));

    EntryPath applied = loadPath(threadHead(turn.threadId()));
    List<MessagePayload> toolResults =
        applied.entries().stream()
            .map(Entry::payload)
            .filter(MessagePayload.class::isInstance)
            .map(MessagePayload.class::cast)
            .filter(
                payload ->
                    payload.toolResultMetadata() != null
                        && "call-0".equals(payload.toolResultMetadata().toolCallId()))
            .toList();
    assertEquals(1, toolResults.size(), "exactly one materialized tool_result, no duplicates");
    ToolResultMetadata metadata = toolResults.getFirst().toolResultMetadata();
    assertEquals(0, metadata.callIndex());
    assertEquals(ToolResultStatus.UNKNOWN, metadata.status());
    ToolResultMessageContent content =
        (ToolResultMessageContent) toolResults.getFirst().message().contents().getFirst();
    assertTrue(content.error());
    assertTrue(
        ((TextMessageContent) content.contents().getFirst())
            .text()
            .contains("cannot be confirmed"));
    TurnEndPayload batchEnd = assertInstanceOf(TurnEndPayload.class, applied.head().payload());
    assertTrue(batchEnd.continueModel());
    assertTrue(store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.tools().loadByAssistantEntry(assistantId)).isEmpty());
    assertTrue(findWork(threadTarget).isPresent());

    // 下一个 claim 真正创建下一轮 ModelInvocation（而不是只留下 TURN_END）。
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(claim(WorkTargetType.THREAD, UUID.randomUUID()).orElseThrow()));
    UUID continuationEntryId = threadHead(turn.threadId());
    ModelInvocation continuation =
        store
            .transaction(
                tx ->
                    builtin(store, tx)
                        .findModelInvocationByTurn(turn.threadId(), continuationEntryId))
            .orElseThrow();
    assertEquals(ModelInvocationStatus.READY, continuation.status());
    assertNotEquals(modelId, continuation.id());
    assertTrue(findWork(new WorkTarget(BuiltinWorkType.MODEL, continuation.id())).isPresent());
    assertEquals(0, toolGateway.startCalls(), "recovery must not re-execute the tool");
  }

  private static final EnvironmentId ENV_ID =
      new EnvironmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

  private static ToolBinding remoteBinding() {
    InputSchema schema = new InputSchema("arguments", Map.of(), Set.of(), true);
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "bash",
                "bash tool",
                "bash",
                schema,
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash"),
        EnvironmentSupport.REQUIRED,
        ENV_ID,
        "bash");
  }

  private static ToolGateway.Succeeded succeeded(String toolCallId) {
    return new ToolGateway.Succeeded(
        new ToolSuccess(
            new ToolResult(toolCallId, List.of(new TextResultContent("ok")), false, "{}")));
  }

  @Test
  void expiredRemoteToolRecoveryReattachesAndSucceedsContinuation() {
    ToolBinding remoteBinding = remoteBinding();
    StoreTestSupport.TurnBaseline turn =
        StoreTestSupport.seedTurnBaseline(store, List.of(remoteBinding));
    UUID userId = seedUserEntry(turn);
    ModelRequestSpec request = succeededRequest();
    ProviderResponse response = StoreTestSupport.assistantResponse("call-0");
    UUID modelId =
        seedRunningModelWithRemoteToolCall(turn, userId, request, response, remoteBinding);
    UUID assistantId =
        store.transaction(
            tx -> builtin(store, tx).findModelInvocation(modelId).orElseThrow().resultEntryId());
    UUID toolId =
        store.transaction(tx -> tx.tools().loadByAssistantEntry(assistantId).getFirst().id());
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, toolId);
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, turn.threadId());
    requestWork(toolTarget, turn.threadId());

    UUID oldToolExecutionId =
        store.transaction(
            tx -> {
              tx.threads().lock(turn.threadId()).orElseThrow();
              return ExecutionExitLedger.register(
                      tx,
                      tx.nextId(),
                      turn.threadId(),
                      toolId,
                      1,
                      OLD_LEASE,
                      ExecutionExitKind.TOOL,
                      null)
                  .executionId();
            });
    UUID oldRemoteExecutionId =
        store.transaction(
            tx -> {
              tx.threads().lock(turn.threadId()).orElseThrow();
              return ExecutionExitLedger.register(
                      tx,
                      tx.nextId(),
                      turn.threadId(),
                      toolId,
                      1,
                      OLD_LEASE,
                      ExecutionExitKind.REMOTE,
                      oldToolExecutionId,
                      ENV_ID)
                  .executionId();
            });

    toolGateway.setReattachHandler(
        reattach -> {
          assertEquals(toolId, reattach.invocationId());
          assertEquals(ENV_ID, reattach.environmentId());
          assertEquals(oldRemoteExecutionId, reattach.remoteExecutionId());
          return new CompletedRun(
              succeeded("call-0"),
              () -> reattach.remoteExecutions().confirmExit(oldRemoteExecutionId));
        });

    ToolProcessor toolProcessor = newToolProcessor();
    assertEquals(
        ProcessResult.STARTED,
        toolProcessor.process(claim(WorkTargetType.TOOL, UUID.randomUUID()).orElseThrow()));

    awaitTrue(
        () -> findTool(toolId).status() == ToolInvocationStatus.SUCCEEDED, Duration.ofSeconds(5));
    awaitTrue(() -> !toolProcessor.hasActiveExecution(), Duration.ofSeconds(5));

    ToolInvocation recovered = findTool(toolId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, recovered.status());
    assertEquals(1, recovered.attempt(), "attempt must remain unchanged");
    assertEquals(0, toolGateway.startCalls(), "must not admit new invocation");
    assertEquals(1, toolGateway.reattachCalls(), "must reattach once");

    // Exit ledger facts verification
    ExecutionExitFact loadedRemote =
        store.transaction(tx -> tx.exits().find(oldRemoteExecutionId)).orElseThrow();
    assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, loadedRemote.state());

    ExecutionExitFact loadedOldTool =
        store.transaction(tx -> tx.exits().find(oldToolExecutionId)).orElseThrow();
    assertEquals(ExecutionExitState.UNCONFIRMED, loadedOldTool.state());

    List<ExecutionExitFact> unconfirmed =
        store.transaction(tx -> tx.exits().loadUnconfirmed(turn.threadId()));
    assertEquals(1, unconfirmed.size());
    assertEquals(oldToolExecutionId, unconfirmed.getFirst().executionId());

    assertTrue(findWork(toolTarget).isEmpty());
    assertTrue(findWork(threadTarget).isPresent());

    // ThreadProcessor 应用 SUCCEEDED batch：写入结果 entry 并要求继续模型循环。
    ThreadProcessor threadProcessor = newThreadProcessor();
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(claim(WorkTargetType.THREAD, UUID.randomUUID()).orElseThrow()));

    EntryPath applied = loadPath(threadHead(turn.threadId()));
    List<MessagePayload> toolResults =
        applied.entries().stream()
            .map(Entry::payload)
            .filter(MessagePayload.class::isInstance)
            .map(MessagePayload.class::cast)
            .filter(
                payload ->
                    payload.toolResultMetadata() != null
                        && "call-0".equals(payload.toolResultMetadata().toolCallId()))
            .toList();
    assertEquals(1, toolResults.size(), "exactly one materialized tool_result");
    ToolResultMetadata metadata = toolResults.getFirst().toolResultMetadata();
    assertEquals(0, metadata.callIndex());
    assertEquals(ToolResultStatus.SUCCEEDED, metadata.status());

    TurnEndPayload batchEnd = assertInstanceOf(TurnEndPayload.class, applied.head().payload());
    assertTrue(batchEnd.continueModel());
    assertTrue(store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.tools().loadByAssistantEntry(assistantId)).isEmpty());
    assertTrue(findWork(threadTarget).isPresent());

    // 下一个 claim 真正创建下一轮 ModelInvocation
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(claim(WorkTargetType.THREAD, UUID.randomUUID()).orElseThrow()));
    UUID continuationEntryId = threadHead(turn.threadId());
    ModelInvocation continuation =
        store
            .transaction(
                tx ->
                    builtin(store, tx)
                        .findModelInvocationByTurn(turn.threadId(), continuationEntryId))
            .orElseThrow();
    assertEquals(ModelInvocationStatus.READY, continuation.status());
    assertNotEquals(modelId, continuation.id());
    assertTrue(findWork(new WorkTarget(BuiltinWorkType.MODEL, continuation.id())).isPresent());
  }

  private ToolProcessor newToolProcessor() {
    InvocationRetryPolicy policy =
        new InvocationRetryPolicy(
            0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    return new ToolProcessor(
        store,
        toolGateway,
        event -> {},
        new ToolProcessorConfig(
            () -> new ProcessorLeaseConfig(LEASE, Duration.ofSeconds(5)),
            () -> policy,
            () -> Duration.ofSeconds(7),
            () -> Duration.ofSeconds(9),
            Duration.ofMinutes(10)),
        Clock.systemUTC(),
        scheduler,
        Runnable::run,
        ExecutionExitTestSupport.lifecycle(store, Clock.systemUTC()),
        ExecutionExitTestSupport.confirmer(store, Clock.systemUTC()),
        (sessionId, toolName, result) -> result);
  }

  private ThreadProcessor newThreadProcessor() {
    TurnResolver resolver =
        (threadId, rootSessionId, path, preparation) ->
            new TurnResolver.Resolved(succeededRequest(), 100_000, 16_384);
    return new ThreadProcessor(
        store,
        builtinRegistry(store, resolver),
        new ThreadProcessorConfig(() -> Duration.ofSeconds(5), () -> new CompactionConfig(20_000)));
  }

  private UUID seedUserEntry(StoreTestSupport.TurnBaseline turn) {
    return store.transaction(
        tx -> {
          var thread = tx.threads().lock(turn.threadId()).orElseThrow();
          UUID id = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      id,
                      turn.sessionId(),
                      turn.turnStartEntryId(),
                      StoreTestSupport.userMessagePayload(),
                      T2));
          tx.threads().update(thread.advanceHead(id, T2));
          return id;
        });
  }

  /**
   * 一条 RUNNING 的模型调用加上其 assistant Entry 与单个 RUNNING attempt 1 的 ToolInvocation，模拟「模型已给出工具调用、工具在途、
   * 持有者随后失联」的线上状态。
   */
  private UUID seedRunningModelWithToolCall(
      StoreTestSupport.TurnBaseline turn,
      UUID requestHeadEntryId,
      ModelRequestSpec request,
      ProviderResponse response) {
    UUID modelId =
        store.transaction(
            tx -> {
              tx.threads().lock(turn.threadId()).orElseThrow();
              UUID id = tx.nextId();
              builtin(store, tx)
                  .insertModelInvocation(
                      new ModelInvocation(
                          id,
                          turn.threadId(),
                          turn.turnStartEntryId(),
                          requestHeadEntryId,
                          request,
                          ModelInvocationStatus.READY,
                          0,
                          null,
                          null,
                          null,
                          null,
                          List.of(),
                          T2,
                          T2,
                          null,
                          null));
              return id;
            });
    store.transaction(
        tx -> {
          var thread = tx.threads().lock(turn.threadId()).orElseThrow();
          builtin(store, tx)
              .updateModelInvocation(
                  builtin(store, tx).lockModelInvocation(modelId).orElseThrow().markRunning(T3));
          UUID assistantId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistantId,
                      turn.sessionId(),
                      requestHeadEntryId,
                      mappedAssistant(request, response, T3),
                      T3));
          builtin(store, tx)
              .updateModelInvocation(
                  builtin(store, tx)
                      .lockModelInvocation(modelId)
                      .orElseThrow()
                      .succeed(response, null, T3));
          builtin(store, tx)
              .updateModelInvocation(
                  builtin(store, tx)
                      .lockModelInvocation(modelId)
                      .orElseThrow()
                      .attachResultEntry(assistantId, T3));
          tx.threads().update(thread.advanceHead(assistantId, T3));
          UUID toolId = tx.nextId();
          tx.tools()
              .insert(
                  List.of(
                      StoreTestSupport.toolInvocation(
                          toolId,
                          turn.threadId(),
                          turn.turnStartEntryId(),
                          assistantId,
                          0,
                          "call-0",
                          ToolInvocationStatus.READY,
                          T3)));
          ToolInvocation ready =
              tx.tools().lockByAssistantEntry(assistantId).getFirst().markApprovalNotRequired(T3);
          tx.tools().update(List.of(ready));
          tx.tools().update(List.of(ready.markRunning(T3)));
          return null;
        });
    return modelId;
  }

  private record AcceptedTask(UUID parentThreadId, UUID childThreadId, UUID toolId) {}

  /** Accept a genuine SETTLE_TOOL_RESULT Join matching a durable task invocation. */
  private AcceptedTask acceptWaitingTask() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    String prompt = "work";
    String argumentsJson = "{\"subagent_type\":\"alpha\",\"prompt\":\"work\"}";
    ToolBinding binding = taskBinding();
    ToolCall call = new ToolCall("call-task", "task", argumentsJson);
    UUID toolId = UUID.randomUUID();
    UUID assistantId =
        store.transaction(
            tx -> {
              var thread = tx.threads().lock(turn.threadId()).orElseThrow();
              UUID userId = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          userId,
                          turn.sessionId(),
                          turn.turnStartEntryId(),
                          StoreTestSupport.userMessagePayload(),
                          T2));
              UUID assistant = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          assistant,
                          turn.sessionId(),
                          userId,
                          new MessagePayload(
                              new AgentMessage(
                                  AgentMessageRole.ASSISTANT,
                                  List.of(
                                      new ToolCallMessageContent(
                                          call.id(), "task", "task", argumentsJson))),
                              new AssistantMessageMetadata(
                                  GenerationStopReason.COMPLETE,
                                  new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                              null),
                          T3));
              tx.threads().update(thread.advanceHead(assistant, T3));
              ToolInvocation ready =
                  new ToolInvocation(
                      toolId,
                      turn.threadId(),
                      turn.turnStartEntryId(),
                      assistant,
                      0,
                      call,
                      binding,
                      ToolInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      T3,
                      T3);
              tx.tools().insert(List.of(ready));
              ToolInvocation allowed = ready.markApprovalNotRequired(T3);
              tx.tools().update(List.of(allowed));
              tx.tools().update(List.of(allowed.markRunning(T3)));
              return assistant;
            });

    SubagentTaskRequest request =
        new SubagentTaskRequest(toolId, turn.threadId(), prompt, "alpha", null, null);
    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = request.childThreadId();
    SpawnTestSupport.Child source =
        new SpawnTestSupport.Child(
            childSessionId,
            childThreadId,
            turn.threadId(),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(AgentMessage.user(prompt)), UUID.randomUUID())));
    SpawnTestSupport.Ticket ticket =
        new SpawnTestSupport.Ticket(
            toolId,
            turn.threadId(),
            assistantId,
            request.requestHash(),
            "alpha",
            null,
            3,
            4,
            8,
            JoinDelivery.SETTLE_TOOL_RESULT);
    SpawnTestSupport.acceptAndJoin(
        runtimeFor(store),
        source,
        StoreTestSupport.branchSettings(),
        ticket,
        AcceptancePreflight.IDENTITY);

    store.transaction(
        tx -> {
          ToolInvocation running = tx.tools().lock(toolId).orElseThrow();
          tx.tools().update(List.of(running.waitForChild(T3)));
          return null;
        });
    assertEquals(
        JoinDelivery.SETTLE_TOOL_RESULT,
        store.transaction(tx -> tx.joins().find(toolId).orElseThrow().delivery()));
    return new AcceptedTask(turn.threadId(), childThreadId, toolId);
  }

  private static ToolBinding taskBinding() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "task",
            "delegate work",
            "task",
            new InputSchema(
                "task arguments",
                Map.of(
                    "subagent_type",
                    new StringSchema("type"),
                    "prompt",
                    new StringSchema("prompt")),
                Set.of("subagent_type", "prompt"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ZERO);
    return new ToolBinding(
        new AgentToolDefinition(descriptor, ToolVisibility.INTERNAL),
        new ContributorBinding("builtin", "task"),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private HarnessRuntime runtimeFor(HarnessStore targetStore) {
    return new HarnessRuntime(
        targetStore,
        builtinRegistry(targetStore, (threadId, rootSessionId, path, prep) -> null),
        () -> new CompactionConfig(20_000));
  }

  private void requestWork(WorkTarget target, UUID ownerThreadId) {
    store.transaction(
        tx -> {
          tx.threads().lock(ownerThreadId).orElseThrow();
          tx.work().request(target);
          return null;
        });
  }

  private UUID seedRunningModelWithRemoteToolCall(
      StoreTestSupport.TurnBaseline turn,
      UUID requestHeadEntryId,
      ModelRequestSpec request,
      ProviderResponse response,
      ToolBinding remoteBinding) {
    UUID modelId =
        store.transaction(
            tx -> {
              tx.threads().lock(turn.threadId()).orElseThrow();
              UUID id = tx.nextId();
              builtin(store, tx)
                  .insertModelInvocation(
                      new ModelInvocation(
                          id,
                          turn.threadId(),
                          turn.turnStartEntryId(),
                          requestHeadEntryId,
                          request,
                          ModelInvocationStatus.READY,
                          0,
                          null,
                          null,
                          null,
                          null,
                          List.of(),
                          T2,
                          T2,
                          null,
                          null));
              return id;
            });
    store.transaction(
        tx -> {
          var thread = tx.threads().lock(turn.threadId()).orElseThrow();
          builtin(store, tx)
              .updateModelInvocation(
                  builtin(store, tx).lockModelInvocation(modelId).orElseThrow().markRunning(T3));
          UUID assistantId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistantId,
                      turn.sessionId(),
                      requestHeadEntryId,
                      mappedAssistant(request, response, T3),
                      T3));
          builtin(store, tx)
              .updateModelInvocation(
                  builtin(store, tx)
                      .lockModelInvocation(modelId)
                      .orElseThrow()
                      .succeed(response, null, T3));
          builtin(store, tx)
              .updateModelInvocation(
                  builtin(store, tx)
                      .lockModelInvocation(modelId)
                      .orElseThrow()
                      .attachResultEntry(assistantId, T3));
          tx.threads().update(thread.advanceHead(assistantId, T3));
          UUID toolId = tx.nextId();
          ToolInvocation ready =
              new ToolInvocation(
                  toolId,
                  turn.threadId(),
                  turn.turnStartEntryId(),
                  assistantId,
                  0,
                  StoreTestSupport.toolCall("call-0"),
                  remoteBinding,
                  ToolInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  T3,
                  T3);
          tx.tools().insert(List.of(ready));
          ToolInvocation running =
              tx.tools().lockByAssistantEntry(assistantId).getFirst().markApprovalNotRequired(T3);
          tx.tools().update(List.of(running));
          tx.tools().update(List.of(running.markRunning(T3)));
          return null;
        });
    return modelId;
  }

  private Optional<ClaimedWork> claim(WorkTargetType type, UUID token) {
    return store.transaction(tx -> tx.work().claimNext(type, token, LEASE));
  }

  private ToolInvocation findTool(UUID toolId) {
    return store.transaction(tx -> tx.tools().find(toolId)).orElseThrow();
  }

  private Optional<Work> findWork(WorkTarget target) {
    return store.transaction(tx -> tx.work().find(target));
  }

  private List<MessagePayload> toolResults(UUID headEntryId, String toolCallId) {
    return loadPath(headEntryId).entries().stream()
        .map(Entry::payload)
        .filter(MessagePayload.class::isInstance)
        .map(MessagePayload.class::cast)
        .filter(
            payload ->
                payload.toolResultMetadata() != null
                    && toolCallId.equals(payload.toolResultMetadata().toolCallId()))
        .toList();
  }

  private UUID threadHead(UUID threadId) {
    return store.transaction(tx -> tx.threads().find(threadId).orElseThrow().headEntryId());
  }

  private EntryPath loadPath(UUID headEntryId) {
    return store.transaction(tx -> tx.entries().loadPath(headEntryId));
  }

  /** 恢复路径唯一允许出现的 Tool Gateway 交互是 0 次（非远端）或 1 次 reattach（远端）。 */
  private static final class CountingToolGateway implements ToolGateway {

    private final AtomicInteger startCalls = new AtomicInteger();
    private final AtomicInteger reattachCalls = new AtomicInteger();
    private volatile Function<ReattachExecution, Run> reattachHandler;

    private int startCalls() {
      return startCalls.get();
    }

    private int reattachCalls() {
      return reattachCalls.get();
    }

    private void setReattachHandler(Function<ReattachExecution, Run> handler) {
      this.reattachHandler = handler;
    }

    @Override
    public PreflightResult preflight(ToolInvocationRequest request, ExecutionScope scope) {
      return new ToolGateway.Allow();
    }

    @Override
    public Admission admit(Execution execution) {
      startCalls.incrementAndGet();
      return new ToolGateway.Busy(Duration.ofSeconds(9));
    }

    @Override
    public Run reattach(ReattachExecution execution)
        throws EnvironmentCapabilityUnavailableException {
      reattachCalls.incrementAndGet();
      if (reattachHandler != null) {
        return reattachHandler.apply(execution);
      }
      throw new UnsupportedOperationException();
    }
  }

  private static final class CompletedRun implements ToolGateway.Run {
    private final ToolGateway.Completion completion;
    private final Runnable onExecute;

    CompletedRun(ToolGateway.Completion completion) {
      this(completion, () -> {});
    }

    CompletedRun(ToolGateway.Completion completion, Runnable onExecute) {
      this.completion = completion;
      this.onExecute = onExecute;
    }

    @Override
    public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
      onExecute.run();
      return completion;
    }

    @Override
    public void close() {}
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while awaiting condition", e);
      }
    }
    assertTrue(condition.getAsBoolean());
  }
}
