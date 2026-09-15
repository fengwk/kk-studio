package fun.fengwk.kkstudio.harness.infra.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PostgresqlRealtimeEventSink} 的真实 PostgreSQL 契约：批量 NOTIFY 的顺序、分块边界、单条超限 RESYNC 降级，
 * 以及未提交事务绝不产生任何通知。
 *
 * <p>顺序断言是关键：批量 SQL 依赖 {@code unnest(...) with ordinality ... order by ord} 保证 {@code pg_notify}
 * 按输入顺序 被调用，PostgreSQL 又按发送顺序投递同一连接的通知——本测试在真实连接上验证这两级顺序叠加后仍然保序。
 */
class PostgresqlRealtimeEventSinkIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_realtime_sink_test");

  static {
    POSTGRES.start();
  }

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
  private DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private Connection listener;
  private PGConnection listenerNotifications;

  @BeforeEach
  void setUp() throws Exception {
    DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
    driverManagerDataSource.setDriverClassName(Driver.class.getName());
    driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
    driverManagerDataSource.setUsername(POSTGRES.getUsername());
    driverManagerDataSource.setPassword(POSTGRES.getPassword());
    dataSource = driverManagerDataSource;
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    listener = newConnection();
    listener.setAutoCommit(true);
    listenerNotifications = listener.unwrap(PGConnection.class);
    try (Statement statement = listener.createStatement()) {
      statement.execute("LISTEN " + PostgresqlRealtimeEventSource.CHANNEL);
    }
    drainNotifications();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (listener != null) {
      listener.close();
    }
  }

  /**
   * 单次有界批量的 N 个事件必须以输入顺序完整投递：证明 {@code order by ord} 与 PostgreSQL 的连接内发送顺序叠加后保序， 且每个通知仍是该事件自己的
   * canonical envelope。
   */
  @Test
  void batchDeliversEveryEnvelopeInInputOrder() throws Exception {
    CountingJdbcTemplate counting = new CountingJdbcTemplate(jdbcTemplate);
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(counting, codec);
    List<RealtimeEvent> events = modelDeltas(50);

    transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

    assertEquals(1, counting.executions, "one bounded batch must use a single SQL roundtrip");
    List<RealtimeNotificationCodec.Envelope> received = awaitNotifications(events.size());
    assertEnvelopes(received, events);
  }

  /** 超过事件数上界的批次拆成多个有界往返后，跨越 chunk 边界的全局顺序仍必须是输入顺序（不是按 chunk 重排或丢失）。 */
  @Test
  void batchSplittingByEventCountBoundPreservesGlobalOrder() throws Exception {
    CountingJdbcTemplate counting = new CountingJdbcTemplate(jdbcTemplate);
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(counting, codec);
    int total = PostgresqlRealtimeEventSink.MAX_BATCH_EVENTS + 7;
    List<RealtimeEvent> events = modelDeltas(total);

    transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

    assertEquals(2, counting.executions, "overflow must split into two bounded roundtrips");
    List<RealtimeNotificationCodec.Envelope> received = awaitNotifications(total);
    assertEnvelopes(received, events);
  }

  /** 超过编码字节上界的批次拆成多个有界往返；每个 chunk 内与整体顺序都保持输入顺序。 */
  @Test
  void batchSplittingByByteBoundPreservesOrder() throws Exception {
    CountingJdbcTemplate counting = new CountingJdbcTemplate(jdbcTemplate);
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(counting, codec);
    String filler = "x".repeat(7000);
    int total = 40;
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      events.add(modelDelta(filler + i, i + 1L));
    }
    int singleEventBytes =
        codec.encodeEvent(events.getFirst()).getBytes(StandardCharsets.UTF_8).length;
    assertTrue(singleEventBytes <= PostgresqlRealtimeEventSink.MAX_NOTIFICATION_BYTES);
    assertTrue((long) singleEventBytes * total > PostgresqlRealtimeEventSink.MAX_BATCH_BYTES);

    transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

    assertTrue(counting.executions > 1, "byte overflow must split into multiple roundtrips");
    List<RealtimeNotificationCodec.Envelope> received = awaitNotifications(total);
    assertEnvelopes(received, events);
  }

  /** 批量内单条超限事件只把该事件降级为 RESYNC，同批其余事件仍以完整 EVENT 顺序投递。 */
  @Test
  void oversizedEventInsideBatchDegradesToResyncWithoutLosingOthers() throws Exception {
    CountingJdbcTemplate counting = new CountingJdbcTemplate(jdbcTemplate);
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(counting, codec);
    RealtimeEvent.ModelDelta oversized = modelDelta("界".repeat(3000), 2L);
    RealtimeEvent.ModelDelta first = modelDelta("before", 1L);
    RealtimeEvent.ModelDelta last = modelDelta("after", 3L);

    transactionTemplate.executeWithoutResult(
        status -> sink.appendAll(List.of(first, oversized, last)));

    List<RealtimeNotificationCodec.Envelope> received = awaitNotifications(3);
    RealtimeNotificationCodec.Envelope.Event firstEvent =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Event.class, received.get(0));
    assertEquals(first, firstEvent.event());
    RealtimeNotificationCodec.Envelope.Resync resync =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Resync.class, received.get(1));
    assertEquals(oversized.threadId(), resync.threadId());
    assertEquals(PostgresqlRealtimeEventSink.OVERSIZE_REASON, resync.reason());
    RealtimeNotificationCodec.Envelope.Event lastEvent =
        assertInstanceOf(RealtimeNotificationCodec.Envelope.Event.class, received.get(2));
    assertEquals(last, lastEvent.event());
  }

  /** 未提交事务（回滚）绝不产生任何通知：批量 NOTIFY 与单条 NOTIFY 都只能在 COMMIT 后到达监听方。 */
  @Test
  void rolledBackBatchAndSingleAppendProduceNoNotifications() throws Exception {
    CountingJdbcTemplate counting = new CountingJdbcTemplate(jdbcTemplate);
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(counting, codec);

    assertThrowsRollback(
        () ->
            transactionTemplate.executeWithoutResult(
                status -> {
                  sink.appendAll(modelDeltas(5));
                  sink.append(modelDelta("single", 99L));
                  throw new IllegalStateException("rollback");
                }));

    assertEquals(
        0,
        countNotificationsWithin(Duration.ofSeconds(1)),
        "uncommitted notify must not be delivered");
  }

  /** 回滚后同一 channel 上的下一次已提交批量仍必须完整保序投递（事务语义不污染后续通知）。 */
  @Test
  void committedBatchAfterRollbackIsStillDeliveredInOrder() throws Exception {
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);

    assertThrowsRollback(
        () ->
            transactionTemplate.executeWithoutResult(
                status -> {
                  sink.appendAll(modelDeltas(3));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(0, countNotificationsWithin(Duration.ofSeconds(1)));

    List<RealtimeEvent> events = modelDeltas(3);
    transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

    assertEnvelopes(awaitNotifications(3), events);
  }

  /** Source 端到端：一次已提交批量后，订阅方按序收到全部事件且不触发任何 resync。 */
  @Test
  void committedBatchReachesSourceSubscribersWithoutResync() throws Exception {
    PostgresqlRealtimeEventSink sink = new PostgresqlRealtimeEventSink(jdbcTemplate, codec);
    UUID threadId = id(1L);
    List<RealtimeEvent> received = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    try (PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec)) {
      source.subscribe(threadId, received::add, resyncs::incrementAndGet);
      List<RealtimeEvent> events = modelDeltas(10);

      transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

      for (String payload : awaitPayloads(events.size())) {
        source.onNotification(payload);
      }
      assertEquals(events, received);
      assertEquals(0, resyncs.get(), "valid canonical notifications must not trigger resync");
    }
  }

  // ---------- helpers ----------

  private List<RealtimeEvent> modelDeltas(int count) {
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      events.add(modelDelta("delta-" + i, i));
    }
    return events;
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text, long sequence) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 1, sequence, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private void assertEnvelopes(
      List<RealtimeNotificationCodec.Envelope> received, List<RealtimeEvent> expected) {
    assertEquals(expected.size(), received.size());
    for (int i = 0; i < expected.size(); i++) {
      RealtimeNotificationCodec.Envelope.Event notification =
          assertInstanceOf(
              RealtimeNotificationCodec.Envelope.Event.class,
              received.get(i),
              "envelope " + i + " must stay a canonical EVENT");
      assertEquals(expected.get(i), notification.event(), "delivery order must match input order");
    }
  }

  /** 轮询真实监听连接直到收到 expected 条通知；超时即失败。 */
  private List<RealtimeNotificationCodec.Envelope> awaitNotifications(int expected)
      throws Exception {
    List<String> payloads = awaitPayloads(expected);
    List<RealtimeNotificationCodec.Envelope> received = new ArrayList<>();
    for (String payload : payloads) {
      received.add(codec.decode(payload));
    }
    return received;
  }

  /** 轮询真实监听连接直到收到 expected 条通知并返回其原始 payload（顺序即投递顺序）。 */
  private List<String> awaitPayloads(int expected) throws Exception {
    List<String> received = new ArrayList<>();
    long deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
    while (received.size() < expected) {
      if (System.nanoTime() > deadline) {
        fail("expected " + expected + " notifications but received " + received.size());
      }
      for (PGNotification notification : pollNotifications(200)) {
        assertEquals(PostgresqlRealtimeEventSource.CHANNEL, notification.getName());
        received.add(notification.getParameter());
      }
    }
    assertEquals(
        0,
        countNotificationsWithin(Duration.ofMillis(300)),
        "no extra notification must follow the expected batch");
    return received;
  }

  private int countNotificationsWithin(Duration window) throws Exception {
    int count = 0;
    long deadline = System.nanoTime() + window.toNanos();
    while (System.nanoTime() < deadline) {
      count += pollNotifications(50).length;
    }
    return count;
  }

  private PGNotification[] pollNotifications(int pollMillis) throws Exception {
    PGNotification[] notifications = listenerNotifications.getNotifications(pollMillis);
    return notifications == null ? new PGNotification[0] : notifications;
  }

  private void drainNotifications() throws Exception {
    while (pollNotifications(200).length > 0) {
      // 清空 setUp 之前可能残留的通知
    }
  }

  private static void assertThrowsRollback(Runnable action) {
    try {
      action.run();
      fail("transaction must be rolled back");
    } catch (IllegalStateException expected) {
      assertEquals("rollback", expected.getMessage());
    }
  }

  private Connection newConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** 委托真实 JdbcTemplate 并统计 SQL 往返次数，用于验证分块边界。 */
  private static final class CountingJdbcTemplate extends JdbcTemplate {

    private final JdbcTemplate delegate;
    private int executions;

    CountingJdbcTemplate(JdbcTemplate delegate) {
      this.delegate = delegate;
    }

    @Override
    public <T> T execute(String sql, PreparedStatementCallback<T> action) {
      executions++;
      return delegate.execute(sql, action);
    }
  }
}
