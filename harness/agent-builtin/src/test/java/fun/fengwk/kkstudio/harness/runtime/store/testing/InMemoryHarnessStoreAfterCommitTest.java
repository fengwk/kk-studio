package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存 Store 的 {@code afterCommit} 语义：只在物理 commit 成功后执行恰一次；rollback 丢弃已登记回调，绝不把失败事务的
 * 副作用泄漏到后续事务（否则会变成“数据库从未提交、进程内却已执行”的分裂状态）。
 */
class InMemoryHarnessStoreAfterCommitTest {

  /** 测试意图：动作在提交状态已可见之后、且 transaction 返回之前执行恰一次。 */
  @Test
  void afterCommitRunsExactlyOnceAfterTheCommittedStateIsVisible() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Baseline baseline = seedThreadBaseline(store);
    long before =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow().version());
    List<String> events = new ArrayList<>();

    store.transaction(
        tx -> {
          ThreadState locked = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(locked.touchVersion(T1));
          store.afterCommit(
              () -> {
                // commit 已完成：动作内部的新事务必须能看到本事务写入的新 version。
                long visible =
                    store.transaction(
                        inner -> inner.findThread(baseline.threadId()).orElseThrow().version());
                assertEquals(before + 1, visible);
                events.add("after-commit");
              });
          events.add("callback");
          return null;
        });

    assertEquals(List.of("callback", "after-commit"), events);
    long after =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow().version());
    assertEquals(before + 1, after);
  }

  /** 测试意图：callback 抛异常（rollback）时登记的动作被丢弃，且不会被后续提交的事务误执行。 */
  @Test
  void afterCommitIsDiscardedOnRollbackAndNeverRunsLater() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    List<String> events = new ArrayList<>();

    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx -> {
                  store.afterCommit(() -> events.add("rolled-back"));
                  throw new IllegalStateException("rollback");
                }));

    assertTrue(events.isEmpty(), "rolled back transaction must not run afterCommit actions");
    // 后续成功事务绝不重放被丢弃的动作：ThreadLocal 已清理，不存在自 cancel。
    store.transaction(
        tx -> {
          store.afterCommit(() -> events.add("committed"));
          return null;
        });
    assertEquals(List.of("committed"), events);
  }

  /** 测试意图：事务外注册是编程错误，必须立即拒绝，而不是静默延迟到未来某个事务。 */
  @Test
  void afterCommitOutsideATransactionIsRejected() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    assertThrows(IllegalStateException.class, () -> store.afterCommit(() -> {}));
    assertThrows(NullPointerException.class, () -> store.afterCommit(null));
  }

  /** 内存实现没有环境事务：手动压缩的事务外守卫始终放行。 */
  @Test
  void assertNoAmbientTransactionIsANoOpForTheInMemoryStore() {
    new InMemoryHarnessStore().assertNoAmbientTransaction();
  }
}
