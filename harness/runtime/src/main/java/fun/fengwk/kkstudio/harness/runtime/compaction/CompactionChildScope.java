package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.UUID;

/**
 * 压缩子执行树的无状态事实判定：只复用既有 durable Join purpose 事实，不引入任何持久化标志，也不按 Agent name 猜身份。
 *
 * <p>两个判定互不相同且必须共用同一事实来源：{@link #isCompactionChild} 是单个 Model invocation 的压缩子身份（直接 child）， {@link
 * #isInCompactionChildTree} 是防递归 guard（自身或任一祖先）。压缩子自身的超限失败回合、子执行树内的手动入口都必须被后者拦住。
 */
public final class CompactionChildScope {

  private CompactionChildScope() {}

  /** 指定 Thread 是否是一个尚未结算的 COMPACTION Join 的直接 child。 */
  public static boolean isCompactionChild(HarnessStore.Transaction tx, UUID threadId) {
    for (ThreadJoin join : tx.loadIncompleteJoins(threadId)) {
      if (join.purpose() == JoinPurpose.COMPACTION) {
        return true;
      }
    }
    return false;
  }

  /** 指定 Thread 是否位于 COMPACTION 子执行树内：自身或任一祖先持有尚未结算的 COMPACTION Join。 */
  public static boolean isInCompactionChildTree(HarnessStore.Transaction tx, ThreadState thread) {
    for (UUID ancestorThreadId : tx.findAncestorChain(thread.id())) {
      if (isCompactionChild(tx, ancestorThreadId)) {
        return true;
      }
    }
    return false;
  }
}
