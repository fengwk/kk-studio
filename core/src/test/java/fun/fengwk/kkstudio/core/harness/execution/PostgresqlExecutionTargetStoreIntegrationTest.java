package fun.fengwk.kkstudio.core.harness.execution;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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

  @Test
  void scheduleInsertsNewRow() {
    int affected = store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE);
    assertEquals(1, affected);

    ExecutionTargetRow row = store.findAll().getFirst();
    assertEquals(ExecutionTargetKind.THREAD, row.targetKind());
    assertEquals(1L, row.targetId());
    assertEquals(BASE, row.availableAt());
  }

  @Test
  void scheduleMovesExistingTargetEarlier() {
    Instant earlier = BASE;
    Instant later = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 2L, null, later));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 2L, null, earlier));

    assertEquals(earlier, store.findAll().getFirst().availableAt());
  }

  @Test
  void laterScheduleDoesNotDelayExistingTarget() {
    Instant earlier = BASE;
    Instant later = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 3L, null, earlier));
    assertEquals(0, store.schedule(ExecutionTargetKind.THREAD, 3L, null, later));

    assertEquals(earlier, store.findAll().getFirst().availableAt());
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
  }

  @Test
  void scheduleRejectsBlankRouteKey() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.schedule(ExecutionTargetKind.THREAD, 5L, "  ", BASE));
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
  void activateOldestEnvironmentMovesOnlyOldestWhenLater() {
    Instant later = BASE.plus(Duration.ofMinutes(10));
    Instant earlier = BASE;
    store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 40L, "env-a", later);
    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, 41L, "env-a", later.plus(Duration.ofMinutes(5)));
    store.schedule(
        ExecutionTargetKind.TOOL_INVOCATION, 42L, "env-a", later.minus(Duration.ofMinutes(5)));

    assertTrue(store.activateOldestEnvironment("env-a", earlier));
    List<ExecutionTargetRow> rows = store.findAll();
    assertEquals(earlier, row(rows, 42L).availableAt());
    assertEquals(later, row(rows, 40L).availableAt());
  }

  @Test
  void activateOldestEnvironmentNoOpWhenAlreadyEarlierOrAbsent() {
    Instant earlier = BASE;
    assertFalse(store.activateOldestEnvironment("env-a", earlier));

    store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 50L, "env-a", earlier);
    // Row is already at the requested time → no update.
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

  private <T> T inTransaction(Supplier<T> operation) {
    return new TransactionTemplate(transactionManager).execute(status -> operation.get());
  }

  private static ExecutionTargetRow row(List<ExecutionTargetRow> rows, long id) {
    return rows.stream().filter(candidate -> candidate.targetId() == id).findFirst().orElseThrow();
  }
}
