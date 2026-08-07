package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class PostgresqlHarnessStoreTransactionTest {

  private HarnessStore store;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
  }

  @Test
  void commitsChangesAndMakesThemVisibleToLaterTransactions() {
    store.transaction(
        tx -> {
          tx.insertSession(session(1));
          return null;
        });
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isPresent()));
  }

  @Test
  void returnsNullWhenCallbackReturnsNull() {
    assertNull(store.transaction(tx -> null));
  }

  @Test
  void rollsBackOnRuntimeExceptionAndRethrowsTheSameInstance() {
    RuntimeException failure = new IllegalStateException("boom");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void rollsBackOnErrorAndRethrowsTheSameInstance() {
    Error failure = new AssertionError("boom");
    Error thrown =
        assertThrows(
            Error.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void callbackDataIntegrityExceptionIsRethrownAsTheSameInstance() {
    DataIntegrityViolationException failure =
        new DataIntegrityViolationException("callback failure");
    DataIntegrityViolationException thrown =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void nextIdStartsAtOneAndSequenceGapsSurviveRollback() {
    assertEquals(1L, store.transaction(HarnessStore.Transaction::nextId));
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  assertEquals(2L, tx.nextId());
                  throw new IllegalStateException("boom");
                }));
    assertEquals(3L, store.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void nextIdExhaustionIsReportedAsArithmeticException() {
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    jdbc.queryForObject(
        "select setval('harness_runtime_id_seq', 9223372036854775807, true)", Long.class);
    assertThrows(
        ArithmeticException.class, () -> store.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void transactionHandleIsRejectedAfterCommit() {
    HarnessStore.Transaction escaped = store.transaction(tx -> tx);
    assertThrows(IllegalStateException.class, escaped::nextId);
    assertThrows(IllegalStateException.class, () -> escaped.insertSession(session(1)));
    assertThrows(IllegalStateException.class, () -> escaped.findSession(1));
    assertThrows(IllegalStateException.class, () -> escaped.loadEntryPath(1));
  }

  @Test
  void transactionHandleIsRejectedAfterRollback() {
    HarnessStore.Transaction[] escaped = new HarnessStore.Transaction[1];
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  escaped[0] = tx;
                  throw new IllegalStateException("boom");
                }));
    assertThrows(IllegalStateException.class, escaped[0]::nextId);
    assertThrows(IllegalStateException.class, () -> escaped[0].findSession(1));
  }

  @Test
  void transactionHandleRejectsCrossThreadUseWhileCallbackIsActive() {
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      store.transaction(
          tx -> {
            IllegalStateException thrown =
                CompletableFuture.supplyAsync(
                        () ->
                            assertThrows(
                                IllegalStateException.class, () -> tx.insertSession(session(1))),
                        executor)
                    .join();
            assertEquals(
                "transaction handle may only be used by its owner thread", thrown.getMessage());
            return null;
          });
    }
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void nestedTransactionIsRejectedAndTheOuterTransactionRollsBack() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      store.transaction(inner -> null);
                      return null;
                    }));
    assertEquals("nested transactions are not supported", thrown.getMessage());
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void rejectsNullCallback() {
    assertThrows(NullPointerException.class, () -> store.transaction(null));
  }

  @Test
  void integrityViolationsUseTheStoreContractExceptionAndRollback() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      tx.insertSession(session(1));
                      return null;
                    }));
    assertTrue(thrown.getMessage().contains("integrity constraint"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void caughtIntegrityViolationStillFailsAndRollsBackTheTransaction() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      try {
                        tx.insertSession(session(1));
                      } catch (IllegalArgumentException ignored) {
                        // PostgreSQL 已中止该事务：正常返回不再合法。
                      }
                      return "must not commit";
                    }));
    assertTrue(thrown.getMessage().contains("integrity constraint"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void caughtAdapterDataAccessFailureStillFailsAndRollsBackTheTransaction() {
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    jdbc.execute("alter table harness_entry rename to harness_entry_unavailable");
    AtomicReference<DataAccessException> caught = new AtomicReference<>();

    DataAccessException thrown =
        assertThrows(
            DataAccessException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(1));
                      try {
                        tx.findEntry(1);
                      } catch (DataAccessException failure) {
                        caught.set(failure);
                      }
                      return "must not commit";
                    }));

    assertSame(caught.get(), thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));
  }

  @Test
  void storeCommitIsIndependentFromAnOuterSpringTransaction() {
    TransactionTemplate outer =
        new TransactionTemplate(
            new DataSourceTransactionManager(PostgresqlHarnessStoreFixture.dataSource()));
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());

    assertThrows(
        IllegalStateException.class,
        () ->
            outer.execute(
                status -> {
                  store.transaction(
                      tx -> {
                        tx.insertSession(session(1));
                        return null;
                      });
                  jdbc.update(
                      "insert into harness_session (id, title, created_at) values (2, null, now())");
                  throw new IllegalStateException("rollback outer");
                }));

    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isPresent()));
    assertFalse(store.<Boolean>transaction(tx -> tx.findSession(2).isPresent()));
  }

  @Test
  void independentThreadsCanRunTransactionsConcurrently() throws Exception {
    CountDownLatch bothInside = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> first =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.insertSession(session(1));
                        bothInside.countDown();
                        await(release);
                        return null;
                      }));
      Future<Void> second =
          executor.submit(
              () ->
                  store.transaction(
                      tx -> {
                        tx.insertSession(session(2));
                        bothInside.countDown();
                        await(release);
                        return null;
                      }));
      assertTrue(bothInside.await(10, TimeUnit.SECONDS));
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isPresent()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(2).isPresent()));
  }

  @Test
  void publicDataSourceConstructorRejectsNull() {
    assertThrows(NullPointerException.class, () -> new PostgresqlHarnessStore(null));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
