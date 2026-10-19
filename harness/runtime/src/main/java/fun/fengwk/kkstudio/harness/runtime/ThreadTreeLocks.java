package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 线程树锁辅助工具：在获取任何 Session/Thread/Work 业务行锁之前获取执行树事务级锁。 */
public final class ThreadTreeLocks {

  private ThreadTreeLocks() {}

  /**
   * 获取指定 Thread 所在执行树的根锁：根取自不可变的 {@code rootThreadId}，无需递归回溯或加锁后复核。
   *
   * @param tx 事务句柄，不能为 null
   * @param threadId 目标线程 ID，不能为 null
   * @return 加锁前读到的目标 Thread（未加行锁）；不存在时以目标 ID 作为根加锁并返回空
   */
  public static Optional<ThreadState> lockForThread(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(threadId, "threadId");
    Optional<ThreadState> thread = tx.findThread(threadId);
    tx.lockTree(thread.map(ThreadState::rootThreadId).orElse(threadId));
    return thread;
  }
}
