package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 线程树锁辅助工具：仅供真正修改树结构或跨 Thread 写入的事务，在获取任何 Session/Thread/Work 业务行锁之前获取执行树事务级锁。
 *
 * <p>经核实，{@code HarnessStore.Transaction#lockThread} 只对目标 {@code harness_thread} 行取 {@code FOR
 * UPDATE}，不会额外先取 Session {@code KEY SHARE}；单实体控制只需要这一把 own Thread 行锁，不要为补锁序而额外获取 Session 或祖先 Thread
 * 锁。
 */
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
