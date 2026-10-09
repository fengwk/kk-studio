package fun.fengwk.kkstudio.harness.infra.realtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeNotificationCodec.Envelope;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.PostgresqlHarnessStoreFixture;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import javax.sql.DataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link BusRealtimeEventSink} 的真实 PostgreSQL 契约：批量发布的顺序、单条超限 RESYNC 降级，
 * 以及未提交事务绝不产生任何通知（只有提交后才本地投递）。
 */
class BusRealtimeEventSinkIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final int DEFAULT_MAX_BYTES = 7900;
  private static final String OVERSIZE_REASON = "EVENT_TOO_LARGE";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_realtime_sink_test");

  static {
    POSTGRES.start();
  }

  private DataSource dataSource;
  private TransactionTemplate transactionTemplate;
  private DefaultNotificationBus bus;
  private BlockingQueue<Envelope> received;
  private NotificationSubscription subscription;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
    driverManagerDataSource.setDriverClassName(Driver.class.getName());
    driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
    driverManagerDataSource.setUsername(POSTGRES.getUsername());
    driverManagerDataSource.setPassword(POSTGRES.getPassword());
    dataSource = driverManagerDataSource;
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    bus =
        new DefaultNotificationBus(
            dataSource,
            UUID.randomUUID(),
            PostgresqlHarnessStoreFixture.ALL_TOPICS,
            NotificationLimits.defaults(),
            Duration.ofMillis(50),
            Duration.ofMillis(50));
    received = new LinkedBlockingQueue<>();
    subscription = bus.subscribe(HarnessNotifications.REALTIME, received::add, () -> {});
  }

  @AfterEach
  void tearDown() {
    if (subscription != null) {
      subscription.close();
    }
    if (bus != null) {
      bus.close();
    }
  }

  /** 单次批量的 N 个事件必须以输入顺序完整投递：证明事务提交后在本地保序派发， 且每个通知仍是该事件自己的 canonical envelope。 */
  @Test
  void batchDeliversEveryEnvelopeInInputOrder() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);
    List<RealtimeEvent> events = modelDeltas(20);

    transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

    List<Envelope> notifications = awaitNotifications(events.size());
    assertEnvelopes(notifications, events);
  }

  /** 批量内单条超限事件只把该事件降级为 RESYNC，同批其余事件仍以完整 EVENT 顺序投递。 */
  @Test
  void oversizedEventInsideBatchDegradesToResyncWithoutLosingOthers() throws Exception {
    RealtimeEvent.ModelDelta normal1 = modelDelta("before", 1L);
    RealtimeEvent.ModelDelta oversized = modelDelta("超大".repeat(200), 2L);
    RealtimeEvent.ModelDelta normal2 = modelDelta("after", 3L);

    int normal1Bytes =
        HarnessNotifications.REALTIME.codec().encode(new Envelope.Event(normal1)).length;
    int normal2Bytes =
        HarnessNotifications.REALTIME.codec().encode(new Envelope.Event(normal2)).length;
    int limit = Math.max(normal1Bytes, normal2Bytes);

    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, limit);

    transactionTemplate.executeWithoutResult(
        status -> sink.appendAll(List.of(normal1, oversized, normal2)));

    List<Envelope> notifications = awaitNotifications(3);
    Envelope.Event firstEvent = assertInstanceOf(Envelope.Event.class, notifications.get(0));
    assertEquals(normal1, firstEvent.event());

    Envelope.Resync resync = assertInstanceOf(Envelope.Resync.class, notifications.get(1));
    assertEquals(oversized.threadId(), resync.threadId());
    assertEquals(OVERSIZE_REASON, resync.reason());

    Envelope.Event lastEvent = assertInstanceOf(Envelope.Event.class, notifications.get(2));
    assertEquals(normal2, lastEvent.event());
  }

  /** 未提交事务（回滚）绝不产生任何通知：批量与单条发布都只能在 COMMIT 后到达监听方。 */
  @Test
  void rolledBackBatchAndSingleAppendProduceNoNotifications() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);

    assertThrowsRollback(
        () ->
            transactionTemplate.executeWithoutResult(
                status -> {
                  sink.appendAll(modelDeltas(5));
                  sink.append(modelDelta("single", 99L));
                  throw new IllegalStateException("rollback");
                }));

    assertNoNotificationsWithin(Duration.ofMillis(500));
  }

  /** 回滚后同一 topic 上的下一次已提交批量仍必须完整保序投递（事务回滚不污染后续通知）。 */
  @Test
  void committedBatchAfterRollbackIsStillDeliveredInOrder() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);

    assertThrowsRollback(
        () ->
            transactionTemplate.executeWithoutResult(
                status -> {
                  sink.appendAll(modelDeltas(3));
                  throw new IllegalStateException("rollback");
                }));
    assertNoNotificationsWithin(Duration.ofMillis(500));

    List<RealtimeEvent> events = modelDeltas(3);
    transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

    assertEnvelopes(awaitNotifications(3), events);
  }

  /** Source 端到端：一次已提交批量后，订阅方按序收到全部事件；只有订阅恢复边界产生一次初始 resync，合法事件不再降级。 */
  @Test
  void committedBatchReachesSourceSubscribersWithoutResync() throws Exception {
    BusRealtimeEventSink sink = new BusRealtimeEventSink(bus, DEFAULT_MAX_BYTES);
    UUID threadId = id(1L);
    List<RealtimeEvent> receivedEvents = new CopyOnWriteArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    try (BusRealtimeEventSource source = new BusRealtimeEventSource()) {
      // 本地订阅必须先于总线订阅注册，总线订阅的初始对账标记才会命中该 Thread。
      source.subscribe(threadId, receivedEvents::add, resyncs::incrementAndGet);
      // 组合根在生产中由 NotificationSubscriptions 绑定这条唯一订阅；本测试显式持有并在 source 之前关闭。
      try (NotificationSubscription realtimeSubscription =
          bus.subscribe(HarnessNotifications.REALTIME, source::onEnvelope, source::onResync)) {
        List<RealtimeEvent> events = modelDeltas(5);

        transactionTemplate.executeWithoutResult(status -> sink.appendAll(events));

        // 等待事件通过 bus 派发到 source
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (receivedEvents.size() < events.size() && System.nanoTime() < deadline) {
          Thread.sleep(50);
        }
        assertEquals(events, receivedEvents);
        // 订阅自身的恢复标记是一次初始 resync；批次内合法 canonical 通知绝不额外触发 resync。
        assertEquals(1, resyncs.get(), "valid canonical notifications must not trigger resync");
      }
    }
  }

  // ---------- helpers ----------

  private List<RealtimeEvent> modelDeltas(int count) {
    List<RealtimeEvent> events = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      events.add(modelDelta("chunk-" + i, (long) i));
    }
    return events;
  }

  private RealtimeEvent.ModelDelta modelDelta(String text, long sequence) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 1, sequence, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private void assertEnvelopes(List<Envelope> receivedList, List<RealtimeEvent> expected) {
    assertEquals(expected.size(), receivedList.size());
    for (int i = 0; i < expected.size(); i++) {
      Envelope.Event notification =
          assertInstanceOf(
              Envelope.Event.class,
              receivedList.get(i),
              "envelope " + i + " must stay a canonical EVENT");
      assertEquals(expected.get(i), notification.event(), "delivery order must match input order");
    }
  }

  private List<Envelope> awaitNotifications(int expected) throws Exception {
    List<Envelope> result = new ArrayList<>();
    for (int i = 0; i < expected; i++) {
      Envelope env = received.poll(5, TimeUnit.SECONDS);
      if (env == null) {
        fail("expected " + expected + " notifications but received " + result.size());
      }
      result.add(env);
    }
    assertNull(
        received.poll(200, TimeUnit.MILLISECONDS),
        "no extra notification must follow expected batch");
    return result;
  }

  private void assertNoNotificationsWithin(Duration window) throws Exception {
    Envelope extra = received.poll(window.toMillis(), TimeUnit.MILLISECONDS);
    assertNull(extra, "expected no notification but received " + extra);
  }

  private static void assertThrowsRollback(Runnable action) {
    try {
      action.run();
      fail("transaction must be rolled back");
    } catch (IllegalStateException expected) {
      assertEquals("rollback", expected.getMessage());
    }
  }
}
