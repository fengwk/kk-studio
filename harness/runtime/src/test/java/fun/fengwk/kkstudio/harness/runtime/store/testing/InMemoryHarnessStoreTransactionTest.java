package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内存版 transaction 语义：commit 可见性、RuntimeException / Error 回滚、nextId 回滚后恢复，以及对逃逸 / 嵌套 transaction
 * 句柄的拒绝。
 */
class InMemoryHarnessStoreTransactionTest {

  private InMemoryHarnessStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
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
  void rollsBackOnError() {
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
  void nextIdStartsAtOneAndIncreasesAcrossCommittedTransactions() {
    assertEquals(1L, store.transaction(HarnessStore.Transaction::nextId));
    assertEquals(2L, store.transaction(HarnessStore.Transaction::nextId));
    assertEquals(3L, store.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void nextIdIsRestoredAfterRollback() {
    assertEquals(1L, store.transaction(HarnessStore.Transaction::nextId));
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  assertEquals(2L, tx.nextId());
                  throw new IllegalStateException("boom");
                }));
    assertEquals(2L, store.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void nextIdOverflowAbortsTheTransactionAndKeepsTheCounter() {
    InMemoryHarnessStore seeded = new InMemoryHarnessStore(Long.MAX_VALUE - 1);
    assertEquals(Long.MAX_VALUE, seeded.transaction(HarnessStore.Transaction::nextId));
    assertThrows(
        ArithmeticException.class, () -> seeded.transaction(HarnessStore.Transaction::nextId));
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
}
