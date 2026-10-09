package fun.fengwk.kkstudio.notification;

import static fun.fengwk.kkstudio.notification.NotificationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationPacket;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real PostgreSQL boundaries, with SQL counting and controlled connection-failure injection. */
@Testcontainers
class NotificationPostgresqlIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  @Test
  void nodeTargetUsesZeroNotifySqlAndWaitsForPhysicalCommit() throws Exception {
    CountingDataSource dataSource = dataSource();
    TransactionTemplate tx = transactions(dataSource);
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    try (DefaultNotificationBus bus = bus(dataSource)) {
      bus.subscribe(EVENTS, received::add, () -> {});
      tx.executeWithoutResult(
          status -> {
            bus.publish(EVENTS, NotificationAddress.node(bus.nodeId()), "commit");
            assertTrue(received.isEmpty(), "no pre-commit local delivery");
            assertEquals(0, dataSource.notifies.get());
          });
      assertEquals("commit", take(received));
      tx.executeWithoutResult(
          status -> {
            bus.publish(EVENTS, NotificationAddress.node(bus.nodeId()), "rollback");
            status.setRollbackOnly();
          });
      bus.publish(EVENTS, NotificationAddress.node(bus.nodeId()), "barrier");
      assertEquals("barrier", take(received));
      assertTrue(received.isEmpty());
      assertEquals(0, dataSource.notifies.get());
    }
  }

  @Test
  void requiredInnerBoundaryDoesNotDeliverOnOuterRollback() throws Exception {
    CountingDataSource dataSource = dataSource();
    TransactionTemplate outer = transactions(dataSource);
    TransactionTemplate inner = transactions(dataSource);
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String table = "fact_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("create table " + table + " (value text)");
    try (DefaultNotificationBus bus = bus(dataSource)) {
      bus.subscribe(EVENTS, received::add, () -> {});
      outer.executeWithoutResult(
          status -> {
            inner.executeWithoutResult(
                nested -> {
                  jdbc.update("insert into " + table + " values (?)", "rolled-back");
                  bus.publish(EVENTS, NotificationAddress.broadcast(), "rolled-back");
                });
            assertTrue(received.isEmpty());
            assertEquals(1, dataSource.notifies.get(), "PG SQL must run before physical commit");
            status.setRollbackOnly();
          });
      assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
      bus.publish(EVENTS, NotificationAddress.node(bus.nodeId()), "barrier");
      assertEquals("barrier", take(received));
      assertTrue(received.isEmpty());
    } finally {
      jdbc.execute("drop table " + table);
    }
  }

  @Test
  void twoNodesBroadcastTargetAndUnicodeDeliverOnlyToIntendedNodeOnce() throws Exception {
    CountingDataSource source = dataSource();
    CountingDataSource remote = dataSource();
    BlockingQueue<String> a = new LinkedBlockingQueue<>();
    BlockingQueue<String> b = new LinkedBlockingQueue<>();
    CountDownLatch readyA = new CountDownLatch(1);
    CountDownLatch readyB = new CountDownLatch(1);
    try (DefaultNotificationBus busA = bus(source);
        DefaultNotificationBus busB = bus(remote)) {
      busA.subscribe(EVENTS, a::add, readyA::countDown);
      busB.subscribe(EVENTS, b::add, readyB::countDown);
      busA.start();
      busB.start();
      await(readyA);
      await(readyB);
      String unicode = "通知😀".repeat(1500);
      transactions(source)
          .executeWithoutResult(
              status -> busA.publish(EVENTS, NotificationAddress.broadcast(), unicode));
      assertEquals(unicode, take(a));
      assertEquals(unicode, take(b));
      busA.publish(EVENTS, NotificationAddress.node(busB.nodeId()), "remote-only");
      assertEquals("remote-only", take(b));
      busB.publish(EVENTS, NotificationAddress.node(busA.nodeId()), "a-only");
      assertEquals("a-only", take(a));
      // Ordered sentinel proves own PG echo did not produce a second local delivery.
      transactions(source)
          .executeWithoutResult(
              status -> busA.publish(EVENTS, NotificationAddress.broadcast(), "barrier"));
      assertEquals("barrier", take(a));
      assertEquals("barrier", take(b));
      assertTrue(a.isEmpty());
      assertTrue(b.isEmpty());
      assertEquals(3, source.notifies.get(), "one SQL batch per logical transaction message");
    }
  }

  @Test
  void ownEchoBeforeAfterCommitCannotBecomeLocalSource() throws Exception {
    CountingDataSource source = dataSource();
    CountingDataSource remote = dataSource();
    BlockingQueue<String> a = new LinkedBlockingQueue<>();
    BlockingQueue<String> b = new LinkedBlockingQueue<>();
    CountDownLatch readyA = new CountDownLatch(1);
    CountDownLatch readyB = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (DefaultNotificationBus busA = bus(source);
        DefaultNotificationBus busB = bus(remote)) {
      busA.subscribe(EVENTS, a::add, readyA::countDown);
      busB.subscribe(EVENTS, b::add, readyB::countDown);
      busA.start();
      busB.start();
      await(readyA);
      await(readyB);
      Thread publisher =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      transactions(source)
                          .executeWithoutResult(
                              status -> {
                                TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                      @Override
                                      public void afterCommit() {
                                        committed.countDown();
                                        hold(release);
                                      }
                                    });
                                busA.publish(EVENTS, NotificationAddress.broadcast(), "committed");
                              });
                    } catch (Throwable error) {
                      failure.set(error);
                    }
                  });
      try {
        await(committed);
        assertEquals("committed", take(b));
        // Wait for the publisher's reader to process its own PG frame, using a later remote frame.
        busB.publish(EVENTS, NotificationAddress.node(busA.nodeId()), "reader-barrier");
        assertEquals("reader-barrier", take(a));
        assertTrue(a.isEmpty(), "own echo must not substitute for afterCommit");
      } finally {
        release.countDown();
        publisher.join(5000);
      }
      assertFalse(publisher.isAlive());
      assertNull(failure.get());
      assertEquals("committed", take(a));
      assertTrue(a.isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  void identicalTransactionHintsMergeLocallyAndRemotelyWithoutMessageIdDefeatingPgSemantics()
      throws Exception {
    CountingDataSource source = dataSource();
    CountingDataSource remote = dataSource();
    BlockingQueue<String> a = new LinkedBlockingQueue<>();
    BlockingQueue<String> b = new LinkedBlockingQueue<>();
    CountDownLatch readyA = new CountDownLatch(1);
    CountDownLatch readyB = new CountDownLatch(1);
    try (DefaultNotificationBus busA = bus(source);
        DefaultNotificationBus busB = bus(remote)) {
      busA.subscribe(HINTS, a::add, readyA::countDown);
      busB.subscribe(HINTS, b::add, readyB::countDown);
      busA.start();
      busB.start();
      await(readyA);
      await(readyB);
      transactions(source)
          .executeWithoutResult(
              status ->
                  busA.publishBatch(
                      HINTS,
                      NotificationAddress.broadcast(),
                      List.of("same", "same", "same", "end")));
      assertEquals("same", take(a));
      assertEquals("end", take(a));
      assertEquals("same", take(b));
      assertEquals("end", take(b));
      assertTrue(a.isEmpty());
      assertTrue(b.isEmpty());
      assertEquals(2, source.notifies.get());
    }
    // Establish the PG fact being preserved: identical channel+payload in a transaction folds to
    // one.
    try (Connection listener = source.getConnection();
        Statement listen = listener.createStatement();
        Connection publisher = source.getConnection();
        Statement send = publisher.createStatement()) {
      listen.execute("LISTEN native_hint_test");
      publisher.setAutoCommit(false);
      send.execute("select pg_notify('native_hint_test', 'same')");
      send.execute("select pg_notify('native_hint_test', 'same')");
      publisher.commit();
      assertEquals(1, listener.unwrap(PGConnection.class).getNotifications(5000).length);
    }
  }

  @Test
  void swallowedNotifySqlFailurePoisonsPhysicalTransactionAndNeverDelivers() throws Exception {
    CountingDataSource source = dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(source);
    String role = "notify_denied_" + UUID.randomUUID().toString().replace("-", "");
    String table = "notify_fact_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("create role " + role);
    jdbc.execute("create table " + table + " (value text)");
    jdbc.execute("revoke execute on function pg_catalog.pg_notify(text, text) from public");
    BlockingQueue<String> values = new LinkedBlockingQueue<>();
    try (DefaultNotificationBus bus = bus(source)) {
      bus.subscribe(EVENTS, values::add, () -> {});
      assertThrows(
          UnexpectedRollbackException.class,
          () ->
              transactions(source)
                  .executeWithoutResult(
                      status -> {
                        jdbc.update("insert into " + table + " values (?)", "never");
                        jdbc.execute("set local role " + role);
                        // A real pg_notify permission error must poison the same physical fact
                        // transaction.
                        assertThrows(
                            RuntimeException.class,
                            () -> bus.publish(EVENTS, NotificationAddress.broadcast(), "never"));
                      }));
      assertTrue(values.isEmpty());
      assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
      bus.publish(EVENTS, NotificationAddress.node(bus.nodeId()), "barrier");
      assertEquals("barrier", take(values));
    } finally {
      jdbc.execute("grant execute on function pg_catalog.pg_notify(text, text) to public");
      jdbc.execute("drop table " + table);
      jdbc.execute("drop role " + role);
    }
  }

  @Test
  void reconnectListensBeforeResyncAndCloseAbortsLongReaderPoll() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<String> values = new LinkedBlockingQueue<>();
    BlockingQueue<Integer> resyncs = new LinkedBlockingQueue<>();
    AtomicInteger count = new AtomicInteger();
    DefaultNotificationBus bus =
        new DefaultNotificationBus(
            source,
            UUID.randomUUID(),
            List.of(EVENTS),
            smallLimits(8),
            Duration.ofSeconds(5),
            Duration.ofMillis(10));
    try (bus) {
      bus.subscribe(EVENTS, values::add, () -> resyncs.add(count.incrementAndGet()));
      // 订阅先于 LISTEN 建连：订阅本身即完成一次权威恢复。
      assertEquals(1, take(resyncs));
      bus.start();
      // LISTEN 建连完成后必须再做一次权威对账。
      assertEquals(2, take(resyncs));
      JdbcTemplate jdbc = new JdbcTemplate(source);
      Integer pid =
          jdbc.queryForObject(
              "select pid from pg_stat_activity where application_name = ? and query like 'LISTEN %'",
              Integer.class, source.applicationName);
      assertTrue(jdbc.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid));
      // Disconnection and LISTEN-completed recovery both schedule authoritative reconciliation.
      while (take(resyncs) < 3 || !bus.healthy()) {
        // The next control marker is generated only once the new LISTEN has succeeded.
      }
      NotificationPacket frame = wire(UUID.randomUUID(), bus.nodeId(), "after-reconnect");
      jdbc.queryForObject(
          "select pg_notify(?, ?)",
          Object.class,
          PgTransport.inboxChannel(bus.nodeId()),
          NotificationCarrier.chunk(frame, 0).encode());
      assertEquals("after-reconnect", take(values));
      long started = System.nanoTime();
      bus.close();
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
      assertFalse(bus.healthy());
      assertThrows(IllegalStateException.class, bus::start);
      assertThrows(
          IllegalStateException.class,
          () -> bus.publish(EVENTS, NotificationAddress.broadcast(), "closed"));
    }
  }

  @Test
  void invalidBatchIsRejectedBeforeAnySqlAndUnknownTopicIsRejected() {
    CountingDataSource source = dataSource();
    try (DefaultNotificationBus bus = bus(source)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> bus.publish(EVENTS, NotificationAddress.broadcast(), "x".repeat(20001)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              bus.publishBatch(
                  EVENTS, NotificationAddress.broadcast(), List.of("ok", "x".repeat(20001))));
      assertThrows(
          IllegalArgumentException.class,
          () -> bus.publish(topic("unknown.topic", false), NotificationAddress.broadcast(), "x"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              bus.publishBatch(
                  EVENTS, NotificationAddress.broadcast(), Collections.nCopies(9, "x")));
      assertEquals(0, source.notifies.get());
    }
  }

  @Test
  void idleListenerValidationFailureInvalidatesThenRelistensBeforeRecovery() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<Boolean> healthAtRecovery = new LinkedBlockingQueue<>();
    BlockingQueue<String> values = new LinkedBlockingQueue<>();
    AtomicReference<PgTransport> reference = new AtomicReference<>();
    UUID node = UUID.randomUUID();
    try (PgTransport transport =
        new PgTransport(
            source,
            node,
            smallLimits(8),
            Duration.ofMillis(20),
            Duration.ofMillis(10),
            wire -> values.add(EVENTS.codec().decode(wire.bytes())),
            ignored -> fail("no topic-level recovery expected"),
            () -> healthAtRecovery.add(reference.get().healthy()))) {
      reference.set(transport);
      transport.start();
      assertTrue(take(healthAtRecovery), "initial recovery follows successful LISTEN");
      // No notification arrives: an active, bounded probe must still detect loss.
      source.failNextValidation.set(true);
      assertFalse(take(healthAtRecovery), "validation failure invalidates listener health");
      assertTrue(take(healthAtRecovery), "recovery follows re-established LISTEN");
      assertEquals(1, source.failedValidationTimeout.get());
      NotificationPacket frame = wire(UUID.randomUUID(), node, "after-idle-recovery");
      sendRaw(
          new JdbcTemplate(source),
          PgTransport.inboxChannel(node),
          NotificationCarrier.chunk(frame, 0).encode());
      assertEquals("after-idle-recovery", take(values));
    }
  }

  private static DefaultNotificationBus bus(CountingDataSource dataSource) {
    return new DefaultNotificationBus(
        dataSource,
        UUID.randomUUID(),
        List.of(EVENTS, HINTS),
        smallLimits(8),
        Duration.ofMillis(20),
        Duration.ofMillis(10));
  }

  @Test
  void repeatedPublishesSharePhysicalCommitBudgetAndAfterCommitCannotRepublish() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<String> values = new LinkedBlockingQueue<>();
    try (DefaultNotificationBus bus = bus(source)) {
      bus.subscribe(HINTS, values::add, () -> {});
      transactions(source)
          .executeWithoutResult(
              status -> {
                bus.publish(HINTS, NotificationAddress.node(bus.nodeId()), "same");
                bus.publish(HINTS, NotificationAddress.node(bus.nodeId()), "same");
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        assertThrows(
                            IllegalStateException.class,
                            () ->
                                bus.publish(
                                    HINTS, NotificationAddress.broadcast(), "after-commit"));
                      }
                    });
              });
      assertEquals("same", take(values));
      assertTrue(values.isEmpty());
      assertEquals(0, source.notifies.get());
      assertThrows(
          UnexpectedRollbackException.class,
          () ->
              transactions(source)
                  .executeWithoutResult(
                      status -> {
                        for (int index = 0; index < 8; index++) {
                          bus.publish(
                              EVENTS, NotificationAddress.node(bus.nodeId()), "item-" + index);
                        }
                        assertThrows(
                            IllegalStateException.class,
                            () ->
                                bus.publish(
                                    EVENTS,
                                    NotificationAddress.node(bus.nodeId()),
                                    "over-capacity"));
                      }));
      assertThrows(
          UnexpectedRollbackException.class,
          () ->
              transactions(source)
                  .executeWithoutResult(
                      status -> {
                        bus.publish(
                            EVENTS, NotificationAddress.node(bus.nodeId()), "x".repeat(15000));
                        bus.publish(
                            EVENTS, NotificationAddress.node(bus.nodeId()), "y".repeat(15000));
                        assertThrows(
                            IllegalStateException.class,
                            () ->
                                bus.publish(
                                    EVENTS,
                                    NotificationAddress.node(bus.nodeId()),
                                    "z".repeat(15000)));
                      }));
      bus.publish(HINTS, NotificationAddress.node(bus.nodeId()), "barrier");
      assertEquals("barrier", take(values));
      assertTrue(values.isEmpty());
    }
  }

  @Test
  void unrelatedDataSourceTransactionIsRejectedRatherThanSentOutOfTransaction() {
    CountingDataSource source = dataSource();
    CountingDataSource unrelated = dataSource();
    try (DefaultNotificationBus bus = bus(source)) {
      transactions(unrelated)
          .executeWithoutResult(
              status ->
                  assertThrows(
                      IllegalStateException.class,
                      () ->
                          bus.publish(EVENTS, NotificationAddress.broadcast(), "wrong-resource")));
      assertEquals(0, source.notifies.get());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DefaultNotificationBus(
                  source,
                  UUID.randomUUID(),
                  List.of(EVENTS, EVENTS),
                  smallLimits(8),
                  Duration.ofMillis(1),
                  Duration.ofMillis(1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DefaultNotificationBus(
                  source,
                  UUID.randomUUID(),
                  List.of(),
                  smallLimits(8),
                  Duration.ofMillis(1),
                  Duration.ofMillis(1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DefaultNotificationBus(
                  source,
                  UUID.randomUUID(),
                  List.of(EVENTS),
                  smallLimits(8),
                  Duration.ofNanos(1),
                  Duration.ofMillis(1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              bus.publishBatch(
                  EVENTS,
                  NotificationAddress.broadcast(),
                  List.of("x".repeat(15000), "y".repeat(15000), "z".repeat(15000))));
    }
  }

  @Test
  void malformedCarrierAndDomainPayloadRecoverWithoutDeliveringHalfMessage() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<String> values = new LinkedBlockingQueue<>();
    BlockingQueue<Integer> recoveries = new LinkedBlockingQueue<>();
    AtomicInteger count = new AtomicInteger();
    try (DefaultNotificationBus bus = bus(source)) {
      bus.subscribe(EVENTS, values::add, () -> recoveries.add(count.incrementAndGet()));
      // 订阅建立即执行权威恢复，不依赖 LISTEN 是否已建连。
      assertEquals(1, take(recoveries));
      bus.start();
      bus.start();
      // LISTEN 建连完成后再做一次全量对账。
      assertEquals(2, take(recoveries));
      JdbcTemplate jdbc = new JdbcTemplate(source);
      sendRaw(jdbc, PgTransport.inboxChannel(bus.nodeId()), "bad-carrier");
      assertEquals(3, take(recoveries));
      NotificationPacket invalid =
          new NotificationPacket(
              UUID.randomUUID(),
              bus.nodeId(),
              EVENTS.name(),
              UUID.randomUUID(),
              new byte[] {(byte) 0xFF});
      sendRaw(
          jdbc,
          PgTransport.inboxChannel(bus.nodeId()),
          NotificationCarrier.chunk(invalid, 0).encode());
      assertEquals(4, take(recoveries));
      NotificationPacket wrongAddress = wire(UUID.randomUUID(), null, "wrong-channel");
      sendRaw(
          jdbc,
          PgTransport.inboxChannel(bus.nodeId()),
          NotificationCarrier.chunk(wrongAddress, 0).encode());
      assertEquals(5, take(recoveries));
      NotificationPacket unknown =
          new NotificationPacket(
              UUID.randomUUID(), bus.nodeId(), "unknown.topic", UUID.randomUUID(), new byte[] {1});
      sendRaw(
          jdbc,
          PgTransport.inboxChannel(bus.nodeId()),
          NotificationCarrier.chunk(unknown, 0).encode());
      NotificationPacket valid = wire(UUID.randomUUID(), bus.nodeId(), "valid");
      sendRaw(
          jdbc,
          PgTransport.inboxChannel(bus.nodeId()),
          NotificationCarrier.chunk(valid, 0).encode());
      assertEquals("valid", take(values));
      assertTrue(values.isEmpty());
    }
  }

  @Test
  void subscribeAfterListenReadyStillPerformsItsOwnAuthoritativeResync() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<Integer> resyncs = new LinkedBlockingQueue<>();
    AtomicInteger count = new AtomicInteger();
    try (DefaultNotificationBus bus = bus(source)) {
      bus.start();
      awaitHealthy(bus);
      int baseline = count.get();
      // 订阅晚于 LISTEN 建连完成：仍必须自行完成一次权威恢复，而不是依赖「下一次通知」。
      bus.subscribe(
          EVENTS,
          ignored -> fail("no notification was published"),
          () -> resyncs.add(count.incrementAndGet()));
      assertEquals(baseline + 1, take(resyncs));
    }
  }

  @Test
  void transientSenderOverflowAndSqlFailureHaveBoundedRecoveryAndNoRetry() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<String> recoveries = new LinkedBlockingQueue<>();
    PgTransport transport =
        new PgTransport(
            source,
            UUID.randomUUID(),
            smallLimits(1),
            Duration.ofMillis(20),
            Duration.ofMillis(10),
            ignored -> {},
            recoveries::add,
            () -> {});
    try (transport) {
      NotificationPacket first = wire(UUID.randomUUID(), null, "first");
      transport.send(first, false);
      assertThrows(
          IllegalStateException.class,
          () -> transport.send(wire(UUID.randomUUID(), null, "overflow"), false));
      assertEquals(EVENTS.name(), take(recoveries));
      assertEquals(0, source.notifies.get());
      source.failNotify = true;
      transport.start();
      assertEquals(EVENTS.name(), take(recoveries));
      assertEquals(1, source.notifies.get());
      transport.close();
      assertThrows(IllegalStateException.class, transport::start);
      assertThrows(IllegalStateException.class, () -> transport.send(first, false));
      assertThrows(IllegalStateException.class, () -> transport.send(first, true));
    }
  }

  @Test
  void transientFragmentSenderRotatesBeforeLargeMessageCompletes() throws Exception {
    CountingDataSource source = dataSource();
    UUID receiver = UUID.randomUUID();
    NotificationPacket large = wire(UUID.randomUUID(), receiver, "x".repeat(18000));
    NotificationPacket control = wire(UUID.randomUUID(), receiver, "control");
    CountDownLatch firstSending = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    source.notifyStarted = firstSending;
    source.notifyRelease = release;
    try (Connection listener = source.getConnection();
        Statement statement = listener.createStatement();
        PgTransport transport =
            new PgTransport(
                source,
                UUID.randomUUID(),
                smallLimits(8),
                Duration.ofMillis(20),
                Duration.ofMillis(10),
                ignored -> {},
                ignored -> {},
                () -> {})) {
      statement.execute("LISTEN " + PgTransport.inboxChannel(receiver));
      transport.send(large, false);
      transport.start();
      await(firstSending);
      transport.send(control, false);
      release.countDown();
      List<NotificationCarrier> frames = new ArrayList<>();
      while (frames.size() < 5) {
        var notifications = listener.unwrap(PGConnection.class).getNotifications(5000);
        assertNotNull(notifications);
        for (var notification : notifications) {
          frames.add(
              NotificationCarrier.decode(notification.getParameter(), smallLimits(8), receiver));
        }
      }
      assertEquals(large.messageId(), frames.get(0).messageId());
      assertEquals(control.messageId(), frames.get(1).messageId());
      assertEquals(large.messageId(), frames.get(2).messageId());
      assertEquals(5, source.notifies.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void notificationTransactionManagerBlocksFirstPublishInAfterCommit() {
    CountingDataSource source = dataSource();
    try (DefaultNotificationBus bus = bus(source)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              notificationTransactions(source)
                  .executeWithoutResult(
                      status -> {
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                              @Override
                              public void afterCommit() {
                                bus.publish(EVENTS, NotificationAddress.broadcast(), "post-commit");
                              }
                            });
                      }));
      assertEquals(0, source.notifies.get());
    }
  }

  @Test
  void notificationTransactionManagerUsesProvidedDataSource() {
    CountingDataSource source = dataSource();
    NotificationTransactionManager transactionManager = new NotificationTransactionManager(source);
    assertEquals(source, transactionManager.getDataSource());
  }

  @Test
  void realPgBlockingSendIsAbortedAndReleasedImmediatelyOnClose() throws Exception {
    CountingDataSource source = dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.execute("ALTER ROLE " + POSTGRES.getUsername() + " SET search_path TO public, pg_catalog");
    try (Connection lockConn = source.getConnection();
        Statement lockStmt = lockConn.createStatement()) {
      lockStmt.execute("select pg_advisory_lock(999888)");
      jdbc.execute(
          "create or replace function public.pg_notify(channel text, payload text) returns void language plpgsql as $$ "
              + "begin perform pg_advisory_lock(999888); end; $$");
      try {
        PgTransport transport =
            new PgTransport(
                source,
                UUID.randomUUID(),
                smallLimits(8),
                Duration.ofMillis(20),
                Duration.ofMillis(10),
                ignored -> {},
                ignored -> {},
                () -> {});
        NotificationPacket message = wire(UUID.randomUUID(), null, "blocking");
        transport.send(message, false);
        transport.start();
        long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean blocked = false;
        while (System.nanoTime() < waitDeadline) {
          Integer count =
              jdbc.queryForObject(
                  "select count(*) from pg_stat_activity where query like '%pg_notify%' and state = 'active' and pid != pg_backend_pid()",
                  Integer.class);
          if (count != null && count > 0) {
            blocked = true;
            break;
          }
          Thread.sleep(20);
        }
        assertTrue(blocked, "sender must be actively blocked in PG");
        long start = System.nanoTime();
        transport.close();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed < 1500, "close must abort blocking sender in < 1.5s, took: " + elapsed);
        assertFalse(transport.healthy());
      } finally {
        lockStmt.execute("select pg_advisory_unlock(999888)");
        jdbc.execute("drop function if exists public.pg_notify(text, text)");
        jdbc.execute("ALTER ROLE " + POSTGRES.getUsername() + " RESET search_path");
      }
    }
  }

  @Test
  void senderFailureAndSuccessRecoversHealthSeparatelyFromReader() throws Exception {
    CountingDataSource source = dataSource();
    BlockingQueue<String> recoveries = new LinkedBlockingQueue<>();
    PgTransport transport =
        new PgTransport(
            source,
            UUID.randomUUID(),
            smallLimits(8),
            Duration.ofMillis(20),
            Duration.ofMillis(10),
            ignored -> {},
            recoveries::add,
            () -> {});
    try (transport) {
      transport.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!transport.healthy() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(transport.healthy(), "transport should be healthy after start");

      source.failNotify = true;
      transport.send(wire(UUID.randomUUID(), null, "fail"), false);
      assertEquals(EVENTS.name(), take(recoveries));
      assertFalse(transport.healthy(), "sender failure must make transport unhealthy");

      source.failNotify = false;
      transport.send(wire(UUID.randomUUID(), null, "recover"), false);
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!transport.healthy() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(transport.healthy(), "successful send must restore transport health");
    }
  }

  @Test
  void busHealthyReflectsFailedSubscriptions() throws Exception {
    CountingDataSource source = dataSource();
    CountDownLatch failed = new CountDownLatch(1);
    AtomicBoolean failRecovery = new AtomicBoolean(false);
    try (DefaultNotificationBus bus = bus(source)) {
      bus.subscribe(
          EVENTS,
          value -> {
            failRecovery.set(true);
            throw new IllegalStateException("consumer error");
          },
          () -> {
            if (failRecovery.get()) {
              failed.countDown();
              throw new IllegalStateException("recovery error");
            }
          });
      bus.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!bus.healthy() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(bus.healthy());
      bus.publish(EVENTS, NotificationAddress.node(bus.nodeId()), "trigger-fail");
      await(failed);
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (bus.healthy() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertFalse(bus.healthy(), "bus must become unhealthy when a subscription fails");
    }
  }

  private static void sendRaw(JdbcTemplate jdbc, String channel, String payload) {
    jdbc.queryForObject("select pg_notify(?, ?)", Object.class, channel, payload);
  }

  private static TransactionTemplate transactions(CountingDataSource dataSource) {
    return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  private static TransactionTemplate notificationTransactions(CountingDataSource dataSource) {
    return new TransactionTemplate(new NotificationTransactionManager(dataSource));
  }

  private static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
    T value = queue.poll(10, TimeUnit.SECONDS);
    assertNotNull(value, "notification/control marker timed out");
    return value;
  }

  private static void awaitHealthy(DefaultNotificationBus bus) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!bus.healthy() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(bus.healthy(), "notification bus did not become healthy");
  }

  private static CountingDataSource dataSource() {
    DriverManagerDataSource delegate =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    String name = "notification-test-" + UUID.randomUUID();
    Properties properties = new Properties();
    properties.setProperty("ApplicationName", name);
    delegate.setConnectionProperties(properties);
    return new CountingDataSource(delegate, name);
  }

  private static final class CountingDataSource extends AbstractDataSource {
    final DriverManagerDataSource delegate;
    final String applicationName;
    final AtomicInteger notifies = new AtomicInteger();
    final AtomicBoolean failNextValidation = new AtomicBoolean();
    final AtomicInteger failedValidationTimeout = new AtomicInteger();
    volatile boolean failNotify;
    volatile CountDownLatch notifyStarted;
    volatile CountDownLatch notifyRelease;

    CountingDataSource(DriverManagerDataSource delegate, String applicationName) {
      this.delegate = delegate;
      this.applicationName = applicationName;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return observe(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return observe(delegate.getConnection(username, password));
    }

    Connection observe(Connection actual) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("isValid") && failNextValidation.getAndSet(false)) {
                  failedValidationTimeout.set((int) args[0]);
                  return false;
                }
                if (method.getName().equals("prepareStatement")
                    && args[0] instanceof String sql
                    && sql.contains("pg_notify")) {
                  notifies.incrementAndGet();
                  if (notifyStarted != null) {
                    notifyStarted.countDown();
                    hold(notifyRelease);
                  }
                  if (failNotify) {
                    try (Statement statement = actual.createStatement()) {
                      statement.execute("select 1 / 0");
                    }
                  }
                }
                try {
                  return method.invoke(actual, args);
                } catch (InvocationTargetException error) {
                  throw error.getCause();
                }
              });
    }
  }
}
