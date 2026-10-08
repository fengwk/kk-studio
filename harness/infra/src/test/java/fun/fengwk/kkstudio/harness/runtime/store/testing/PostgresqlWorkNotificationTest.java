package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSignal;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import javax.sql.DataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class PostgresqlWorkNotificationTest {

  private HarnessStore store;
  private DefaultNotificationBus bus;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    bus = PostgresqlHarnessStoreFixture.notificationBus();
  }

  @Test
  void workMutationsPublishOnlyCommittedAvailabilityHints() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    BlockingQueue<NotificationSignal> signals = new LinkedBlockingQueue<>();

    try (NotificationSubscription ignored =
        bus.subscribe(HarnessNotifications.WORK_AVAILABLE, signals::add, () -> {})) {

      assertThrows(
          IllegalStateException.class,
          () ->
              store.transaction(
                  tx -> {
                    tx.lockThread(baseline.threadId()).orElseThrow();
                    tx.requestWork(target, T0);
                    throw new IllegalStateException("rollback");
                  }));
      assertNoSignal(signals);
      assertTrue(store.transaction(tx -> tx.findWork(target)).isEmpty());

      requestWork(baseline.threadId(), target, T0);
      assertSignal(signals);

      ClaimedWork first =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, T1, "lease-1", Duration.between(T1, T5)))
              .orElseThrow();
      assertNoSignal(signals);
      inTransaction(store, tx -> tx.renewWork(first, T1, Duration.ofSeconds(5)));
      assertNoSignal(signals);

      requestWork(baseline.threadId(), target, T1);
      assertSignal(signals);
      assertTrue(store.transaction(tx -> tx.completeWork(first, T2)).isPresent());
      assertSignal(signals);

      ClaimedWork second =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, T2, "lease-2", Duration.between(T2, T5)))
              .orElseThrow();
      assertNoSignal(signals);
      inTransaction(store, tx -> tx.rescheduleWork(second, T2, Duration.ZERO));
      assertSignal(signals);

      ClaimedWork third =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, T3, "lease-3", Duration.ofSeconds(60)))
              .orElseThrow();
      assertNoSignal(signals);
      assertTrue(store.transaction(tx -> tx.completeWork(third, T4)).isEmpty());
      assertNoSignal(signals);

      requestWork(baseline.threadId(), target, T4);
      assertSignal(signals);
      boolean deleted =
          store.transaction(
              tx -> {
                tx.lockThread(baseline.threadId()).orElseThrow();
                return tx.deleteWork(target);
              });
      assertTrue(deleted);
      assertNoSignal(signals);
    }
  }

  @Test
  void caughtNotifyFailureStillFailsAndRollsBackEveryAvailabilityMutation() {
    assertCaughtNotifyFailureRollsBackRequest();
    assertCaughtNotifyFailureRollsBackReschedule();
    assertCaughtNotifyFailureRollsBackStaleCompletion();
  }

  private void requestWork(UUID threadId, WorkTarget target, Instant requestedAt) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(target, requestedAt);
        });
  }

  private static void assertCaughtNotifyFailureRollsBackRequest() {
    HarnessStore durable = PostgresqlHarnessStoreFixture.resetAndCreate();
    Baseline baseline = seedThreadBaseline(durable);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    HarnessStore failing = notifyFailingStore();
    AtomicReference<IllegalStateException> caught = new AtomicReference<>();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                failing.transaction(
                    tx -> {
                      tx.lockThread(baseline.threadId()).orElseThrow();
                      try {
                        tx.requestWork(target, T0);
                      } catch (IllegalStateException error) {
                        caught.set(error);
                      }
                      return null;
                    }));

    assertSame(caught.get(), thrown);
    assertTrue(durable.transaction(tx -> tx.findWork(target)).isEmpty());
  }

  private static void assertCaughtNotifyFailureRollsBackReschedule() {
    HarnessStore durable = PostgresqlHarnessStoreFixture.resetAndCreate();
    Baseline baseline = seedThreadBaseline(durable);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        durable,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T0);
        });
    ClaimedWork claim =
        durable
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-1", Duration.ofSeconds(60)))
            .orElseThrow();
    Work beforeFailure = durable.transaction(tx -> tx.findWork(target)).orElseThrow();
    HarnessStore failing = notifyFailingStore();
    AtomicReference<IllegalStateException> caught = new AtomicReference<>();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                failing.transaction(
                    tx -> {
                      try {
                        tx.rescheduleWork(claim, T1, Duration.ofSeconds(1));
                      } catch (IllegalStateException error) {
                        caught.set(error);
                      }
                      return null;
                    }));

    assertSame(caught.get(), thrown);
    assertEquals(beforeFailure, durable.transaction(tx -> tx.findWork(target)).orElseThrow());
  }

  private static void assertCaughtNotifyFailureRollsBackStaleCompletion() {
    HarnessStore durable = PostgresqlHarnessStoreFixture.resetAndCreate();
    Baseline baseline = seedThreadBaseline(durable);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        durable,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T0);
        });
    ClaimedWork claim =
        durable
            .transaction(
                tx ->
                    tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-1", Duration.ofSeconds(60)))
            .orElseThrow();
    inTransaction(
        durable,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T1);
        });
    Work beforeFailure = durable.transaction(tx -> tx.findWork(target)).orElseThrow();
    HarnessStore failing = notifyFailingStore();
    AtomicReference<IllegalStateException> caught = new AtomicReference<>();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                failing.transaction(
                    tx -> {
                      try {
                        tx.completeWork(claim, T2);
                      } catch (IllegalStateException error) {
                        caught.set(error);
                      }
                      return null;
                    }));

    assertSame(caught.get(), thrown);
    assertEquals(beforeFailure, durable.transaction(tx -> tx.findWork(target)).orElseThrow());
  }

  private static HarnessStore notifyFailingStore() {
    DefaultNotificationBus failingBus = PostgresqlHarnessStoreFixture.newBus();
    failingBus.close();
    DataSource dataSource = PostgresqlHarnessStoreFixture.dataSource();
    return new PostgresqlHarnessStore(
        dataSource,
        new DataSourceTransactionManager(dataSource),
        PostgresqlHarnessStoreFixture.idGenerator(),
        failingBus);
  }

  private static void assertSignal(BlockingQueue<NotificationSignal> queue) throws Exception {
    NotificationSignal signal = queue.poll(5, TimeUnit.SECONDS);
    assertNotNull(signal);
    assertEquals(NotificationSignal.CHANGED, signal);
    drainSignals(queue);
  }

  private static void assertNoSignal(BlockingQueue<NotificationSignal> queue) throws Exception {
    NotificationSignal signal = queue.poll(150, TimeUnit.MILLISECONDS);
    assertNull(signal);
  }

  private static void drainSignals(BlockingQueue<NotificationSignal> queue) throws Exception {
    while (queue.poll(50, TimeUnit.MILLISECONDS) != null) {}
  }
}
