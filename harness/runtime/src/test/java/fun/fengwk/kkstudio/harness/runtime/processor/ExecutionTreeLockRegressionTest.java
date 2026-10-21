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
 * 回归测试：验证 ModelExecution 与 ToolExecution 的业务回写事务只锁本 Thread 的 Thread/Model 或 Tool/Work，
 * 不再获取执行树锁；同时验证 checkpoint 仍只锁 Model 与 Work。
 */
class ExecutionTreeLockRegressionTest {

  private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(10));
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(5);

  /** 逐事件即时推送、1ms 后 checkpoint 的测试配置。 */
  private static final ModelStreamConfig IMMEDIATE_STREAM =
      new ModelStreamConfig(Duration.ofMillis(1), 1, 1, Duration.ofMillis(1));

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
   * 测试意图：ModelExecution 的 markRunning 与终态回写只按 Thread -> Model -> Work 锁定本 Thread 的事实行， 事务中不出现执行树锁。
   */
  @Test
  void modelExecutionTerminalPathTakesNoTreeLock() {
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

    // 请求 MODEL work（READY 状态由 execution.markRunning 推进）
    store.transaction(
        tx -> {
          tx.lockThread(openTurn.threadId());
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId));
          return null;
        });

    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.MODEL, "token-model-1", Duration.ofSeconds(60)))
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
                LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, IMMEDIATE_STREAM),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            ignored -> {});

    assertTrue(execution.markRunning());

    // markRunning 事务：本 Thread -> Model -> Work，无树锁
    assertFalse(recordingStore.recordedOperations().isEmpty());
    assertEquals(
        List.of(
            "lockThread",
            "lockModelInvocation",
            "lockClaimedWork",
            "updateModelInvocation",
            "updateThread"),
        recordingStore.recordedOperations().get(0));

    recordingStore.clearLogs();

    // 触发失败终态
    execution.finish(
        new ModelGateway.Failed(
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "invalid input")));

    ModelInvocation terminalModel =
        store.transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.FAILED, terminalModel.status());

    // commitTerminal 事务只锁本 Thread 的 Thread/Model/Work 并完成 Work
    List<List<String>> txLogs = recordingStore.recordedOperations();
    assertFalse(txLogs.isEmpty(), "must record commitTerminal transaction");
    List<String> terminalTx = txLogs.get(txLogs.size() - 1);
    assertEquals(
        List.of(
            "lockThread",
            "lockModelInvocation",
            "requestWork",
            "lockClaimedWork",
            "updateModelInvocation",
            "updateThread",
            "completeWork"),
        terminalTx);
    assertTrue(
        txLogs.stream().flatMap(List::stream).noneMatch("lockTree"::equals),
        "single-thread execution state commits must not take the tree lock");
  }

  /** 测试意图：验证 ModelExecution 的 checkpoint 事务不取执行树锁，只按 Model -> Work 顺序锁定自己的行。 */
  @Test
  void modelExecutionCheckpointTakesNoTreeLock() {
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
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, invocationId));
          return null;
        });

    ClaimedWork claim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.MODEL, "token-model-2", Duration.ofSeconds(60)))
            .orElseThrow();

    ModelStreamConfig flushConfig =
        new ModelStreamConfig(Duration.ofMinutes(1), 1024, 1024 * 1024, Duration.ofMinutes(1));
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

    assertTrue(execution.markRunning());
    recordingStore.clearLogs();

    execution.onEvent(new ProviderStreamEvent.TextDelta("hello "));
    execution.onEvent(new ProviderStreamEvent.TextDelta("world"));
    assertTrue(
        recordingStore.recordedOperations().isEmpty(), "deltas alone must not touch the store");

    execution.requestCheckpoint(1);

    List<List<String>> txLogs = recordingStore.recordedOperations();
    assertEquals(1, txLogs.size(), "exactly one checkpoint transaction");
    List<String> checkpointTx = txLogs.get(0);
    assertEquals(
        List.of("lockModelInvocation", "lockClaimedWork", "updateModelInvocation"),
        checkpointTx,
        "checkpoint must lock Model then Work only, with no tree lock");
  }

  /** 测试意图：ToolExecution 激活异常路径的 markRunning 与终态回写只锁本 Thread 的 Thread/Tool/Work， 不取执行树锁。 */
  @Test
  void toolExecutionTerminalPathTakesNoTreeLock() {
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
    assertEquals(
        List.of(
            List.of(
                "lockThread",
                "lockToolInvocation",
                "lockClaimedWork",
                "updateToolInvocations",
                "updateThread"),
            List.of(
                "lockThread",
                "lockToolInvocation",
                "requestWork",
                "lockClaimedWork",
                "updateToolInvocations",
                "updateThread",
                "completeWork")),
        txLogs);
    assertTrue(
        txLogs.stream().flatMap(List::stream).noneMatch("lockTree"::equals),
        "single-thread execution state commits must not take the tree lock");
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
