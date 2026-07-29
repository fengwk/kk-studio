package fun.fengwk.kkstudio.core.harness.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import javax.sql.DataSource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Integration tests for {@link PostgresqlExecutionTargetListener}: NOTIFY reaches the dispatcher,
 * and the listener holds a single long-lived connection across multiple wake events (no reconnect
 * churn).
 */
class PostgresqlExecutionTargetListenerIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionTargetStore store;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager txm;

  @Test
  void notifyReachesDispatcherAndWakesIt() throws Exception {
    CountDownLatch handlerInvoked = new CountDownLatch(1);

    ScheduledExecutorService drain =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "listener-it-drain");
              t.setDaemon(true);
              return t;
            });
    ScheduledExecutorService wake =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "listener-it-wake");
              t.setDaemon(true);
              return t;
            });

    TransactionTemplate tx = new TransactionTemplate(txm);
    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            row -> {
              boolean deleted = claimAndDelete(tx, store, row);
              if (deleted) {
                handlerInvoked.countDown();
              }
              return deleted;
            },
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    // Short poll so the test runs quickly.
    PostgresqlExecutionTargetListener listener =
        new PostgresqlExecutionTargetListener(dataSource, dispatcher, 100L, 200L);
    try {
      dispatcher.start();
      listener.start();

      // Pre-existing due row also exercises the startup wake.
      store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE.minus(Duration.ofMinutes(1)));

      assertTrue(
          handlerInvoked.await(5, TimeUnit.SECONDS),
          "listener must wake the dispatcher on insert NOTIFY");
    } finally {
      listener.stop();
      dispatcher.stop();
      drain.shutdownNow();
      wake.shutdownNow();
    }
  }

  @Test
  void listenerHoldsSingleConnectionAcrossMultipleWakeEvents() throws Exception {
    ScheduledExecutorService drain =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "listener-churn-drain");
              t.setDaemon(true);
              return t;
            });
    ScheduledExecutorService wake =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "listener-churn-wake");
              t.setDaemon(true);
              return t;
            });

    CountingDataSource countingDs = new CountingDataSource(dataSource);
    TransactionTemplate tx = new TransactionTemplate(txm);

    PostgresqlExecutionTargetDispatcher dispatcher =
        new PostgresqlExecutionTargetDispatcher(
            store,
            ExecutionTargetRouteEligibility.empty(),
            row -> claimAndDelete(tx, store, row),
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    // We replace the dispatcher's dataSource view with our counting one
    // for the listener's perspective only.
    PostgresqlExecutionTargetListener listener =
        new PostgresqlExecutionTargetListener(countingDs, dispatcher, 200L, 500L);
    try {
      dispatcher.start();
      listener.start();
      // Generate several NOTIFY events spaced apart.
      for (int i = 0; i < 5; i++) {
        store.schedule(
            ExecutionTargetKind.THREAD, 1000L + i, null, BASE.minus(Duration.ofMinutes(1)));
        Thread.sleep(150);
      }
      // Allow the listener to drain a few notifications.
      Thread.sleep(800);
      assertEquals(
          1,
          countingDs.count(),
          "listener must hold a single connection for its lifetime; got " + countingDs.count());
    } finally {
      listener.stop();
      dispatcher.stop();
      drain.shutdownNow();
      wake.shutdownNow();
    }
  }

  private static boolean claimAndDelete(
      TransactionTemplate tx, PostgresqlExecutionTargetStore store, ExecutionTargetRow row) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                store
                    .lockDue(row.targetKind(), row.targetId(), row.availableAt())
                    .map(locked -> store.deleteLocked(locked.targetKind(), locked.targetId()) == 1)
                    .orElse(false)));
  }

  /** Wraps a {@link DataSource} to count {@code getConnection()} invocations. */
  private static final class CountingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger connections = new AtomicInteger();

    CountingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    int count() {
      return connections.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
      connections.incrementAndGet();
      return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      connections.incrementAndGet();
      return delegate.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {}

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {}

    @Override
    public int getLoginTimeout() throws SQLException {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return false;
    }
  }
}
