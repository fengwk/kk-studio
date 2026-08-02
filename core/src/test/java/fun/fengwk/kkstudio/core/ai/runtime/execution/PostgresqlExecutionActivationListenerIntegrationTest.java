package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import javax.sql.DataSource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
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

/** 验证 PostgreSQL NOTIFY 到 dispatcher 的唤醒链路和 listener 连接复用。 */
class PostgresqlExecutionActivationListenerIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionActivationStore store;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager txm;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Test
  void notifyReachesDispatcherAndWakesIt() throws Exception {
    CountDownLatch handlerInvoked = new CountDownLatch(1);

    ScheduledExecutorService drain =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "listener-it-drain");
              thread.setDaemon(true);
              return thread;
            });
    ScheduledExecutorService wake =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "listener-it-wake");
              thread.setDaemon(true);
              return thread;
            });

    TransactionTemplate tx = new TransactionTemplate(txm);
    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            activation -> {
              boolean deleted = claimAndDelete(tx, store, activation);
              if (deleted) {
                handlerInvoked.countDown();
              }
              return deleted;
            },
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    PostgresqlExecutionActivationListener listener =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 100L, 200L);
    try {
      dispatcher.start();
      listener.start();
      store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE.minus(Duration.ofMinutes(1)));

      assertTrue(handlerInvoked.await(5, TimeUnit.SECONDS), "NOTIFY 必须唤醒 dispatcher");
    } finally {
      listener.stop();
      dispatcher.stop();
      drain.shutdownNow();
      wake.shutdownNow();
    }
  }

  @Test
  void successiveNotificationsEachWakeDispatcher() throws Exception {
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    PostgresqlExecutionActivationListener listener =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 100L, 200L);
    try {
      listener.start();
      verify(dispatcher, timeout(5_000).times(1)).wake();
      clearInvocations(dispatcher);

      notifyExecutionActivation();
      verify(dispatcher, timeout(5_000).times(1)).wake();

      notifyExecutionActivation();
      verify(dispatcher, timeout(5_000).times(2)).wake();
    } finally {
      listener.stop();
    }
  }

  @Test
  void listenerHoldsSingleConnectionAcrossMultipleWakeEvents() throws Exception {
    ScheduledExecutorService drain =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "listener-churn-drain");
              thread.setDaemon(true);
              return thread;
            });
    ScheduledExecutorService wake =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "listener-churn-wake");
              thread.setDaemon(true);
              return thread;
            });

    CountingDataSource countingDataSource = new CountingDataSource(dataSource);
    TransactionTemplate tx = new TransactionTemplate(txm);

    PostgresqlExecutionActivationDispatcher dispatcher =
        new PostgresqlExecutionActivationDispatcher(
            store,
            ExecutionActivationEnvironmentEligibility.empty(),
            activation -> claimAndDelete(tx, store, activation),
            Clock.fixed(BASE, ZoneOffset.UTC),
            drain,
            wake);
    PostgresqlExecutionActivationListener listener =
        new PostgresqlExecutionActivationListener(countingDataSource, dispatcher, 200L, 500L);
    try {
      dispatcher.start();
      listener.start();
      for (int index = 0; index < 5; index++) {
        store.schedule(
            ExecutionTargetKind.THREAD, 1000L + index, null, BASE.minus(Duration.ofMinutes(1)));
        Thread.sleep(150);
      }
      Thread.sleep(800);
      assertEquals(1, countingDataSource.count(), "listener 生命周期内必须复用单连接");
    } finally {
      listener.stop();
      dispatcher.stop();
      drain.shutdownNow();
      wake.shutdownNow();
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
                    .map(locked -> store.deleteLocked(locked.targetKind(), locked.targetId()) == 1)
                    .orElse(false)));
  }

  private void notifyExecutionActivation() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "select pg_notify('" + PostgresqlExecutionActivationListener.CHANNEL + "', 'test')");
    }
  }

  /** 只统计 DataSource 建立连接的次数。 */
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
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
