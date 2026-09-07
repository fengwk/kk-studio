package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** PR 1 Java 运行时聚合状态机自动化测试： 验证有界批次、单 drain owner 严格保序、定时器、终态合并单次 UPDATE 与各类异常收敛边界。 */
class ModelExecutionStreamFlushTest {

  private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
  private static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(10));
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(5);
  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));

  private final List<ExecutorService> executorsToClose = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() {
    for (ExecutorService executor : executorsToClose) {
      executor.shutdownNow();
    }
  }

  /**
   * 意图：验证多个 safe delta 在未达到容量阈值与定时器超时前既不写库也不发布； 一次 flush 触发后，仅执行恰好一次 updateModelInvocation，且
   * sequence 与 checkpoint 文本均正确。
   */
  @Test
  void multipleSafeDeltasDoNotPersistOrPublishBeforeThresholdAndFlushInSingleUpdate() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 4, 1024 * 1024);
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("hello "));
    listener.onEvent(new ProviderStreamEvent.TextDelta("world"));

    // 尚未达到 4 个事件容量，且定时器未到期：不写不发
    assertEquals(
        0,
        fixture.store.modelInvocationUpdateCount(),
        "deltas below threshold must not update invocation");
    assertTrue(fixture.sink.deltas().isEmpty(), "deltas below threshold must not be published");

    // 补齐到 4 个事件触发满批 flush
    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("thinking "));
    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("now"));

    // 满批触发一次且仅一次 updateModelInvocation
    assertEquals(
        1,
        fixture.store.modelInvocationUpdateCount(),
        "exactly one updateModelInvocation upon batch flush");
    ModelInvocation committed = fixture.currentModel();
    assertNotNull(committed.streamCheckpoint());
    assertEquals(4, committed.streamCheckpoint().sequence());
    assertEquals("hello world", committed.streamCheckpoint().text());
    assertEquals("thinking now", committed.streamCheckpoint().thinking());

    // 4 个 delta 按 sequence 严格递增发布
    List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
    assertEquals(4, deltas.size());
    for (int i = 0; i < deltas.size(); i++) {
      assertEquals(i + 1, deltas.get(i).sequence());
    }
  }

  /**
   * 意图：验证单 delta 即使 Provider 暂停也必然由 timer 刷出；后续 delta 到达时不重置首事件 deadline（不 debounce）； 定时器的 DB
   * 写入与发布确实由 flushExecutor 执行而非 scheduler；stale generation 触发时判定为 no-op。
   */
  @Test
  void singleDeltaTimerFlushesEventuallyAndSubsequentDeltasDoNotDebounceFirstDeadline()
      throws Exception {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMillis(100), 10, 1024 * 1024);

    AtomicInteger scheduleCount = new AtomicInteger();
    AtomicReference<Runnable> capturedTask = new AtomicReference<>();
    ScheduledExecutorService controlledScheduler =
        createControlledScheduler(scheduleCount, capturedTask);

    AtomicReference<String> dbUpdateThread = new AtomicReference<>();
    AtomicReference<String> publishThread = new AtomicReference<>();
    ExecutorService flushExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "flush-worker-thread"));
    executorsToClose.add(flushExecutor);

    Fixture fixture =
        createFixture(
            flushConfig,
            NO_RETRY,
            controlledScheduler,
            flushExecutor,
            dbUpdateThread,
            publishThread);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("first "));

    // 调度了一次 timer
    assertEquals(1, scheduleCount.get(), "timer must be scheduled for first delta");
    assertNotNull(capturedTask.get(), "timer task must be captured");

    // 首事件 deadline 到期前发送第二个事件：不应重新 schedule（不 debounce 首事件 deadline）
    listener.onEvent(new ProviderStreamEvent.TextDelta("second"));
    assertEquals(1, scheduleCount.get(), "subsequent delta must not debounce or reschedule timer");

    // 尚未触发定时器：0 update，0 publish
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 模拟定时器到期：在 scheduler 线程触发任务（该任务仅往 flushExecutor 投递，不执行 DB/publish）
    Runnable timerAction = capturedTask.get();
    timerAction.run();

    // 等待 flush-worker-thread 执行完毕
    await(() -> fixture.store.modelInvocationUpdateCount() == 1, Duration.ofSeconds(2));

    // 验证 DB 写入与发布确实在 flush-worker-thread 上执行
    assertEquals(
        "flush-worker-thread",
        dbUpdateThread.get(),
        "DB update must execute on flushExecutor thread");
    assertEquals(
        "flush-worker-thread", publishThread.get(), "publish must execute on flushExecutor thread");

    ModelInvocation committed = fixture.currentModel();
    assertNotNull(committed.streamCheckpoint());
    assertEquals(2, committed.streamCheckpoint().sequence());
    assertEquals("first second", committed.streamCheckpoint().text());
    assertEquals(2, fixture.sink.deltas().size());

    // 验证 stale generation：使用过期的代际 0 触发 deliverFlush，必须直接 no-op
    fixture.execution.deliverFlush(0);
    assertEquals(
        1,
        fixture.store.modelInvocationUpdateCount(),
        "stale generation deliverFlush must be no-op");
    assertEquals(2, fixture.sink.deltas().size(), "stale generation must not publish");
  }

  /** 意图：验证事件数达到配置阈值时立即触发 flush。 */
  @Test
  void thresholdByEventsFlushesExactlyAtLimit() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 3, 1024 * 1024);
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("e1"));
    listener.onEvent(new ProviderStreamEvent.TextDelta("e2"));
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 第 3 个事件达到 maxEvents (3)
    listener.onEvent(new ProviderStreamEvent.TextDelta("e3"));
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    assertEquals(3, fixture.sink.deltas().size());
  }

  /** 意图：验证增量 payload 字节数达到配置阈值时立即触发 flush。 */
  @Test
  void thresholdByPayloadBytesFlushesExactlyAtLimit() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 100, 30);
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("1234567890")); // 10 bytes
    listener.onEvent(new ProviderStreamEvent.TextDelta("1234567890")); // 10 bytes (累计 20)
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 第 3 个事件（累计 30 bytes 达到 maxPayloadBytes）
    listener.onEvent(new ProviderStreamEvent.TextDelta("1234567890"));
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    assertEquals(3, fixture.sink.deltas().size());
  }

  /**
   * 意图：验证纯工具批次（ToolCallDelta）仅通过只读 fence 并推进 sequence 发布， 绝不 UPDATE ModelInvocation（0 次
   * ModelInvocation UPDATE）。
   */
  @Test
  void pureToolBatchPublishesWithoutModelInvocationUpdate() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 3, 1024 * 1024);
    Fixture fixture = createFixture(flushConfig, NO_RETRY, requestWithTool());
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(
        new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", "{\"cmd\":\"ls\"}"));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, null, null, "\n"));
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(1, "call_2", "read", "{}"));

    // 纯工具批次达到 3 条触发 flush：0 次 updateModelInvocation
    assertEquals(
        0,
        fixture.store.modelInvocationUpdateCount(),
        "pure tool batch must have 0 ModelInvocation UPDATE");
    assertEquals(3, fixture.sink.deltas().size(), "pure tool batch events must be published");
    assertNull(
        fixture.currentModel().streamCheckpoint(), "pure tool batch must not create checkpoint");
  }

  /** 意图：验证单条事件超过聚合阈值时先收敛前一批，再单独处理超大事件。 */
  @Test
  void oversizedSingleEventFlushesPreBatchThenFlushesOversizedIndependently() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 10, 20);
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("small")); // 5 bytes < 20
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    String oversizedText = "this is an oversized event payload that exceeds twenty bytes";
    listener.onEvent(new ProviderStreamEvent.TextDelta(oversizedText));

    // 预期：前一批 small 先提交并发布（1 次），超大事件单独提交并发布（第 2 次），合计 2 次 UPDATE
    assertEquals(
        2,
        fixture.store.modelInvocationUpdateCount(),
        "pre-batch flush + oversized single flush = 2 updates");
    assertEquals(
        2, fixture.sink.deltas().size(), "both small and oversized delta published in order");
    assertEquals(1, fixture.sink.deltas().get(0).sequence());
    assertEquals(2, fixture.sink.deltas().get(1).sequence());
    assertEquals("small" + oversizedText, fixture.currentModel().streamCheckpoint().text());
  }

  /** 意图：验证 success terminal 吸收未刷内容、只一次 update，并按 sequence 先发布未刷批次再发布 completion gap。 */
  @Test
  void successTerminalAbsorbsUnflushedBatchWithSingleUpdateAndSequencePreserved() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 10, 1024 * 1024);
    Fixture fixture = createFixture(flushConfig, NO_RETRY, requestWithTool());
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("buffered "));
    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("thinking "));

    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 终态到达（带 final 文本补齐 gap）
    ProviderResponse finalResponse =
        response(
            "buffered completion",
            "thinking complete",
            List.of(new ProviderToolCall("call_1", "bash", "{}")));
    listener.onSucceeded(finalResponse);

    // 终态事务只发生一次 updateModelInvocation
    assertEquals(
        1,
        fixture.store.modelInvocationUpdateCount(),
        "success terminal absorbs batch in exactly 1 UPDATE");
    ModelInvocation committed = fixture.currentModel();
    assertEquals(ModelInvocationStatus.SUCCEEDED, committed.status());
    assertEquals("buffered completion", committed.streamCheckpoint().text());
    assertEquals("thinking complete", committed.streamCheckpoint().thinking());

    // 校验发布：未刷的 TextDelta(1), ThinkingDelta(2)，以及 gap deltas (TextGap 3, ThinkingGap 4, ToolGap 5)
    List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
    assertEquals(5, deltas.size());
    for (int i = 0; i < deltas.size(); i++) {
      assertEquals(i + 1, deltas.get(i).sequence());
    }
  }

  /**
   * 意图：验证 failure / retry / UNKNOWN 吸收未刷批次且只 UPDATE 一次， 同时未发布的旧 attempt 批次绝对不作为活动 MODEL_DELTA 发出。
   */
  @Test
  void failureRetryUnknownAbsorbsUnflushedBatchWithSingleUpdateAndSuppressesOldAttemptBatch() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 10, 1024 * 1024);

    // Case 1: TRANSIENT 错误触发 retry
    InvocationRetryPolicy retryPolicy =
        new InvocationRetryPolicy(
            2, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
    Fixture fixture1 = createFixture(flushConfig, retryPolicy);
    fixture1.start();

    fixture1.listener().onEvent(new ProviderStreamEvent.TextDelta("unflushed text"));
    fixture1
        .listener()
        .onFailed(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "temporary network drop"));

    assertEquals(
        1, fixture1.store.modelInvocationUpdateCount(), "retry absorbs batch in exactly 1 update");
    assertTrue(
        fixture1.sink.deltas().isEmpty(),
        "old attempt unflushed deltas must not be published on retry");
    ModelInvocation model1 = fixture1.currentModel();
    assertEquals(ModelInvocationStatus.READY, model1.status());
    assertNull(model1.streamCheckpoint(), "active checkpoint is cleared on retry");
    assertEquals(1, model1.failedAttempts().size());
    assertEquals("unflushed text", model1.failedAttempts().getFirst().text());
    assertEquals(1, model1.failedAttempts().getFirst().sequence());

    // Case 2: 不可重试错误 FAILED
    Fixture fixture2 = createFixture(flushConfig, NO_RETRY);
    fixture2.start();

    fixture2.listener().onEvent(new ProviderStreamEvent.TextDelta("partial failed text"));
    fixture2
        .listener()
        .onFailed(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "invalid input"));

    assertEquals(
        1,
        fixture2.store.modelInvocationUpdateCount(),
        "failure terminal absorbs batch in exactly 1 update");
    assertTrue(
        fixture2.sink.deltas().isEmpty(), "unflushed deltas must not be published on failure");
    ModelInvocation model2 = fixture2.currentModel();
    assertEquals(ModelInvocationStatus.FAILED, model2.status());
    assertNotNull(model2.streamCheckpoint());
    assertEquals("partial failed text", model2.streamCheckpoint().text());

    // Case 3: UNKNOWN
    Fixture fixture3 = createFixture(flushConfig, NO_RETRY);
    fixture3.start();

    fixture3.listener().onEvent(new ProviderStreamEvent.TextDelta("partial unknown text"));
    fixture3
        .listener()
        .onUnknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unknown lease lost"));

    assertEquals(
        1,
        fixture3.store.modelInvocationUpdateCount(),
        "unknown terminal absorbs batch in exactly 1 update");
    assertTrue(
        fixture3.sink.deltas().isEmpty(), "unflushed deltas must not be published on unknown");
    ModelInvocation model3 = fixture3.currentModel();
    assertEquals(ModelInvocationStatus.UNKNOWN, model3.status());
    assertNotNull(model3.streamCheckpoint());
    assertEquals("partial unknown text", model3.streamCheckpoint().text());
  }

  /**
   * 意图：验证单 drain owner 下并发 flush 与 callback / terminal 的提交后发布不会乱序： 用 CountDownLatch 阻塞 flush 的
   * post-commit publish，在此期间并发调用 terminal， 直接证明同一 drain ownership 覆盖 post-commit publish，终态提交绝不可能抢在
   * flush publish 之前完成。
   */
  @Test
  void concurrentFlushAndTerminalStrictlyOrderedByDrainOwner() throws Exception {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 1, 1024 * 1024);

    CountDownLatch sinkReached = new CountDownLatch(1);
    CountDownLatch sinkRelease = new CountDownLatch(1);

    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.sink.blockingHook =
        delta -> {
          if (delta.sequence() == 1) {
            sinkReached.countDown();
            try {
              sinkRelease.await();
            } catch (InterruptedException ignored) {
            }
          }
        };
    fixture.start();

    // 线程 1 发送 safe delta（达到 maxEvents=1 触发 flush，commit 成功后在 publish 阶段阻塞）
    Thread thread1 =
        Thread.ofPlatform()
            .start(() -> fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("hello ")));

    assertTrue(sinkReached.await(5, TimeUnit.SECONDS), "sink must be reached by thread1");

    // 线程 2 在此时发起 terminal 提交
    AtomicBoolean thread2Finished = new AtomicBoolean();
    Thread thread2 =
        Thread.ofPlatform()
            .start(
                () -> {
                  fixture.listener().onSucceeded(response("hello world"));
                  thread2Finished.set(true);
                });

    // 等待一小段让线程 2 尝试获取锁
    Thread.sleep(50);

    // 断言：线程 1 持有 drainLock 正在 publish，线程 2 被完全挡在外面：terminal 绝未提交！
    assertEquals(
        1,
        fixture.store.modelInvocationUpdateCount(),
        "terminal must not commit while drainLock is held for publish");
    assertEquals(ModelInvocationStatus.RUNNING, fixture.currentModel().status());
    assertFalse(thread2Finished.get(), "thread2 must be blocked waiting for drainLock");

    // 放行线程 1 的 publish
    sinkRelease.countDown();
    thread1.join(5000);
    thread2.join(5000);

    // 线程 2 随后获得 drainLock 完成 terminal 提交与发布
    assertEquals(2, fixture.store.modelInvocationUpdateCount());
    assertEquals(ModelInvocationStatus.SUCCEEDED, fixture.currentModel().status());

    // 验证发布顺序严格单调：seq=1 先于 seq=2
    List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
    assertEquals(2, deltas.size());
    assertEquals(1, deltas.get(0).sequence());
    assertEquals(2, deltas.get(1).sequence());
  }

  /** 意图：验证 Claim Loss 竞态下（以合法锁序删除 MODEL work），未提交或事务失败的批次绝对不发布。 */
  @Test
  void claimLossAtFencingSuppressesPublicationAndAbandons() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 10, 1024 * 1024);
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    // 进 batch 1 个事件（未刷）
    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("first"));
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 模拟 claim lost（按合法锁序：先锁 Thread，再删除 MODEL work）
    fixture
        .store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(fixture.baseline.threadId()).orElseThrow();
              tx.deleteWork(new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
              return null;
            });

    // 再次发送事件触发 fence
    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("second"));

    // 校验：fence 失败，绝不发布任何未提交 delta，execution 安全收敛为 abandoned
    assertTrue(fixture.sink.deltas().isEmpty(), "claim loss must not publish uncommitted delta");
    assertTrue(fixture.execution.abandoned(), "execution must be abandoned on claim loss");
  }

  /**
   * 意图：验证 executor / scheduler rejection 与 gate buffer 边界安全收敛： 1. scheduler rejection 安全收敛
   * abandon，且不在持有 monitor 状态下 cancel handle； 2. flushExecutor rejection 安全收敛 abandon； 3. gate 打开前
   * EVENT 数量有界（超限背压拒绝），但 terminal 信号不能因达到 maxEvents 而被错误拒绝。
   */
  /**
   * 意图：验证批次定时器遭遇 scheduler 拒绝时，execution 安全收敛为 abandoned 且 handle 被取消， cancel 绝不在持 monitor 状态下触发死锁。
   */
  @Test
  void batchTimerSchedulerRejectionConvergesToAbandonedWithoutHoldingMonitor() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 10, 1024 * 1024);
    ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(delegate);

    ScheduledExecutorService rejectingBatchScheduler =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if ("schedule".equals(method.getName())
                      && args != null
                      && args.length == 3
                      && args[1] instanceof Long delay
                      && delay.longValue() == flushConfig.maxDelay().toMillis()) {
                    throw new RejectedExecutionException("batch timer queue full");
                  }
                  return method.invoke(delegate, args);
                });

    Fixture fixture = createFixture(flushConfig, NO_RETRY, rejectingBatchScheduler, Runnable::run);
    fixture.start();

    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("trigger batch schedule"));
    assertTrue(fixture.execution.abandoned(), "scheduler rejection must converge to abandoned");
    assertTrue(fixture.handle.isCancelled(), "handle must be cancelled upon scheduler rejection");
  }

  /** 意图：验证批次定时器执行时如果 flushExecutor 拒绝投递，execution 安全收敛为 abandoned 且 handle 被取消。 */
  @Test
  void batchTimerFlushExecutorRejectionConvergesToAbandoned() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMillis(100), 10, 1024 * 1024);
    Executor rejectingFlushExecutor =
        task -> {
          throw new RejectedExecutionException("flushExecutor queue is full");
        };
    AtomicReference<Runnable> capturedTask = new AtomicReference<>();
    ScheduledExecutorService controlledScheduler =
        createControlledScheduler(new AtomicInteger(), capturedTask);

    Fixture fixture =
        createFixture(flushConfig, NO_RETRY, controlledScheduler, rejectingFlushExecutor);
    fixture.start();

    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("start batch"));
    assertNotNull(capturedTask.get(), "timer task must be captured");

    // 模拟 scheduler 触发任务：调用 flushExecutor.execute 抛出 rejection
    capturedTask.get().run();
    assertTrue(fixture.execution.abandoned(), "flushExecutor rejection must converge to abandoned");
    assertTrue(
        fixture.handle.isCancelled(), "handle must be cancelled upon flushExecutor rejection");
  }

  /** 意图：验证 gate buffer 达到 maxEvents 容积时，合法 terminal 信号绝不被丢弃或误拒， 并在后续 activate 打开门控后成功落地终态。 */
  @Test
  void gateBufferTerminalAtCapacityIsAcceptedAndProcessedOnActivation() {
    StreamFlushConfig flushConfig = new StreamFlushConfig(Duration.ofMinutes(1), 2, 1024 * 1024);
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);

    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);
    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            e -> {});

    // 填满 maxEvents (2 个事件)
    execution.onEvent(new ProviderStreamEvent.TextDelta("e1"));
    execution.onEvent(new ProviderStreamEvent.TextDelta("e2"));

    // 投递 terminal 信号：不能仅因 pending.size() >= maxEvents 而被拒绝！
    execution.onSucceeded(response("e1e2 completed"));

    // 推进 modelInvocation 到 DISPATCHING 模拟真实流程
    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    store.resetUpdateCount();
    FakeHandle handle = new FakeHandle();
    // 执行 activate：打开 gate 并成功落地 terminal
    ProcessResult result = execution.activate(handle);
    assertEquals(
        ProcessResult.TERMINATED,
        result,
        "terminal at capacity must be processed successfully upon activate");
    ModelInvocation model =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    // activate 期间共 3 次 update：markRunning(1) + 重放满批2个事件flush(1) + terminal终态(1)
    assertEquals(
        3, store.modelInvocationUpdateCount(), "markRunning + batch flush + terminal = 3 updates");
  }

  // ---------------------------------------------------------------------------------------------
  // Test Helpers & Fixtures
  // ---------------------------------------------------------------------------------------------

  private void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("condition not met within timeout " + timeout);
      }
      Thread.sleep(10);
    }
  }

  private ScheduledExecutorService createControlledScheduler(
      AtomicInteger scheduleCount, AtomicReference<Runnable> capturedTask) {
    ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(delegate);

    return (ScheduledExecutorService)
        Proxy.newProxyInstance(
            ScheduledExecutorService.class.getClassLoader(),
            new Class<?>[] {ScheduledExecutorService.class},
            (proxy, method, args) -> {
              if ("schedule".equals(method.getName())
                  && args != null
                  && args.length == 3
                  && args[0] instanceof Runnable task) {
                scheduleCount.incrementAndGet();
                capturedTask.set(task);
                ScheduledFuture<?> fakeFuture =
                    (ScheduledFuture<?>)
                        Proxy.newProxyInstance(
                            ScheduledFuture.class.getClassLoader(),
                            new Class<?>[] {ScheduledFuture.class},
                            (p, m, a) -> {
                              if ("cancel".equals(m.getName())) {
                                return true;
                              }
                              return null;
                            });
                return fakeFuture;
              }
              return method.invoke(delegate, args);
            });
  }

  private Fixture createFixture(StreamFlushConfig flushConfig, InvocationRetryPolicy retryPolicy) {
    return createFixture(flushConfig, retryPolicy, requestSpec());
  }

  private Fixture createFixture(
      StreamFlushConfig flushConfig,
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec) {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    return createFixture(
        flushConfig, retryPolicy, scheduler, Runnable::run, requestSpec, null, null);
  }

  private Fixture createFixture(
      StreamFlushConfig flushConfig,
      InvocationRetryPolicy retryPolicy,
      ScheduledExecutorService scheduler,
      Executor flushExecutor) {
    return createFixture(
        flushConfig, retryPolicy, scheduler, flushExecutor, requestSpec(), null, null);
  }

  private Fixture createFixture(
      StreamFlushConfig flushConfig,
      InvocationRetryPolicy retryPolicy,
      ScheduledExecutorService scheduler,
      Executor flushExecutor,
      AtomicReference<String> dbThreadRecord,
      AtomicReference<String> publishThreadRecord) {
    return createFixture(
        flushConfig,
        retryPolicy,
        scheduler,
        flushExecutor,
        requestSpec(),
        dbThreadRecord,
        publishThreadRecord);
  }

  private Fixture createFixture(
      StreamFlushConfig flushConfig,
      InvocationRetryPolicy retryPolicy,
      ScheduledExecutorService scheduler,
      Executor flushExecutor,
      ModelRequestSpec requestSpec,
      AtomicReference<String> dbThreadRecord,
      AtomicReference<String> publishThreadRecord) {
    CountingStore store = new CountingStore(new InMemoryHarnessStore(), dbThreadRecord);
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec, NOW);
    FakeGateway gateway = new FakeGateway();
    RecordingSink sink = new RecordingSink(publishThreadRecord);

    ModelProcessor processor =
        new ModelProcessor(
            store,
            gateway,
            sink,
            new ModelProcessorConfig(LEASE_CONFIG, () -> retryPolicy, FALLBACK_DELAY, flushConfig),
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            flushExecutor);

    return new Fixture(store, baseline, invocationId, gateway, sink, processor, flushConfig);
  }

  private static final class Fixture {
    final CountingStore store;
    final Baseline baseline;
    final UUID invocationId;
    final FakeGateway gateway;
    final RecordingSink sink;
    final ModelProcessor processor;
    final StreamFlushConfig flushConfig;
    final FakeHandle handle = new FakeHandle();
    ModelExecution execution;

    Fixture(
        CountingStore store,
        Baseline baseline,
        UUID invocationId,
        FakeGateway gateway,
        RecordingSink sink,
        ModelProcessor processor,
        StreamFlushConfig flushConfig) {
      this.store = store;
      this.baseline = baseline;
      this.invocationId = invocationId;
      this.gateway = gateway;
      this.sink = sink;
      this.processor = processor;
      this.flushConfig = flushConfig;
    }

    void start() {
      gateway.queue(new ModelGateway.Started(handle));
      ClaimedWork claim = claim(store.delegate(), invocationId, NOW);
      assertEquals(ProcessResult.STARTED, processor.process(claim));
      this.execution = (ModelExecution) gateway.listener(invocationId);
      // 排除 seed 与 markRunning 的初始更新，启动后重置计数器
      store.resetUpdateCount();
    }

    ModelGateway.Listener listener() {
      return execution != null ? execution : gateway.listener(invocationId);
    }

    ModelInvocation currentModel() {
      return store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    }
  }

  private static ProviderResponse response(String text) {
    return response(text, GenerationStopReason.COMPLETE);
  }

  private static ProviderResponse response(String text, GenerationStopReason stopReason) {
    return new ProviderResponse(
        text, null, List.of(), stopReason, usage(), cost(), "req-1", null, null);
  }

  private static ProviderResponse response(
      String text, String thinking, List<ProviderToolCall> toolCalls) {
    return new ProviderResponse(
        text,
        thinking,
        toolCalls,
        GenerationStopReason.COMPLETE,
        usage(),
        cost(),
        "req-1",
        null,
        null);
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ModelRequestSpec requestSpec() {
    return spec(List.of());
  }

  private static ModelRequestSpec requestWithTool() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "bash",
            "1.0",
            "run bash commands",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(
                new AgentToolId("test.bash"), descriptor, ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "bash", List.of()),
            false,
            null);
    return spec(List.of(binding));
  }

  private static ModelRequestSpec spec(List<ToolBinding> bindings) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        bindings,
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
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

  private static Baseline seedBaseline(HarnessStore store, Instant now) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, now));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
          UUID turnStartEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), threadId, 100_000, 16_384, null),
                  now.plusMillis(1)));
          UUID userId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  userId,
                  sessionId,
                  turnStartEntryId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("hi"))),
                      null,
                      null),
                  now.plusMillis(2)));
          tx.insertThread(
              ThreadProcessorTestSupport.threadState(threadId, sessionId, turnStartEntryId, now));
          return new Baseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  private static UUID seedInvocation(
      HarnessStore store, Baseline baseline, ModelRequestSpec requestSpec, Instant now) {
    return store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          UUID id = tx.nextId();
          tx.insertModelInvocation(
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
                  now));
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), now);
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id), now);
          return id;
        });
  }

  private static ClaimedWork claim(HarnessStore store, UUID invocationId, Instant now) {
    return store
        .transaction(
            tx ->
                tx.claimNextWork(
                    WorkTargetType.MODEL, now, "token-" + invocationId, now.plusSeconds(60)))
        .orElseThrow();
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings(null, "agent", new ModelSelection("provider", "model", "v1"));
  }

  private record Baseline(UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID threadId) {}

  private static final class FakeHandle implements ModelGateway.Handle {
    private final AtomicBoolean activated = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void activate() {
      activated.set(true);
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    boolean isCancelled() {
      return cancelled.get();
    }
  }

  private static final class FakeGateway implements ModelGateway {
    final LinkedList<Object> results = new LinkedList<>();
    final ConcurrentHashMap<UUID, Listener> listeners = new ConcurrentHashMap<>();

    void queue(Object result) {
      results.add(result);
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      listeners.put(execution.invocationId(), listener);
      Object next = results.isEmpty() ? null : results.removeFirst();
      if (next instanceof StartResult startResult) {
        return startResult;
      }
      throw new IllegalStateException("no queued start result");
    }

    Listener listener(UUID invocationId) {
      return listeners.get(invocationId);
    }
  }

  private static final class RecordingSink implements RealtimeEventSink {
    private final List<RealtimeEvent.ModelDelta> deltas = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> threadRecord;
    volatile Consumer<RealtimeEvent.ModelDelta> blockingHook;

    RecordingSink() {
      this(null);
    }

    RecordingSink(AtomicReference<String> threadRecord) {
      this.threadRecord = threadRecord;
    }

    @Override
    public void append(RealtimeEvent event) {
      if (threadRecord != null) {
        threadRecord.set(Thread.currentThread().getName());
      }
      if (event instanceof RealtimeEvent.ModelDelta delta) {
        if (blockingHook != null) {
          blockingHook.accept(delta);
        }
        deltas.add(delta);
      }
    }

    List<RealtimeEvent.ModelDelta> deltas() {
      return List.copyOf(deltas);
    }
  }

  /** 测试专用 Store 代理：拦截 updateModelInvocation 并进行精确调用计数，同时解包异常保证 delegate 原样抛出。 */
  static final class CountingStore implements HarnessStore {
    private final InMemoryHarnessStore delegate;
    private final AtomicInteger modelInvocationUpdateCount = new AtomicInteger();
    private final AtomicReference<String> threadRecord;

    CountingStore(InMemoryHarnessStore delegate) {
      this(delegate, null);
    }

    CountingStore(InMemoryHarnessStore delegate, AtomicReference<String> threadRecord) {
      this.delegate = Objects.requireNonNull(delegate);
      this.threadRecord = threadRecord;
    }

    InMemoryHarnessStore delegate() {
      return delegate;
    }

    int modelInvocationUpdateCount() {
      return modelInvocationUpdateCount.get();
    }

    void resetUpdateCount() {
      modelInvocationUpdateCount.set(0);
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      return delegate.transaction(
          tx -> {
            Transaction proxyTx =
                (Transaction)
                    Proxy.newProxyInstance(
                        Transaction.class.getClassLoader(),
                        new Class<?>[] {Transaction.class},
                        (proxy, method, args) -> {
                          if ("updateModelInvocation".equals(method.getName())) {
                            if (threadRecord != null) {
                              threadRecord.set(Thread.currentThread().getName());
                            }
                            modelInvocationUpdateCount.incrementAndGet();
                          }
                          try {
                            return method.invoke(tx, args);
                          } catch (InvocationTargetException ite) {
                            Throwable cause = ite.getCause();
                            if (cause instanceof RuntimeException re) {
                              throw re;
                            }
                            if (cause instanceof Error err) {
                              throw err;
                            }
                            throw ite;
                          }
                        });
            return callback.apply(proxyTx);
          });
    }
  }
}
