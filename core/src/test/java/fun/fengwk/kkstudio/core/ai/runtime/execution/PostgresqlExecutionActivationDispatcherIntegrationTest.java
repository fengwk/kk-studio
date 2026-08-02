package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证分发器的启动扫描、Environment READY、最近 wakeAt 和并发收敛行为。 */
class PostgresqlExecutionActivationDispatcherIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionActivationStore store;
  @Autowired private PlatformTransactionManager txm;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Test
  void startupDrainProcessesPreExistingDueActivations() throws Exception {
    store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE.minus(Duration.ofMinutes(1)));
    store.schedule(
        ExecutionTargetKind.MODEL_INVOCATION, 2L, null, BASE.minus(Duration.ofMinutes(2)));

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch processed = new CountDownLatch(2);

    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain = singleThread("dispatcher-startup-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-startup-wake");

    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            handler.wrap(processed),
            clock,
            drain,
            wake);
    try {
      dispatcher.start();
      assertTrue(processed.await(5, TimeUnit.SECONDS), "启动 drain 必须处理已有 due 激活");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }

    assertEquals(Set.of(1L, 2L), new HashSet<>(handler.handled));
  }

  @Test
  void offlineEnvironmentActivationWaitsAndIsDispatchedAfterReady() throws Exception {
    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, 10L, "env-a", BASE.minus(Duration.ofMinutes(1)));

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch firstReady = new CountDownLatch(1);

    AtomicReference<ExecutionActivationEnvironmentEligibility> eligibility =
        new AtomicReference<>(ExecutionActivationEnvironmentEligibility.empty());
    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain = singleThread("dispatcher-environment-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-environment-wake");

    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            new ExecutionActivationEnvironmentEligibility() {
              @Override
              public Set<String> readyEnvironmentNames() {
                return eligibility.get().readyEnvironmentNames();
              }
            },
            handler.wrap(firstReady),
            clock,
            drain,
            wake);
    try {
      dispatcher.start();
      assertFalse(
          firstReady.await(500, TimeUnit.MILLISECONDS),
          "Environment 未 READY 时不得领取 durable activation");
      assertTrue(
          store.findAll().stream().anyMatch(activation -> activation.targetId() == 10L),
          "未 READY 的 activation 必须保留");

      eligibility.set(ExecutionActivationEnvironmentEligibility.of(Set.of("env-a")));
      dispatcher.wake();
      assertTrue(firstReady.await(5, TimeUnit.SECONDS), "READY 后必须允许领取 durable activation");
      assertEquals(List.of(10L), handler.handled);
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void parkedActivationIsInvisibleUntilExplicitActivation() throws Exception {
    store.park(
        ExecutionTargetKind.TOOL_INVOCATION, 11L, "env-a", BASE.minus(Duration.ofMinutes(1)));

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch processed = new CountDownLatch(1);
    ScheduledExecutorService drain = singleThread("dispatcher-parked-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-parked-wake");
    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.of(Set.of("env-a")),
            handler.wrap(processed),
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    try {
      dispatcher.start();
      assertFalse(processed.await(500, TimeUnit.MILLISECONDS), "PARKED activation 不得进入 dispatcher");
      assertEquals(
          ActivationState.PARKED,
          store.findAll().stream()
              .filter(activation -> activation.targetId() == 11L)
              .findFirst()
              .orElseThrow()
              .activationState());

      assertEquals(
          0,
          store.schedule(
              ExecutionTargetKind.TOOL_INVOCATION,
              11L,
              "env-a",
              BASE.minus(Duration.ofMinutes(1))));
      assertFalse(
          processed.await(500, TimeUnit.MILLISECONDS), "schedule 不得隐式唤醒已有 PARKED activation");

      new TransactionTemplate(txm)
          .execute(
              status -> {
                store.lock(ExecutionTargetKind.TOOL_INVOCATION, 11L).orElseThrow();
                return store.activateLocked(
                    ExecutionTargetKind.TOOL_INVOCATION, 11L, BASE.minus(Duration.ofMinutes(1)));
              });
      dispatcher.wake();
      assertTrue(processed.await(5, TimeUnit.SECONDS), "显式 activate 后必须分发");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void nearestWakeTimerFiresWithoutIncidentalSignal() throws Exception {
    Instant wakeAt = BASE.plus(Duration.ofMillis(500));
    store.schedule(ExecutionTargetKind.THREAD, 30L, null, wakeAt);

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch handled = new CountDownLatch(1);

    SettableClock clock = new SettableClock(BASE);
    ScheduledExecutorService drain = singleThread("dispatcher-timer-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-timer-wake");

    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            handler.wrap(handled),
            clock,
            drain,
            wake);
    try {
      dispatcher.start();
      clock.advanceTo(wakeAt.plusMillis(1));
      assertTrue(handled.await(5, TimeUnit.SECONDS), "最近 wakeAt 定时器必须触发分发");
      assertEquals(List.of(30L), handler.handled);
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void twoDispatchersConvergeWithoutDuplicateSuccessfulHandling() throws Exception {
    store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE.minus(Duration.ofMinutes(1)));
    store.schedule(ExecutionTargetKind.THREAD, 2L, null, BASE.minus(Duration.ofMinutes(2)));
    store.schedule(ExecutionTargetKind.THREAD, 3L, null, BASE.minus(Duration.ofMinutes(3)));

    Set<Long> globalHandled = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicateCount = new AtomicInteger();
    CountDownLatch allHandled = new CountDownLatch(3);

    TransactionTemplate tx = new TransactionTemplate(txm);
    ExecutionActivationHandler handler =
        activation -> {
          boolean claimed = claimAndDelete(tx, store, activation);
          if (claimed && globalHandled.add(activation.targetId())) {
            allHandled.countDown();
            return true;
          }
          if (claimed) {
            duplicateCount.incrementAndGet();
          }
          return false;
        };

    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain1 = singleThread("dispatcher-converge-1-drain");
    ScheduledExecutorService wake1 = singleThread("dispatcher-converge-1-wake");
    ScheduledExecutorService drain2 = singleThread("dispatcher-converge-2-drain");
    ScheduledExecutorService wake2 = singleThread("dispatcher-converge-2-wake");

    PostgresqlExecutionActivationDispatcher first =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            handler,
            clock,
            drain1,
            wake1);
    PostgresqlExecutionActivationDispatcher second =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            handler,
            clock,
            drain2,
            wake2);
    try {
      first.start();
      second.start();
      assertTrue(allHandled.await(5, TimeUnit.SECONDS), "所有 activation 必须只成功处理一次");
      assertEquals(Set.of(1L, 2L, 3L), globalHandled);
      assertEquals(0, duplicateCount.get());
    } finally {
      first.stop();
      second.stop();
      shutdown(drain1);
      shutdown(drain2);
      shutdown(wake1);
      shutdown(wake2);
    }
  }

  @Test
  void wakeDuringDrainTriggersAnotherDrain() throws Exception {
    store.schedule(ExecutionTargetKind.THREAD, 100L, null, BASE.minus(Duration.ofMinutes(1)));

    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch twoHandlerInvocations = new CountDownLatch(2);
    AtomicBoolean secondSaw = new AtomicBoolean();
    TransactionTemplate tx = new TransactionTemplate(txm);

    ExecutionActivationHandler handler =
        activation -> {
          twoHandlerInvocations.countDown();
          if (activation.targetId() == 100L) {
            firstEntered.countDown();
            try {
              assertTrue(releaseFirst.await(5, TimeUnit.SECONDS), "release latch must arrive");
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              return false;
            }
          } else {
            secondSaw.set(true);
          }
          return claimAndDelete(tx, store, activation);
        };

    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain = singleThread("dispatcher-coalesce-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-coalesce-wake");

    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store, ExecutionActivationEnvironmentEligibility.empty(), handler, clock, drain, wake);
    try {
      dispatcher.start();
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "首条 handler 必须进入");
      store.schedule(
          ExecutionTargetKind.MODEL_INVOCATION, 101L, null, BASE.minus(Duration.ofMinutes(2)));
      dispatcher.wake();
      releaseFirst.countDown();
      assertTrue(twoHandlerInvocations.await(5, TimeUnit.SECONDS), "drain 内唤醒不得丢失");
      assertTrue(secondSaw.get(), "第二条 activation 必须被处理");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void timerSerializationRunsDrainOnlyOnDrainExecutor() throws Exception {
    Instant wakeAt = Instant.now().plusMillis(300);
    store.schedule(ExecutionTargetKind.THREAD, 200L, null, wakeAt);

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch handled = new CountDownLatch(1);
    ScheduledExecutorService drain = singleThread("dispatcher-timer-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-timer-wake");

    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            handler.wrap(handled),
            Clock.systemUTC(),
            drain,
            wake);
    try {
      dispatcher.start();
      assertTrue(handled.await(10, TimeUnit.SECONDS), "最近 wakeAt 定时器必须唤醒 dispatcher");
      assertEquals(List.of(200L), handler.handled);
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void handlerFailureDoesNotDiscardOtherDueWork() throws Exception {
    store.schedule(ExecutionTargetKind.THREAD, 300L, null, BASE.minus(Duration.ofMinutes(1)));
    store.schedule(
        ExecutionTargetKind.MODEL_INVOCATION, 301L, null, BASE.minus(Duration.ofMinutes(2)));

    AtomicInteger failingAttempts = new AtomicInteger();
    CountDownLatch completed = new CountDownLatch(2);
    TransactionTemplate tx = new TransactionTemplate(txm);
    ExecutionActivationHandler handler =
        activation -> {
          if (activation.targetId() == 300L && failingAttempts.getAndIncrement() == 0) {
            throw new IllegalStateException("transient activation handler failure");
          }
          boolean claimed = claimAndDelete(tx, store, activation);
          if (claimed) {
            completed.countDown();
          }
          return claimed;
        };
    ScheduledExecutorService drain = singleThread("dispatcher-handler-failure-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-handler-failure-wake");
    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            handler,
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    try {
      dispatcher.start();
      assertTrue(completed.await(5, TimeUnit.SECONDS), "失败 handler 不得丢失其他 due work");
      assertEquals(2, failingAttempts.get(), "失败 activation 必须被定时重试");
      assertTrue(store.findAll().isEmpty(), "两条 activation 最终都应被领取");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  private static ScheduledExecutorService singleThread(String name) {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void shutdown(ExecutorService executor) {
    executor.shutdownNow();
    try {
      executor.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class RecordingHandler {

    final List<Long> handled = Collections.synchronizedList(new ArrayList<>());
    private final TransactionTemplate tx;
    private final PostgresqlExecutionActivationStore store;

    RecordingHandler(TransactionTemplate tx, PostgresqlExecutionActivationStore store) {
      this.tx = tx;
      this.store = store;
    }

    ExecutionActivationHandler wrap(CountDownLatch latch) {
      return activation -> {
        boolean claimed = claimAndDelete(tx, store, activation);
        if (claimed) {
          handled.add(activation.targetId());
          latch.countDown();
        }
        return claimed;
      };
    }
  }

  private static final class SettableClock extends Clock {

    private volatile Instant now;

    SettableClock(Instant start) {
      this.now = start;
    }

    void advanceTo(Instant target) {
      this.now = target;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static boolean claimAndDelete(
      TransactionTemplate tx,
      PostgresqlExecutionActivationStore store,
      ExecutionActivation activation) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                store
                    .lockDue(activation.targetKind(), activation.targetId(), activation.wakeAt())
                    .map(
                        locked -> {
                          assertEquals(
                              1, store.deleteLocked(locked.targetKind(), locked.targetId()));
                          return true;
                        })
                    .orElse(false)));
  }
}
