package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.EntryCleanup;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion;
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

/** 会话删除闭包与规范锁序；产品清理回调与 Driver 生命周期在同一事务内执行。 */
final class SessionDeletion {
  private SessionDeletion() {}

  /** 删除闭包：Driver 私有行必须由显式生命周期在 Thread 行之前清理。 */
  static void delete(
      HarnessStore.Transaction tx,
      List<UUID> sessionIds,
      Consumer<List<UUID>> deleteRelations,
      Consumer<List<UUID>> releaseRefs,
      DriverThreadDeletion driverDeletion) {
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
    List<UUID> locked = lockSettledThreads(tx, confirmed.threads());
    deleteRelations.accept(List.copyOf(presentSessions));
    deleteThreadsDeep(tx, locked, driverDeletion);
    for (UUID sessionId : confirmed.sessions()) {
      releaseRefs.accept(List.of(sessionId));
      tx.entries().delete(sessionId);
      tx.sessions().delete(sessionId);
    }
  }

  static void deleteThread(
      HarnessStore.Transaction tx,
      UUID rootId,
      Long expectedVersion,
      EntryCleanup cleanup,
      DriverThreadDeletion driverDeletion) {
    DeletionScope scope = discoverTree(tx, rootId);
    tx.threads().lockTree(rootId);
    for (UUID sessionId : scope.sessions()) {
      tx.sessions().lockForUpdate(sessionId).orElseThrow();
    }
    DeletionScope confirmed = discoverTree(tx, rootId);
    if (!scope.sessions().containsAll(confirmed.sessions())) {
      throw new IllegalStateException(
          "harness execution tree changed while acquiring its deletion locks");
    }
    List<UUID> locked = lockSettledThreads(tx, confirmed.threads());
    if (expectedVersion != null) {
      ThreadState target = tx.threads().find(rootId).orElseThrow();
      if (target.version() != expectedVersion) {
        throw DomainException.versionConflict("thread", expectedVersion, target.version());
      }
      if (tx.threads().listBySession(target.sessionId()).stream()
          .noneMatch(
              t ->
                  !rootId.equals(t.id())
                      && t.kind() == ThreadKind.USER
                      && t.parentThreadId() == null)) {
        throw DomainException.conflict(
            "RESOURCE_IN_USE", "Delete the session to delete its last public thread");
      }
    }
    deleteThreadsDeep(tx, locked, driverDeletion);
    for (UUID sessionId : confirmed.sessions()) {
      collectHistory(tx, sessionId, cleanup);
    }
  }

  private static DeletionScope discoverTree(HarnessStore.Transaction tx, UUID rootId) {
    tx.threads()
        .find(rootId)
        .filter(t -> t.parentThreadId() == null && t.kind() != ThreadKind.COMPACTION)
        .orElseThrow(
            () -> DomainException.notFound("thread", "public execution root does not exist"));
    List<ThreadState> tree = tx.threads().listByRoot(rootId);
    return new DeletionScope(
        tree.stream().map(ThreadState::sessionId).distinct().sorted(UuidOrder.COMPARATOR).toList(),
        Set.copyOf(tree.stream().map(ThreadState::id).toList()),
        List.of(rootId));
  }

  private static void collectHistory(
      HarnessStore.Transaction tx, UUID sessionId, EntryCleanup cleanup) {
    Set<UUID> heads = new HashSet<>();
    tx.threads().listBySession(sessionId).forEach(t -> heads.add(t.headEntryId()));
    List<Entry> all = tx.entries().loadBySession(sessionId);
    List<Entry> retained = new ArrayList<>();
    List<Entry> removed = new ArrayList<>();
    for (Entry entry : all.reversed()) {
      if (heads.contains(entry.id()) || entry.payload() instanceof RootPayload) {
        retained.add(entry);
        heads.add(entry.parentEntryId());
      } else {
        removed.add(entry);
      }
    }
    if (!removed.isEmpty()) {
      tx.entries().deleteEntries(removed.stream().map(Entry::id).toList());
      cleanup.accept(sessionId, List.copyOf(removed), List.copyOf(retained));
    }
  }

  private static List<UUID> lockSettledThreads(HarnessStore.Transaction tx, Set<UUID> threadIds) {
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
    for (UUID threadId : ordered) {
      ThreadState thread = tx.threads().find(threadId).orElseThrow();
      if (!ThreadLifecycleCoordinator.isQuiescent(tx, thread)
          || !tx.commands().loadQueued(threadId).isEmpty()
          || ThreadInputDemand.hasUnconsumedNotification(tx, thread)
          || !tx.joins().loadIncomplete(threadId).isEmpty()) {
        throw DomainException.conflict(
            HarnessConflictReason.EXECUTION_NOT_SETTLED.name(),
            "execution is not settled: " + threadId);
      }
    }
    return ordered;
  }

  private static void deleteThreadsDeep(
      HarnessStore.Transaction tx, List<UUID> ordered, DriverThreadDeletion driverDeletion) {
    if (ordered.isEmpty()) {
      return;
    }
    tx.joins().deleteForThreads(ordered);
    if (tx.threads().delete(ordered, driverDeletion) != ordered.size()) {
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
