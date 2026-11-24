package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

/** PostgreSQL-backed checks for lock order across Store callbacks. */
class PostgresqlHarnessLockLedgerTest {
  private HarnessStore store;
  private TransactionTemplate outer;
  private TransactionTemplate requiresNew;
  private Baseline first;
  private Baseline second;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    first = seedThreadBaseline(store);
    second = seedThreadBaseline(store);
    DataSourceTransactionManager manager =
        new DataSourceTransactionManager(PostgresqlHarnessStoreFixture.dataSource());
    outer = new TransactionTemplate(manager);
    requiresNew = new TransactionTemplate(manager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void rejectsReverseRankAcrossCallbacks() {
    outer.executeWithoutResult(
        status -> {
          store.transaction(tx -> tx.threads().lock(first.threadId()).orElseThrow());
          store.transaction(
              tx -> {
                var error =
                    assertThrows(
                        IllegalStateException.class,
                        () -> tx.sessions().lockForUpdate(second.sessionId()));
                assertTrue(error.getMessage().contains("cannot acquire SESSION after THREAD"));
                return null;
              });
        });
  }

  @Test
  void allowsIncreasingRankAndReusesAlreadyLockedRowsAcrossCallbacks() {
    outer.executeWithoutResult(
        status -> {
          store.transaction(tx -> tx.sessions().lockForUpdate(first.sessionId()).orElseThrow());
          store.transaction(tx -> tx.threads().lock(first.threadId()).orElseThrow());
          HarnessStore sameDatabase = PostgresqlHarnessStoreFixture.create();
          sameDatabase.transaction(
              tx -> {
                tx.sessions().lockForKeyShare(first.sessionId()).orElseThrow();
                tx.sessions().lockForUpdate(first.sessionId()).orElseThrow();
                tx.threads().lock(first.threadId()).orElseThrow();
                return null;
              });
        });
  }

  @Test
  void suspendsAndResumesLedgerAroundRequiresNewTransaction() {
    outer.executeWithoutResult(
        status -> {
          store.transaction(tx -> tx.threads().lock(first.threadId()).orElseThrow());
          requiresNew.executeWithoutResult(
              inner ->
                  store.transaction(
                      tx -> tx.sessions().lockForUpdate(second.sessionId()).orElseThrow()));
          store.transaction(
              tx -> {
                assertThrows(
                    IllegalStateException.class,
                    () -> tx.sessions().lockForUpdate(second.sessionId()));
                return null;
              });
        });
  }
}
