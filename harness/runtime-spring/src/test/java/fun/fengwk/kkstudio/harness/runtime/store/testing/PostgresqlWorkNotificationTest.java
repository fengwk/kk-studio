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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class PostgresqlWorkNotificationTest {

  private static final String WORK_CHANNEL = "harness_runtime_work";

  private HarnessStore store;
  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    dataSource = PostgresqlHarnessStoreFixture.dataSource();
  }

  @Test
  void workMutationsPublishOnlyCommittedAvailabilityHints() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(true);
      PGConnection notifications = connection.unwrap(PGConnection.class);
      statement.execute("LISTEN " + WORK_CHANNEL);

      assertThrows(
          IllegalStateException.class,
          () ->
              store.transaction(
                  tx -> {
                    tx.lockThread(baseline.threadId()).orElseThrow();
                    tx.requestWork(target, T0);
                    throw new IllegalStateException("rollback");
                  }));
      assertNoNotification(notifications);
      assertTrue(store.transaction(tx -> tx.findWork(target)).isEmpty());

      requestWork(baseline.threadId(), target, T0);
      assertNotification(notifications);

      ClaimedWork first =
          store
              .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T1, "lease-1", T5))
              .orElseThrow();
      assertNoNotification(notifications);
      inTransaction(store, tx -> tx.renewWork(first, T1, T5.plusSeconds(1)));
      assertNoNotification(notifications);

      requestWork(baseline.threadId(), target, T1);
      assertNotification(notifications);
      assertTrue(store.transaction(tx -> tx.completeWork(first, T2)).isPresent());
      assertNotification(notifications);

      ClaimedWork second =
          store
              .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T2, "lease-2", T5))
              .orElseThrow();
      assertNoNotification(notifications);
      inTransaction(store, tx -> tx.rescheduleWork(second, T2, T3));
      assertNotification(notifications);

      ClaimedWork third =
          store
              .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T3, "lease-3", T5))
              .orElseThrow();
      assertNoNotification(notifications);
      assertTrue(store.transaction(tx -> tx.completeWork(third, T4)).isEmpty());
      assertNoNotification(notifications);

      requestWork(baseline.threadId(), target, T4);
      assertNotification(notifications);
      boolean deleted =
          store.transaction(
              tx -> {
                tx.lockThread(baseline.threadId()).orElseThrow();
                return tx.deleteWork(target);
              });
      assertTrue(deleted);
      assertNoNotification(notifications);
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
    AtomicReference<DataAccessException> caught = new AtomicReference<>();

    DataAccessException thrown =
        assertThrows(
            DataAccessException.class,
            () ->
                failing.transaction(
                    tx -> {
                      tx.lockThread(baseline.threadId()).orElseThrow();
                      try {
                        tx.requestWork(target, T0);
                      } catch (DataAccessException error) {
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
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-1", T5))
            .orElseThrow();
    Work beforeFailure = durable.transaction(tx -> tx.findWork(target)).orElseThrow();
    HarnessStore failing = notifyFailingStore();
    AtomicReference<DataAccessException> caught = new AtomicReference<>();

    DataAccessException thrown =
        assertThrows(
            DataAccessException.class,
            () ->
                failing.transaction(
                    tx -> {
                      try {
                        tx.rescheduleWork(claim, T1, T2);
                      } catch (DataAccessException error) {
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
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-1", T5))
            .orElseThrow();
    inTransaction(
        durable,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, T1);
        });
    Work beforeFailure = durable.transaction(tx -> tx.findWork(target)).orElseThrow();
    HarnessStore failing = notifyFailingStore();
    AtomicReference<DataAccessException> caught = new AtomicReference<>();

    DataAccessException thrown =
        assertThrows(
            DataAccessException.class,
            () ->
                failing.transaction(
                    tx -> {
                      try {
                        tx.completeWork(claim, T2);
                      } catch (DataAccessException error) {
                        caught.set(error);
                      }
                      return null;
                    }));

    assertSame(caught.get(), thrown);
    assertEquals(beforeFailure, durable.transaction(tx -> tx.findWork(target)).orElseThrow());
  }

  private static HarnessStore notifyFailingStore() {
    DataSource failingDataSource =
        interceptNotifyPreparation(PostgresqlHarnessStoreFixture.dataSource());
    return new PostgresqlHarnessStore(
        failingDataSource,
        new DataSourceTransactionManager(failingDataSource),
        PostgresqlHarnessStoreFixture.idGenerator());
  }

  private static DataSource interceptNotifyPreparation(DataSource delegate) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              Object result = invoke(delegate, method, arguments);
              if (method.getName().equals("getConnection")
                  && result instanceof Connection connection) {
                return interceptNotifyPreparation(connection);
              }
              return result;
            });
  }

  private static Connection interceptNotifyPreparation(Connection delegate) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("prepareStatement")
                  && arguments != null
                  && arguments.length > 0
                  && arguments[0] instanceof String sql
                  && sql.contains("pg_notify")) {
                throw new SQLException("injected pg_notify preparation failure");
              }
              return invoke(delegate, method, arguments);
            });
  }

  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  private static void assertNotification(PGConnection connection) throws Exception {
    PGNotification[] notifications = connection.getNotifications(5_000);
    assertNotNull(notifications);
    assertTrue(notifications.length > 0);
    assertEquals(WORK_CHANNEL, notifications[0].getName());
    drainNotifications(connection);
  }

  private static void assertNoNotification(PGConnection connection) throws Exception {
    PGNotification[] notifications = connection.getNotifications(200);
    assertTrue(notifications == null || notifications.length == 0);
  }

  private static void drainNotifications(PGConnection connection) throws Exception {
    while (true) {
      PGNotification[] notifications = connection.getNotifications(1);
      if (notifications == null || notifications.length == 0) {
        return;
      }
    }
  }
}
