package fun.fengwk.kkstudio.core.harness.execution;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * PostgreSQL contracts for {@link ExecutionTargetStore}: schedule earliest-wins semantics, lockDue
 * + rescheduleLocked + deleteLocked pairs, activateOldestEnvironment route-key activation,
 * lock-free eligible-due scan, and the schema-level NOTIFY triggers (insert and strictly-earlier
 * update only).
 */
class PostgresqlExecutionTargetStoreIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionTargetStore store;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Test
  void scheduleInsertsNewRow() {
    int affected = store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE);
    assertEquals(1, affected);

    ExecutionTargetRow row = store.findAll().getFirst();
    assertEquals(ExecutionTargetKind.THREAD, row.targetKind());
    assertEquals(1L, row.targetId());
    assertEquals(BASE, row.availableAt());
    assertTrue(row.dispatchEnabled());
  }

  @Test
  void scheduleMovesExistingTargetEarlier() {
    Instant earlier = BASE;
    Instant later = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 2L, null, later));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 2L, null, earlier));

    assertEquals(earlier, store.findAll().getFirst().availableAt());
    assertTrue(store.findAll().getFirst().dispatchEnabled());
  }

  @Test
  void laterScheduleDoesNotDelayExistingTarget() {
    Instant earlier = BASE;
    Instant later = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 3L, null, earlier));
    assertEquals(0, store.schedule(ExecutionTargetKind.THREAD, 3L, null, later));

    assertEquals(earlier, store.findAll().getFirst().availableAt());
    assertTrue(store.findAll().getFirst().dispatchEnabled());
  }

  @Test
  void earlierScheduleUpdatesRouteMetadata() {
    Instant later = BASE.plus(Duration.ofMinutes(10));
    Instant earlier = BASE.plus(Duration.ofMinutes(1));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 4L, null, later));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 4L, "env-a", earlier));

    ExecutionTargetRow row = store.findAll().getFirst();
    assertEquals(earlier, row.availableAt());
    assertEquals("env-a", row.routeKey());
    assertTrue(row.dispatchEnabled());
  }

  @Test
  void scheduleRejectsBlankRouteKey() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.schedule(ExecutionTargetKind.THREAD, 5L, "  ", BASE));
  }

  @Test
  void parkCreatesDisabledTargetWithoutOverwritingExistingRows() {
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 6L, "env-a", BASE.plus(Duration.ofMinutes(5))));
    assertEquals(
        0,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 6L, "env-b", BASE.minus(Duration.ofMinutes(5))));

    ExecutionTargetRow row = store.findAll().getFirst();
    assertEquals("env-a", row.routeKey());
    assertEquals(BASE.plus(Duration.ofMinutes(5)), row.availableAt());
    assertFalse(row.dispatchEnabled());
    assertTrue(
        inTransaction(() -> store.lock(ExecutionTargetKind.TOOL_INVOCATION, 6L)).isPresent());
    assertFalse(
        inTransaction(
                () ->
                    store.lockDue(
                        ExecutionTargetKind.TOOL_INVOCATION, 6L, BASE.plus(Duration.ofHours(1))))
            .isPresent());
  }

  @Test
  void scheduleEnablesParkedTargetAndUsesRequestedNormalTime() {
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-a", BASE));
    Instant later = BASE.plus(Duration.ofMinutes(10));

    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-b", later));

    ExecutionTargetRow row = store.findAll().getFirst();
    assertEquals("env-b", row.routeKey());
    assertEquals(later, row.availableAt());
    assertTrue(row.dispatchEnabled());
  }

  @Test
  void rescheduleLockedPreservesParkedDispatchGate() {
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 8L, "env-a", BASE));

    assertTrue(
        inTransaction(
            () ->
                store
                    .lock(ExecutionTargetKind.TOOL_INVOCATION, 8L)
                    .map(
                        row ->
                            store.rescheduleLocked(
                                    row.targetKind(),
                                    row.targetId(),
                                    row.routeKey(),
                                    BASE.plus(Duration.ofMinutes(1)))
                                == 1)
                    .orElse(false)));

    ExecutionTargetRow row = store.findAll().getFirst();
    assertFalse(row.dispatchEnabled());
    assertEquals(BASE.plus(Duration.ofMinutes(1)), row.availableAt());
    assertTrue(
        store
            .findEligibleDue(
                ExecutionTargetRouteEligibility.of(Set.of("env-a")),
                BASE.plus(Duration.ofHours(1)),
                10)
            .isEmpty());
  }

  @Test
  void lockDueReturnsRowOnlyWhenDue() {
    store.schedule(ExecutionTargetKind.THREAD, 10L, null, BASE.plus(Duration.ofMinutes(5)));

    assertFalse(
        inTransaction(() -> store.lockDue(ExecutionTargetKind.THREAD, 10L, BASE)).isPresent());
    assertTrue(
        inTransaction(
                () ->
                    store.lockDue(
                        ExecutionTargetKind.THREAD, 10L, BASE.plus(Duration.ofMinutes(10))))
            .isPresent());
    assertFalse(
        inTransaction(() -> store.lockDue(ExecutionTargetKind.MODEL_INVOCATION, 10L, BASE))
            .isPresent());
    assertFalse(
        inTransaction(() -> store.lockDue(ExecutionTargetKind.THREAD, 99L, BASE)).isPresent());
  }

  @Test
  void lockDueRescheduleLockedAndDeleteLockedFormAnAtomicCycle() {
    Instant retryAt = BASE.plus(Duration.ofMinutes(2));
    assertEquals(1, store.schedule(ExecutionTargetKind.MODEL_INVOCATION, 20L, null, retryAt));

    // lock + reschedule in one transaction; the row moves to a new lease.
    boolean rescheduled =
        inTransaction(
            () ->
                store
                    .lockDue(ExecutionTargetKind.MODEL_INVOCATION, 20L, retryAt.plusSeconds(1))
                    .map(
                        row -> {
                          Instant lease = retryAt.plus(Duration.ofMinutes(30));
                          assertEquals(
                              1,
                              store.rescheduleLocked(
                                  row.targetKind(), row.targetId(), null, lease));
                          return true;
                        })
                    .orElse(false));
    assertTrue(rescheduled);
    ExecutionTargetRow moved = store.findAll().getFirst();
    assertEquals(retryAt.plus(Duration.ofMinutes(30)), moved.availableAt());

    // Now lock + delete: the row is gone after the transaction commits.
    boolean deleted =
        inTransaction(
            () ->
                store
                    .lockDue(
                        ExecutionTargetKind.MODEL_INVOCATION,
                        20L,
                        moved.availableAt().plusSeconds(1))
                    .map(
                        row -> {
                          assertEquals(1, store.deleteLocked(row.targetKind(), row.targetId()));
                          return true;
                        })
                    .orElse(false));
    assertTrue(deleted);
    assertTrue(store.findAll().isEmpty());
  }

  @Test
  void deleteIfExistsReturnsAffectedCount() {
    assertEquals(0, store.deleteIfExists(ExecutionTargetKind.THREAD, 30L));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 30L, null, BASE));
    assertEquals(1, store.deleteIfExists(ExecutionTargetKind.THREAD, 30L));
    assertEquals(0, store.deleteIfExists(ExecutionTargetKind.THREAD, 30L));
  }

  @Test
  void activateOldestEnvironmentUsesToolCreationOrderAndParksLaterSiblings() {
    Instant later = BASE.plus(Duration.ofMinutes(10));
    insertEnvironmentTools(
        "env-a",
        List.of(
            new ToolSeed(40L, 0, BASE),
            new ToolSeed(41L, 1, BASE.plusMillis(1)),
            new ToolSeed(42L, 2, BASE.plusMillis(2))));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 40L, "env-a", later));
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 41L, "env-a", later.plus(Duration.ofMinutes(5))));
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 42L, "env-a", later.minus(Duration.ofMinutes(5))));

    assertTrue(store.activateOldestEnvironment("env-a", BASE));
    List<ExecutionTargetRow> rows = store.findAll();
    assertEquals(BASE, row(rows, 40L).availableAt());
    assertTrue(row(rows, 40L).dispatchEnabled());
    assertEquals(later.plus(Duration.ofMinutes(5)), row(rows, 41L).availableAt());
    assertFalse(row(rows, 41L).dispatchEnabled());
    assertEquals(later.minus(Duration.ofMinutes(5)), row(rows, 42L).availableAt());
    assertFalse(row(rows, 42L).dispatchEnabled());
  }

  @Test
  void activateOldestEnvironmentKeepsQueuedHeadAndBlocksActiveHead() {
    insertEnvironmentTools(
        "env-a", List.of(new ToolSeed(50L, 0, BASE), new ToolSeed(51L, 1, BASE.plusMillis(1))));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 50L, "env-a", BASE));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 51L, "env-a", BASE));
    assertFalse(store.activateOldestEnvironment("env-a", BASE));
    assertFalse(row(store.findAll(), 51L).dispatchEnabled());

    insertEnvironmentTools(
        "env-b", List.of(new ToolSeed(52L, 0, BASE), new ToolSeed(53L, 1, BASE.plusMillis(1))));
    Instant runningAt = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 52L, "env-b", runningAt));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 53L, "env-b", BASE));
    markRunning(52L, BASE);

    assertFalse(store.activateOldestEnvironment("env-b", BASE));
    List<ExecutionTargetRow> rows = store.findAll();
    assertEquals(runningAt, row(rows, 52L).availableAt());
    assertTrue(row(rows, 52L).dispatchEnabled());
    assertFalse(row(rows, 53L).dispatchEnabled());
  }

  @Test
  void lockedFifoHeadDoesNotLetActivationSkipToLaterSibling() throws Exception {
    insertEnvironmentTools(
        "env-lock", List.of(new ToolSeed(90L, 0, BASE), new ToolSeed(91L, 1, BASE.plusMillis(1))));
    Instant parkedAt = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 90L, "env-lock", parkedAt));
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 91L, "env-lock", parkedAt));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection lockConnection = newConnection();
        PreparedStatement lockStatement =
            lockConnection.prepareStatement(
                "select target_id from harness_execution_target"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ? for update")) {
      lockConnection.setAutoCommit(false);
      lockStatement.setLong(1, 90L);
      try (ResultSet result = lockStatement.executeQuery()) {
        assertTrue(result.next(), "oldest target must exist before locking");
      }

      Future<Boolean> activation =
          executor.submit(() -> store.activateOldestEnvironment("env-lock", BASE));
      assertFalse(
          activation.get(5, TimeUnit.SECONDS),
          "a locked FIFO head must be skipped, not replaced by a later sibling");
      assertFalse(row(store.findAll(), 90L).dispatchEnabled());
      assertFalse(row(store.findAll(), 91L).dispatchEnabled());

      lockConnection.commit();
    } finally {
      executor.shutdownNow();
    }

    assertTrue(store.activateOldestEnvironment("env-lock", BASE));
    List<ExecutionTargetRow> rows = store.findAll();
    assertTrue(row(rows, 90L).dispatchEnabled());
    assertEquals(BASE, row(rows, 90L).availableAt());
    assertFalse(row(rows, 91L).dispatchEnabled());
  }

  @Test
  void activateOldestEnvironmentNoOpWhenAlreadyEarlierOrAbsent() {
    Instant earlier = BASE;
    assertFalse(store.activateOldestEnvironment("env-a", earlier));

    insertEnvironmentTools("env-a", List.of(new ToolSeed(54L, 0, BASE)));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 54L, "env-a", earlier));
    // Row is already enabled at the requested time, so activation is a normal no-op.
    assertFalse(store.activateOldestEnvironment("env-a", earlier));
  }

  @Test
  void routeKeyNullTargetsAlwaysEligibleAndScopedRoutesNeedEligibility() {
    store.schedule(ExecutionTargetKind.THREAD, 60L, null, BASE.minus(Duration.ofMinutes(1)));
    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, 61L, "env-a", BASE.minus(Duration.ofMinutes(1)));
    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, 62L, "env-b", BASE.minus(Duration.ofMinutes(1)));
    store.schedule(
        ExecutionTargetKind.MODEL_INVOCATION, 63L, null, BASE.plus(Duration.ofMinutes(1)));
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 64L, "env-a", BASE.minus(Duration.ofMinutes(2))));

    ExecutionTargetRouteEligibility empty = ExecutionTargetRouteEligibility.empty();
    List<ExecutionTargetRow> emptyScan = store.findEligibleDue(empty, BASE, 10);
    assertEquals(1, emptyScan.size());
    assertEquals(60L, emptyScan.get(0).targetId());

    List<ExecutionTargetRow> withA =
        store.findEligibleDue(ExecutionTargetRouteEligibility.of(Set.of("env-a")), BASE, 10);
    assertEquals(2, withA.size());

    Optional<Instant> emptyNearest = store.findNearestEligibleAvailableAt(empty);
    assertEquals(BASE.minus(Duration.ofMinutes(1)), emptyNearest.orElseThrow());
  }

  @Test
  void triggerNotifiesOnInsertAndOnStrictlyEarlierUpdateOnly() throws Exception {
    AtomicReference<String> payloadRef = new AtomicReference<>();
    CountDownLatch insertNotify = new CountDownLatch(1);
    CountDownLatch earlierNotify = new CountDownLatch(1);
    CountDownLatch laterNotify = new CountDownLatch(1);

    try (Connection conn = newConnection()) {
      PGConnection pgConn = conn.unwrap(PGConnection.class);
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("LISTEN " + PostgresqlExecutionTargetListener.CHANNEL);
      }
      Thread listener =
          new Thread(
              () -> {
                try {
                  long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                  while (System.nanoTime() < deadline) {
                    PGNotification[] notes = pgConn.getNotifications(100);
                    if (notes == null) {
                      if (!insertNotify.await(0, TimeUnit.NANOSECONDS)
                          || !earlierNotify.await(0, TimeUnit.NANOSECONDS)
                          || !laterNotify.await(0, TimeUnit.NANOSECONDS)) {
                        continue;
                      }
                      return;
                    }
                    for (PGNotification note : notes) {
                      payloadRef.set(note.getParameter());
                      if (insertNotify.getCount() > 0) {
                        insertNotify.countDown();
                      } else if (earlierNotify.getCount() > 0) {
                        earlierNotify.countDown();
                      } else {
                        laterNotify.countDown();
                      }
                    }
                    if (insertNotify.getCount() == 0
                        && earlierNotify.getCount() == 0
                        && laterNotify.getCount() == 0) {
                      return;
                    }
                  }
                } catch (SQLException | InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
              },
              "trigger-notify-listener");
      listener.setDaemon(true);
      listener.start();

      // INSERT notifies.
      store.schedule(ExecutionTargetKind.THREAD, 70L, null, BASE);
      assertTrue(insertNotify.await(5, TimeUnit.SECONDS), "insert must notify");
      assertEquals("", payloadRef.get());

      // Strictly earlier update notifies.
      store.schedule(ExecutionTargetKind.THREAD, 70L, null, BASE.minus(Duration.ofMinutes(1)));
      assertTrue(earlierNotify.await(5, TimeUnit.SECONDS), "earlier update must notify");

      // Later update does NOT notify.
      store.schedule(ExecutionTargetKind.THREAD, 70L, null, BASE.plus(Duration.ofMinutes(1)));
      assertFalse(
          laterNotify.await(800, TimeUnit.MILLISECONDS),
          "later update must not notify (lease extension semantics)");
    }
  }

  @Test
  void parkedRowsStaySilentUntilEnabledAndDisabledRewritesStaySilent() throws Exception {
    try (Connection conn = newConnection()) {
      PGConnection pgConn = conn.unwrap(PGConnection.class);
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("LISTEN " + PostgresqlExecutionTargetListener.CHANNEL);
      }

      assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 71L, "env-a", BASE));
      assertFalse(awaitNotification(pgConn, 500), "parked insert must not notify");

      assertEquals(
          1,
          store.schedule(
              ExecutionTargetKind.TOOL_INVOCATION, 71L, "env-a", BASE.plus(Duration.ofMinutes(1))));
      assertTrue(awaitNotification(pgConn, 3_000), "enabling a parked row must notify");

      assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 72L, "env-a", BASE));
      assertFalse(awaitNotification(pgConn, 500), "second parked insert must not notify");
      assertTrue(
          inTransaction(
              () ->
                  store
                      .lock(ExecutionTargetKind.TOOL_INVOCATION, 72L)
                      .map(
                          row ->
                              store.rescheduleLocked(
                                      row.targetKind(),
                                      row.targetId(),
                                      row.routeKey(),
                                      BASE.minus(Duration.ofMinutes(1)))
                                  == 1)
                      .orElse(false)));
      assertFalse(awaitNotification(pgConn, 500), "disabled-row rewrite must not notify");
    }
  }

  @Test
  void insertTriggerPayloadIsAlwaysEmpty() throws Exception {
    CountDownLatch insertNotify = new CountDownLatch(1);
    AtomicReference<String> payload = new AtomicReference<>();
    try (Connection conn = newConnection()) {
      PGConnection pgConn = conn.unwrap(PGConnection.class);
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("LISTEN " + PostgresqlExecutionTargetListener.CHANNEL);
      }
      Thread listener =
          new Thread(
              () -> {
                try {
                  long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                  while (insertNotify.getCount() > 0 && System.nanoTime() < deadline) {
                    PGNotification[] notes = pgConn.getNotifications(100);
                    if (notes != null && notes.length > 0) {
                      payload.set(notes[0].getParameter());
                      insertNotify.countDown();
                    }
                  }
                } catch (SQLException ignored) {
                }
              },
              "trigger-payload-listener");
      listener.setDaemon(true);
      listener.start();

      store.schedule(ExecutionTargetKind.MODEL_INVOCATION, 80L, "env-a", BASE);
      assertTrue(insertNotify.await(3, TimeUnit.SECONDS), "insert must notify");
      assertNotNull(payload.get(), "payload must be present");
      assertEquals("", payload.get(), "payload must be empty");
    }
  }

  private void insertEnvironmentTools(String routeKey, List<ToolSeed> seeds) {
    if (seeds.isEmpty()) {
      throw new IllegalArgumentException("seeds must not be empty");
    }
    long firstId = seeds.getFirst().invocationId();
    long sessionId = 1_000_000L + firstId * 10;
    long threadId = sessionId + 1;
    long rootEntryId = sessionId + 2;
    long assistantEntryId = sessionId + 3;
    try (Connection connection = newConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_session (id, title, created_at) values (?, 'fixture', ?)")) {
          statement.setLong(1, sessionId);
          statement.setObject(2, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, ?)")) {
          statement.setLong(1, rootEntryId);
          statement.setLong(2, sessionId);
          statement.setObject(3, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)")) {
          statement.setLong(1, assistantEntryId);
          statement.setLong(2, sessionId);
          statement.setLong(3, rootEntryId);
          statement.setObject(4, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_thread (id, head_entry_id, input_sequence, runnable,"
                    + " execution_epoch, created_at, updated_at) values (?, ?, 0, false, 0, ?, ?)")) {
          statement.setLong(1, threadId);
          statement.setLong(2, assistantEntryId);
          statement.setObject(3, offset(seeds.getFirst().createdAt()));
          statement.setObject(4, offset(seeds.getFirst().createdAt()));
          assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id,"
                    + " ordinal, tool_call_id, descriptor, arguments, location, environment_name,"
                    + " execution_epoch, status, attempt, created_at) values (?, ?, ?, ?, ?, ?,"
                    + " '{}'::jsonb, '{}'::jsonb, 'ENVIRONMENT', ?, 0, 'QUEUED', 1, ?)")) {
          for (ToolSeed seed : seeds) {
            statement.setLong(1, seed.invocationId());
            statement.setLong(2, threadId);
            statement.setLong(3, sessionId);
            statement.setLong(4, assistantEntryId);
            statement.setInt(5, seed.ordinal());
            statement.setString(6, "call-" + seed.invocationId());
            statement.setString(7, routeKey);
            statement.setObject(8, offset(seed.createdAt()));
            assertEquals(1, statement.executeUpdate());
          }
        }
        connection.commit();
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  // ---- strict locked operations: parkLocked / activateLocked with null platform route ----

  @Test
  void parkLockedPlatformTargetWithNullRouteDisablesAndPreservesRoute() {
    Instant initialAt = BASE;
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 800L, null, initialAt));
    Instant parkedAt = BASE.plusSeconds(5);

    assertEquals(
        1,
        inTransaction(
            () -> {
              // The park path takes the lock first; requireActiveTransaction is the precondition.
              ExecutionTargetRow row =
                  store.lock(ExecutionTargetKind.TOOL_INVOCATION, 800L).orElseThrow();
              assertNull(row.routeKey(), "PLATFORM target carries a null route key");
              return store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 800L, null, parkedAt);
            }));

    ExecutionTargetRow row = store.findAll().getFirst();
    assertEquals(800L, row.targetId());
    assertNull(row.routeKey(), "PLATFORM parkLocked must preserve the null route key");
    assertFalse(row.dispatchEnabled());
    assertEquals(parkedAt, row.availableAt());
  }

  @Test
  void parkLockedRequiresActiveTransaction() {
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 810L, null, BASE));
    assertThrows(
        IllegalStateException.class,
        () -> store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 810L, null, BASE));
  }

  @Test
  void activateLockedPlatformTargetWithNullRouteEnables() {
    Instant at = BASE;
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 820L, null, at));
    // Park it.
    assertEquals(
        1,
        inTransaction(
            () -> {
              store.lock(ExecutionTargetKind.TOOL_INVOCATION, 820L).orElseThrow();
              return store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 820L, null, at);
            }));
    Instant reenabledAt = BASE.plusSeconds(10);

    assertEquals(
        1,
        inTransaction(
            () -> {
              store.lock(ExecutionTargetKind.TOOL_INVOCATION, 820L).orElseThrow();
              return store.activateLocked(
                  ExecutionTargetKind.TOOL_INVOCATION, 820L, null, reenabledAt);
            }));

    ExecutionTargetRow row = store.findAll().getFirst();
    assertNull(row.routeKey());
    assertTrue(row.dispatchEnabled());
    assertEquals(reenabledAt, row.availableAt());
  }

  @Test
  void activateLockedRequiresActiveTransaction() {
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 830L, null, BASE));
    assertThrows(
        IllegalStateException.class,
        () -> store.activateLocked(ExecutionTargetKind.TOOL_INVOCATION, 830L, null, BASE));
  }

  @Test
  void parkLockedRejectsBlankRouteKey() {
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 840L, "env-a", BASE));
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          inTransaction(
              () -> {
                store.lock(ExecutionTargetKind.TOOL_INVOCATION, 840L).orElseThrow();
                return store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 840L, " ", BASE);
              });
        });
  }

  private static void markRunning(long invocationId, Instant startedAt) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_tool_invocation set status = 'RUNNING', worker_token = ?,"
                    + " worker_until = ?, started_at = ?, deadline_at = ?, last_activity_at = ?"
                    + " where id = ?")) {
      statement.setString(1, "worker-" + invocationId);
      statement.setObject(2, offset(startedAt.plus(Duration.ofMinutes(5))));
      statement.setObject(3, offset(startedAt));
      statement.setObject(4, offset(startedAt.plus(Duration.ofMinutes(30))));
      statement.setObject(5, offset(startedAt));
      statement.setLong(6, invocationId);
      assertEquals(1, statement.executeUpdate());
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private static boolean awaitNotification(PGConnection connection, long timeoutMillis)
      throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      long remainingMillis =
          Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
      PGNotification[] notifications =
          connection.getNotifications((int) Math.min(100, remainingMillis));
      if (notifications != null && notifications.length > 0) {
        return true;
      }
    }
    return false;
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(value.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private <T> T inTransaction(Supplier<T> operation) {
    return new TransactionTemplate(transactionManager).execute(status -> operation.get());
  }

  private static ExecutionTargetRow row(List<ExecutionTargetRow> rows, long id) {
    return rows.stream().filter(candidate -> candidate.targetId() == id).findFirst().orElseThrow();
  }

  private record ToolSeed(long invocationId, int ordinal, Instant createdAt) {}
}
