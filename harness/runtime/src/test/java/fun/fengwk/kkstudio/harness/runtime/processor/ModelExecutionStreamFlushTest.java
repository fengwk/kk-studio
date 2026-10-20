package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
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
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
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
import java.util.function.BiConsumer;
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

  private static final long CHECKPOINT_TIMER_MILLIS = Duration.ofMinutes(10).toMillis();

  private final List<ExecutorService> executorsToClose = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() {
    for (ExecutorService executor : executorsToClose) {
      executor.shutdownNow();
    }
  }

  /**
   * 意图：验证推送先于落库：delta 在批次上界到达时立即发布（零事务、零 UPDATE）；checkpoint 随后单独落库一次， 提交成功之后才发布 checkpoint 事件。
   */
  @Test
  void deltasArePushedBeforeAnyPersistenceAndCheckpointEventFollowsCommit() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 4, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("hello "));
    listener.onEvent(new ProviderStreamEvent.TextDelta("world"));
    assertTrue(fixture.sink.deltas().isEmpty(), "deltas below the batch bound wait for the window");

    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("thinking "));
    listener.onEvent(new ProviderStreamEvent.ThinkingDelta("now"));

    // 满批立即推送，整个过程没有碰数据库
    assertEquals(0, fixture.store.transactionCount(), "push must not open a transaction");
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertNull(fixture.currentModel().streamCheckpoint());
    List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
    assertEquals(4, deltas.size());
    assertEquals(1, fixture.sink.appendAllCalls(), "one batch is one appendAll");
    for (int i = 0; i < deltas.size(); i++) {
      assertEquals(i + 1, deltas.get(i).sequence());
    }
    assertTrue(fixture.sink.checkpoints().isEmpty());

    // checkpoint 独立落库一次：单事务单 UPDATE，提交后才发布事件
    fixture.execution.deliverCheckpoint(0, false);
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    ModelInvocation committed = fixture.currentModel();
    assertEquals(4, committed.streamCheckpoint().sequence());
    assertEquals("hello world", committed.streamCheckpoint().text());
    assertEquals("thinking now", committed.streamCheckpoint().thinking());
    assertEquals(List.of(4L), fixture.sink.checkpoints());
    assertTrue(
        fixture.sink.events().get(fixture.sink.events().size() - 1)
            instanceof RealtimeEvent.ModelCheckpoint,
        "checkpoint event is published after the deltas and after the commit");
  }


  /**
   * 意图：量化验证容量足够时，1/100/1000 条 delta 在同一窗口内合并为一次批量发布且全程零数据库开销； checkpoint 单独落库时仍是单事务、单行锁、单 UPDATE。
   */
  @Test
  void oneHundredAndThousandDeltasWithinOneWindowArePushedAsOneBatchWithoutDatabase() {
    for (int eventCount : List.of(1, 100, 1000)) {
      AtomicInteger scheduleCount = new AtomicInteger();
      AtomicReference<Runnable> capturedTask = new AtomicReference<>();
      ScheduledExecutorService scheduler = createControlledScheduler(scheduleCount, capturedTask);
      ModelStreamConfig flushConfig =
          new ModelStreamConfig(
              Duration.ofMinutes(1),
              eventCount + 1,
              Math.max(1024, eventCount + 1),
              Duration.ofMinutes(10));
      Fixture fixture = createFixture(flushConfig, NO_RETRY, scheduler, Runnable::run);
      fixture.start();

      for (int i = 0; i < eventCount; i++) {
        fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("x"));
      }
      assertEquals(1, scheduleCount.get(), "exactly one push timer per window");
      assertEquals(0, fixture.store.transactionCount());
      assertTrue(fixture.sink.deltas().isEmpty());

      capturedTask.get().run();
      assertEquals(eventCount, fixture.sink.deltas().size());
      assertEquals(1, fixture.sink.appendAllCalls(), "one window is one appendAll");
      assertEquals(0, fixture.sink.appendCalls());
      assertEquals(0, fixture.store.transactionCount(), "pushing never opens a transaction");
      List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
      for (int i = 0; i < deltas.size(); i++) {
        assertEquals(i + 1, deltas.get(i).sequence());
      }

      fixture.execution.deliverCheckpoint(0, false);
      assertEquals(1, fixture.store.modelInvocationUpdateCount());
      assertEquals(1, fixture.store.transactionCount());
      assertEquals(1, fixture.store.modelInvocationLockCount());
      assertEquals(1, fixture.store.claimedWorkLockCount());
      assertEquals(eventCount, fixture.currentModel().streamCheckpoint().text().length());
      fixture.processor.close();
    }
  }


  /**
   * 意图：验证单 delta 即使 Provider 暂停也必然由推送定时器发出；后续 delta 不重置首事件 deadline（不 debounce）； 推送与追帧 checkpoint 都由
   * flushExecutor 执行而非 scheduler；过期代际触发为 no-op。
   */
  @Test
  void singleDeltaTimerPushesEventuallyAndSubsequentDeltasDoNotDebounceFirstDeadline()
      throws Exception {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMillis(100), 10, 1024 * 1024, Duration.ofMinutes(10));

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
    assertEquals(1, scheduleCount.get(), "push timer must be scheduled for the first delta");
    assertNotNull(capturedTask.get());

    listener.onEvent(new ProviderStreamEvent.TextDelta("second"));
    assertEquals(1, scheduleCount.get(), "subsequent delta must not debounce or reschedule timer");
    assertTrue(fixture.sink.deltas().isEmpty());

    // scheduler 线程只投递，推送在 flushExecutor 上完成
    capturedTask.get().run();
    await(() -> fixture.sink.deltas().size() == 2, Duration.ofSeconds(2));
    assertEquals("flush-worker-thread", publishThread.get());
    assertEquals(0, fixture.store.modelInvocationUpdateCount(), "push never persists");

    // 追帧请求：checkpoint 同样在 flushExecutor 上落库
    fixture.execution.requestCheckpoint();
    await(() -> fixture.store.modelInvocationUpdateCount() == 1, Duration.ofSeconds(2));
    assertEquals("flush-worker-thread", dbUpdateThread.get());
    assertEquals("first second", fixture.currentModel().streamCheckpoint().text());
    assertEquals(2, fixture.currentModel().streamCheckpoint().sequence());

    // 过期代际的推送定时器必须直接 no-op
    fixture.execution.deliverPush(0);
    assertEquals(2, fixture.sink.deltas().size(), "stale generation must not publish");
  }


  /** 意图：验证事件数达到配置阈值时立即推送。 */
  @Test
  void thresholdByEventsPushesExactlyAtLimit() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 3, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("e1"));
    listener.onEvent(new ProviderStreamEvent.TextDelta("e2"));
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 第 3 个事件达到 maxBatchEvents (3)：立即推送，仍不落库
    listener.onEvent(new ProviderStreamEvent.TextDelta("e3"));
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertEquals(3, fixture.sink.deltas().size());
  }

  /** 意图：验证增量 payload 字节数达到配置阈值时立即推送。 */
  @Test
  void thresholdByPayloadBytesPushesExactlyAtLimit() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 100, 30, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("1234567890")); // 10 bytes
    listener.onEvent(new ProviderStreamEvent.TextDelta("1234567890")); // 10 bytes (累计 20)
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    // 第 3 个事件（累计 30 bytes 达到 maxPayloadBytes）
    listener.onEvent(new ProviderStreamEvent.TextDelta("1234567890"));
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertEquals(3, fixture.sink.deltas().size());
  }

  /**
   * 意图：验证纯工具批次（ToolCallDelta）仅通过只读 fence 并推进 sequence 发布， 绝不 UPDATE ModelInvocation（0 次
   * ModelInvocation UPDATE）。
   */
  @Test
  void pureToolBatchPublishesWithoutModelInvocationUpdate() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 3, 1024 * 1024, Duration.ofMinutes(10));
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

    // 追帧请求在没有任何 safe delta 时不写库，也没有可答复的 checkpoint 事件
    fixture.execution.deliverCheckpoint(0, true);
    assertEquals(0, fixture.store.transactionCount());
    assertTrue(fixture.sink.checkpoints().isEmpty());
  }

  /** 意图：验证单条事件超过字节上界时立即与前一批一起推送，顺序不变。 */
  @Test
  void oversizedSingleEventPushesTogetherWithPendingBatchInOrder() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 20, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("small")); // 5 bytes < 20
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertTrue(fixture.sink.deltas().isEmpty());

    String oversizedText = "this is an oversized event payload that exceeds twenty bytes";
    listener.onEvent(new ProviderStreamEvent.TextDelta(oversizedText));

    // 超大事件触达字节上界：连同前一批按序一次推送，不落库
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertEquals(
        2, fixture.sink.deltas().size(), "both small and oversized delta published in order");
    assertEquals(1, fixture.sink.deltas().get(0).sequence());
    assertEquals(2, fixture.sink.deltas().get(1).sequence());
  }

  /** 意图：验证 success terminal 吸收未刷内容、只一次 update，并按 sequence 先发布未刷批次再发布 completion gap。 */
  @Test
  void successTerminalAbsorbsUnflushedBatchWithSingleUpdateAndSequencePreserved() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
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
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));

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
  void concurrentPushAndTerminalStrictlyOrderedByDrainOwner() throws Exception {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 1, 1024 * 1024, Duration.ofMinutes(10));

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

    // 线程 1 发送 safe delta（达到 maxBatchEvents=1 立即推送，在 publish 阶段阻塞）
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
        0,
        fixture.store.modelInvocationUpdateCount(),
        "terminal must not commit while drainLock is held for publish");
    assertEquals(ModelInvocationStatus.RUNNING, fixture.currentModel().status());
    assertFalse(thread2Finished.get(), "thread2 must be blocked waiting for drainLock");

    // 放行线程 1 的 publish
    sinkRelease.countDown();
    thread1.join(5000);
    thread2.join(5000);

    // 线程 2 随后获得 drainLock 完成 terminal 提交与发布
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    assertEquals(ModelInvocationStatus.SUCCEEDED, fixture.currentModel().status());

    // 验证发布顺序严格单调：seq=1 先于 seq=2
    List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
    assertEquals(2, deltas.size());
    assertEquals(1, deltas.get(0).sequence());
    assertEquals(2, deltas.get(1).sequence());
  }

  /**
   * 意图：验证 Claim Loss 竞态下（以合法锁序删除 MODEL work），检测发生在 checkpoint 提交围栏： 围栏失败即 abandon，不发布 checkpoint 事件，尚未推送的缓冲被丢弃，此后不再发布。
   */
  @Test
  void claimLossAtCheckpointFenceAbandonsAndStopsPublishing() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("first"));
    assertEquals(0, fixture.store.transactionCount(), "buffered delta must not open a transaction");

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

    fixture.execution.deliverCheckpoint(0, true);

    assertTrue(fixture.execution.abandoned(), "execution must be abandoned on claim loss");
    assertTrue(fixture.sink.checkpoints().isEmpty(), "no checkpoint event for a failed fence");
    assertEquals(0, fixture.store.modelInvocationUpdateCount());

    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("second"));
    assertTrue(fixture.sink.deltas().isEmpty(), "a lost execution must not publish anything");
  }

  /**
   * 意图：验证 lease 丢失（Stop / 另一实例 recovery 后 MODEL Work 被删除）发生在 checkpoint 与 terminal 两个提交围栏之前时， 缓冲 delta 一律不发布、不写库，durable
   * 行保持 RUNNING 供 lease recovery 收敛。
   */
  @Test
  void leaseLossBeforeCheckpointAndTerminalNeverPublishesOrWrites() {
    assertLeaseLossBoundary("checkpoint", fixture -> fixture.execution.deliverCheckpoint(0, true));
    assertLeaseLossBoundary("terminal", fixture -> fixture.listener().onSucceeded(response("first")));
  }

  /** 意图：验证 attempt 已被另一 attempt 接管（durable RUNNING attempt 前进）时，checkpoint 与 terminal 围栏都因 attempt 不匹配而拒绝提交。 */
  @Test
  void attemptChangeBeforeCheckpointAndTerminalNeverPublishesOrWrites() {
    assertAttemptChangeBoundary(
        "checkpoint", fixture -> fixture.execution.deliverCheckpoint(0, true));
    assertAttemptChangeBoundary(
        "terminal", fixture -> fixture.listener().onSucceeded(response("first")));
  }

  /** 意图：验证本地取消（Stop / cancel 到 abandon）后，任何缓冲 delta 都绝不发布、绝不写库：围栏与心跳共享同一 abandon 收敛路径。 */
  @Test
  void cancellationBeforeFlushDiscardsBufferedDeltasWithoutWrites() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    fixture.listener().onEvent(delta("first"));
    assertEquals(0, fixture.store.transactionCount(), "buffered delta must not touch the store");

    fixture.processor.cancel(fixture.invocationId);
    fixture.store.resetUpdateCount();

    // 取消后到达的容量边界与后续 delta 都必须 no-op
    fixture.listener().onEvent(delta("second"));
    fixture.listener().onSucceeded(response("firstsecond"));

    assertEquals(0, fixture.store.transactionCount(), "cancelled execution must not write");
    assertEquals(0, fixture.store.modelInvocationUpdateCount());
    assertEquals(0, fixture.sink.appendAllCalls());
    assertTrue(fixture.sink.deltas().isEmpty(), "cancelled execution must not publish");
    assertEquals(ModelInvocationStatus.RUNNING, fixture.currentModel().status());
  }

  /**
   * 意图：验证 terminal 补齐 gap 时产生的大量 delta 严格按 {@link ModelStreamConfig} 上界分块批量派发： 每个分块恰好一次 {@code
   * appendAll}，全部 sequence 连续且保序，既不是 N 次单条发布，也不是一次无界批量调用。
   */
  @Test
  void terminalGapDeltasAreDispatchedInBoundedBatchesPreservingOrder() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY, requestWithTool());
    fixture.start();

    // 流中不产生任何 delta，terminal 一次补齐 text gap + 5 个 tool gap = 6 条
    List<ProviderToolCall> toolCalls =
        List.of(
            new ProviderToolCall("call_1", "bash", "{}"),
            new ProviderToolCall("call_2", "bash", "{}"),
            new ProviderToolCall("call_3", "bash", "{}"),
            new ProviderToolCall("call_4", "bash", "{}"),
            new ProviderToolCall("call_5", "bash", "{}"));
    fixture.listener().onSucceeded(response("answer", toolCalls, GenerationStopReason.COMPLETE));

    assertEquals(ModelInvocationStatus.SUCCEEDED, fixture.currentModel().status());
    assertEquals(6, fixture.sink.deltas().size(), "terminal must publish every gap delta");
    assertEquals(
        List.of(2, 2, 2),
        fixture.sink.batchSizes(),
        "gap deltas must be dispatched in bounded chunks of maxEvents");
    assertEquals(3, fixture.sink.appendAllCalls());
    assertEquals(0, fixture.sink.appendCalls(), "committed deltas must not use per-event append");
    for (int i = 0; i < 6; i++) {
      assertEquals(i + 1, fixture.sink.deltas().get(i).sequence());
    }
  }

  /**
   * 意图：验证批量发布的分块失败隔离语义：单个分块抛错只丢弃该分块及其之后的投影，已成功投递的前缀保持投递， 且 durable 终态与调用方返回值完全不受影响（best-effort）。
   */
  @Test
  void publishChunkFailureIsIsolatedAndDoesNotChangeTerminal() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    // 第 2 个分块（sequence 3,4）失败；第 1 个分块（sequence 1,2）必须先成功投递
    fixture.sink.failAppendAllOnCall.set(2);
    for (int i = 0; i < 4; i++) {
      fixture.listener().onEvent(delta("d" + i));
    }

    assertEquals(ModelInvocationStatus.RUNNING, fixture.currentModel().status());
    assertEquals(2, fixture.sink.appendAllCalls());
    assertEquals(List.of(2, 2), fixture.sink.batchSizes());
    List<RealtimeEvent.ModelDelta> deltas = fixture.sink.deltas();
    assertEquals(2, deltas.size(), "only the successful chunk prefix is delivered");
    assertEquals(1, deltas.get(0).sequence());
    assertEquals(2, deltas.get(1).sequence());

    // durable checkpoint 不受投影失败影响
    fixture.execution.deliverCheckpoint(0, true);
    assertEquals(4, fixture.currentModel().streamCheckpoint().sequence());

    // 后续 terminal 仍必须成功收敛为 durable SUCCEEDED
    fixture.listener().onSucceeded(response("d0d1d2d3"));
    assertEquals(ModelInvocationStatus.SUCCEEDED, fixture.currentModel().status());
    assertEquals("d0d1d2d3", fixture.currentModel().result().text());
  }

  /** lease 丢失边界断言：缓冲 1 条 delta 后删除 MODEL Work，再触发指定边界，断言零发布、零写入且 durable 保持 RUNNING。 */
  private void assertLeaseLossBoundary(String boundary, Consumer<Fixture> trigger) {
    Fixture fixture = boundaryFixture();
    fixture
        .store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(fixture.baseline.threadId()).orElseThrow();
              tx.deleteWork(new WorkTarget(WorkTargetType.MODEL, fixture.invocationId));
              return null;
            });
    fixture.store.resetUpdateCount();

    trigger.accept(fixture);

    assertFenceRejectedWithoutWrites(fixture, "lease loss before " + boundary);
  }

  /** attempt 不匹配边界断言：缓冲 1 条 delta 后把 durable attempt 推进到 2，再触发边界，断言零发布、零写入。 */
  private void assertAttemptChangeBoundary(String boundary, Consumer<Fixture> trigger) {
    Fixture fixture = boundaryFixture();
    advanceToSecondAttempt(fixture);
    fixture.store.resetUpdateCount();

    trigger.accept(fixture);

    assertFenceRejectedWithoutWrites(fixture, "attempt change before " + boundary);
  }

  private void assertFenceRejectedWithoutWrites(Fixture fixture, String scenario) {
    assertEquals(0, fixture.store.modelInvocationUpdateCount(), scenario + ": must not UPDATE");
    assertEquals(0, fixture.sink.appendAllCalls(), scenario + ": must not dispatch appendAll");
    assertEquals(0, fixture.sink.appendCalls(), scenario + ": must not dispatch append");
    assertTrue(fixture.sink.events().isEmpty(), scenario + ": must not publish anything");
    assertTrue(fixture.execution.abandoned(), scenario + ": execution must be abandoned");
    assertEquals(
        ModelInvocationStatus.RUNNING,
        fixture.currentModel().status(),
        scenario + ": durable row must stay RUNNING for lease recovery");
    assertTrue(fixture.handle.isCancelled(), scenario + ": handle must be cancelled");
  }

  /** 缓冲 1 条 delta（未达 maxBatchEvents=2、窗口 100ms 未到期）的 fixture。 */
  private Fixture boundaryFixture() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMillis(100), 2, 1024 * 1024, Duration.ofMinutes(10));
    AtomicInteger scheduleCount = new AtomicInteger();
    AtomicReference<Runnable> capturedTask = new AtomicReference<>();
    ScheduledExecutorService controlledScheduler =
        createControlledScheduler(scheduleCount, capturedTask);
    Fixture fixture = createFixture(flushConfig, NO_RETRY, controlledScheduler, Runnable::run);
    fixture.start();

    fixture.listener().onEvent(delta("first"));
    assertEquals(1, scheduleCount.get(), "first buffered delta must schedule the push timer");
    assertEquals(0, fixture.store.transactionCount(), "buffered delta must not touch the store");
    return fixture;
  }

  /**
   * 以合法状态机把 durable 行推进到 RUNNING attempt=2，模拟同一 invocation 已被下一次 attempt 接管： RUNNING(1) -> READY(1,
   * retry) -> DISPATCHING -> RUNNING(2)。
   *
   * <p>Store 的 {@code updateModelInvocation} 始终以**已存储行**为基准调用 {@code validateTransition}，
   * 因此每一步合法转换都必须单独 update 落地，不能只把局部表达式拆开。
   */
  private static void advanceToSecondAttempt(Fixture fixture) {
    fixture
        .store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(fixture.baseline.threadId()).orElseThrow();
              ModelInvocation model = tx.lockModelInvocation(fixture.invocationId).orElseThrow();
              ModelInvocationError error =
                  new ModelInvocationError(ProviderErrorKind.TRANSIENT, "superseded");
              model =
                  model.retryReady(new ModelAttemptFailure(1, 0L, "", "", error, NOW, NOW), NOW);
              tx.updateModelInvocation(model);
              model = model.beginDispatch(NOW);
              tx.updateModelInvocation(model);
              model = model.markRunning(NOW);
              tx.updateModelInvocation(model);
              return null;
            });
  }

  private static ProviderStreamEvent delta(String text) {
    return new ProviderStreamEvent.TextDelta(text);
  }

  /**
   * 意图：验证批次定时器遭遇 scheduler 拒绝时，execution 安全收敛为 abandoned 且 handle 被取消， cancel 绝不在持 monitor 状态下触发死锁。
   */
  @Test
  void batchTimerSchedulerRejectionConvergesToAbandonedWithoutHoldingMonitor() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
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
                      && delay.longValue() == flushConfig.pushWindow().toMillis()) {
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
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMillis(100), 10, 1024 * 1024, Duration.ofMinutes(10));
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
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
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
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
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
    // activate 期间共 2 次 update：markRunning(1) + terminal 终态(1)；重放的 delta 只推送不落库
    assertEquals(
        2, store.modelInvocationUpdateCount(), "markRunning + terminal = 2 updates");
  }

  /**
   * 意图：验证 checkpoint 持久化遭遇 Store 失败时，正确语义为 abandon 本地 execution，绝不伪装为 Provider INVALID_RESPONSE 写
   * FAILED/retry，不发布 checkpoint 事件，保持 durable RUNNING+claimed 供 lease recovery 收敛。
   */
  @Test
  void checkpointStoreFailureAbandonsLocalExecutionWithoutMaskingAsInvalidResponse() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("e1"));
    assertFalse(fixture.execution.abandoned(), "execution must stay active before the checkpoint");

    fixture.store.updateInvocationFailure =
        new RuntimeException("simulated db failure during checkpoint");
    fixture.execution.deliverCheckpoint(0, true);

    assertTrue(fixture.execution.abandoned(), "execution must be abandoned upon checkpoint failure");
    assertTrue(fixture.handle.isCancelled(), "handle must be cancelled upon checkpoint failure");

    ModelInvocation model = fixture.currentModel();
    assertEquals(
        ModelInvocationStatus.RUNNING,
        model.status(),
        "model must remain RUNNING for lease recovery");
    assertEquals(1, model.attempt(), "attempt must not change");
    assertNull(model.error(), "must not write ModelInvocationError for infrastructure failure");
    assertTrue(model.failedAttempts().isEmpty(), "must not reschedule retry");

    Work work =
        fixture
            .store
            .delegate()
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)))
            .orElseThrow();
    assertNotNull(work.leaseToken(), "work must remain claimed");
    assertEquals(
        fixture.execution.claim().leaseToken(),
        work.leaseToken(),
        "lease token must remain identical");
    assertTrue(fixture.sink.checkpoints().isEmpty(), "no checkpoint event after a failed commit");
  }


  /**
   * 意图：验证 checkpoint 定时器触发的持久化遭遇 Store 失败时，execution 安全收敛为 abandoned 且 handle 取消，绝不写 FAILED/retry，保持 durable
   * RUNNING+claimed 供 lease recovery 收敛。
   */
  @Test
  void timerCheckpointStoreFailureAbandonsLocalExecutionWithoutMaskingAsInvalidResponse() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMillis(100), 10, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("e1"));
    fixture.store.updateInvocationFailure =
        new RuntimeException("simulated db failure during timer checkpoint");

    // 首个 safe delta 武装的 checkpoint 定时器代际为 0
    fixture.execution.deliverCheckpoint(0, false);

    assertTrue(fixture.execution.abandoned());
    assertTrue(fixture.handle.isCancelled());
    ModelInvocation model = fixture.currentModel();
    assertEquals(ModelInvocationStatus.RUNNING, model.status(), "model must remain RUNNING");
    assertEquals(1, model.attempt());
    assertNull(model.error());
    assertTrue(model.failedAttempts().isEmpty());
    Work work =
        fixture
            .store
            .delegate()
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, fixture.invocationId)))
            .orElseThrow();
    assertNotNull(work.leaseToken());
    assertEquals(fixture.execution.claim().leaseToken(), work.leaseToken());
    assertTrue(fixture.sink.checkpoints().isEmpty());
  }


  /**
   * 意图：验证 Provider stream event 自身非法（如 tool call 标识冲突）时， 仍然正确收敛为 INVALID_RESPONSE
   * 终态（或重试），不与基础设施失败混淆。
   */
  @Test
  void invalidProviderEventConvergesToInvalidResponseTerminal() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY, requestWithTool());
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_1", "bash", null));

    // 发送冲突的 tool call delta（同一 index 不同 call id），导致 accumulator 抛出 IllegalArgumentException
    listener.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));

    // execution 必须已 abandon
    assertTrue(fixture.execution.abandoned());
    // 数据库状态收敛为 FAILED 且错误类别为 INVALID_RESPONSE
    ModelInvocation model = fixture.currentModel();
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertNotNull(model.error());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
  }

  /**
   * 意图：验证空内容 delta 不得强制推进 checkpoint： 1. checkpoint 路径：接收空 delta 后再次 checkpoint 不发起无意义 UPDATE；
   * 2. 空 delta 仍按普通 realtime delta 分配递增 sequence 并正常发布； 3. 终态路径：流尾部接收空 delta 后进入 succeeded，终态 checkpoint 复用已有 safe
   * sequence，不抬高 sequence。
   */
  @Test
  void emptyDeltaDoesNotAdvanceCheckpointInPeriodicOrTerminalPaths() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 1, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    fixture.execution.deliverCheckpoint(0, true);
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    ModelInvocation modelAfterFirst = fixture.currentModel();
    assertNotNull(modelAfterFirst.streamCheckpoint());
    assertEquals(1L, modelAfterFirst.streamCheckpoint().sequence());
    assertEquals("hello", modelAfterFirst.streamCheckpoint().text());

    listener.onEvent(new ProviderStreamEvent.TextDelta(""));
    fixture.execution.deliverCheckpoint(0, true);
    assertEquals(
        1,
        fixture.store.modelInvocationUpdateCount(),
        "empty delta must not trigger checkpoint update");
    assertEquals(
        1L, fixture.currentModel().streamCheckpoint().sequence(), "checkpoint sequence must remain 1");
    // 无前进的追帧请求重发已落库 checkpoint 的事件
    assertEquals(List.of(1L, 1L), fixture.sink.checkpoints());

    assertEquals(2, fixture.sink.deltas().size());
    assertEquals(1L, fixture.sink.deltas().get(0).sequence());
    assertEquals(
        "hello", ((ProviderStreamEvent.TextDelta) fixture.sink.deltas().get(0).delta()).text());
    assertEquals(2L, fixture.sink.deltas().get(1).sequence());
    assertEquals("", ((ProviderStreamEvent.TextDelta) fixture.sink.deltas().get(1).delta()).text());

    listener.onSucceeded(response("hello"));
    ModelInvocation finalModel = fixture.currentModel();
    assertEquals(ModelInvocationStatus.SUCCEEDED, finalModel.status());
    assertNotNull(finalModel.streamCheckpoint());
    assertEquals(
        1L,
        finalModel.streamCheckpoint().sequence(),
        "final checkpoint must reuse original safe sequence");
    assertEquals("hello", finalModel.streamCheckpoint().text());
  }


  /**
   * 意图：验证流在接收空 delta 之后发生 failure，生成的 failure 审计与 final checkpoint 复用原有 safe sequence，不被空 delta 抬高。
   */
  @Test
  void emptyDeltaDoesNotAdvanceCheckpointInFailureTerminalPath() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 1, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();

    ModelGateway.Listener listener = fixture.listener();
    listener.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    fixture.execution.deliverCheckpoint(0, true);
    assertEquals(1L, fixture.currentModel().streamCheckpoint().sequence());

    // 发送空 delta
    listener.onEvent(new ProviderStreamEvent.TextDelta(""));

    // 触发 FAILED
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "transient error");
    listener.onFailed(error);

    ModelInvocation model = fixture.currentModel();
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertNotNull(model.streamCheckpoint());
    assertEquals(
        1L,
        model.streamCheckpoint().sequence(),
        "failed checkpoint sequence must not be advanced by empty delta");
  }

  /**
   * 意图：验证在 handle.activate 同步回调中缓冲非法 Provider event 时， 异常被 ModelExecution 边界隔离，收敛为 INVALID_RESPONSE
   * terminal，异常不逃出 activate()。
   */
  @Test
  void synchronousActivateBufferedInvalidEventIsIsolatedAndConvergesToInvalidResponse() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestWithTool(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    ModelExecution[] executionHolder = new ModelExecution[1];
    ModelGateway.Handle synchronousHandle =
        new ModelGateway.Handle() {
          @Override
          public void activate() {
            // 在 activate 内部同步投递两个冲突的 tool call 片段
            executionHolder[0].onEvent(
                new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
            executionHolder[0].onEvent(
                new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));
          }

          @Override
          public void cancel() {}
        };

    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    executionHolder[0] = execution;

    // 执行 activate：异常不得抛出，返回 TERMINATED（NO_RETRY 下转 FAILED）
    ProcessResult result = execution.activate(synchronousHandle);
    assertEquals(
        ProcessResult.TERMINATED,
        result,
        "illegal provider event in buffer must converge to TERMINATED");
    ModelInvocation model =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, model.error().kind());
  }

  /**
   * 意图：验证在 handle.activate 同步回调中缓冲 terminal 信号，drain 期间持久化遭遇 DB 失败时， 异常被 ModelExecution 边界隔离，返回
   * LOST_OWNERSHIP，保持 durable RUNNING。
   */
  @Test
  void synchronousActivateBufferedTerminalStoreFailureIsIsolatedAndReturnsLostOwnership() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    ModelExecution[] executionHolder = new ModelExecution[1];
    ModelGateway.Handle synchronousHandle =
        new ModelGateway.Handle() {
          @Override
          public void activate() {
            // markRunning 已成功执行；在 drain 开始前注入 terminal commit 时的 store 失败
            store.updateInvocationFailure = new RuntimeException("db error during terminal commit");
            executionHolder[0].onSucceeded(response("hello"));
          }

          @Override
          public void cancel() {}
        };

    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    executionHolder[0] = execution;

    // 执行 activate：异常不得逃出，返回 LOST_OWNERSHIP
    ProcessResult result = execution.activate(synchronousHandle);
    assertEquals(ProcessResult.LOST_OWNERSHIP, result);
    assertTrue(execution.abandoned());

    ModelInvocation model =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
  }

  /**
   * 意图：验证 activation gate 的普通 EVENT 缓冲数量严格有界；max+1 属于本地背压失败，保留 RUNNING/claim 供 lease recovery，而
   * terminal 在容量边界仍可落地。
   */
  @Test
  void gateBufferPendingCapacityMaxPlusOneRejectsOrdinaryEventWhileTerminalIsAccepted() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 2, 1024 * 1024, Duration.ofMinutes(10));
    Fixture overflow = createFixture(flushConfig, NO_RETRY);
    AtomicBoolean overflowHandleCancelled = new AtomicBoolean();
    ModelGateway.Handle overflowingHandle =
        new ModelGateway.Handle() {
          @Override
          public void activate() {
            overflow.listener().onEvent(new ProviderStreamEvent.TextDelta("e1"));
            overflow.listener().onEvent(new ProviderStreamEvent.TextDelta("e2"));
            overflow.listener().onEvent(new ProviderStreamEvent.TextDelta("e3"));
          }

          @Override
          public void cancel() {
            overflowHandleCancelled.set(true);
          }
        };

    assertEquals(ProcessResult.LOST_OWNERSHIP, overflow.start(overflowingHandle));
    assertTrue(overflow.execution.abandoned());
    assertTrue(overflowHandleCancelled.get());
    assertEquals(ModelInvocationStatus.RUNNING, overflow.currentModel().status());
    assertNull(overflow.currentModel().error());
    assertTrue(overflow.currentModel().failedAttempts().isEmpty());
    Work overflowWork =
        overflow
            .store
            .delegate()
            .transaction(
                tx ->
                    tx.findWork(new WorkTarget(WorkTargetType.MODEL, overflow.invocationId))
                        .orElseThrow());
    assertNotNull(overflowWork.leaseToken());
    assertFalse(overflow.processor.hasActiveExecution());
    assertTrue(overflow.sink.deltas().isEmpty());

    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    Baseline baseline2 = seedBaseline(store.delegate(), NOW.plusSeconds(10));
    UUID invocationId2 =
        seedInvocation(store.delegate(), baseline2, requestSpec(), NOW.plusSeconds(10));
    ClaimedWork claim2 = claim(store.delegate(), invocationId2, NOW.plusSeconds(10));
    FakeHandle handle2 = new FakeHandle();
    ModelExecution execution2 =
        new ModelExecution(
            store,
            sink,
            claim2,
            baseline2.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});

    // 填满 maxEvents (2 个事件)
    execution2.onEvent(new ProviderStreamEvent.TextDelta("e1"));
    execution2.onEvent(new ProviderStreamEvent.TextDelta("e2"));
    assertFalse(execution2.abandoned());

    // 在容量边界投递 terminal 信号：绝不因 pending.size() >= maxEvents 而被拒绝！
    execution2.onSucceeded(response("e1e2 completed"));
    assertFalse(execution2.abandoned(), "terminal signal must not be rejected at capacity");

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline2.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId2).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW.plusSeconds(10)));
              return null;
            });

    // activate 后顺利打开门控并成功将 terminal 落地落盘
    ProcessResult result = execution2.activate(handle2);
    assertEquals(
        ProcessResult.TERMINATED, result, "terminal signal must be processed on activation");
    ModelInvocation finalModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId2)).orElseThrow();
    assertEquals(ModelInvocationStatus.SUCCEEDED, finalModel.status());
  }

  /**
   * 意图：验证非法同步 buffered EVENT 触发 INVALID_RESPONSE 收敛时再注入 store failure，异常被 ModelExecution 隔离，返回
   * LOST_OWNERSHIP，durable 保持 RUNNING。
   */
  @Test
  void synchronousActivateBufferedInvalidEventStoreFailureIsIsolatedAndReturnsLostOwnership() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    ModelExecution[] executionHolder = new ModelExecution[1];
    ModelGateway.Handle synchronousHandle =
        new ModelGateway.Handle() {
          @Override
          public void activate() {
            // markRunning 成功后，注入失败终态持久化时的 store 失败
            store.updateInvocationFailure =
                new RuntimeException("db error during failure terminal commit");
            // 同步投递事件，随后投递前缀冲突的非法 response，触发 INVALID_RESPONSE 收敛
            executionHolder[0].onEvent(new ProviderStreamEvent.TextDelta("valid"));
            executionHolder[0].onSucceeded(response("conflicts with valid"));
          }

          @Override
          public void cancel() {}
        };

    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    executionHolder[0] = execution;

    ProcessResult result = execution.activate(synchronousHandle);
    assertEquals(ProcessResult.LOST_OWNERSHIP, result);
    assertTrue(execution.abandoned());

    ModelInvocation model =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
  }

  /** 意图：验证 Gateway 激活抛出异常且 UNKNOWN 终态持久化亦失败时，异常在边界被隔离并返回 LOST_OWNERSHIP，durable 保持 RUNNING。 */
  @Test
  void activationFailureStoreFailureIsIsolatedAndReturnsLostOwnership() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    ModelGateway.Handle failingHandle =
        new ModelGateway.Handle() {
          @Override
          public void activate() {
            // markRunning 成功后，注入持久化 UNKNOWN 失败并抛出激活异常
            store.updateInvocationFailure = new RuntimeException("db error during UNKNOWN commit");
            throw new RuntimeException("gateway activation failed");
          }

          @Override
          public void cancel() {}
        };

    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});

    ProcessResult result = execution.activate(failingHandle);
    assertEquals(ProcessResult.LOST_OWNERSHIP, result);
    assertTrue(execution.abandoned());

    ModelInvocation model =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
  }

  /**
   * 意图：验证同步 activate 缓冲普通事件在 drain 期间若遭遇 scheduler 调度拒绝，事件处理返回 LOST，activate 返回 LOST_OWNERSHIP 而非
   * STARTED。
   */
  @Test
  void synchronousActivateBufferedEventSchedulerRejectionReturnsLostOwnership() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(delegate);

    ScheduledExecutorService rejectingScheduler =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if ("schedule".equals(method.getName())) {
                    throw new RejectedExecutionException("scheduler queue full");
                  }
                  return method.invoke(delegate, args);
                });

    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    ModelExecution[] executionHolder = new ModelExecution[1];
    ModelGateway.Handle synchronousHandle =
        new ModelGateway.Handle() {
          @Override
          public void activate() {
            executionHolder[0].onEvent(new ProviderStreamEvent.TextDelta("hello"));
          }

          @Override
          public void cancel() {}
        };

    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            rejectingScheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    executionHolder[0] = execution;

    ProcessResult result = execution.activate(synchronousHandle);
    assertEquals(
        ProcessResult.LOST_OWNERSHIP,
        result,
        "scheduler rejection during drain must return LOST_OWNERSHIP rather than STARTED");
    assertTrue(execution.abandoned());
  }

  /**
   * 意图：验证单 drain owner 覆盖 post-commit publish：批次已提交并正在发布（drainLock 被持有）时， terminal 信号只能先 CAS 再将
   * durable 提交排在 publish 之后；此时仍指向上一代际的 timer 必须是 no-op，绝不 abandon 正在被 terminal 收敛的 execution。
   */
  @Test
  void timerRejectionAfterTerminalClaimIsStaleNoOpAndDoesNotBlockTerminalCommit() throws Exception {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMillis(100), 2, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    AtomicReference<Runnable> capturedTimerTask = new AtomicReference<>();
    CountDownLatch scheduledLatch = new CountDownLatch(1);
    ScheduledFuture<?> dummyFuture =
        (ScheduledFuture<?>)
            Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[] {ScheduledFuture.class},
                (proxy, method, args) -> {
                  if ("cancel".equals(method.getName())) {
                    return Boolean.TRUE;
                  }
                  return null;
                });

    ScheduledExecutorService mockScheduler =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if ("schedule".equals(method.getName()) && args != null && args.length >= 1) {
                    if (((Long) args[1]) < CHECKPOINT_TIMER_MILLIS) {
                      capturedTimerTask.set((Runnable) args[0]);
                      scheduledLatch.countDown();
                    }
                    return dummyFuture;
                  }
                  return null;
                });

    Executor rejectingExecutor =
        task -> {
          throw new RejectedExecutionException("flush executor busy");
        };

    CountDownLatch publishingLatch = new CountDownLatch(1);
    CountDownLatch releasePublishLatch = new CountDownLatch(1);
    sink.blockingHook =
        delta -> {
          if (delta.sequence() == 2) {
            publishingLatch.countDown();
            try {
              releasePublishLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          }
        };

    FakeHandle handle = new FakeHandle();
    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            mockScheduler,
            Runnable::run,
            rejectingExecutor,
            e -> {});

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    // 1. 发送第一个事件，调度批次定时器
    execution.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    assertTrue(scheduledLatch.await(1, TimeUnit.SECONDS));
    assertNotNull(capturedTimerTask.get());

    // 2. 启动异步线程 T1 发送第二个事件：到达 maxEvents 触发容量 flush，提交成功后阻塞在 post-commit publish
    ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);
    executorsToClose.add(asyncExecutor);

    asyncExecutor.submit(() -> execution.onEvent(new ProviderStreamEvent.TextDelta(" world")));
    assertTrue(publishingLatch.await(5, TimeUnit.SECONDS));

    // 3. 在 drainLock 正被 T1 持有时，启动异步线程 T2 调用 onSucceeded：
    //    T2 首先成功原子完成 terminal.compareAndSet(false, true)，随后阻塞在 drainLock.lock()
    Thread[] t2Holder = new Thread[1];
    CountDownLatch t2StartedLatch = new CountDownLatch(1);
    asyncExecutor.submit(
        () -> {
          t2Holder[0] = Thread.currentThread();
          t2StartedLatch.countDown();
          execution.onSucceeded(response("hello world"));
        });
    assertTrue(t2StartedLatch.await(1, TimeUnit.SECONDS));
    await(
        () ->
            t2Holder[0] != null
                && (t2Holder[0].getState() == Thread.State.WAITING
                    || t2Holder[0].getState() == Thread.State.BLOCKED),
        Duration.ofSeconds(2));

    // 4. 此时 terminal 已经 CAS 为 true，而 timer 仍指向已被容量 flush 推进的旧代际：必须是 no-op，绝不 abandon！
    capturedTimerTask.get().run();
    assertFalse(execution.abandoned(), "stale timer after terminal claim must be no-op");
    assertEquals(
        ModelInvocationStatus.RUNNING,
        store
            .delegate()
            .transaction(tx -> tx.findModelInvocation(invocationId))
            .orElseThrow()
            .status(),
        "terminal must not commit before the in-flight publish completes");

    // 5. 释放 T1 的 publish，T2 随后获取 drainLock 并成功落地 SUCCEEDED
    releasePublishLatch.countDown();
    await(
        () ->
            store
                .delegate()
                .transaction(tx -> tx.findModelInvocation(invocationId))
                .orElseThrow()
                .status()
                .isTerminal(),
        Duration.ofSeconds(5));

    ModelInvocation finalModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(
        ModelInvocationStatus.SUCCEEDED, finalModel.status(), "terminal must commit successfully");
    // 容量 flush 已将 2 条 delta 按序提交并发布；final text 与累积文本一致，因此 terminal 不产生 gap delta
    List<RealtimeEvent.ModelDelta> deltas = sink.deltas();
    assertEquals(2, deltas.size());
    assertEquals(1, deltas.get(0).sequence());
    assertEquals(2, deltas.get(1).sequence());
  }

  /** 意图：验证已失效旧代际 timer 遭遇 executor rejection 时，为 stale no-op，不误伤当前批。 */
  @Test
  void staleGenerationTimerRejectionDoesNotAbandonCurrentExecution() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMillis(100), 2, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    AtomicReference<Runnable> capturedTimerTask = new AtomicReference<>();
    ScheduledFuture<?> dummyFuture =
        (ScheduledFuture<?>)
            Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[] {ScheduledFuture.class},
                (proxy, method, args) -> {
                  if ("cancel".equals(method.getName())) {
                    return Boolean.TRUE;
                  }
                  return null;
                });

    ScheduledExecutorService mockScheduler =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if ("schedule".equals(method.getName()) && args != null && args.length >= 1) {
                    if (((Long) args[1]) < CHECKPOINT_TIMER_MILLIS) {
                      capturedTimerTask.set((Runnable) args[0]);
                    }
                    return dummyFuture;
                  }
                  return null;
                });

    Executor rejectingExecutor =
        task -> {
          throw new RejectedExecutionException("executor queue full");
        };

    FakeHandle handle = new FakeHandle();
    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            mockScheduler,
            Runnable::run,
            rejectingExecutor,
            e -> {});

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    // 事件 1：调度代际 0 的 timer
    execution.onEvent(new ProviderStreamEvent.TextDelta("e1"));
    Runnable staleTimer = capturedTimerTask.get();
    assertNotNull(staleTimer);

    // 事件 2：达到 maxEvents=2 触发容量 flush，generation 递增，staleTimer 变为旧代际
    execution.onEvent(new ProviderStreamEvent.TextDelta("e2"));

    // 执行旧代际 timer：应当直接检查到代际失效退出，不 abandon execution
    staleTimer.run();
    assertFalse(execution.abandoned(), "stale timer rejection must not abandon execution");
  }

  /** 意图：验证门控前 pending 载荷字节约束：单个超阈值事件可独占，已有事件时超阈值则被拒绝并 abandon，terminal 信号在边界仍不受阻断并成功落地。 */
  @Test
  void gateBufferPendingPayloadBytesBoundaryAndTerminalAcceptance() {
    // maxPayloadBytes = 10 字节
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 10, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);

    // 1. 单个超阈值事件（15 字节）可独占 pending
    UUID id1 = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    ClaimedWork claim1 = claim(store.delegate(), id1, NOW);
    ModelExecution exec1 =
        new ModelExecution(
            store,
            sink,
            claim1,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    exec1.onEvent(new ProviderStreamEvent.TextDelta("012345678901234")); // 15 字节 > 10
    assertFalse(
        exec1.abandoned(), "single oversized event must be allowed to monopolize pending buffer");

    // 2. 已有普通事件（6 字节）时，新事件（5 字节）使总字节（11 字节 > 10）超阈值，被拒绝并收敛为 abandoned
    Baseline baseline2 = seedBaseline(store.delegate(), NOW.plusSeconds(10));
    UUID id2 = seedInvocation(store.delegate(), baseline2, requestSpec(), NOW.plusSeconds(10));
    ClaimedWork claim2 = claim(store.delegate(), id2, NOW.plusSeconds(10));
    ModelExecution exec2 =
        new ModelExecution(
            store,
            sink,
            claim2,
            baseline2.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    exec2.onEvent(new ProviderStreamEvent.TextDelta("123456")); // 6 bytes <= 10
    assertFalse(exec2.abandoned());
    exec2.onEvent(new ProviderStreamEvent.TextDelta("12345")); // 6 + 5 = 11 > 10
    assertTrue(
        exec2.abandoned(), "subsequent event exceeding total payload bytes must abandon execution");

    // 3. pending 处于超阈值状态时，terminal 信号依然不受阻断并成功落地
    Baseline baseline3 = seedBaseline(store.delegate(), NOW.plusSeconds(20));
    UUID id3 = seedInvocation(store.delegate(), baseline3, requestSpec(), NOW.plusSeconds(20));
    ClaimedWork claim3 = claim(store.delegate(), id3, NOW.plusSeconds(20));
    FakeHandle handle3 = new FakeHandle();
    ModelExecution exec3 =
        new ModelExecution(
            store,
            sink,
            claim3,
            baseline3.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});
    exec3.onEvent(new ProviderStreamEvent.TextDelta("012345678901234")); // 15 字节独占
    exec3.onSucceeded(response("012345678901234 completed")); // terminal 信号
    assertFalse(exec3.abandoned(), "terminal signal must not be rejected at payload boundary");

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline3.threadId());
              ModelInvocation model = tx.lockModelInvocation(id3).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW.plusSeconds(20)));
              return null;
            });

    ProcessResult result = exec3.activate(handle3);
    assertEquals(ProcessResult.TERMINATED, result);
    ModelInvocation finalModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(id3)).orElseThrow();
    assertEquals(ModelInvocationStatus.SUCCEEDED, finalModel.status());
  }

  /** 意图：验证已提交 partial 遭遇合法 FILTERED 响应时，一次 UPDATE 落地 SUCCEEDED 并清除 checkpoint，不发布回退 delta。 */
  @Test
  void committedPartialWithFilteredResponseClearsCheckpointAndDoesNotPublishRegression() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 1, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    FakeHandle handle = new FakeHandle();
    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});

    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    // 投递 delta（立即推送）并提交 checkpoint
    execution.onEvent(new ProviderStreamEvent.TextDelta("sensitive content"));
    execution.deliverCheckpoint(0, true);
    ModelInvocation committedModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertNotNull(committedModel.streamCheckpoint(), "checkpoint must be committed");
    assertEquals("sensitive content", committedModel.streamCheckpoint().text());
    assertEquals(1, sink.deltas().size());

    int updatesBefore = store.modelInvocationUpdateCount.get();

    // 投递合法 FILTERED 响应（内容被过滤为空，不作为前缀）
    execution.onSucceeded(response("", GenerationStopReason.FILTERED));

    // 校验：恰好单次 UPDATE 为 SUCCEEDED，checkpoint 撤回为 null
    assertEquals(
        updatesBefore + 1,
        store.modelInvocationUpdateCount.get(),
        "must update exactly once to terminal");
    ModelInvocation finalModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.SUCCEEDED, finalModel.status());
    assertNull(finalModel.streamCheckpoint(), "checkpoint must be withdrawn/cleared on FILTERED");
    // 校验：不得发布回退 delta
    assertEquals(1, sink.deltas().size(), "must not publish regression delta");
  }

  /** 意图：验证未刷盘 partial 遭遇合法 FILTERED 响应时，一次 UPDATE 为 SUCCEEDED 且 checkpoint 为 null，丢弃未刷批次。 */
  @Test
  void unflushedPartialWithFilteredResponseClearsCheckpointAndDiscardsBatch() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    FakeHandle handle = new FakeHandle();
    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});

    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    // 投递 delta，未达到 maxEvents=10，停留在 batchItems 中未刷盘
    execution.onEvent(new ProviderStreamEvent.TextDelta("unflushed sensitive"));
    int updatesBefore = store.modelInvocationUpdateCount.get();

    // 投递合法 FILTERED 响应
    execution.onSucceeded(response("", GenerationStopReason.FILTERED));

    // 校验：单次 UPDATE 为 SUCCEEDED，checkpoint 保持 null，未刷批次被丢弃未发布
    assertEquals(
        updatesBefore + 1,
        store.modelInvocationUpdateCount.get(),
        "must update exactly once to terminal");
    ModelInvocation finalModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.SUCCEEDED, finalModel.status());
    assertNull(finalModel.streamCheckpoint(), "checkpoint must be null");
    assertTrue(sink.deltas().isEmpty(), "unflushed deltas must be discarded, nothing published");
  }

  /** 意图：验证非法 FILTERED 响应（携带 toolCalls）按现有语义收敛为 INVALID_RESPONSE 失败。 */
  @Test
  void illegalFilteredResponseWithToolCallsConvergesToInvalidResponseFailure() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    CountingStore store = new CountingStore(new InMemoryHarnessStore());
    Baseline baseline = seedBaseline(store.delegate(), NOW);
    UUID invocationId = seedInvocation(store.delegate(), baseline, requestSpec(), NOW);
    RecordingSink sink = new RecordingSink();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    ClaimedWork claim = claim(store.delegate(), invocationId, NOW);

    store
        .delegate()
        .transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(NOW));
              return null;
            });

    FakeHandle handle = new FakeHandle();
    ModelExecution execution =
        new ModelExecution(
            store,
            sink,
            claim,
            baseline.threadId(),
            1,
            false,
            List.of(),
            new ModelProcessorConfig(LEASE_CONFIG, () -> NO_RETRY, FALLBACK_DELAY, flushConfig),
            "test-provider",
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            Runnable::run,
            e -> {});

    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    // 非法 FILTERED：携带 tool call
    ProviderResponse illegalFiltered =
        response(
            "",
            List.of(new ProviderToolCall("call-1", "tool", "{}")),
            GenerationStopReason.FILTERED);
    execution.onSucceeded(illegalFiltered);

    ModelInvocation finalModel =
        store.delegate().transaction(tx -> tx.findModelInvocation(invocationId)).orElseThrow();
    assertEquals(ModelInvocationStatus.FAILED, finalModel.status());
    assertNotNull(finalModel.error());
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, finalModel.error().kind());
  }

  /**
   * 意图：验证 provider native replay state 的保留集合与「非撤下终态」一致——CONTINUE 续写请求必须原样携带上游 native 字段，FILTERED
   * 这类协议撤下态则不保留。
   */
  @Test
  void continuationTerminalRetainsProviderReplayStateWhileFilteredDropsIt() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 10, 1024 * 1024, Duration.ofMinutes(10));
    Fixture continuationFixture = createFixture(flushConfig, NO_RETRY);
    continuationFixture.start();
    ProviderReplayState replayState = sampleReplayState();

    continuationFixture
        .listener()
        .onSucceeded(
            new ProviderCompletion(response("paused", GenerationStopReason.CONTINUE), replayState));

    ModelInvocation continued = continuationFixture.currentModel();
    assertEquals(ModelInvocationStatus.SUCCEEDED, continued.status());
    assertEquals(GenerationStopReason.CONTINUE, continued.result().stopReason());
    assertNull(continued.resultEntryId());
    assertEquals(replayState, continued.providerReplayState());

    Fixture filteredFixture = createFixture(flushConfig, NO_RETRY);
    filteredFixture.start();

    filteredFixture
        .listener()
        .onSucceeded(
            new ProviderCompletion(response("", GenerationStopReason.FILTERED), replayState));

    ModelInvocation filtered = filteredFixture.currentModel();
    assertEquals(ModelInvocationStatus.SUCCEEDED, filtered.status());
    assertNull(filtered.providerReplayState());
  }

  // ---------------------------------------------------------------------------------------------
  // Test Helpers & Fixtures
  // ---------------------------------------------------------------------------------------------

  /** 意图：验证追帧请求经 ModelProcessor 路由到持有该 Thread 执行的 execution，立即落库并发布 checkpoint 事件；其它 Thread 的请求是 no-op。 */
  @Test
  void catchUpRequestForcesImmediateCheckpointOnlyForTheExecutingThread() {
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 100, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY);
    fixture.start();
    fixture.listener().onEvent(new ProviderStreamEvent.TextDelta("abc"));
    fixture.listener().onEvent(new ProviderStreamEvent.ThinkingDelta("xyz"));

    fixture.processor.requestCheckpoint(UUID.randomUUID());
    assertEquals(0, fixture.store.transactionCount(), "unrelated thread must be a no-op");
    assertTrue(fixture.sink.checkpoints().isEmpty());

    fixture.processor.requestCheckpoint(fixture.baseline.threadId());
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    assertEquals(2, fixture.currentModel().streamCheckpoint().sequence());
    assertEquals("abc", fixture.currentModel().streamCheckpoint().text());
    assertEquals(List.of(2L), fixture.sink.checkpoints());
    RealtimeEvent.ModelCheckpoint event =
        (RealtimeEvent.ModelCheckpoint) fixture.sink.events().getLast();
    assertEquals(fixture.baseline.threadId(), event.threadId());
    assertEquals(fixture.invocationId, event.modelInvocationId());
    assertEquals(1, event.attempt());
  }

  /** 意图：验证 checkpoint 节奏：固定 deadline 只在上次落库之后的第一个 safe delta 武装一次，只有前进时才写库。 */
  @Test
  void checkpointTimerIsArmedOncePerIntervalAndOnlyPersistsAdvancedState() {
    long checkpointMillis = Duration.ofMinutes(10).toMillis();
    List<Long> scheduledDelays = new CopyOnWriteArrayList<>();
    ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(delegate);
    ScheduledExecutorService recording =
        (ScheduledExecutorService)
            Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(),
                new Class<?>[] {ScheduledExecutorService.class},
                (proxy, method, args) -> {
                  if ("schedule".equals(method.getName()) && args != null && args.length == 3) {
                    scheduledDelays.add((Long) args[1]);
                  }
                  return method.invoke(delegate, args);
                });
    ModelStreamConfig flushConfig = new ModelStreamConfig(Duration.ofMinutes(1), 1, 1024 * 1024, Duration.ofMinutes(10));
    Fixture fixture = createFixture(flushConfig, NO_RETRY, recording, Runnable::run);
    fixture.start();

    for (int i = 0; i < 3; i++) {
      fixture.listener().onEvent(delta("e" + i));
    }
    assertEquals(
        1,
        scheduledDelays.stream().filter(delay -> delay == checkpointMillis).count(),
        "three safe deltas arm one checkpoint deadline");
    assertEquals(0, fixture.store.modelInvocationUpdateCount());

    fixture.execution.deliverCheckpoint(0, false);
    assertEquals(1, fixture.store.modelInvocationUpdateCount());
    assertEquals(3, fixture.currentModel().streamCheckpoint().sequence());

    // 没有新内容：定时器代际已失效，不写库
    fixture.execution.deliverCheckpoint(0, false);
    assertEquals(1, fixture.store.modelInvocationUpdateCount());

    // 新的 safe delta 武装下一个 deadline
    fixture.listener().onEvent(delta("e3"));
    assertEquals(
        2,
        scheduledDelays.stream().filter(delay -> delay == checkpointMillis).count(),
        "the next safe delta after a checkpoint arms a new deadline");
  }

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
                // 本类的 checkpoint 间隔都是 10 分钟：只捕获推送定时器，checkpoint 由测试直接调用 deliverCheckpoint。
                if (((Long) args[1]) < CHECKPOINT_TIMER_MILLIS) {
                  scheduleCount.incrementAndGet();
                  capturedTask.set(task);
                }
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

  private Fixture createFixture(ModelStreamConfig flushConfig, InvocationRetryPolicy retryPolicy) {
    return createFixture(flushConfig, retryPolicy, requestSpec());
  }

  private Fixture createFixture(
      ModelStreamConfig flushConfig,
      InvocationRetryPolicy retryPolicy,
      ModelRequestSpec requestSpec) {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    executorsToClose.add(scheduler);
    return createFixture(
        flushConfig, retryPolicy, scheduler, Runnable::run, requestSpec, null, null);
  }

  private Fixture createFixture(
      ModelStreamConfig flushConfig,
      InvocationRetryPolicy retryPolicy,
      ScheduledExecutorService scheduler,
      Executor flushExecutor) {
    return createFixture(
        flushConfig, retryPolicy, scheduler, flushExecutor, requestSpec(), null, null);
  }

  private Fixture createFixture(
      ModelStreamConfig flushConfig,
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
      ModelStreamConfig flushConfig,
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
            Runnable::run,
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
    final ModelStreamConfig flushConfig;
    final FakeHandle handle = new FakeHandle();
    ModelExecution execution;

    Fixture(
        CountingStore store,
        Baseline baseline,
        UUID invocationId,
        FakeGateway gateway,
        RecordingSink sink,
        ModelProcessor processor,
        ModelStreamConfig flushConfig) {
      this.store = store;
      this.baseline = baseline;
      this.invocationId = invocationId;
      this.gateway = gateway;
      this.sink = sink;
      this.processor = processor;
      this.flushConfig = flushConfig;
    }

    void start() {
      assertEquals(ProcessResult.STARTED, start(handle));
    }

    ProcessResult start(ModelGateway.Handle startedHandle) {
      gateway.queue(new ModelGateway.Started(startedHandle));
      ClaimedWork claim = claim(store.delegate(), invocationId, NOW);
      ProcessResult result = processor.process(claim);
      this.execution = (ModelExecution) gateway.listener(invocationId);
      // 排除 seed 与 markRunning 的初始更新，启动后重置计数器
      store.resetUpdateCount();
      return result;
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
    return new ProviderResponse(text, null, List.of(), stopReason, usage(), "req-1", null, null);
  }

  private static ProviderResponse response(
      String text, List<ProviderToolCall> toolCalls, GenerationStopReason stopReason) {
    return new ProviderResponse(text, null, toolCalls, stopReason, usage(), "req-1", null, null);
  }

  private static ProviderResponse response(
      String text, String thinking, List<ProviderToolCall> toolCalls) {
    return new ProviderResponse(
        text, thinking, toolCalls, GenerationStopReason.COMPLETE, usage(), "req-1", null, null);
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }

  /** 测试用 native replay state：CONTINUE 等非撤下终态必须原样保留它（FILTERED 不保留）。 */
  private static ProviderReplayState sampleReplayState() {
    return new ProviderReplayState(
        ProviderReplayFormat.OPENAI_RESPONSES,
        new ProviderReplayAffinity(
            ProviderType.OPENAI_RESPONSES, "minimax", new UUID(0L, 63L), "minimax-m2"),
        JsonNodeFactory.instance.objectNode().put("k", "v"));
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
            "run bash commands",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "bash", List.of()),
            EnvironmentSupport.NONE,
            null,
            null);
    return spec(List.of(binding));
  }

  private static ModelRequestSpec spec(List<ToolBinding> bindings) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        bindings,
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static Baseline seedBaseline(HarnessStore store, Instant now) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, now));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), now));
          UUID turnStartEntryId = tx.nextId();
          tx.insertEntry(
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
                      null),
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
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id));
          return id;
        });
  }

  private static ClaimedWork claim(HarnessStore store, UUID invocationId, Instant now) {
    return store
        .transaction(
            tx ->
                tx.claimNextWork(
                    WorkTargetType.MODEL, "token-" + invocationId, Duration.ofSeconds(60)))
        .orElseThrow();
  }

  private static BranchSettings branchSettings() {
    return new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
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

  /**
   * 记录型 sink：既能统计单条 {@code append}，也能统计批量 {@link #appendAll} 的调用次数与每次批量大小，用于断言「N 个已提交 delta
   * 恰好一次批量派发、而不是 N 次单条发布」。
   *
   * <p>覆写 {@code appendAll} 但保持与默认实现等价的逐条语义，保证既覆盖批量路径，也不改变 observable 顺序。
   */
  private static final class RecordingSink implements RealtimeEventSink {
    private final List<RealtimeEvent.ModelDelta> deltas = new CopyOnWriteArrayList<>();
    private final List<RealtimeEvent> events = new CopyOnWriteArrayList<>();
    private final List<Long> checkpoints = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> threadRecord;
    private final AtomicInteger appendCalls = new AtomicInteger();
    private final AtomicInteger appendAllCalls = new AtomicInteger();
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    volatile Consumer<RealtimeEvent.ModelDelta> blockingHook;

    /** 第 N 次 {@code appendAll} 调用（1-based）抛错；0 表示不注入失败。 */
    final AtomicInteger failAppendAllOnCall = new AtomicInteger();

    RecordingSink() {
      this(null);
    }

    RecordingSink(AtomicReference<String> threadRecord) {
      this.threadRecord = threadRecord;
    }

    @Override
    public void append(RealtimeEvent event) {
      appendCalls.incrementAndGet();
      record(event);
    }

    @Override
    public void appendAll(List<RealtimeEvent> events) {
      int call = appendAllCalls.incrementAndGet();
      batchSizes.add(events.size());
      if (failAppendAllOnCall.get() == call) {
        throw new IllegalStateException("notification channel unavailable on call " + call);
      }
      for (RealtimeEvent event : events) {
        record(event);
      }
    }

    private void record(RealtimeEvent event) {
      if (threadRecord != null) {
        threadRecord.set(Thread.currentThread().getName());
      }
      if (event instanceof RealtimeEvent.ModelDelta delta) {
        if (blockingHook != null) {
          blockingHook.accept(delta);
        }
        deltas.add(delta);
      } else if (event instanceof RealtimeEvent.ModelCheckpoint checkpoint) {
        checkpoints.add(checkpoint.sequence());
      }
      events.add(event);
    }

    /** 按到达顺序记录的全部事件。 */
    List<RealtimeEvent> events() {
      return List.copyOf(events);
    }

    /** 已发布 checkpoint 事件的 sequence。 */
    List<Long> checkpoints() {
      return List.copyOf(checkpoints);
    }

    List<RealtimeEvent.ModelDelta> deltas() {
      return List.copyOf(deltas);
    }

    int appendCalls() {
      return appendCalls.get();
    }

    int appendAllCalls() {
      return appendAllCalls.get();
    }

    List<Integer> batchSizes() {
      return List.copyOf(batchSizes);
    }
  }

  /**
   * 测试专用 Store 代理：精确计数事务、行锁查询（{@code lockModelInvocation} / {@code lockClaimedWork}）与 {@code
   * updateModelInvocation}（对应真实实现中的 UPDATE / SELECT ... FOR UPDATE SQL 次数），并支持失败注入，同时解包异常保证
   * delegate 原样抛出。
   *
   * <p>只统计 UPDATE 次数不足以证明「每个 buffered delta 零数据库开销」：必须同时证明零事务、零行锁查询、零 SQL 执行。
   */
  static final class CountingStore implements HarnessStore {
    private final InMemoryHarnessStore delegate;
    private final AtomicInteger modelInvocationUpdateCount = new AtomicInteger();
    private final AtomicInteger transactionCount = new AtomicInteger();
    private final AtomicInteger modelInvocationLockCount = new AtomicInteger();
    private final AtomicInteger claimedWorkLockCount = new AtomicInteger();
    private final AtomicReference<String> threadRecord;
    volatile RuntimeException updateInvocationFailure;
    volatile RuntimeException transactionFailure;

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

    int transactionCount() {
      return transactionCount.get();
    }

    int modelInvocationLockCount() {
      return modelInvocationLockCount.get();
    }

    int claimedWorkLockCount() {
      return claimedWorkLockCount.get();
    }

    void resetUpdateCount() {
      modelInvocationUpdateCount.set(0);
      transactionCount.set(0);
      modelInvocationLockCount.set(0);
      claimedWorkLockCount.set(0);
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
      if (transactionFailure != null) {
        throw transactionFailure;
      }
      transactionCount.incrementAndGet();
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
                            if (updateInvocationFailure != null) {
                              throw updateInvocationFailure;
                            }
                          } else if ("lockModelInvocation".equals(method.getName())) {
                            modelInvocationLockCount.incrementAndGet();
                          } else if ("lockClaimedWork".equals(method.getName())) {
                            claimedWorkLockCount.incrementAndGet();
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
