package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;
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

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.sql.Connection;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * PostgreSQL 合约覆盖 {@link ExecutionActivationStore}：schedule 最早时间优先语义、行锁与 delete 配对、到期扫描、 SCHEDULED
 * 与 PARKED 状态切换、Environment FIFO 之外的通用激活持久化行为，以及 schema 级 NOTIFY 触发器（插入和仅严格提前的更新）。 Environment FIFO
 * 推进由 {@link PostgresqlEnvironmentToolActivationQueueIntegrationTest} 独立覆盖。
 */
class PostgresqlExecutionActivationStoreIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private PostgresqlExecutionActivationStore store;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  @Test
  void scheduleInsertsNewRow() {
    int affected = store.schedule(ExecutionTargetKind.THREAD, 1L, null, BASE);
    assertEquals(1, affected);

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals(ExecutionTargetKind.THREAD, row.targetKind());
    assertEquals(1L, row.targetId());
    assertEquals(BASE, row.wakeAt());
    assertEquals(ActivationState.SCHEDULED, row.activationState());
  }

  @Test
  void scheduleMovesExistingActivationEarlier() {
    Instant earlier = BASE;
    Instant later = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 2L, null, later));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 2L, null, earlier));

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals(earlier, row.wakeAt());
    assertEquals(ActivationState.SCHEDULED, row.activationState());
  }

  @Test
  void laterScheduleDoesNotDelayExistingActivation() {
    Instant earlier = BASE;
    Instant later = BASE.plus(Duration.ofMinutes(10));
    assertEquals(1, store.schedule(ExecutionTargetKind.THREAD, 3L, null, earlier));
    assertEquals(0, store.schedule(ExecutionTargetKind.THREAD, 3L, null, later));

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals(earlier, row.wakeAt());
    assertEquals(ActivationState.SCHEDULED, row.activationState());
  }

  @Test
  void scheduleWithSameEnvironmentReplacesWakeAtWhenEarlier() {
    Instant later = BASE.plus(Duration.ofMinutes(10));
    Instant earlier = BASE.plus(Duration.ofMinutes(1));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 4L, "env-a", later));
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 4L, "env-a", earlier));

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals(earlier, row.wakeAt());
    assertEquals("env-a", row.environmentName());
    assertEquals(ActivationState.SCHEDULED, row.activationState());
  }

  @Test
  void scheduleWithDifferentEnvironmentDoesNotReplaceScheduledEnvironment() {
    Instant first = BASE.plus(Duration.ofMinutes(10));
    Instant conflictingEarlier = BASE;

    // 首次 schedule 以 null 环境创建 SCHEDULED 记录。
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 100L, null, first));

    ExecutionActivation nullEnv = store.findAll().getFirst();
    assertNull(nullEnv.environmentName());
    assertEquals(first, nullEnv.wakeAt());
    assertEquals(ActivationState.SCHEDULED, nullEnv.activationState());

    // 即使 wakeAt 更早，不同 environmentName 也不得替换现有 environment 或 wakeAt。
    assertEquals(
        0, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 100L, "env-x", conflictingEarlier));

    ExecutionActivation after = store.findAll().getFirst();
    assertNull(
        after.environmentName(), "schedule 传入不同 environmentName 时不得替换现有 schedule 的 environment");
    assertEquals(first, after.wakeAt(), "schedule 不得改动 wakeAt");
    assertEquals(ActivationState.SCHEDULED, after.activationState());

    // 另一个不同 Environment 同样不能触发 wakeAt 推进。
    assertEquals(
        0,
        store.schedule(
            ExecutionTargetKind.TOOL_INVOCATION,
            100L,
            "env-y",
            conflictingEarlier.minusSeconds(1)));
    ExecutionActivation stillNull = store.findAll().getFirst();
    assertNull(stillNull.environmentName());
    assertEquals(first, stillNull.wakeAt());
  }

  @Test
  void scheduleWithNonDistinctNullEnvironmentReplacesWakeAtWhenEarlier() {
    Instant initial = BASE.plus(Duration.ofMinutes(10));
    Instant earlier = BASE;

    // 首次 schedule 以 null 环境创建 SCHEDULED 记录。
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 300L, null, initial));
    ExecutionActivation first = store.findAll().getFirst();
    assertNull(first.environmentName());
    assertEquals(initial, first.wakeAt());

    // schedule 仍以 null environment 触发：null IS NOT DISTINCT FROM null 命中，可替换 wakeAt。
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 300L, null, earlier));
    ExecutionActivation replaced = store.findAll().getFirst();
    assertNull(replaced.environmentName());
    assertEquals(earlier, replaced.wakeAt());
    assertEquals(ActivationState.SCHEDULED, replaced.activationState());

    // 更晚的 wakeAt 仍然被忽略。
    assertEquals(
        0, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 300L, null, BASE.plusSeconds(60)));
    assertEquals(earlier, store.findAll().getFirst().wakeAt());
  }

  @Test
  void scheduleRejectsBlankEnvironmentName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 5L, "  ", BASE));
  }

  @Test
  void scheduleRejectsEnvironmentNameOnNonToolInvocation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.schedule(ExecutionTargetKind.THREAD, 5L, "env-a", BASE));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.schedule(ExecutionTargetKind.MODEL_INVOCATION, 5L, "env-a", BASE));
  }

  @Test
  void parkCreatesParkedActivationWithoutOverwritingExistingRows() {
    assertEquals(
        1,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 6L, "env-a", BASE.plus(Duration.ofMinutes(5))));
    assertEquals(
        0,
        store.park(
            ExecutionTargetKind.TOOL_INVOCATION, 6L, "env-b", BASE.minus(Duration.ofMinutes(5))));

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals("env-a", row.environmentName());
    assertEquals(BASE.plus(Duration.ofMinutes(5)), row.wakeAt());
    assertEquals(ActivationState.PARKED, row.activationState());
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
  void scheduleDoesNotImplicitlyActivateParkedActivation() {
    Instant parkAt = BASE;
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-a", parkAt));

    Instant later = BASE.plus(Duration.ofMinutes(10));

    // schedule 不得隐式唤醒已有 PARKED 记录，传入相同 environment + 更晚 wakeAt 时返回 0。
    assertEquals(0, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-a", later));

    ExecutionActivation stillParked = store.findAll().getFirst();
    assertEquals("env-a", stillParked.environmentName());
    assertEquals(parkAt, stillParked.wakeAt(), "schedule 不得改写 PARKED wakeAt");
    assertEquals(ActivationState.PARKED, stillParked.activationState());

    // 不同 environment 也不能替换 PARKED 记录的 environment。
    assertEquals(0, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-b", later));

    ExecutionActivation unchanged = store.findAll().getFirst();
    assertEquals("env-a", unchanged.environmentName());
    assertEquals(parkAt, unchanged.wakeAt());
    assertEquals(ActivationState.PARKED, unchanged.activationState());
  }

  @Test
  void explicitActivateAfterScheduleOnParkedTransitionsToScheduled() {
    Instant parkAt = BASE;
    assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-a", parkAt));

    Instant later = BASE.plus(Duration.ofMinutes(10));
    // schedule 不会唤醒 PARKED 记录。
    assertEquals(0, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 7L, "env-a", later));
    assertEquals(ActivationState.PARKED, store.findAll().getFirst().activationState());

    // 显式在事务中调用 activateLocked 完成 PARKED -> SCHEDULED 的状态切换。
    Instant reenableAt = BASE.plus(Duration.ofMinutes(15));
    boolean activated =
        inTransaction(
            () ->
                store
                    .lock(ExecutionTargetKind.TOOL_INVOCATION, 7L)
                    .map(
                        row ->
                            store.activateLocked(row.targetKind(), row.targetId(), reenableAt) == 1)
                    .orElse(false));

    assertTrue(activated);

    ExecutionActivation reenabled = store.findAll().getFirst();
    assertEquals("env-a", reenabled.environmentName(), "activateLocked 须保留 environment");
    assertEquals(ActivationState.SCHEDULED, reenabled.activationState());
    assertEquals(reenableAt, reenabled.wakeAt());
  }

  @Test
  void rescheduleLockedPreservesParkedStateAndEnvironment() {
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
                                    BASE.plus(Duration.ofMinutes(1)))
                                == 1)
                    .orElse(false)));

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals(
        ActivationState.PARKED, row.activationState(), "rescheduleLocked 不得切换 activationState");
    assertEquals(BASE.plus(Duration.ofMinutes(1)), row.wakeAt());
    assertEquals("env-a", row.environmentName(), "rescheduleLocked 不得改写 environmentName");
    assertTrue(
        store
            .findEligibleDue(
                ExecutionActivationEnvironmentEligibility.of(Set.of("env-a")),
                BASE.plus(Duration.ofHours(1)),
                10)
            .isEmpty(),
        "PARKED 激活不得进入 due 扫描");
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

    // 在同一事务中执行 lock + reschedule，使记录移动到新的 lease。
    boolean rescheduled =
        inTransaction(
            () ->
                store
                    .lockDue(ExecutionTargetKind.MODEL_INVOCATION, 20L, retryAt.plusSeconds(1))
                    .map(
                        row -> {
                          Instant lease = retryAt.plus(Duration.ofMinutes(30));
                          assertEquals(
                              1, store.rescheduleLocked(row.targetKind(), row.targetId(), lease));
                          return true;
                        })
                    .orElse(false));
    assertTrue(rescheduled);
    ExecutionActivation moved = store.findAll().getFirst();
    assertEquals(retryAt.plus(Duration.ofMinutes(30)), moved.wakeAt());

    // 再执行 lock + delete：事务提交后记录应当消失。
    boolean deleted =
        inTransaction(
            () ->
                store
                    .lockDue(
                        ExecutionTargetKind.MODEL_INVOCATION, 20L, moved.wakeAt().plusSeconds(1))
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
  void nullEnvironmentActivationsAlwaysEligibleAndScopedEnvironmentsNeedEligibility() {
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

    ExecutionActivationEnvironmentEligibility empty =
        ExecutionActivationEnvironmentEligibility.empty();
    List<ExecutionActivation> emptyScan = store.findEligibleDue(empty, BASE, 10);
    assertEquals(1, emptyScan.size());
    assertEquals(60L, emptyScan.get(0).targetId());

    List<ExecutionActivation> withA =
        store.findEligibleDue(
            ExecutionActivationEnvironmentEligibility.of(Set.of("env-a")), BASE, 10);
    assertEquals(2, withA.size());

    Optional<Instant> emptyNearest = store.findNearestEligibleWakeAt(empty);
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
        stmt.execute("LISTEN " + PostgresqlExecutionActivationListener.CHANNEL);
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

      // INSERT 会发送通知。
      store.schedule(ExecutionTargetKind.THREAD, 70L, null, BASE);
      assertTrue(insertNotify.await(5, TimeUnit.SECONDS), "insert must notify");
      assertEquals("", payloadRef.get());

      // 严格提前的更新会发送通知。
      store.schedule(ExecutionTargetKind.THREAD, 70L, null, BASE.minus(Duration.ofMinutes(1)));
      assertTrue(earlierNotify.await(5, TimeUnit.SECONDS), "earlier update must notify");

      // 更晚的更新不会发送通知。
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
        stmt.execute("LISTEN " + PostgresqlExecutionActivationListener.CHANNEL);
      }

      assertEquals(1, store.park(ExecutionTargetKind.TOOL_INVOCATION, 71L, "env-a", BASE));
      assertFalse(awaitNotification(pgConn, 500), "parked insert must not notify");

      // schedule 不会唤醒已有 PARKED 记录，因此也不会触发通知。
      assertEquals(
          0,
          store.schedule(
              ExecutionTargetKind.TOOL_INVOCATION, 71L, "env-a", BASE.plus(Duration.ofMinutes(1))));
      assertFalse(awaitNotification(pgConn, 500), "schedule on PARKED must not notify");

      // 显式在事务中 PARKED -> SCHEDULED 必须触发通知。
      boolean activated =
          inTransaction(
              () ->
                  store
                      .lock(ExecutionTargetKind.TOOL_INVOCATION, 71L)
                      .map(
                          row ->
                              store.activateLocked(
                                      row.targetKind(),
                                      row.targetId(),
                                      BASE.plus(Duration.ofMinutes(1)))
                                  == 1)
                      .orElse(false));
      assertTrue(activated);
      assertTrue(awaitNotification(pgConn, 3_000), "PARKED -> SCHEDULED must notify");

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
                                      BASE.minus(Duration.ofMinutes(1)))
                                  == 1)
                      .orElse(false)));
      assertFalse(awaitNotification(pgConn, 500), "PARKED reschedule must not notify");
    }
  }

  @Test
  void insertTriggerPayloadIsAlwaysEmpty() throws Exception {
    CountDownLatch insertNotify = new CountDownLatch(1);
    AtomicReference<String> payload = new AtomicReference<>();
    try (Connection conn = newConnection()) {
      PGConnection pgConn = conn.unwrap(PGConnection.class);
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("LISTEN " + PostgresqlExecutionActivationListener.CHANNEL);
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

      store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 80L, "env-a", BASE);
      assertTrue(insertNotify.await(3, TimeUnit.SECONDS), "insert must notify");
      assertNotNull(payload.get(), "payload must be present");
      assertEquals("", payload.get(), "payload must be empty");
    }
  }

  // ---- null PLATFORM environment 下的严格锁定操作：parkLocked / activateLocked ----

  @Test
  void parkLockedPlatformActivationWithNullEnvironmentDisablesAndPreservesEnvironment() {
    Instant initialAt = BASE;
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 800L, null, initialAt));
    Instant parkedAt = BASE.plusSeconds(5);

    assertEquals(
        1,
        inTransaction(
            () -> {
              // park 路径会先获取锁；requireActiveTransaction 是前置条件。
              ExecutionActivation row =
                  store.lock(ExecutionTargetKind.TOOL_INVOCATION, 800L).orElseThrow();
              assertNull(
                  row.environmentName(), "PLATFORM activation carries a null environment name");
              return store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 800L, parkedAt);
            }));

    ExecutionActivation row = store.findAll().getFirst();
    assertEquals(800L, row.targetId());
    assertNull(
        row.environmentName(), "PLATFORM parkLocked must preserve the null environment name");
    assertEquals(ActivationState.PARKED, row.activationState());
    assertEquals(parkedAt, row.wakeAt());
  }

  @Test
  void parkLockedRequiresActiveTransaction() {
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 810L, null, BASE));
    assertThrows(
        IllegalStateException.class,
        () -> store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 810L, BASE));
  }

  @Test
  void activateLockedPlatformActivationWithNullEnvironmentEnables() {
    Instant at = BASE;
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 820L, null, at));
    // 先停放记录。
    assertEquals(
        1,
        inTransaction(
            () -> {
              store.lock(ExecutionTargetKind.TOOL_INVOCATION, 820L).orElseThrow();
              return store.parkLocked(ExecutionTargetKind.TOOL_INVOCATION, 820L, at);
            }));
    Instant reenabledAt = BASE.plusSeconds(10);

    assertEquals(
        1,
        inTransaction(
            () -> {
              store.lock(ExecutionTargetKind.TOOL_INVOCATION, 820L).orElseThrow();
              return store.activateLocked(ExecutionTargetKind.TOOL_INVOCATION, 820L, reenabledAt);
            }));

    ExecutionActivation row = store.findAll().getFirst();
    assertNull(row.environmentName());
    assertEquals(ActivationState.SCHEDULED, row.activationState());
    assertEquals(reenabledAt, row.wakeAt());
  }

  @Test
  void activateLockedRequiresActiveTransaction() {
    assertEquals(1, store.schedule(ExecutionTargetKind.TOOL_INVOCATION, 830L, null, BASE));
    assertThrows(
        IllegalStateException.class,
        () -> store.activateLocked(ExecutionTargetKind.TOOL_INVOCATION, 830L, BASE));
  }

  @Test
  void executionActivationRecordEnforcesDomainInvariants() {
    Instant at = BASE;

    assertThrows(
        NullPointerException.class,
        () -> new ExecutionActivation(null, 1L, null, ActivationState.SCHEDULED, at));
    assertThrows(
        NullPointerException.class,
        () -> new ExecutionActivation(ExecutionTargetKind.THREAD, 1L, null, null, at));
    assertThrows(
        NullPointerException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 1L, null, ActivationState.SCHEDULED, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 0L, null, ActivationState.SCHEDULED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, -1L, null, ActivationState.SCHEDULED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, "", ActivationState.SCHEDULED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, "   ", ActivationState.SCHEDULED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, " env-a", ActivationState.SCHEDULED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, "env-a ", ActivationState.SCHEDULED, at));

    String tooLong = "x".repeat(129);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, tooLong, ActivationState.SCHEDULED, at));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 1L, "env-a", ActivationState.SCHEDULED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.MODEL_INVOCATION, 1L, "env-a", ActivationState.SCHEDULED, at));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 1L, null, ActivationState.PARKED, at));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.MODEL_INVOCATION, 1L, null, ActivationState.PARKED, at));
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
}
