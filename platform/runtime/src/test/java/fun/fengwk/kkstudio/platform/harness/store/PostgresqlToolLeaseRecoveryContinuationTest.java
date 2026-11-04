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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
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
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 真实 PostgreSQL 上的 Tool 租约失联恢复与模型循环续跑验收：失联工具不再执行，只生成如实的错误 tool_result；ThreadProcessor 应用该 batch 并要求
 * {@code continueModel = true}；下一个 claim 真正创建下一轮 ModelInvocation。
 */
class PostgresqlToolLeaseRecoveryContinuationTest {

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
        toolProcessor.process(claim(WorkTargetType.TOOL, "tool-a").orElseThrow()));

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
        threadProcessor.process(claim(WorkTargetType.THREAD, "batch").orElseThrow()));

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
        threadProcessor.process(claim(WorkTargetType.THREAD, "continuation").orElseThrow()));
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
    assertTrue(findWork(new WorkTarget(WorkTargetType.MODEL, continuation.id())).isPresent());
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
        new ContributorBinding("core", "bash", List.of()),
        EnvironmentSupport.REQUIRED,
        ENV_ID,
        "bash");
  }

  private static ToolGateway.Succeeded succeeded(String toolCallId) {
    return new ToolGateway.Succeeded(
        ToolSuccess.withoutEffects(
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
                      "old-token",
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
                      "old-token",
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
        toolProcessor.process(claim(WorkTargetType.TOOL, "tool-recoverer").orElseThrow()));

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
        threadProcessor.process(claim(WorkTargetType.THREAD, "batch").orElseThrow()));

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
        threadProcessor.process(claim(WorkTargetType.THREAD, "continuation").orElseThrow()));
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
    assertTrue(findWork(new WorkTarget(WorkTargetType.MODEL, continuation.id())).isPresent());
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
            new ProcessorLeaseConfig(LEASE, Duration.ofSeconds(5)),
            () -> policy,
            Duration.ofSeconds(7),
            Duration.ofSeconds(9),
            Duration.ofMinutes(10)),
        Clock.systemUTC(),
        scheduler,
        Runnable::run,
        ExecutionExitTestSupport.confirmer(store, Clock.systemUTC()),
        (sessionId, toolName, result) -> result);
  }

  private ThreadProcessor newThreadProcessor() {
    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Resolved(succeededRequest(), 100_000, 16_384);
    return new ThreadProcessor(
        store,
        builtinRegistry(store, resolver),
        new ThreadProcessorConfig(Duration.ofSeconds(5), () -> new CompactionConfig(20_000)));
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
                          T2));
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
                      .succeed(response, null, null, T3));
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
                          T2));
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
                      .succeed(response, null, null, T3));
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

  private Optional<ClaimedWork> claim(WorkTargetType type, String token) {
    return store.transaction(tx -> tx.work().claimNext(type, token, LEASE));
  }

  private ToolInvocation findTool(UUID toolId) {
    return store.transaction(tx -> tx.tools().find(toolId)).orElseThrow();
  }

  private Optional<Work> findWork(WorkTarget target) {
    return store.transaction(tx -> tx.work().find(target));
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
    public PreflightResult preflight(ToolInvocationRequest request) {
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
