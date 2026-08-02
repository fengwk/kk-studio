package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class PostgresqlExecutionActivationListenerTest {

  @Test
  void rejectsInvalidIntervalsAndStopBeforeStartIsSafe() {
    DataSource dataSource = mock(DataSource.class);
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> new PostgresqlExecutionActivationListener(dataSource, dispatcher, 0L, 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PostgresqlExecutionActivationListener(dataSource, dispatcher, 1L, 0L));

    PostgresqlExecutionActivationListener listener =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 1L, 1L);
    listener.stop();
    verifyNoInteractions(dataSource, dispatcher);
  }

  @Test
  void pollsNullEmptyAndNonEmptyNotificationsAndIgnoresDuplicateStart() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PGConnection pgConnection = mock(PGConnection.class);
    Statement statement = mock(Statement.class);
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(connection.createStatement()).thenReturn(statement);
    AtomicReference<PostgresqlExecutionActivationListener> listener = new AtomicReference<>();
    AtomicInteger polls = new AtomicInteger();
    PGNotification notification = mock(PGNotification.class);
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            ignored ->
                switch (polls.incrementAndGet()) {
                  case 1 -> null;
                  case 2 -> new PGNotification[0];
                  case 3 -> new PGNotification[] {notification};
                  default -> {
                    listener.get().stop();
                    yield null;
                  }
                });
    PostgresqlExecutionActivationListener subject =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 1L, 1L);
    listener.set(subject);

    subject.start();
    subject.start();

    verify(dispatcher, timeout(2_000).times(2)).wake();
    assertTrue(await(() -> polls.get() >= 4), "listener must finish the controlled poll sequence");
    verify(dataSource).getConnection();
    verify(statement).execute("LISTEN " + PostgresqlExecutionActivationListener.CHANNEL);
    subject.stop();
  }

  @Test
  void reconnectsAfterAConnectionThatCannotUnwrapPostgresql() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection invalidConnection = mock(Connection.class);
    Connection validConnection = mock(Connection.class);
    PGConnection pgConnection = mock(PGConnection.class);
    Statement statement = mock(Statement.class);
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    when(dataSource.getConnection()).thenReturn(invalidConnection, validConnection);
    when(invalidConnection.isWrapperFor(PGConnection.class)).thenReturn(false);
    when(validConnection.isWrapperFor(PGConnection.class)).thenReturn(true);
    when(validConnection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(validConnection.createStatement()).thenReturn(statement);
    AtomicReference<PostgresqlExecutionActivationListener> listener = new AtomicReference<>();
    doAnswer(
            ignored -> {
              listener.get().stop();
              return null;
            })
        .when(dispatcher)
        .wake();
    PostgresqlExecutionActivationListener subject =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 1L, 1L);
    listener.set(subject);

    subject.start();

    verify(dispatcher, timeout(2_000)).wake();
    verify(dataSource, timeout(2_000).times(2)).getConnection();
    verify(pgConnection, never()).getNotifications(anyInt());
    subject.stop();
  }

  @Test
  void connectionFailureObservedAfterStopExitsWithoutReconnect() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    CountDownLatch connectionEntered = new CountDownLatch(1);
    CountDownLatch failureReturned = new CountDownLatch(1);
    when(dataSource.getConnection())
        .thenAnswer(
            ignored -> {
              connectionEntered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
              }
              failureReturned.countDown();
              throw new SQLException("connection failed after stop");
            });
    PostgresqlExecutionActivationListener listener =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 1L, 1L);

    listener.start();
    assertTrue(connectionEntered.await(2, TimeUnit.SECONDS));
    listener.stop();
    assertTrue(failureReturned.await(2, TimeUnit.SECONDS));

    Thread.sleep(20L);
    verify(dataSource).getConnection();
    verifyNoInteractions(dispatcher);
  }

  @Test
  void stopInterruptsReconnectBackoff() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    CountDownLatch connectionAttempted = new CountDownLatch(1);
    when(dataSource.getConnection())
        .thenAnswer(
            ignored -> {
              connectionAttempted.countDown();
              throw new SQLException("connection unavailable");
            });
    PostgresqlExecutionActivationListener listener =
        new PostgresqlExecutionActivationListener(dataSource, dispatcher, 1L, 10_000L);

    listener.start();
    assertTrue(connectionAttempted.await(2, TimeUnit.SECONDS));
    Thread loopThread = awaitListenerThread(Thread.State.TIMED_WAITING);
    listener.stop();
    loopThread.join(2_000L);

    assertFalse(loopThread.isAlive());
    verifyNoInteractions(dispatcher);
  }

  private static boolean await(Check check) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
    while (System.nanoTime() < deadline) {
      if (check.evaluate()) {
        return true;
      }
      Thread.sleep(5L);
    }
    return check.evaluate();
  }

  private static Thread awaitListenerThread(Thread.State state) throws InterruptedException {
    AtomicReference<Thread> found = new AtomicReference<>();
    assertTrue(
        await(
            () -> {
              Thread.getAllStackTraces().keySet().stream()
                  .filter(Thread::isAlive)
                  .filter(thread -> "execution-activation-listen".equals(thread.getName()))
                  .filter(thread -> thread.getState() == state)
                  .findFirst()
                  .ifPresent(found::set);
              return found.get() != null;
            }),
        "listener thread did not reach state " + state);
    return found.get();
  }

  @FunctionalInterface
  private interface Check {
    boolean evaluate();
  }
}
