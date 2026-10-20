package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupNotificationCodec;
import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 专用 realtime LISTEN 连接：注册 harness_realtime 与 harness_stream_catchup，独立启动、投递并按生命周期关闭。 */
class RealtimeNotificationLoopWiringTest {

  private static final UUID LOCAL = UUID.fromString("00000000-0000-0000-0000-000000000900");
  private static final UUID REMOTE = UUID.fromString("00000000-0000-0000-0000-000000000901");
  private static final UUID THREAD = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void dedicatedLoopListensOnlyRealtimeChannelAndDeliversToBus() throws Exception {
    RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
    RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            event -> {
              throw new AssertionError("remote event must not be re-published");
            },
            () -> false);
    RealtimeEvent event = modelDelta("remote");
    CountDownLatch delivered = new CountDownLatch(1);
    bus.subscribe(THREAD, ignored -> delivered.countDown(), () -> {});

    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    PGNotification notification = mock(PGNotification.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(notification.getName()).thenReturn(PostgresqlRealtimeEventSource.CHANNEL);
    when(notification.getParameter()).thenReturn(codec.encodeEvent(REMOTE, event));
    AtomicInteger polls = new AtomicInteger();
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation ->
                polls.getAndIncrement() == 0
                    ? new PGNotification[] {notification}
                    : new PGNotification[0]);
    List<String> executedSql = new CopyOnWriteArrayList<>();
    when(statement.execute(any(String.class)))
        .thenAnswer(
            invocation -> {
              executedSql.add(invocation.getArgument(0));
              return false;
            });

    HarnessStreamCatchupService catchupService = mock(HarnessStreamCatchupService.class);
    PostgresqlNotificationLoop loop =
        ApplicationEventConfiguration.createRealtimeNotificationLoop(
            dataSource, bus, catchupService, Duration.ofMillis(10), Duration.ofMillis(1));
    loop.start();
    assertTrue(delivered.await(5, TimeUnit.SECONDS));
    loop.close();

    // 专用 bean 必须实际启动自己的连接并 LISTEN 实时 + 跨节点追帧两个通道；关闭后循环不再运行。
    assertEquals(
        List.of(
            "LISTEN " + PostgresqlRealtimeEventSource.CHANNEL,
            "LISTEN " + HarnessStreamCatchupService.CHANNEL),
        executedSql);
    assertFalse(loop.isRunning());
    verify(connection).abort(any());
  }

  /** 追帧通知使用同一专用连接但独立 channel，交付给 catchup service；实时与追帧 handler 互不替代。 */
  @Test
  void dedicatedLoopDeliversCatchupNotificationToService() throws Exception {
    RealtimeNotificationCodec realtimeCodec = new RealtimeNotificationCodec();
    RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            realtimeCodec,
            new PostgresqlRealtimeEventSource(realtimeCodec),
            event -> {
              throw new AssertionError("remote event must not be re-published");
            },
            () -> false);
    HarnessStreamCatchupNotificationCodec catchupCodec =
        new HarnessStreamCatchupNotificationCodec();
    String payload =
        catchupCodec.encode(
            new HarnessStreamCatchupNotificationCodec.Envelope(
                REMOTE, THREAD, UUID.fromString("00000000-0000-0000-0000-000000000042"), 2));
    HarnessStreamCatchupService catchupService = mock(HarnessStreamCatchupService.class);

    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    PGNotification notification = mock(PGNotification.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(notification.getName()).thenReturn(HarnessStreamCatchupService.CHANNEL);
    when(notification.getParameter()).thenReturn(payload);
    AtomicInteger polls = new AtomicInteger();
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation ->
                polls.getAndIncrement() == 0
                    ? new PGNotification[] {notification}
                    : new PGNotification[0]);

    PostgresqlNotificationLoop loop =
        ApplicationEventConfiguration.createRealtimeNotificationLoop(
            dataSource, bus, catchupService, Duration.ofMillis(10), Duration.ofMillis(1));
    loop.start();
    verify(catchupService, timeout(5_000)).onNotification(payload);
    loop.close();
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        THREAD,
        UUID.fromString("00000000-0000-0000-0000-000000000042"),
        1,
        1L,
        new ProviderStreamEvent.TextDelta(text),
        Instant.parse("2026-08-05T00:00:00Z"));
  }
}
