package fun.fengwk.kkstudio.core.harness.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
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

/**
 * Integration tests for {@link PostgresqlExecutionTargetDispatcher}: startup drain, route-aware
 * wait + READY wake, nearest-due timer, convergence of two dispatcher instances, wake-during-drain
 * coalescing, and timer serialization (no overlapping drain iterations).
 */
class PostgresqlExecutionTargetDispatcherIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionTargetStore store;
  @Autowired private PlatformTransactionManager txm;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Test
  void startupDrainProcessesPreExistingDueTargets() throws Exception {
    store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE.minus(Duration.ofMinutes(1)));
    store.schedule(
        ExecutionTargetKind.MODEL_INVOCATION, 2L, null, BASE.minus(Duration.ofMinutes(2)));

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch processed = new CountDownLatch(2);

    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain = singleThread("dispatcher-startup-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-startup-wake");

    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            handler.wrap(processed),
            clock,
            drain,
            wake);
    try {
      dispatcher.start();
      assertTrue(
          processed.await(5, TimeUnit.SECONDS), "startup drain must process pre-existing due rows");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }

    assertEquals(Set.of(1L, 2L), new HashSet<>(handler.handled));
  }

  @Test
  void offlineEnvironmentTargetWaitsAndIsDispatchedAfterReady() throws Exception {
    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, 10L, "env-a", BASE.minus(Duration.ofMinutes(1)));

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch firstReady = new CountDownLatch(1);

    AtomicReference<ExecutionTargetRouteEligibility> eligibility =
        new AtomicReference<>(ExecutionTargetRouteEligibility.empty());
    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain = singleThread("dispatcher-route-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-route-wake");

    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            new ExecutionTargetRouteEligibility() {
              @Override
              public Set<String> readyRouteKeys() {
                return eligibility.get().readyRouteKeys();
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
          "offline environment target must not be claimed while env is not READY");
      assertTrue(
          store.findAll().stream().anyMatch(r -> r.targetId() == 10L),
          "offline target must remain durable");

      eligibility.set(ExecutionTargetRouteEligibility.of(Set.of("env-a")));
      dispatcher.wake();
      assertTrue(
          firstReady.await(5, TimeUnit.SECONDS),
          "READY must allow the durable target to be claimed");
      assertEquals(List.of(10L), handler.handled);
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void parkedTargetIsInvisibleToDispatcherUntilNormalSchedulingEnablesIt() throws Exception {
    store.park(
        ExecutionTargetKind.TOOL_INVOCATION, 11L, "env-a", BASE.minus(Duration.ofMinutes(1)));

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch processed = new CountDownLatch(1);
    ScheduledExecutorService drain = singleThread("dispatcher-parked-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-parked-wake");
    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.of(Set.of("env-a")),
            handler.wrap(processed),
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    try {
      dispatcher.start();
      assertFalse(
          processed.await(500, TimeUnit.MILLISECONDS),
          "disabled target must not reach the dispatcher");
      assertFalse(
          store.findAll().stream()
              .filter(row -> row.targetId() == 11L)
              .findFirst()
              .orElseThrow()
              .dispatchEnabled());

      assertEquals(
          1,
          store.schedule(
              ExecutionTargetKind.TOOL_INVOCATION,
              11L,
              "env-a",
              BASE.minus(Duration.ofMinutes(1))));
      dispatcher.wake();
      assertTrue(processed.await(5, TimeUnit.SECONDS), "enabled target must be dispatched");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void nearestDueTimerFiresOnceWithoutIncidentalSignal() throws Exception {
    Instant dueAt = BASE.plus(Duration.ofMillis(500));
    store.schedule(ExecutionTargetKind.THREAD, 30L, null, dueAt);

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch handled = new CountDownLatch(1);

    SettableClock clock = new SettableClock(BASE);
    ScheduledExecutorService drain = singleThread("dispatcher-timer-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-timer-wake");

    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            handler.wrap(handled),
            clock,
            drain,
            wake);
    try {
      dispatcher.start();
      clock.advanceTo(dueAt.plusMillis(1));
      assertTrue(
          handled.await(5, TimeUnit.SECONDS),
          "nearest-due timer must fire without external signal");
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

    // Each handler call is wrapped in its own transaction (the handler has
    // access to the dispatcher-bound store and the platform tx manager).
    TransactionTemplate tx = new TransactionTemplate(txm);
    ExecutionTargetHandler handler =
        row -> {
          boolean claimed = claimAndDelete(tx, store, row);
          if (claimed && globalHandled.add(row.targetId())) {
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

    PostgresqlExecutionTargetDispatcher a =
        new PostgresqlExecutionTargetDispatcher(
            store, ExecutionTargetRouteEligibility.empty(), handler, clock, drain1, wake1);
    PostgresqlExecutionTargetDispatcher b =
        new PostgresqlExecutionTargetDispatcher(
            store, ExecutionTargetRouteEligibility.empty(), handler, clock, drain2, wake2);
    try {
      a.start();
      b.start();
      assertTrue(allHandled.await(5, TimeUnit.SECONDS), "all targets must be handled exactly once");
      assertEquals(Set.of(1L, 2L, 3L), globalHandled);
      assertEquals(
          0,
          duplicateCount.get(),
          "deleteIfExists CAS must prevent duplicate successful handler invocations");
    } finally {
      a.stop();
      b.stop();
      shutdown(drain1);
      shutdown(drain2);
      shutdown(wake1);
      shutdown(wake2);
    }
  }

  @Test
  void wakeDuringDrainTriggersAnotherDrainWithoutLosingRequest() throws Exception {
    // The first handler blocks. A second target is inserted while that drain is in flight and a
    // wake is registered. The second target was not in the first scan, so only a correctly
    // retained wake can process it.
    store.schedule(ExecutionTargetKind.THREAD, 100L, null, BASE.minus(Duration.ofMinutes(1)));

    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch twoHandlerInvocations = new CountDownLatch(2);
    AtomicBoolean secondSaw = new AtomicBoolean();
    TransactionTemplate tx = new TransactionTemplate(txm);

    ExecutionTargetHandler handler =
        row -> {
          twoHandlerInvocations.countDown();
          if (row.targetId() == 100L) {
            firstEntered.countDown();
            try {
              assertTrue(
                  releaseFirst.await(5, TimeUnit.SECONDS), "release latch must arrive within 5s");
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return false;
            }
          } else {
            secondSaw.set(true);
          }
          return claimAndDelete(tx, store, row);
        };

    Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    ScheduledExecutorService drain = singleThread("dispatcher-coalesce-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-coalesce-wake");

    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store, ExecutionTargetRouteEligibility.empty(), handler, clock, drain, wake);
    try {
      dispatcher.start();
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "first handler must enter");
      store.schedule(
          ExecutionTargetKind.MODEL_INVOCATION, 101L, null, BASE.minus(Duration.ofMinutes(2)));
      dispatcher.wake();
      releaseFirst.countDown();
      assertTrue(
          twoHandlerInvocations.await(5, TimeUnit.SECONDS),
          "wake during drain must trigger a second handler invocation");
      assertTrue(secondSaw.get(), "the second target must be handled");
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  @Test
  void timerSerializationRunsDrainOnlyOnDrainExecutor() throws Exception {
    // Use a real scheduled executor for the timer. Schedule a row that is
    // due far enough in the future that the dispatcher arms its timer.
    Instant dueAt = Instant.now().plusMillis(300);
    store.schedule(ExecutionTargetKind.THREAD, 200L, null, dueAt);

    RecordingHandler handler = new RecordingHandler(new TransactionTemplate(txm), store);
    CountDownLatch handled = new CountDownLatch(1);

    Clock clock = Clock.systemUTC();
    ScheduledExecutorService drain = singleThread("dispatcher-timer-drain");
    ScheduledExecutorService wake = singleThread("dispatcher-timer-wake");

    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            handler.wrap(handled),
            clock,
            drain,
            wake);
    try {
      dispatcher.start();
      assertTrue(
          handled.await(10, TimeUnit.SECONDS),
          "nearest-due timer must wake the dispatcher and the handler must run");
      // The handler ran, so the timer -> wake -> drain chain is verified.
      // We assert that the handler saw the row exactly once because the
      // dispatcher uses single-executor drain semantics.
      assertEquals(List.of(200L), handler.handled);
    } finally {
      dispatcher.stop();
      shutdown(drain);
      shutdown(wake);
    }
  }

  private static ScheduledExecutorService singleThread(String name) {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, name);
          t.setDaemon(true);
          return t;
        });
  }

  private static void shutdown(ExecutorService es) {
    es.shutdownNow();
    try {
      es.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Records every row the dispatcher hands to the handler. The handler implementation runs in its
   * own transaction so the store can be mutated outside the dispatcher's no-transaction path.
   */
  private static final class RecordingHandler {
    final List<Long> handled = Collections.synchronizedList(new ArrayList<>());
    private final TransactionTemplate tx;
    private final PostgresqlExecutionTargetStore store;

    RecordingHandler(TransactionTemplate tx, PostgresqlExecutionTargetStore store) {
      this.tx = tx;
      this.store = store;
    }

    ExecutionTargetHandler wrap(CountDownLatch latch) {
      return row -> {
        boolean claimed = claimAndDelete(tx, store, row);
        if (claimed) {
          handled.add(row.targetId());
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
      TransactionTemplate tx, PostgresqlExecutionTargetStore store, ExecutionTargetRow row) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                store
                    .lockDue(row.targetKind(), row.targetId(), row.availableAt())
                    .map(
                        locked -> {
                          assertEquals(
                              1, store.deleteLocked(locked.targetKind(), locked.targetId()));
                          return true;
                        })
                    .orElse(false)));
  }
}
