package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 线程树锁辅助工具：在获取任何 Session/Thread/Work 业务行锁之前获取执行树事务级锁。 */
public final class ThreadTreeLocks {

  private ThreadTreeLocks() {}

  /**
   * 按 ancestor chain 获取指定 Thread 所在执行树的根锁，并在持有树锁后重新读取 ancestor chain 进行确认。
   *
   * <p>首次读取为空表示目标线程缺失，此时以目标 ID 作为根提示加锁；加锁后重读为空仍是合法缺失，返回空链。只有确认链非空且与首次读取不一致，才说明执行树发生了真实结构漂移（ {@code
   * parentThreadId} 不可变，结构写入由根互斥串行化），此时 fail-closed 抛出 {@link IllegalStateException}。
   *
   * @param tx 事务句柄，不能为 null
   * @param threadId 目标线程 ID，不能为 null
   * @return 持有树锁后确认的 head-to-root 祖先链；目标线程不存在或被并发删除时返回空列表
   */
  public static List<UUID> lockForThread(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(threadId, "threadId");
    List<UUID> hint = tx.findAncestorChain(threadId);
    UUID root = hint.isEmpty() ? threadId : hint.get(hint.size() - 1);
    tx.lockTree(root);
    List<UUID> confirmed = tx.findAncestorChain(threadId);
    if (confirmed.isEmpty()) {
      return List.of();
    }
    if (!hint.equals(confirmed)) {
      throw new IllegalStateException("execution tree changed while acquiring its lock");
    }
    return confirmed;
  }
}
