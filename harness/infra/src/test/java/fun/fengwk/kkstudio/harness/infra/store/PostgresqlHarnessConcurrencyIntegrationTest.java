package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreCallProbe.wrap;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

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
 * 真实 PostgreSQL 并发回归：持根树锁的合法深删除在另一事务的「首次 Thread 读取」与「取得树 advisory 锁」之间提交时，
 * 线程生命周期协调器在树锁内重读目标线程并把它当作合法缺失（null），而不是按结构漂移 fail-closed。
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
  void lockThreadWithParentReturnsNullWhenSessionDeletedDuringTreeLockWait() throws Exception {
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
                          ThreadLifecycleCoordinator.lockThreadWithParent(
                              tx, baseline.threadId())));
      assertTrue(firstReadDone.await(10, TimeUnit.SECONDS), "first thread read must complete");
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
            "threads.find:" + baseline.threadId(),
            "threads.lockTree:" + baseline.threadId(),
            "threads.find:" + baseline.threadId()),
        pausing.operations(),
        "coordinator must re-read the target after the tree lock and return before any row lock");
  }

  /** 深删除：持根树锁后按 Session -> Thread 顺序删除执行事实与 Entry/Session。 */
  private void deleteSessionAndThread(Baseline baseline) {
    store.transaction(
        tx -> {
          tx.threads().lockTree(baseline.threadId());
          tx.sessions().lockForUpdate(baseline.sessionId()).orElseThrow();
          tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads().delete(List.of(baseline.threadId()));
          tx.entries().delete(baseline.sessionId());
          tx.sessions().delete(baseline.sessionId());
          return null;
        });
  }

  /**
   * 在目标线程的首次 Thread 读取之后插入确定的暂停点：该读取仍返回删除前的真实行，随后等待并发深删除提交，
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
                  wrap(
                      tx,
                      (call, next) -> {
                        String name = call.operation();
                        // 记录全部 lock* 与 Thread 读取：任何业务行锁都会出现在完整操作序列里。
                        if (name.contains(".lock") || name.equals("threads.find")) {
                          operations.add(name + ":" + call.argument(0));
                        }
                        if (name.equals("threads.find")
                            && pausedThreadId.equals(call.argument(0))) {
                          Object thread = next.invoke();
                          if (paused.compareAndSet(false, true)) {
                            firstReadDone.countDown();
                            await(deletionCommitted);
                          }
                          return thread;
                        }
                        return next.invoke();
                      })));
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
