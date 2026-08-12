package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 验证生产 {@link CoreModelGateway} admission 契约：在 {@code start} 返回之前不应有回调（两阶段激活：admission gate 仅通过
 * {@code handle.activate()} 打开，且 cancel-before-activate 会唤醒等待任务），提交前分类具有确定性（Rejected / Busy /
 * Indeterminate / throw）、terminal-once 回调桥接、错误类型分类，以及在 pre-task、pre-bind 和 post-bind 窗口中尽力取消。
 */
class CoreModelGatewayTest {

  private static final Duration BUSY_DELAY = Duration.ofSeconds(7);
  private static final ModelCallTimeoutPolicy TIMEOUT_POLICY =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(45), Duration.ofSeconds(3));
  private static final ModelGatewayConfig CONFIG = new ModelGatewayConfig(BUSY_DELAY);
  private static final ProviderRequest PROVIDER_REQUEST = providerRequest();
  private static final UUID INVOCATION_ID = new UUID(0L, 42L);
  private static final ModelInvocationRequest INVOCATION_REQUEST =
      new ModelInvocationRequest(
          new EnvironmentName("env-1"),
          PROVIDER_REQUEST,
          List.of(),
          List.of(),
          false,
          100_000,
          null);

  @Test
  void startReturnsStartedImmediatelyAndBridgesStreamCallbacks() throws Exception {
    ControlledProvider provider = new ControlledProvider();
    try (Fixture fixture = new Fixture(provider)) {
      long startedAt = System.nanoTime();
      ModelGateway.StartResult result = fixture.start();
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      ModelGateway.Started started = assertInstanceOf(ModelGateway.Started.class, result);
      assertNotNull(started.handle());
      assertTrue(elapsedMillis < 1_000L, "start must not wait for Provider I/O");
      // 两阶段激活：activate 之前 Provider 绝不能启动。
      assertNull(provider.request.get());
      started.handle().activate();
      provider.awaitStarted();

      ProviderStreamEvent.TextDelta first = new ProviderStreamEvent.TextDelta("hello");
      ProviderStreamEvent.ThinkingDelta second = new ProviderStreamEvent.ThinkingDelta("thinking");
      provider.emit(first);
      provider.emit(second);
      provider.complete();
      fixture.listener.awaitTerminal();

      assertEquals(List.of(first, second), fixture.listener.events);
      assertEquals(1, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.unknownCount.get());
      assertSame(provider.response, fixture.listener.succeeded.get());
    }
  }

  @Test
  void transportUsesEffectiveRequestFromResolutionNotTheOriginal() throws Exception {
    // 解析器把持久 request 的 cache control 按当前 capability 规范化后返回有效请求；transport 必须使用它。
    ProviderRequest effective =
        new ProviderRequest(
            PROVIDER_REQUEST.model(),
            PROVIDER_REQUEST.variant(),
            PROVIDER_REQUEST.messages(),
            PROVIDER_REQUEST.tools(),
            ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-effective-key"));
    ControlledProvider provider = new ControlledProvider();
    try (Fixture fixture =
        new Fixture(
            new Resolution(effective, ignored -> provider),
            provider,
            Executors.newSingleThreadExecutor())) {
      fixture.startAndActivate();
      provider.awaitStarted();

      assertSame(effective, provider.request.get());
      assertNotSame(PROVIDER_REQUEST, provider.request.get());
    }
  }

  @Test
  void forwardsFrozenRequestAndConfiguredTimeoutPolicyToProvider() throws Exception {
    ControlledProvider provider = new ControlledProvider();
    try (Fixture fixture = new Fixture(provider)) {
      fixture.startAndActivate();
      provider.awaitStarted();

      assertSame(PROVIDER_REQUEST, provider.request.get());
      assertSame(TIMEOUT_POLICY, fixture.resolution.openedPolicy.get());
      assertEquals(1, fixture.resolution.openCount.get());
    }
  }

  @Test
  void neverDeliversCallbacksBeforeStartReturnsEvenWhenProviderCallsSynchronously() {
    ControlledProvider provider = new ControlledProvider();
    provider.syncCompleteOnStart = true;
    provider.blockBeforeStreamBody = new CountDownLatch(1);
    try (Fixture fixture = new Fixture(provider)) {
      fixture.startAndActivate();
      provider.awaitStarted();

      // transport task 在越过 admission gate 后阻塞于 stream() 内；同步 onComplete 尚未投递，且 start() 已返回。
      assertEquals(0, fixture.listener.terminalCount());

      provider.blockBeforeStreamBody.countDown();
      fixture.listener.awaitTerminal();

      assertEquals(1, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.unknownCount.get());
    }
  }

  @Test
  void returnsBusyWithConfiguredDelayWhenExecutorRejectsSubmission() {
    try (Fixture fixture = new Fixture(new ControlledProvider(), new RejectingExecutor())) {
      ModelGateway.StartResult result = fixture.start();

      ModelGateway.Busy busy = assertInstanceOf(ModelGateway.Busy.class, result);
      assertEquals(BUSY_DELAY, busy.retryAfter());
      assertEquals(1, fixture.resolution.resolveCount.get());
      assertEquals(0, fixture.resolution.openCount.get());
      assertEquals(0, fixture.listener.terminalCount());
    }
  }

  @Test
  void returnsIndeterminateWhenSubmissionOutcomeIsAmbiguous() {
    try (Fixture fixture = new Fixture(new ControlledProvider(), new BrokenExecutor())) {
      ModelGateway.StartResult result = fixture.start();

      ModelGateway.Indeterminate indeterminate =
          assertInstanceOf(ModelGateway.Indeterminate.class, result);
      assertEquals(ProviderErrorKind.TRANSIENT, indeterminate.error().kind());
      assertTrue(indeterminate.error().message().contains("cannot be confirmed"));
      assertEquals(0, fixture.listener.terminalCount());
    }
  }

  @Test
  void rejectsDeterministicResolutionFailureWithInvalidRequest() {
    try (Fixture fixture = new Fixture(new ControlledProvider())) {
      fixture.resolution.resolveFailure =
          new IllegalArgumentException("provider not found: provider");

      ModelGateway.StartResult result = fixture.start();

      ModelGateway.Rejected rejected = assertInstanceOf(ModelGateway.Rejected.class, result);
      assertEquals(ProviderErrorKind.INVALID_REQUEST, rejected.error().kind());
      assertEquals("provider not found: provider", rejected.error().message());
      assertEquals(0, fixture.listener.terminalCount());
    }
  }

  @Test
  void propagatesTransientResolutionFailureForReschedule() {
    try (Fixture fixture = new Fixture(new ControlledProvider(), new CountingExecutor())) {
      fixture.resolution.resolveFailure = new IllegalStateException("provider database down");

      IllegalStateException failure = assertThrows(IllegalStateException.class, fixture::start);

      assertEquals("provider database down", failure.getMessage());
      // 只有构造探针到达 executor；transport task 从未提交。
      assertEquals(1, ((CountingExecutor) fixture.executor).executeCount.get());
      assertEquals(0, fixture.listener.terminalCount());
    }
  }

  @Test
  void classifiesProviderSetupFailuresAsInvalidRequestOnFailed() throws Exception {
    IllegalStateException createFailure = new IllegalStateException("cannot create client");
    try (Fixture fixture =
        new Fixture(
            new Resolution(
                ignored -> {
                  throw createFailure;
                }))) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
      assertTrue(fixture.listener.failed.get().message().contains("cannot create client"));
    }

    // opener 返回的 null provider 通过 setup-failure 路径体现为 INVALID_REQUEST。
    try (Fixture fixture = new Fixture(new Resolution(ignored -> null))) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
      assertEquals(0, fixture.listener.succeededCount.get());
    }
  }

  @Test
  void forwardsClassifiedProviderExceptionToOnFailed() throws Exception {
    ProviderException transientFailure =
        new ProviderException(ProviderErrorKind.TRANSIENT, "temporarily unavailable");
    ControlledProvider transientProvider = new ControlledProvider();
    transientProvider.classifiedStartFailure = transientFailure;
    try (Fixture fixture = new Fixture(transientProvider)) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(ProviderErrorKind.TRANSIENT, fixture.listener.failed.get().kind());
      assertEquals("temporarily unavailable", fixture.listener.failed.get().message());
    }

    ControlledProvider authProvider = new ControlledProvider();
    authProvider.classifiedStartFailure =
        new ProviderException(ProviderErrorKind.AUTHENTICATION, "invalid credential");
    try (Fixture fixture = new Fixture(authProvider)) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(ProviderErrorKind.AUTHENTICATION, fixture.listener.failed.get().kind());
      assertEquals("invalid credential", fixture.listener.failed.get().message());
    }
  }

  @Test
  void reportsUnclassifiedStreamFailureAsUnknownNotFakeFailure() throws Exception {
    ControlledProvider provider = new ControlledProvider();
    provider.startFailure = new IllegalStateException("adapter bug without classification");
    try (Fixture fixture = new Fixture(provider)) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(ProviderErrorKind.TRANSIENT, fixture.listener.unknown.get().kind());
      assertTrue(fixture.listener.unknown.get().message().contains("cannot be confirmed"));
      assertTrue(
          fixture.listener.unknown.get().message().contains("adapter bug without classification"));
    }
  }

  @Test
  void rejectsNullStreamAsInvalidRequest() throws Exception {
    ControlledProvider provider = new ControlledProvider();
    provider.returnNullStream = true;
    try (Fixture fixture = new Fixture(provider)) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
      assertTrue(fixture.listener.failed.get().message().contains("null stream"));
    }
  }

  @Test
  void rejectsNullCallbackPayloadsAndStreams() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.handler.get().onEvent(null, fixture.provider.stream);
      fixture.listener.awaitTerminal();
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.handler.get().onComplete(null, fixture.provider.stream);
      fixture.listener.awaitTerminal();
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.handler.get().onError(null, fixture.provider.stream);
      fixture.listener.awaitTerminal();
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.handler.get().onEvent(new ProviderStreamEvent.TextDelta("x"), null);
      fixture.listener.awaitTerminal();
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
    }
  }

  @Test
  void rejectsStreamReturnedAfterCallbackBoundADifferentStream() throws Exception {
    TestStream callbackStream = new TestStream();
    TestStream returnedStream = new TestStream();
    ModelProvider provider =
        (request, handler) -> {
          handler.onEvent(new ProviderStreamEvent.TextDelta("partial"), callbackStream);
          return returnedStream;
        };
    try (Fixture fixture = new Fixture(new Resolution(ignored -> provider))) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.failed.get().kind());
      assertEquals(1, fixture.listener.events.size());
    }
  }

  @Test
  void ignoresLateDeltasAndDuplicateTerminals() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "first failure"));
      fixture.listener.awaitTerminal();
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("late"));
      fixture.provider.complete();
      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "second failure"));
      fixture.awaitIdle();

      assertEquals(1, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.unknownCount.get());
      assertTrue(fixture.listener.events.isEmpty());
      assertEquals(1, fixture.listener.terminalCount());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.complete();
      fixture.listener.awaitTerminal();
      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "late failure"));
      fixture.awaitIdle();

      assertEquals(1, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(1, fixture.listener.terminalCount());
    }
  }

  @Test
  void reportsListenerDeltaFailureAsUnknownTerminal() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnEvent = true;
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.emit(new ProviderStreamEvent.TextDelta("unprocessable"));
      fixture.listener.awaitTerminal();
      fixture.provider.complete();
      fixture.awaitIdle();

      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertTrue(fixture.listener.unknown.get().message().contains("cannot be confirmed"));
    }
  }

  @Test
  void isolatesListenerTerminalFailuresWithoutSecondTerminal() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnSucceeded = true;
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.complete();
      fixture.listener.awaitTerminal();
      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "late"));
      fixture.awaitIdle();

      assertEquals(1, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnFailed = true;
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "failure"));
      fixture.listener.awaitTerminal();
      fixture.provider.complete();
      fixture.awaitIdle();

      assertEquals(1, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
    }
  }

  /**
   * 关键竞态 1：cancel-before-bind；Provider 同步 onComplete；bind 的 auto-cancel 同步重入 onError。FIFO 下
   * complete 获胜。
   */
  @Test
  void cancelBeforeBindWithSynchronousReentrantCancelCompletesWithFifoCompleteWinning()
      throws Exception {
    ControlledProvider provider = new ControlledProvider();
    provider.syncCompleteOnStart = true;
    provider.blockBeforeStreamBody = new CountDownLatch(1);
    // bind 的 auto-cancel 同步重入桥：onComplete 已先入队，FIFO 下 complete 获胜，重入的 error 被 terminal-once 丢弃。
    provider.stream.cancelHook =
        () ->
            provider
                .handler
                .get()
                .onError(
                    new ProviderException(ProviderErrorKind.TRANSIENT, "reentrant cancel failure"),
                    provider.stream);
    try (Fixture fixture = new Fixture(provider)) {
      ModelGateway.Started started = fixture.startAndActivate();
      provider.awaitStarted();
      // cancel-before-bind：transport task 阻塞在 stream() 内、stream 尚未绑定，handle 取消只记录意图。
      started.handle().cancel();
      provider.blockBeforeStreamBody.countDown();
      fixture.listener.awaitTerminal();
      fixture.awaitIdle();
      // 恰好一个 terminal（Succeeded），重入的 onError 绝不产生第二个回调；auto-cancel 恰好一次。
      assertEquals(1, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.unknownCount.get());
      assertEquals(1, fixture.listener.terminalCount());
      assertEquals(1, provider.stream.cancelCount.get());
    }
  }

  /** 关键竞态 2：cancel-before-bind；stream.cancel 启动独立回调线程并 join 它——回调线程只短暂获取桥 monitor 入队，绝不死锁。 */
  @Test
  void cancelBeforeBindWithCancelJoiningCallbackThreadCompletesWithoutDeadlock() throws Exception {
    ControlledProvider provider = new ControlledProvider();
    provider.blockBeforeStreamBody = new CountDownLatch(1);
    CountDownLatch callbackDone = new CountDownLatch(1);
    AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    provider.stream.cancelHook =
        () -> {
          Thread callback =
              new Thread(
                  () -> {
                    try {
                      provider
                          .handler
                          .get()
                          .onError(
                              new ProviderException(
                                  ProviderErrorKind.TRANSIENT, "async cancel failure"),
                              provider.stream);
                    } catch (Throwable failure) {
                      callbackFailure.set(failure);
                    } finally {
                      callbackDone.countDown();
                    }
                  },
                  "reentrant-cancel-callback");
          callback.start();
          try {
            callback.join(5_000L);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        };
    try (Fixture fixture = new Fixture(provider)) {
      ModelGateway.Started started = fixture.startAndActivate();
      provider.awaitStarted();
      started.handle().cancel();
      // 释放 stream()：transport task 绑定 stream 触发 auto-cancel；cancel join 回调线程——join 必须在超时内返回。
      provider.blockBeforeStreamBody.countDown();
      assertTrue(callbackDone.await(5L, TimeUnit.SECONDS), "cancel callback thread must complete");
      fixture.listener.awaitTerminal();
      fixture.awaitIdle();
      // 恰好一个 terminal：异步 cancel 回调是第一个（也是唯一一个）信号。
      assertEquals(1, fixture.listener.terminalCount());
      assertEquals(1, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertNull(callbackFailure.get());
    }
  }

  /** 关键竞态 3：并发 / 重复 complete+error 保持 terminal-once 且按 FIFO 排序（先入队者获胜）。 */
  @Test
  void concurrentCompleteAndErrorRemainTerminalOnceAndOrdered() throws Exception {
    for (int iteration = 0; iteration < 20; iteration++) {
      try (Fixture fixture = new Fixture()) {
        fixture.startAndActivate();
        fixture.provider.awaitStarted();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Thread completer =
            new Thread(
                () -> {
                  ready.countDown();
                  await(go, "race start");
                  fixture.provider.complete();
                },
                "race-complete");
        Thread failer =
            new Thread(
                () -> {
                  ready.countDown();
                  await(go, "race start");
                  fixture.provider.fail(
                      new ProviderException(ProviderErrorKind.TRANSIENT, "race failure"));
                },
                "race-error");
        completer.start();
        failer.start();
        await(ready, "race ready");
        go.countDown();
        completer.join(5_000L);
        failer.join(5_000L);
        assertFalse(completer.isAlive(), "completer thread must finish");
        assertFalse(failer.isAlive(), "failer thread must finish");
        fixture.listener.awaitTerminal();
        fixture.awaitIdle();
        // 恰好一个 terminal，且是两者之一；重复 terminal 一律被忽略。
        assertEquals(1, fixture.listener.terminalCount(), "iteration " + iteration);
        assertEquals(
            1,
            fixture.listener.succeededCount.get() + fixture.listener.failedCount.get(),
            "iteration " + iteration);
        fixture.provider.complete();
        fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "duplicate"));
        fixture.awaitIdle();
        assertEquals(1, fixture.listener.terminalCount(), "duplicate terminal must be ignored");
      }
    }
  }

  /** 关键竞态 4：terminal listener 抛异常只记录，绝不发出第二个 terminal 回调（含 UNKNOWN 自身）。 */
  @Test
  void throwingUnknownTerminalListenerStillConvergesExactlyOneTerminal() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnEvent = true;
      fixture.listener.throwOnUnknown = true;
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.emit(new ProviderStreamEvent.TextDelta("unprocessable"));
      fixture.listener.awaitTerminal();
      fixture.provider.complete();
      fixture.awaitIdle();

      // onEvent 失败选择第一个 terminal UNKNOWN；listener 拒绝该 UNKNOWN 只记录，绝不发出第二个 terminal。
      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(1, fixture.listener.terminalCount());
    }
  }

  /** 缓冲溢出：drainer 被阻塞时无界信号流被保守上限截断，确定性收敛恰好一次 UNKNOWN，绝无第二个 terminal。 */
  @Test
  void bufferedOverflowSelectsExactlyOneUnknownWithoutSecondTerminal() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.startAndActivate();
      fixture.provider.awaitStarted();
      CountDownLatch listenerEntered = new CountDownLatch(1);
      CountDownLatch releaseListener = new CountDownLatch(1);
      fixture.listener.eventEntered = listenerEntered;
      fixture.listener.eventGate = releaseListener;
      Thread spammer =
          new Thread(
              () -> {
                await(listenerEntered, "listener entered gate");
                for (int i = 0; i <= CoreModelGateway.MAX_BUFFERED_SIGNALS; i++) {
                  fixture.provider.emit(new ProviderStreamEvent.TextDelta("spam-" + i));
                }
                releaseListener.countDown();
              },
              "overflow-spammer");
      spammer.start();
      // 测试线程入队第一个 event 并成为 drainer：listener 阻塞在 gate 上，drainer 暂停，spammer 填满缓冲。
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("first"));
      // main 从 gate 恢复后 drainer 继续：队列已空且溢出已标记——确定性选择恰好一次 UNKNOWN。
      fixture.listener.awaitTerminal();
      fixture.awaitIdle();
      // 溢出确定性选择恰好一次 UNKNOWN；无 Succeeded / Failed；只有第一个 event 被投递。
      assertEquals(1, fixture.listener.terminalCount());
      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertTrue(fixture.listener.unknown.get().message().contains("exceeded"));
      assertEquals(1, fixture.listener.events.size());
    }
  }

  @Test
  void cancelBeforeTaskPreventsProviderStart() {
    PausedExecutor executor = new PausedExecutor();
    ControlledProvider provider = new ControlledProvider();
    try (Fixture fixture = new Fixture(provider, executor)) {
      executor.tasks.clear();
      ModelGateway.Started started = assertInstanceOf(ModelGateway.Started.class, fixture.start());

      // cancel-before-activate：唤醒等待的 transport task 使其中止——绝不启动 Provider、不泄漏等待线程。
      started.handle().cancel();
      executor.runAll();

      assertEquals(0, fixture.resolution.openCount.get());
      assertNull(provider.request.get());
      assertEquals(0, fixture.listener.terminalCount());
    }
  }

  @Test
  void cancelBeforeStreamBindCancelsTheDelayedStreamExactlyOnce() throws Exception {
    ControlledProvider provider = new ControlledProvider();
    provider.blockBeforeStreamBody = new CountDownLatch(1);
    try (Fixture fixture = new Fixture(provider)) {
      ModelGateway.Started started = fixture.startAndActivate();
      provider.awaitStarted();

      started.handle().cancel();
      provider.blockBeforeStreamBody.countDown();
      provider.stream.awaitCancelled();
      started.handle().cancel();

      assertEquals(1, provider.stream.cancelCount.get());
      assertEquals(0, fixture.listener.terminalCount());
    }
  }

  @Test
  void cancelAfterBindIsImmediateAndIdempotent() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ModelGateway.Started started = fixture.startAndActivate();
      fixture.provider.awaitStarted();
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("bind"));

      started.handle().cancel();
      started.handle().cancel();

      assertEquals(1, fixture.provider.stream.cancelCount.get());
    }
  }

  @Test
  void cancelAfterTerminalIsBestEffortAndIdempotent() throws Exception {
    try (Fixture fixture = new Fixture()) {
      ModelGateway.Started started = fixture.startAndActivate();
      fixture.provider.awaitStarted();
      fixture.provider.complete();
      fixture.listener.awaitTerminal();

      started.handle().cancel();
      started.handle().cancel();

      assertEquals(1, fixture.provider.stream.cancelCount.get());
      assertEquals(1, fixture.listener.succeededCount.get());
    }
  }

  @Test
  void autoCancelSwallowsStreamCancelFailuresButHandleCancelPropagates() throws Exception {
    TestStream throwingStream = new TestStream();
    throwingStream.cancelThrows.set(true);
    ControlledProvider provider = new ControlledProvider(throwingStream);
    provider.blockBeforeStreamBody = new CountDownLatch(1);
    try (Fixture fixture = new Fixture(provider, new CapturingExecutor())) {
      ModelGateway.Started started = fixture.startAndActivate();
      provider.awaitStarted();

      started.handle().cancel();
      provider.blockBeforeStreamBody.countDown();
      await(provider.stream.cancelAttempted, "delayed-bind auto-cancel attempt");
      fixture.awaitIdle();

      assertEquals(0, fixture.listener.terminalCount());
      CapturingExecutor executor = (CapturingExecutor) fixture.executor;
      assertEquals(0, executor.failures.size(), "delayed-bind auto-cancel must be swallowed");
    }

    try (Fixture fixture = new Fixture()) {
      ModelGateway.Started started = fixture.startAndActivate();
      fixture.provider.awaitStarted();
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("bind"));
      fixture.provider.stream.cancelThrows.set(true);

      assertThrows(IllegalStateException.class, started.handle()::cancel);
    }
  }

  @Test
  void admissionGateBlocksUntilOpenedAndAbortsInterruptedTask() throws Exception {
    CoreModelGateway.StartGate gate = new CoreModelGateway.StartGate();
    AtomicBoolean admitted = new AtomicBoolean();
    Thread interrupted = new Thread(() -> admitted.set(gate.awaitStartReturned()));
    interrupted.start();
    interrupted.interrupt();
    interrupted.join(5_000L);
    assertFalse(admitted.get(), "an interrupted task must abort before admission is committed");

    CoreModelGateway.StartGate opened = new CoreModelGateway.StartGate();
    AtomicBoolean admittedAfterOpen = new AtomicBoolean();
    Thread waiting = new Thread(() -> admittedAfterOpen.set(opened.awaitStartReturned()));
    waiting.start();
    opened.open();
    waiting.join(5_000L);
    assertTrue(admittedAfterOpen.get(), "the task must proceed once start() has returned");
  }

  @Test
  void cancelBeforeActivationWakesWaitingTaskWithoutLeak() throws Exception {
    CoreModelGateway.StartGate gate = new CoreModelGateway.StartGate();
    AtomicBoolean admitted = new AtomicBoolean(true);
    Thread waiting = new Thread(() -> admitted.set(gate.awaitStartReturned()));
    waiting.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (waiting.isAlive()
        && waiting.getState() != Thread.State.WAITING
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    // cancel-before-activate：gate.cancel 唤醒等待任务；任务必须中止（绝不启动 Provider）且线程不泄漏。
    gate.cancel();
    waiting.join(5_000L);
    assertFalse(waiting.isAlive(), "the waiting task must wake and terminate");
    assertFalse(admitted.get(), "a cancelled-before-activate task must abort");
  }

  @Test
  void interruptedTransportTaskDefersExactlyOneUnknownUntilActivation() {
    ControlledProvider provider = new ControlledProvider();
    try (Fixture fixture = new Fixture(provider, new InterruptingExecutor())) {
      ModelGateway.StartResult result = fixture.start();
      ModelGateway.Started started = assertInstanceOf(ModelGateway.Started.class, result);
      // 等待期被中断：任务已退出（execute 阻塞到任务线程 WAITING 后中断并 join），start 返回时无任何回调。
      assertEquals(0, fixture.listener.terminalCount());
      assertNull(provider.request.get(), "provider must never be touched");
      // 激活：执行已接受但 Provider 从未启动，结果无法确认——恰好一次 UNKNOWN；绝不打开 gate / 触碰 Provider。
      started.handle().activate();
      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(ProviderErrorKind.TRANSIENT, fixture.listener.unknown.get().kind());
      assertTrue(fixture.listener.unknown.get().message().contains("cannot be confirmed"));
      assertNull(provider.request.get(), "provider must never be touched");
    }
  }

  @Test
  void cancelAfterInterruptedTransportTaskKeepsSilent() {
    ControlledProvider provider = new ControlledProvider();
    try (Fixture fixture = new Fixture(provider, new InterruptingExecutor())) {
      ModelGateway.StartResult result = fixture.start();
      ModelGateway.Started started = assertInstanceOf(ModelGateway.Started.class, result);
      // 中断后 cancel-before-activate：延迟的 UNKNOWN 被丢弃，保持静默（无回调、无 Provider、无泄漏）。
      started.handle().cancel();
      started.handle().activate();
      assertEquals(0, fixture.listener.terminalCount(), "cancel-before-activate must stay silent");
      assertNull(provider.request.get(), "provider must never be touched");
    }
  }

  /** 确定性竞态：activate 在 defer 之前赢（任务在 defer 前被激活）——defer 必须立即投递恰好一次 UNKNOWN。 */
  @Test
  void activationWinningBeforeDeferDeliversExactlyOneUnknown() {
    CoreModelGateway.StartGate gate = new CoreModelGateway.StartGate();
    RecordingListener listener = new RecordingListener();
    CoreModelGateway.GatewayHandle handle = new CoreModelGateway.GatewayHandle(gate, listener);
    // 确定性顺序：先 activate（deferredFailure 尚不存在，只打开 gate），任务线程随后才 defer。
    handle.activate();
    handle.deferActivationFailure(
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT, "interrupted before activation; outcome unknown"));
    // activate 先赢：任务已死、Provider 从未启动，defer 立即收敛恰好一次 UNKNOWN。
    assertEquals(1, listener.unknownCount.get(), "activation-wins race must deliver one UNKNOWN");
    assertEquals(0, listener.succeededCount.get());
    assertEquals(0, listener.failedCount.get());
    assertEquals(ProviderErrorKind.TRANSIENT, listener.unknown.get().kind());
    // terminal-once / 幂等：重复 defer 与 activate 都绝不产生第二次投递。
    handle.deferActivationFailure(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "duplicate defer"));
    handle.activate();
    assertEquals(1, listener.unknownCount.get());
  }

  /** 取消仍压制延迟的激活失败：cancel 先到、或 defer 后 cancel，activate 都保持静默。 */
  @Test
  void cancellationSuppressesDeferredActivationFailure() {
    CoreModelGateway.StartGate gate = new CoreModelGateway.StartGate();
    RecordingListener listener = new RecordingListener();
    CoreModelGateway.GatewayHandle handle = new CoreModelGateway.GatewayHandle(gate, listener);
    handle.cancel();
    handle.deferActivationFailure(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "interrupted"));
    handle.activate();
    assertEquals(0, listener.terminalCount(), "cancel-before-defer must stay silent");

    CoreModelGateway.StartGate secondGate = new CoreModelGateway.StartGate();
    RecordingListener secondListener = new RecordingListener();
    CoreModelGateway.GatewayHandle secondHandle =
        new CoreModelGateway.GatewayHandle(secondGate, secondListener);
    secondHandle.deferActivationFailure(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "interrupted"));
    secondHandle.cancel();
    secondHandle.activate();
    assertEquals(0, secondListener.terminalCount(), "cancel-after-defer must stay silent");
  }

  /** unclassified provider 失败携带 adversarial getMessage：安全提取后仍恰好一次 UNKNOWN，terminal 回调绝不消失。 */
  @Test
  void unclassifiedProviderFailureWithAdversarialMessageConvergesExactlyOneUnknown() {
    ControlledProvider provider = new ControlledProvider();
    provider.startFailure =
        new RuntimeException("provider stream broken") {
          @Override
          public String getMessage() {
            throw new IllegalStateException("adversarial message");
          }
        };
    try (Fixture fixture = new Fixture(provider)) {
      fixture.startAndActivate();
      fixture.listener.awaitTerminal();

      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertTrue(fixture.listener.unknown.get().message().contains("cannot be confirmed"));
    }
  }

  /** listener 失败携带 adversarial getMessage：安全提取后仍恰好一次 UNKNOWN（已接受的执行绝不静默消失）。 */
  @Test
  void listenerFailureWithAdversarialMessageConvergesExactlyOneUnknown() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.listener.eventFailure =
          new RuntimeException("listener projection failed") {
            @Override
            public String getMessage() {
              throw new IllegalStateException("adversarial message");
            }
          };
      fixture.startAndActivate();
      fixture.provider.awaitStarted();

      fixture.provider.emit(new ProviderStreamEvent.TextDelta("unprocessable"));
      fixture.listener.awaitTerminal();
      fixture.provider.complete();
      fixture.awaitIdle();

      assertEquals(1, fixture.listener.unknownCount.get());
      assertEquals(0, fixture.listener.succeededCount.get());
      assertEquals(0, fixture.listener.failedCount.get());
      assertTrue(fixture.listener.unknown.get().message().contains("cannot be confirmed"));
    }
  }

  @Test
  void rejectsInlineExecutorsAtConstruction() {
    assertThrows(
        IllegalStateException.class,
        () -> new CoreModelGateway(new Resolution(ignored -> null), new InlineExecutor(), CONFIG));
    assertThrows(
        IllegalStateException.class,
        () ->
            new CoreModelGateway(
                new Resolution(ignored -> null),
                new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1),
                    new ThreadPoolExecutor.CallerRunsPolicy()),
                CONFIG));
  }

  @Test
  void rejectsSilentDiscardExecutorPoliciesAtConstruction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new CoreModelGateway(
                new Resolution(ignored -> null),
                new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1),
                    new ThreadPoolExecutor.DiscardPolicy()),
                CONFIG));
    assertThrows(
        IllegalStateException.class,
        () ->
            new CoreModelGateway(
                new Resolution(ignored -> null),
                new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1),
                    new ThreadPoolExecutor.DiscardOldestPolicy()),
                CONFIG));
  }

  @Test
  void startRejectsNullArguments() {
    try (Fixture fixture = new Fixture()) {
      assertThrows(
          NullPointerException.class,
          () ->
              fixture.subject.start(
                  new ModelGateway.Execution(INVOCATION_ID, 1, INVOCATION_REQUEST), null));
      assertThrows(NullPointerException.class, () -> fixture.subject.start(null, fixture.listener));
    }
  }

  private static ProviderRequest providerRequest() {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            "provider",
            "frozen-model",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            new ModelPricing(
                "USD",
                "batch",
                "priority",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    return new ProviderRequest(
        descriptor, variant, List.of(), List.of(), ProviderCacheControl.none());
  }

  private static ProviderResponse response() {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    return new ProviderResponse(
        "completed", "", List.of(), ProviderStopReason.COMPLETED, usage, cost, null, null, "{}");
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(5L, TimeUnit.SECONDS), description);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for " + description, failure);
    }
  }

  private static final class Fixture implements AutoCloseable {

    private final Resolution resolution;
    private final ExecutorService executor;
    private final RecordingListener listener = new RecordingListener();
    private final CoreModelGateway subject;
    private final ControlledProvider provider;

    private Fixture() {
      this(new ControlledProvider());
    }

    private Fixture(ControlledProvider provider) {
      this(provider, Executors.newSingleThreadExecutor());
    }

    private Fixture(ControlledProvider provider, ExecutorService executor) {
      this(new Resolution(ignored -> provider), provider, executor);
    }

    private Fixture(Resolution resolution) {
      this(resolution, null, Executors.newSingleThreadExecutor());
    }

    private Fixture(Resolution resolution, ControlledProvider provider, ExecutorService executor) {
      this.resolution = resolution;
      this.provider = provider == null ? new ControlledProvider() : provider;
      this.executor = executor;
      this.subject = new CoreModelGateway(resolution, executor, CONFIG);
    }

    private ModelGateway.StartResult start() {
      return subject.start(
          new ModelGateway.Execution(INVOCATION_ID, 1, INVOCATION_REQUEST), listener);
    }

    /** 两阶段激活的标准路径：start 返回 Started 后先 activate（打开 Gateway 回调 gate）再驱动 Provider。 */
    private ModelGateway.Started startAndActivate() {
      ModelGateway.Started started = assertInstanceOf(ModelGateway.Started.class, start());
      started.handle().activate();
      return started;
    }

    private void awaitIdle() throws Exception {
      executor.submit(() -> {}).get(5L, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }

  /** 假的 {@link ProviderResolutionService}，opener 与失败注入均可配置。 */
  private static final class Resolution implements ProviderResolutionService {

    private final AtomicInteger resolveCount = new AtomicInteger();
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicReference<ModelCallTimeoutPolicy> openedPolicy = new AtomicReference<>();
    private final Function<ModelCallTimeoutPolicy, ModelProvider> opener;
    private final ProviderRequest effectiveRequest;
    private volatile RuntimeException resolveFailure;

    private Resolution(Function<ModelCallTimeoutPolicy, ModelProvider> opener) {
      this(null, opener);
    }

    private Resolution(
        ProviderRequest effectiveRequest, Function<ModelCallTimeoutPolicy, ModelProvider> opener) {
      this.effectiveRequest = effectiveRequest;
      this.opener = opener;
    }

    @Override
    public ResolvedExecution resolve(ProviderRequest request) {
      resolveCount.incrementAndGet();
      if (resolveFailure != null) {
        throw resolveFailure;
      }
      // effectiveRequest 为空时原样透传，模拟"解析未改写请求"的路径。
      ProviderRequest effective = effectiveRequest == null ? request : effectiveRequest;
      return new ResolvedExecution(
          effective,
          TIMEOUT_POLICY,
          policy -> {
            openCount.incrementAndGet();
            openedPolicy.set(policy);
            return opener.apply(policy);
          });
    }
  }

  /** 假的 {@link ModelProvider}：可在 stream() 内阻塞、失败或同步交付。 */
  private static final class ControlledProvider implements ModelProvider {

    private final TestStream stream;
    private final AtomicReference<ProviderRequest> request = new AtomicReference<>();
    private final AtomicReference<ProviderStreamHandler> handler = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile CountDownLatch blockBeforeStreamBody;
    private volatile RuntimeException startFailure;
    private volatile ProviderException classifiedStartFailure;
    private volatile boolean returnNullStream;
    private volatile boolean syncCompleteOnStart;
    private final ProviderResponse response = response();

    private ControlledProvider() {
      this(new TestStream());
    }

    private ControlledProvider(TestStream stream) {
      this.stream = stream;
    }

    @Override
    public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
      this.request.set(request);
      this.handler.set(handler);
      started.countDown();
      if (blockBeforeStreamBody != null) {
        await(blockBeforeStreamBody, "provider stream body release");
      }
      if (classifiedStartFailure != null) {
        throw classifiedStartFailure;
      }
      if (startFailure != null) {
        throw startFailure;
      }
      if (syncCompleteOnStart) {
        handler.onComplete(response, stream);
      }
      return returnNullStream ? null : stream;
    }

    private void awaitStarted() {
      await(started, "provider stream start");
    }

    private void emit(ProviderStreamEvent event) {
      handler.get().onEvent(event, stream);
    }

    private void complete() {
      handler.get().onComplete(response, stream);
    }

    private void fail(ProviderException error) {
      handler.get().onError(error, stream);
    }
  }

  private static final class TestStream implements ProviderStream {

    private final AtomicInteger cancelCount = new AtomicInteger();
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private final CountDownLatch cancelAttempted = new CountDownLatch(1);
    private final AtomicBoolean cancelThrows = new AtomicBoolean();
    private volatile Runnable cancelHook;

    @Override
    public void cancel() {
      cancelAttempted.countDown();
      if (cancelThrows.get()) {
        throw new IllegalStateException("stream cancel failed");
      }
      cancelCount.incrementAndGet();
      cancelled.countDown();
      Runnable hook = cancelHook;
      if (hook != null) {
        hook.run();
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.getCount() == 0;
    }

    private void awaitCancelled() {
      await(cancelled, "stream cancel");
    }
  }

  private static final class RecordingListener implements ModelGateway.Listener {

    private final List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<ProviderResponse> succeeded = new AtomicReference<>();
    private final AtomicReference<ModelInvocationError> failed = new AtomicReference<>();
    private final AtomicReference<ModelInvocationError> unknown = new AtomicReference<>();
    private final AtomicInteger succeededCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final AtomicInteger unknownCount = new AtomicInteger();
    private final CountDownLatch terminal = new CountDownLatch(1);
    private volatile boolean throwOnEvent;
    private volatile RuntimeException eventFailure;
    private volatile boolean throwOnSucceeded;
    private volatile boolean throwOnFailed;
    private volatile boolean throwOnUnknown;
    private volatile CountDownLatch eventGate;
    private volatile CountDownLatch eventEntered;

    @Override
    public void onEvent(ProviderStreamEvent event) {
      CountDownLatch gate = eventGate;
      if (gate != null) {
        CountDownLatch entered = eventEntered;
        if (entered != null) {
          entered.countDown();
        }
        await(gate, "listener event gate");
      }
      RuntimeException failure = eventFailure;
      if (failure != null) {
        throw failure;
      }
      if (throwOnEvent) {
        throw new IllegalStateException("event projection failed");
      }
      events.add(event);
    }

    @Override
    public void onSucceeded(ProviderResponse response) {
      succeededCount.incrementAndGet();
      succeeded.set(response);
      terminal.countDown();
      if (throwOnSucceeded) {
        throw new IllegalStateException("completion persistence failed");
      }
    }

    @Override
    public void onFailed(ModelInvocationError error) {
      failedCount.incrementAndGet();
      failed.set(error);
      terminal.countDown();
      if (throwOnFailed) {
        throw new IllegalStateException("failure persistence failed");
      }
    }

    @Override
    public void onUnknown(ModelInvocationError error) {
      unknownCount.incrementAndGet();
      unknown.set(error);
      terminal.countDown();
      if (throwOnUnknown) {
        throw new IllegalStateException("unknown persistence failed");
      }
    }

    private int terminalCount() {
      return succeededCount.get() + failedCount.get() + unknownCount.get();
    }

    private void awaitTerminal() {
      await(terminal, "listener terminal");
    }
  }

  private abstract static class StubExecutorService extends AbstractExecutorService {

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }
  }

  private static final class RejectingExecutor extends StubExecutorService {

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("executor rejected submission");
    }
  }

  private static final class BrokenExecutor extends StubExecutorService {

    @Override
    public void execute(Runnable command) {
      throw new IllegalStateException("executor is broken");
    }
  }

  private static final class CountingExecutor extends StubExecutorService {

    private final AtomicInteger executeCount = new AtomicInteger();

    @Override
    public void execute(Runnable command) {
      executeCount.incrementAndGet();
    }
  }

  private static final class PausedExecutor extends StubExecutorService {

    private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private void runAll() {
      for (Runnable task : tasks) {
        task.run();
      }
    }
  }

  private static final class InlineExecutor extends StubExecutorService {

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }

  /**
   * 每个任务启动独立线程并阻塞 execute 直到任务线程进入 WAITING（admission gate 等待）后中断它并 join；用于确定性触发等待期中断 路径（start
   * 返回时任务已退出，激活时才投递延迟的 UNKNOWN）。
   */
  private static final class InterruptingExecutor extends StubExecutorService {

    @Override
    public void execute(Runnable command) {
      Thread thread = new Thread(command, "model-gateway-interrupting-executor");
      thread.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (thread.isAlive()
          && thread.getState() != Thread.State.WAITING
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** 在新线程上运行任务，并记录任何逃逸出的异常。 */
  private static final class CapturingExecutor extends StubExecutorService {

    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    @Override
    public void execute(Runnable command) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  command.run();
                } catch (Throwable failure) {
                  failures.add(failure);
                }
              });
      thread.setDaemon(true);
      thread.start();
    }
  }
}
