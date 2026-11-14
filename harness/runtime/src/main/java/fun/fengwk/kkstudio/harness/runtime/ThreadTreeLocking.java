package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 接受命令与 Agent spawn 共用的执行树锁阶梯：Tree Advisory Lock → Session (KEY SHARE) → Thread (FOR UPDATE)。
 *
 * <p>先在树锁内确定完整行锁集合，再按 UuidOrder 排序后逐级加锁；任何一步都不重新进入树锁。
 */
final class ThreadTreeLocking {

  /** 已锁定的锚点祖先链与其行状态；{@code chain} 保持祖先到锚点的顺序。 */
  static final class LockedAncestors {
    final List<UUID> chain;
    final Map<UUID, ThreadState> threads;

    LockedAncestors(List<UUID> chain, Map<UUID, ThreadState> threads) {
      this.chain = chain;
      this.threads = threads;
    }
  }

  private ThreadTreeLocking() {}

  /**
   * 锁定 {@code anchorThreadId} 所在的整棵树、其祖先链，以及调用方额外声明、必须同属该锚点的 Thread id。
   *
   * @param anchorIsParent 为 true 时锚点必须是仍然存在的父 Thread，缺失按 join parent 语义拒绝
   */
  static LockedAncestors lockTreeAndAncestors(
      HarnessStore.Transaction tx,
      UUID anchorThreadId,
      boolean anchorIsParent,
      Set<UUID> additionalThreadIds) {
    lockTree(tx, anchorThreadId, anchorIsParent);
    List<UUID> chain = tx.threads().findAncestorChain(anchorThreadId);
    if (anchorIsParent && chain.isEmpty()) {
      throw DomainException.notFound("thread", "join parent no longer exists");
    }
    Set<UUID> allThreadIds =
        collectLockedThreadIds(tx, anchorThreadId, anchorIsParent, chain, additionalThreadIds);
    lockSessions(tx, allThreadIds, chain, anchorIsParent);
    return new LockedAncestors(chain, lockThreads(tx, allThreadIds, chain, anchorIsParent));
  }

  private static void lockTree(
      HarnessStore.Transaction tx, UUID anchorThreadId, boolean anchorIsParent) {
    ThreadState anchor = tx.threads().find(anchorThreadId).orElse(null);
    if (anchorIsParent && anchor == null) {
      throw new IllegalArgumentException("join parent does not exist");
    }
    tx.threads().lockTree(anchor == null ? anchorThreadId : anchor.rootThreadId());
  }

  /** 祖先链 + 仍存在且父引用与锚点一致的额外 Thread id；父引用不一致即 Thread id 复用冲突。 */
  private static Set<UUID> collectLockedThreadIds(
      HarnessStore.Transaction tx,
      UUID anchorThreadId,
      boolean anchorIsParent,
      List<UUID> chain,
      Set<UUID> additionalThreadIds) {
    Set<UUID> allThreadIds = new LinkedHashSet<>(chain);
    if (additionalThreadIds == null) {
      return allThreadIds;
    }
    for (UUID id : additionalThreadIds) {
      ThreadState existing = tx.threads().find(id).orElse(null);
      if (existing == null) {
        continue;
      }
      if (!Objects.equals(existing.parentThreadId(), anchorThreadId)) {
        throw conflict(
            HarnessConflictReason.THREAD_ID_REUSED,
            "thread " + id + " already belongs to another execution parent");
      }
      allThreadIds.add(id);
    }
    return allThreadIds;
  }

  private static void lockSessions(
      HarnessStore.Transaction tx,
      Set<UUID> allThreadIds,
      List<UUID> chain,
      boolean anchorIsParent) {
    Set<UUID> sessionIds = new LinkedHashSet<>();
    for (UUID id : allThreadIds) {
      ThreadState state =
          tx.threads().find(id).orElseThrow(() -> missingThread(id, chain, anchorIsParent));
      sessionIds.add(state.sessionId());
    }
    List<UUID> sortedSessionIds = sessionIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID sessionId : sortedSessionIds) {
      tx.sessions().lockForKeyShare(sessionId).orElseThrow();
    }
  }

  private static Map<UUID, ThreadState> lockThreads(
      HarnessStore.Transaction tx,
      Set<UUID> allThreadIds,
      List<UUID> chain,
      boolean anchorIsParent) {
    List<UUID> sortedThreadIds = allThreadIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    Map<UUID, ThreadState> lockedThreads = new HashMap<>();
    for (UUID id : sortedThreadIds) {
      ThreadState locked =
          tx.threads().lock(id).orElseThrow(() -> missingThread(id, chain, anchorIsParent));
      lockedThreads.put(id, locked);
    }
    return lockedThreads;
  }

  /** 已锁定的行在锁内消失：锚点父缺失按 join parent 语义拒绝，其余为 not-found。 */
  private static RuntimeException missingThread(
      UUID threadId, List<UUID> chain, boolean anchorIsParent) {
    if (anchorIsParent && chain.contains(threadId)) {
      return new IllegalArgumentException("join parent does not exist");
    }
    return DomainException.notFound("thread", "thread " + threadId + " does not exist");
  }

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }
}
