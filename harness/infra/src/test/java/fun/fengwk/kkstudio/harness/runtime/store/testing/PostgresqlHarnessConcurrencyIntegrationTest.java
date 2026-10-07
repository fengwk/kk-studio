package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadTreeLocks;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 真实 PostgreSQL 并发回归：持根树锁的合法深删除在另一事务的「首次 ancestor 读取」与「取得树 advisory 锁」之间提交时， 树锁 helper
 * 与线程生命周期协调器把目标线程当作合法缺失（空链 / null），而不是按结构漂移 fail-closed。
 *
 * <p>时序由有界 latch 精确控制，不依赖 sleep；删除失败时在 finally 释放等待方。
 */
class PostgresqlHarnessConcurrencyIntegrationTest {

  private HarnessStore store;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
  }

  @Test
  void lockForThreadTreatsConcurrentSessionDeletionDuringTreeLockWaitAsMissing() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    CountDownLatch firstReadDone = new CountDownLatch(1);
    CountDownLatch deletionCommitted = new CountDownLatch(1);
    PausingStore pausing =
        new PausingStore(store, baseline.threadId(), firstReadDone, deletionCommitted);

    List<UUID> confirmed;
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<List<UUID>> waiting =
          executor.submit(
              () ->
                  pausing.transaction(
                      tx -> ThreadTreeLocks.lockForThread(tx, baseline.threadId())));
      assertTrue(firstReadDone.await(10, TimeUnit.SECONDS), "first ancestor read must complete");
      try {
        deleteSessionAndThread(baseline);
      } finally {
        deletionCommitted.countDown();
      }
      confirmed = waiting.get(10, TimeUnit.SECONDS);
    }

    assertTrue(confirmed.isEmpty(), "concurrently deleted thread must be a legal missing chain");
    // 完整操作序列：首次读取 -> 树锁 -> 加锁后确认读取；全程无任何业务行锁，故不出现 lock 秩乱。
    assertEquals(
        List.of(
            "findAncestorChain:" + baseline.threadId(),
            "lockTree:" + baseline.threadId(),
            "findAncestorChain:" + baseline.threadId()),
        pausing.operations(),
        "helper must re-read after the tree lock without taking any business row lock");
    assertTrue(
        store.transaction(tx -> tx.findThread(baseline.threadId())).isEmpty(),
        "fixture must have deleted the thread");
  }

  @Test
  void lockThreadWithAncestorsReturnsNullWhenSessionDeletedDuringTreeLockWait() throws Exception {
    Baseline baseline = seedThreadBaseline(store);
    CountDownLatch firstReadDone = new CountDownLatch(1);
    CountDownLatch deletionCommitted = new CountDownLatch(1);
    PausingStore pausing =
        new PausingStore(store, baseline.threadId(), firstReadDone, deletionCommitted);

    Object locked;
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<Object> waiting =
          executor.submit(
              () ->
                  pausing.transaction(
                      tx ->
                          ThreadLifecycleCoordinator.lockThreadWithAncestors(
                              tx, baseline.threadId())));
      assertTrue(firstReadDone.await(10, TimeUnit.SECONDS), "first ancestor read must complete");
      try {
        deleteSessionAndThread(baseline);
      } finally {
        deletionCommitted.countDown();
      }
      locked = waiting.get(10, TimeUnit.SECONDS);
    }

    assertNull(locked, "concurrently deleted thread must yield null, not a drift failure");
    assertEquals(
        List.of(
            "findAncestorChain:" + baseline.threadId(),
            "lockTree:" + baseline.threadId(),
            "findAncestorChain:" + baseline.threadId()),
        pausing.operations(),
        "coordinator must return before taking any Session/Thread row lock");
  }

  /** 深删除：持根树锁后按 Session -> Thread 顺序删除执行事实与 Entry/Session。 */
  private void deleteSessionAndThread(Baseline baseline) {
    store.transaction(
        tx -> {
          tx.lockTree(baseline.threadId());
          tx.lockSessionForUpdate(baseline.sessionId()).orElseThrow();
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.deleteThreads(List.of(baseline.threadId()));
          tx.deleteEntries(baseline.sessionId());
          tx.deleteSession(baseline.sessionId());
          return null;
        });
  }

  /**
   * 在目标线程的首次 ancestor 读取之后插入确定的暂停点：该读取仍返回删除前的真实链，随后等待并发深删除提交，
   * 使后续「取得树锁后的确认读取」观察到已删除状态——即真实场景中锁等待期间的合法并发删除。
   */
  private static final class PausingStore implements HarnessStore {

    private final HarnessStore delegate;
    private final UUID pausedThreadId;
    private final CountDownLatch firstReadDone;
    private final CountDownLatch deletionCommitted;
    private final List<String> operations = new CopyOnWriteArrayList<>();
    private final AtomicBoolean paused = new AtomicBoolean();

    PausingStore(
        HarnessStore delegate,
        UUID pausedThreadId,
        CountDownLatch firstReadDone,
        CountDownLatch deletionCommitted) {
      this.delegate = delegate;
      this.pausedThreadId = pausedThreadId;
      this.firstReadDone = firstReadDone;
      this.deletionCommitted = deletionCommitted;
    }

    List<String> operations() {
      return operations;
    }

    @Override
    public void afterCommit(Runnable action) {
      delegate.afterCommit(action);
    }

    @Override
    public void assertNoAmbientTransaction() {
      delegate.assertNoAmbientTransaction();
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      return delegate.transaction(
          tx ->
              callback.apply(
                  (Transaction)
                      Proxy.newProxyInstance(
                          Transaction.class.getClassLoader(),
                          new Class<?>[] {Transaction.class},
                          (proxy, method, args) -> {
                            String name = method.getName();
                            // 记录全部 lock* 与每次 ancestor 读取：任何业务行锁都会出现在完整操作序列里。
                            if (name.startsWith("lock") || name.equals("findAncestorChain")) {
                              operations.add(name + ":" + args[0]);
                            }
                            if (name.equals("findAncestorChain")
                                && pausedThreadId.equals(args[0])) {
                              @SuppressWarnings("unchecked")
                              List<UUID> chain = (List<UUID>) invoke(tx, method, args);
                              if (paused.compareAndSet(false, true)) {
                                firstReadDone.countDown();
                                await(deletionCommitted);
                              }
                              return chain;
                            }
                            return invoke(tx, method, args);
                          })));
    }

    private static Object invoke(Transaction tx, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(tx, args);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("timed out waiting for the concurrent deletion");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "interrupted while waiting for the concurrent deletion", error);
      }
    }
  }
}
