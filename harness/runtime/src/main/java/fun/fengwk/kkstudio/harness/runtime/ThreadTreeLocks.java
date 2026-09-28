package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 线程树锁辅助工具：在获取任何 Session/Thread/Work 业务行锁之前获取执行树事务级锁。 */
final class ThreadTreeLocks {

  private ThreadTreeLocks() {}

  /**
   * 按 ancestor chain 获取指定 Thread 所在执行树的根锁，并在持有树锁后重新读取 ancestor chain 进行确认。
   *
   * @param tx 事务句柄，不能为 null
   * @param threadId 目标线程 ID，不能为 null
   */
  static void lockForThread(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(threadId, "threadId");
    List<UUID> chain = tx.findAncestorChain(threadId);
    UUID root = chain.isEmpty() ? threadId : chain.get(chain.size() - 1);
    tx.lockTree(root);
    if (!chain.equals(tx.findAncestorChain(threadId))) {
      throw new IllegalStateException("execution tree changed while acquiring its lock");
    }
  }
}
