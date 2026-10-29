package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.UUID;
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
    UUID sessionId = TestIds.id(1);
    store.transaction(
        tx -> {
          tx.insertSession(session(sessionId));
          return null;
        });
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(sessionId).isPresent()));
  }

  @Test
  void returnsNullWhenCallbackReturnsNull() {
    assertNull(store.transaction(tx -> null));
  }

  @Test
  void rollsBackOnRuntimeExceptionAndRethrowsTheSameInstance() {
    RuntimeException failure = new IllegalStateException("boom");
    UUID sessionId = TestIds.id(1);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(sessionId));
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(sessionId).isEmpty()));
  }

  @Test
  void rollsBackOnError() {
    Error failure = new AssertionError("boom");
    UUID sessionId = TestIds.id(1);
    Error thrown =
        assertThrows(
            Error.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(sessionId));
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(sessionId).isEmpty()));
  }

  @Test
  void rollsBackInputDriverModelAndWorkInTheSameTransaction() {
    // 测试意图：同事务写入输入历史、Builtin 私有 ModelInvocation 与两类 Work 后失败，三者必须一起不可见；
    // 使用真实 InMemoryHarnessStore 的 copy-on-write 事务，而不是 no-op fake。
    StoreTestSupport.TurnBaseline baseline = seedTurnBaseline(store);
    IllegalArgumentException failure = new IllegalArgumentException("same transaction failure");
    UUID inputEntryId = TestIds.id(100);
    UUID modelId = TestIds.id(200);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                store.transaction(
                    tx -> {
                      ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
                      tx.insertEntry(
                          new Entry(
                              inputEntryId,
                              baseline.sessionId(),
                              baseline.turnStartEntryId(),
                              userMessagePayload(),
                              T2));
                      tx.updateThread(thread.advanceHead(inputEntryId, T2));
                      builtin(tx)
                          .insertModelInvocation(
                              modelInvocation(
                                  modelId,
                                  baseline.threadId(),
                                  baseline.turnStartEntryId(),
                                  inputEntryId,
                                  ModelInvocationStatus.READY,
                                  null,
                                  T2));
                      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
                      tx.requestWork(new WorkTarget(WorkTargetType.MODEL, modelId));
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertTrue(store.<Boolean>transaction(tx -> tx.findEntry(inputEntryId).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(tx -> builtin(tx).findModelInvocation(modelId).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, modelId)).isEmpty()));
    assertEquals(
        baseline.turnStartEntryId(),
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow().headEntryId()));
  }

  @Test
  void nextIdStartsAtOneAndIncreasesAcrossCommittedTransactions() {
    assertEquals(TestIds.id(1), store.transaction(HarnessStore.Transaction::nextId));
    assertEquals(TestIds.id(2), store.transaction(HarnessStore.Transaction::nextId));
    assertEquals(TestIds.id(3), store.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void nextIdIsRestoredAfterRollback() {
    assertEquals(TestIds.id(1), store.transaction(HarnessStore.Transaction::nextId));
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  assertEquals(TestIds.id(2), tx.nextId());
                  throw new IllegalStateException("boom");
                }));
    assertEquals(TestIds.id(2), store.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void nextIdOverflowAbortsTheTransactionAndKeepsTheCounter() {
    InMemoryHarnessStore seeded = new InMemoryHarnessStore(Long.MAX_VALUE - 1);
    assertEquals(TestIds.id(Long.MAX_VALUE), seeded.transaction(HarnessStore.Transaction::nextId));
    assertThrows(
        ArithmeticException.class, () -> seeded.transaction(HarnessStore.Transaction::nextId));
  }

  @Test
  void transactionHandleIsRejectedAfterCommit() {
    HarnessStore.Transaction escaped = store.transaction(tx -> tx);
    UUID sessionId = TestIds.id(1);
    assertThrows(IllegalStateException.class, escaped::nextId);
    assertThrows(IllegalStateException.class, () -> escaped.insertSession(session(sessionId)));
    assertThrows(IllegalStateException.class, () -> escaped.findSession(sessionId));
    assertThrows(IllegalStateException.class, () -> escaped.loadEntryPath(sessionId));
    assertThrows(IllegalStateException.class, () -> escaped.loadEntriesBySessionId(sessionId));
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
    UUID sessionId = TestIds.id(1);
    assertThrows(IllegalStateException.class, () -> escaped[0].findSession(sessionId));
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
                                IllegalStateException.class,
                                () -> tx.insertSession(session(TestIds.id(1)))),
                        executor)
                    .join();
            assertEquals(
                "transaction handle may only be used by its owner thread", thrown.getMessage());
            return null;
          });
    }
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(TestIds.id(1)).isEmpty()));
  }

  @Test
  void nestedTransactionIsRejectedAndTheOuterTransactionRollsBack() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.transaction(
                    tx -> {
                      tx.insertSession(session(TestIds.id(1)));
                      store.transaction(inner -> null);
                      return null;
                    }));
    assertEquals("nested transactions are not supported", thrown.getMessage());
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(TestIds.id(1)).isEmpty()));
  }

  @Test
  void rejectsNullCallback() {
    assertThrows(NullPointerException.class, () -> store.transaction(null));
  }
}
