package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

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
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID userId = seedUserEntry(turn);
    ModelRequestSpec request = StoreTestSupport.succeededRequest();
    ProviderResponse response = StoreTestSupport.assistantResponse("call-0");
    UUID modelId = seedRunningModelWithToolCall(turn, userId, request, response);
    UUID assistantId =
        store.transaction(tx -> tx.findModelInvocation(modelId).orElseThrow().resultEntryId());
    UUID toolId =
        store.transaction(
            tx -> tx.loadToolInvocationsByAssistantEntryId(assistantId).getFirst().id());
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
    assertTrue(store.transaction(tx -> tx.findModelInvocation(modelId)).isEmpty());
    assertTrue(
        store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantId)).isEmpty());
    assertTrue(findWork(threadTarget).isPresent());

    // 下一个 claim 真正创建下一轮 ModelInvocation（而不是只留下 TURN_END）。
    assertEquals(
        ThreadProcessResult.COMPLETED,
        threadProcessor.process(claim(WorkTargetType.THREAD, "continuation").orElseThrow()));
    UUID continuationEntryId = threadHead(turn.threadId());
    ModelInvocation continuation =
        store
            .transaction(tx -> tx.findModelInvocationByTurn(turn.threadId(), continuationEntryId))
            .orElseThrow();
    assertEquals(ModelInvocationStatus.READY, continuation.status());
    assertNotEquals(modelId, continuation.id());
    assertTrue(findWork(new WorkTarget(WorkTargetType.MODEL, continuation.id())).isPresent());
    assertEquals(0, toolGateway.startCalls(), "recovery must not re-execute the tool");
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
            Duration.ofSeconds(9)),
        Clock.systemUTC(),
        scheduler,
        Runnable::run);
  }

  private ThreadProcessor newThreadProcessor() {
    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Resolved(StoreTestSupport.succeededRequest(), 100_000, 16_384);
    return new ThreadProcessor(
        store,
        resolver,
        new ThreadProcessorConfig(
            new ProcessorLeaseConfig(LEASE, Duration.ofSeconds(5)),
            Duration.ofSeconds(5),
            () -> new CompactionConfig(20_000)),
        Clock.systemUTC(),
        scheduler,
        Runnable::run);
  }

  private UUID seedUserEntry(StoreTestSupport.TurnBaseline turn) {
    return store.transaction(
        tx -> {
          var thread = tx.lockThread(turn.threadId()).orElseThrow();
          UUID id = tx.nextId();
          tx.insertEntry(
              new Entry(
                  id,
                  turn.sessionId(),
                  turn.turnStartEntryId(),
                  StoreTestSupport.userMessagePayload(),
                  T2));
          tx.updateThread(thread.advanceHead(id, T2));
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
              tx.lockThread(turn.threadId()).orElseThrow();
              UUID id = tx.nextId();
              tx.insertModelInvocation(
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
          var thread = tx.lockThread(turn.threadId()).orElseThrow();
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().beginDispatch(T3));
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().markRunning(T3));
          UUID assistantId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  assistantId,
                  turn.sessionId(),
                  requestHeadEntryId,
                  StoreTestSupport.mappedAssistant(request, response),
                  T3));
          tx.updateModelInvocation(
              tx.lockModelInvocation(modelId).orElseThrow().succeed(response, null, null, T3));
          tx.updateModelInvocation(
              tx.lockModelInvocation(modelId).orElseThrow().attachResultEntry(assistantId, T3));
          tx.updateThread(thread.advanceHead(assistantId, T3));
          UUID toolId = tx.nextId();
          tx.insertToolInvocations(
              List.of(
                  StoreTestSupport.toolInvocation(
                      toolId, modelId, assistantId, 0, "call-0", ToolInvocationStatus.READY, T3)));
          ToolInvocation ready =
              tx.lockToolInvocationsByAssistantEntryId(assistantId)
                  .getFirst()
                  .markApprovalNotRequired(T3);
          tx.updateToolInvocations(List.of(ready));
          ToolInvocation dispatching = ready.beginDispatch(T3);
          tx.updateToolInvocations(List.of(dispatching));
          tx.updateToolInvocations(List.of(dispatching.markRunning(T3)));
          return null;
        });
    return modelId;
  }

  private void requestWork(WorkTarget target, UUID ownerThreadId) {
    store.transaction(
        tx -> {
          tx.lockThread(ownerThreadId).orElseThrow();
          tx.requestWork(target, Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(1));
          return null;
        });
  }

  private Optional<ClaimedWork> claim(WorkTargetType type, String token) {
    return store.transaction(
        tx -> tx.claimNextWork(type, Instant.now().truncatedTo(ChronoUnit.MILLIS), token, LEASE));
  }

  private ToolInvocation findTool(UUID toolId) {
    return store.transaction(tx -> tx.findToolInvocation(toolId)).orElseThrow();
  }

  private Optional<Work> findWork(WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target));
  }

  private UUID threadHead(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().headEntryId());
  }

  private EntryPath loadPath(UUID headEntryId) {
    return store.transaction(tx -> tx.loadEntryPath(headEntryId));
  }

  /** 恢复路径唯一允许出现的 Tool Gateway 交互是 0 次。 */
  private static final class CountingToolGateway implements ToolGateway {

    private final AtomicInteger startCalls = new AtomicInteger();

    private int startCalls() {
      return startCalls.get();
    }

    @Override
    public PreflightResult preflight(ToolInvocationRequest request) {
      return new ToolGateway.Allow();
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      startCalls.incrementAndGet();
      return new ToolGateway.RetryLater(Duration.ofSeconds(9));
    }
  }
}
