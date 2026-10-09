package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Optional;
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

  /**
   * 直接压缩子 Thread 的父 COMPACTION TURN_START 冻结事实：该 Thread 自身持有未结算 COMPACTION Join 时返回父路径上与该 Join
   * invocation id 精确匹配的 {@link CompactionStart}；不是直接压缩子（普通 Thread / TASK subagent）时返回 {@link
   * Optional#empty()}，调用方因此不受影响。
   *
   * <p>事实缺失一律 fail closed：Join 缺少父 Thread、父 Thread 缺失、或父路径缺少匹配该 Join 的冻结 TURN_START 都抛 {@link
   * IllegalStateException}，绝不退化为“无 cap 的普通请求”，也绝不按 Agent name 猜身份。
   */
  public static Optional<CompactionStart> frozenStartFor(
      HarnessStore.Transaction tx, UUID childThreadId) {
    for (ThreadJoin join : tx.loadIncompleteJoins(childThreadId)) {
      if (join.purpose() != JoinPurpose.COMPACTION) {
        continue;
      }
      UUID parentThreadId = join.parentThreadId();
      if (parentThreadId == null) {
        throw new IllegalStateException(
            "COMPACTION join " + join.invocationId() + " has no parent thread");
      }
      ThreadState parent =
          tx.findThread(parentThreadId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "COMPACTION join "
                              + join.invocationId()
                              + " references missing parent thread "
                              + parentThreadId));
      for (Entry entry : tx.loadEntryPath(parent.headEntryId()).entries()) {
        if (entry.payload() instanceof TurnStartPayload start
            && start.reason() == TurnStartReason.COMPACTION
            && start.compaction() != null
            && join.invocationId().equals(start.compaction().joinInvocationId())) {
          return Optional.of(start.compaction());
        }
      }
      throw new IllegalStateException(
          "COMPACTION join "
              + join.invocationId()
              + " has no frozen compaction turn start on the parent path");
    }
    return Optional.empty();
  }
}
