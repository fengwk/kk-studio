package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * 回归测试：验证 ModelExecution 与 ToolExecution 的业务回写事务均以获取树锁 (lockTree) 为首个操作， 满足 Tree Advisory Lock ->
 * Session -> Thread -> Model/Tool -> Work 的规范锁序。
 */
class ExecutionTreeLockRegressionTest {

  private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(10));
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(5);
  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));

  private final List<ScheduledExecutorService> schedulersToClose = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() {
    for (ScheduledExecutorService scheduler : schedulersToClose) {
      scheduler.shutdownNow();
    }
  }

  /**
   * 测试意图：验证 ModelExecution 在终态回写 (commitTerminal) 事务中，首个操作必须是获取执行树锁 (lockTree)， 随后依次锁定
   * Thread、ModelInvocation 并完成 Work。
   */
  @Test
  void modelExecutionTerminalPathAcquiresTreeLockFirst() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    RecordingStore recordingStore = new RecordingStore(store);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    schedulersToClose.add(scheduler);

    ThreadProcessorTestSupport.OpenTurnBaseline openTurn =
        ThreadProcessorTestSupport.seedOpenInputTurn(store);
    UUID invocationId =
        ThreadProcessorTestSupport.seedModelInvocation(
            store,
            openTurn.threadId(),
            openTurn.turnStartEntryId(),
            openTurn.userEntryId(),
            ModelInvocationStatus.READY,
            ThreadProcessorTestSupport.plainRequest(),
            null,
            null);

    // 请求 MODEL work 并推进为 DISPATCHING 前置状态
    store.transaction(
        tx -> {
          tx.lockThread(openTurn.threadId());
          ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId), NOW);
          tx.updateModelInvocation(model.beginDispatch(NOW));
          return null;
        });

    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, NOW, "token-model-1", Duration.ofSeconds(60)))
            .orElseThrow();

    ModelExecution execution =
        new ModelExecution(
            recordingStore,
            event -> {},
            claim,
            openTurn.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(
                LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, StreamFlushConfig.IMMEDIATE),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            ignored -> {});

    ModelGateway.Handle handle =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {}

          @Override
          public void activate() {}
        };

    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    // markRunning 事务已被记录，首个操作应为 lockTree
    assertFalse(recordingStore.recordedOperations().isEmpty());
    assertEquals("lockTree", recordingStore.recordedOperations().get(0).get(0));

    recordingStore.clearLogs();

    // 触发失败终态
    execution.onFailed(
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "invalid input"));

    ModelInvocation terminalModel =
        store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.FAILED, terminalModel.status());

    // 验证 commitTerminal 事务首个操作为 lockTree
    List<List<String>> txLogs = recordingStore.recordedOperations();
    assertFalse(txLogs.isEmpty(), "must record commitTerminal transaction");
    List<String> terminalTx = txLogs.get(txLogs.size() - 1);
    assertEquals(
        "lockTree", terminalTx.get(0), "first operation in commitTerminal must be lockTree");
    assertTrue(terminalTx.contains("lockThread"), "commitTerminal must lockThread");
    assertTrue(
        terminalTx.contains("lockModelInvocation"), "commitTerminal must lockModelInvocation");
    assertTrue(terminalTx.contains("completeWork"), "commitTerminal must completeWork");
  }

  /** 测试意图：验证 ModelExecution 在流式批次刷盘 (commitBatchFlushLocked) 事务中，首个操作必须是获取执行树锁 (lockTree)。 */
  @Test
  void modelExecutionBatchFlushAcquiresTreeLockFirst() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    RecordingStore recordingStore = new RecordingStore(store);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    schedulersToClose.add(scheduler);

    ThreadProcessorTestSupport.OpenTurnBaseline openTurn =
        ThreadProcessorTestSupport.seedOpenInputTurn(store);
    UUID invocationId =
        ThreadProcessorTestSupport.seedModelInvocation(
            store,
            openTurn.threadId(),
            openTurn.turnStartEntryId(),
            openTurn.userEntryId(),
            ModelInvocationStatus.READY,
            ThreadProcessorTestSupport.plainRequest(),
            null,
            null);

    store.transaction(
        tx -> {
          tx.lockThread(openTurn.threadId());
          ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId), NOW);
          tx.updateModelInvocation(model.beginDispatch(NOW));
          return null;
        });

    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.MODEL, NOW, "token-model-2", Duration.ofSeconds(60)))
            .orElseThrow();

    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 2, 1024 * 1024);
    ModelExecution execution =
        new ModelExecution(
            recordingStore,
            event -> {},
            claim,
            openTurn.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            ignored -> {});

    ModelGateway.Handle handle =
        new ModelGateway.Handle() {
          @Override
          public void cancel() {}

          @Override
          public void activate() {}
        };

    assertEquals(ProcessResult.STARTED, execution.activate(handle));
    recordingStore.clearLogs();

    // 发送两个 delta 达到阈值 (2) 触发 batch flush
    execution.onEvent(new ProviderStreamEvent.TextDelta("hello "));
    execution.onEvent(new ProviderStreamEvent.TextDelta("world"));

    List<List<String>> txLogs = recordingStore.recordedOperations();
    assertFalse(txLogs.isEmpty(), "must record batch flush transaction");
    List<String> flushTx = txLogs.get(0);
    assertEquals("lockTree", flushTx.get(0), "first operation in batch flush must be lockTree");
    assertTrue(flushTx.contains("lockModelInvocation"), "batch flush must lockModelInvocation");
    assertTrue(flushTx.contains("updateModelInvocation"), "batch flush must updateModelInvocation");
  }

  /**
   * 测试意图：验证 ToolExecution 在激活异常导致的终态回写 (commitTerminal) 事务中，首个操作必须是获取执行树锁 (lockTree)， 随后锁定
   * Thread、ToolInvocation 并完成 Work。
   */
  @Test
  void toolExecutionTerminalPathAcquiresTreeLockFirst() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    schedulersToClose.add(fixture.scheduler);

    RecordingStore recordingStore = new RecordingStore(fixture.store);
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toDispatched(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);

    ToolExecution execution =
        new ToolExecution(
            recordingStore,
            fixture.sink,
            claim,
            fixture.baseline.threadId(),
            1,
            fixture.request,
            new ToolProcessorConfig(
                ToolProcessorTestSupport.LEASE_CONFIG,
                () -> ToolProcessorTestSupport.NO_RETRY,
                ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
                ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
            fixture.clock,
            fixture.scheduler,
            Runnable::run,
            ignored -> {});

    ToolGateway.Handle failingActivation =
        new ToolGateway.Handle() {
          @Override
          public void cancel() {}

          @Override
          public void activate() {
            throw new IllegalStateException("gateway activation failed");
          }
        };

    recordingStore.clearLogs();
    assertEquals(ProcessResult.TERMINATED, execution.activate(failingActivation));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());

    List<List<String>> txLogs = recordingStore.recordedOperations();
    assertFalse(txLogs.isEmpty(), "must record transactions");
    for (List<String> txOps : txLogs) {
      assertEquals(
          "lockTree", txOps.get(0), "first operation in mutating transaction must be lockTree");
    }
    List<String> terminalTx = txLogs.get(txLogs.size() - 1);
    assertTrue(terminalTx.contains("lockThread"), "commitTerminal must lockThread");
    assertTrue(terminalTx.contains("lockToolInvocation"), "commitTerminal must lockToolInvocation");
    assertTrue(terminalTx.contains("completeWork"), "commitTerminal must completeWork");
  }

  // --- 记录 Store 装饰器 ---

  static final class RecordingStore implements HarnessStore {
    private final InMemoryHarnessStore delegate;
    private final List<List<String>> recordedOperations = new CopyOnWriteArrayList<>();

    RecordingStore(InMemoryHarnessStore delegate) {
      this.delegate = delegate;
    }

    List<List<String>> recordedOperations() {
      return recordedOperations;
    }

    void clearLogs() {
      recordedOperations.clear();
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
            List<String> ops = new ArrayList<>();
            recordedOperations.add(ops);
            Transaction proxy =
                (Transaction)
                    Proxy.newProxyInstance(
                        Transaction.class.getClassLoader(),
                        new Class<?>[] {Transaction.class},
                        (p, method, args) -> {
                          String name = method.getName();
                          if (name.startsWith("lock")
                              || name.startsWith("request")
                              || name.startsWith("update")
                              || name.startsWith("complete")
                              || name.startsWith("reschedule")) {
                            ops.add(name);
                          }
                          try {
                            return method.invoke(tx, args);
                          } catch (InvocationTargetException e) {
                            throw e.getCause();
                          }
                        });
            return callback.apply(proxy);
          });
    }
  }
}
