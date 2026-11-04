package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/** 会话删除闭包与规范锁序；产品清理回调在同一事务内执行。 */
final class SessionDeletion {
  private SessionDeletion() {}

  static void delete(
      HarnessStore.Transaction tx,
      List<UUID> sessionIds,
      Consumer<List<UUID>> deleteRelations,
      Consumer<List<UUID>> releaseRefs) {
    DeletionScope scope = discoverScope(tx, sessionIds);
    for (UUID root : scope.roots()) {
      tx.threads().lockTree(root);
    }
    List<UUID> presentSessions = new ArrayList<>(scope.sessions().size());
    for (UUID sessionId : scope.sessions()) {
      if (tx.sessions().lockForUpdate(sessionId).isPresent()) {
        presentSessions.add(sessionId);
      }
    }
    DeletionScope confirmed = discoverScope(tx, presentSessions);
    if (!scope.sessions().containsAll(confirmed.sessions())
        || !scope.roots().containsAll(confirmed.roots())) {
      throw new IllegalStateException(
          "harness execution tree changed while acquiring its deletion locks");
    }
    deleteRelations.accept(List.copyOf(presentSessions));
    deleteThreadsDeep(tx, confirmed.threads());
    for (UUID sessionId : confirmed.sessions()) {
      releaseRefs.accept(List.of(sessionId));
      tx.entries().delete(sessionId);
      tx.sessions().delete(sessionId);
    }
  }

  private static void deleteThreadsDeep(HarnessStore.Transaction tx, Set<UUID> threadIds) {
    List<UUID> ordered = threadIds.stream().sorted(UuidOrder.COMPARATOR).toList();
    for (UUID threadId : ordered) {
      tx.threads().lock(threadId).orElseThrow();
    }
    for (UUID threadId : ordered) {
      ThreadState thread = tx.threads().find(threadId).orElseThrow();
      if (thread.parentThreadId() != null && !threadIds.contains(thread.parentThreadId())) {
        throw new IllegalStateException(
            "cannot delete a child thread while its execution parent survives");
      }
      if (tx.threads().listChildren(threadId).stream()
          .anyMatch(child -> !threadIds.contains(child.id()))) {
        throw new IllegalStateException(
            "cannot delete a parent thread while its execution children survive");
      }
    }
    if (ordered.isEmpty()) {
      return;
    }
    tx.joins().deleteForThreads(ordered);
    if (tx.threads().delete(ordered) != ordered.size()) {
      throw new IllegalStateException("not all locked threads were deleted");
    }
  }

  private record DeletionScope(List<UUID> sessions, Set<UUID> threads, List<UUID> roots) {}

  /** 以 Session 为单位推进，以覆盖子 Session 中无父 root fork；锁前后各发现一次。 */
  private static DeletionScope discoverScope(
      HarnessStore.Transaction tx, List<UUID> initialSessions) {
    Set<UUID> sessions = new TreeSet<>(UuidOrder.COMPARATOR);
    Set<UUID> threads = new HashSet<>();
    List<UUID> pending = new ArrayList<>(initialSessions);
    for (int index = 0; index < pending.size(); index++) {
      UUID sessionId = pending.get(index);
      if (!sessions.add(sessionId)) {
        continue;
      }
      for (ThreadState thread : tx.threads().listBySession(sessionId)) {
        threads.add(thread.id());
        for (ThreadState child : tx.threads().listChildren(thread.id())) {
          if (!sessions.contains(child.sessionId())) {
            pending.add(child.sessionId());
          }
        }
      }
    }
    List<UUID> roots =
        threads.stream()
            .map(id -> tx.threads().find(id))
            .flatMap(Optional::stream)
            .map(ThreadState::rootThreadId)
            .distinct()
            .sorted(UuidOrder.COMPARATOR)
            .toList();
    return new DeletionScope(List.copyOf(sessions), Set.copyOf(threads), roots);
  }
}
