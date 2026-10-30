package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thread Join 聚合原语：回执身份 {@code invocationId}，创建时必须是 unmatched 且子 Thread 已锁定。
 *
 * <p>已冻结的 Join 回执必须与子 Thread 历史一致；frozen task 策略的准入先获取全局 Join 准入锁，再获取执行树锁。 删除要求子 Thread
 * 在已持有的树锁内，且不得删除仍会被交付的 Join。
 */
final class InMemoryJoinStore implements JoinStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryJoinStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public void lockAdmission() {
    tx.checkOpen();
    tx.locks().lockJoinAdmission();
  }

  @Override
  public void insert(ThreadJoin join) {
    tx.checkOpen();
    Objects.requireNonNull(join, "join");
    HarnessStoreTime.requireMillisecondPrecision(join.createdAt());
    HarnessStoreTime.requireMillisecondPrecision(join.updatedAt());
    if (join.matched() || join.deliveryCommandSequence() != null || join.reminderTurn() != 0) {
      throw new IllegalArgumentException("new join must be unmatched");
    }
    InMemoryHarnessTransaction.requireAbsent(state.joins, join.invocationId(), "join");
    if (!state.threads.containsKey(join.childThreadId())) {
      throw new IllegalArgumentException(
          "child thread " + join.childThreadId() + " does not exist");
    }
    if (join.parentThreadId() != null && !state.threads.containsKey(join.parentThreadId())) {
      throw new IllegalArgumentException(
          "parent thread " + join.parentThreadId() + " does not exist");
    }
    InMemoryState.CommandKey sourceKey =
        new InMemoryState.CommandKey(join.childThreadId(), join.sourceCommandSequence());
    if (!state.commands.containsKey(sourceKey)) {
      throw new IllegalArgumentException(
          "source command "
              + join.childThreadId()
              + "/"
              + join.sourceCommandSequence()
              + " does not exist");
    }
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(join.childThreadId()));
    state.joins.put(join.invocationId(), join);
  }

  @Override
  public Optional<ThreadJoin> find(UUID invocationId) {
    tx.checkOpen();
    Objects.requireNonNull(invocationId, "invocationId");
    return Optional.ofNullable(state.joins.get(invocationId));
  }

  @Override
  public List<ThreadJoin> loadIncomplete(UUID childThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(childThreadId, "childThreadId");
    return state.joins.values().stream()
        .filter(
            join ->
                join.childThreadId().equals(childThreadId)
                    && !join.matched()
                    && join.supersededByInvocationId() == null)
        .sorted(
            Comparator.comparing(ThreadJoin::createdAt)
                .thenComparing(ThreadJoin::invocationId, UuidOrder.COMPARATOR))
        .toList();
  }

  @Override
  public int countIncompleteChildren(UUID parentThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    return (int)
        state.joins.values().stream()
            .filter(
                join ->
                    parentThreadId.equals(join.parentThreadId())
                        && !join.matched()
                        && join.supersededByInvocationId() == null
                        && join.delivery() == JoinDelivery.NOTIFY_PARENT)
            .count();
  }

  @Override
  public boolean hasIncompleteOwnerChild(UUID parentThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    return state.joins.values().stream()
        .anyMatch(
            join ->
                parentThreadId.equals(join.parentThreadId())
                    && !join.matched()
                    && join.supersededByInvocationId() == null
                    && join.delivery() == JoinDelivery.SETTLE_OWNER_TURN);
  }

  @Override
  public int countIncompleteSubagent() {
    tx.checkOpen();
    return (int)
        state.joins.values().stream()
            .filter(
                join ->
                    join.parentThreadId() != null
                        && join.delivery() == JoinDelivery.NOTIFY_PARENT
                        && !join.matched()
                        && join.supersededByInvocationId() == null)
            .count();
  }

  @Override
  public void update(ThreadJoin join) {
    tx.checkOpen();
    Objects.requireNonNull(join, "join");
    HarnessStoreTime.requireMillisecondPrecision(join.createdAt());
    HarnessStoreTime.requireMillisecondPrecision(join.updatedAt());
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(join.childThreadId()));
    ThreadJoin old =
        find(join.invocationId())
            .orElseThrow(
                () -> new IllegalArgumentException("join not found: " + join.invocationId()));
    ThreadJoin.validateTransition(old, join);
    requireValidJoinReceipt(join);
    if (join.terminalEntryId() != null) {
      tx.requireExistingEntry(join.terminalEntryId());
    }
    if (join.finalAnswerEntryId() != null) {
      tx.requireExistingEntry(join.finalAnswerEntryId());
    }
    if (join.deliveryCommandSequence() != null) {
      if (join.parentThreadId() == null) {
        throw new IllegalArgumentException("delivery requires a parent thread");
      }
      InMemoryState.CommandKey deliveryKey =
          new InMemoryState.CommandKey(join.parentThreadId(), join.deliveryCommandSequence());
      if (!state.commands.containsKey(deliveryKey)) {
        throw new IllegalArgumentException(
            "delivery command "
                + join.parentThreadId()
                + "/"
                + join.deliveryCommandSequence()
                + " does not exist");
      }
    }
    state.joins.put(join.invocationId(), join);
  }

  /**
   * 已冻结的 Join 回执必须与子 Thread 历史一致：terminal 与子 Thread 当前 head 必须在同一 branch（互为祖先）；terminal 是 TurnEnd
   * 时其 Turn 必须由子 Thread 拥有；final answer 是 ASSISTANT MESSAGE 时必须位于源命令应用 Entry 之后、terminal 路径上。共享
   * store 契约允许 opaque 占位 Entry，因此非 TurnEnd/非 ASSISTANT 的占位仅做同 Session 校验。
   */
  private void requireValidJoinReceipt(ThreadJoin join) {
    if (join.terminalEntryId() == null) {
      return;
    }
    UUID childThreadId = join.childThreadId();
    ThreadState child = state.threads.get(childThreadId);
    if (child == null) {
      throw new IllegalArgumentException("thread " + childThreadId + " does not exist");
    }
    EntryPath terminalPath = tx.entries().loadPath(join.terminalEntryId());
    EntryPath childPath = tx.entries().loadPath(child.headEntryId());
    if (indexOfEntry(childPath, join.terminalEntryId()) < 0
        && indexOfEntry(terminalPath, child.headEntryId()) < 0) {
      throw new IllegalArgumentException(
          "join terminal entry must be on the child thread branch: " + join.terminalEntryId());
    }
    if (terminalPath.head().payload() instanceof TurnEndPayload end) {
      requireTurnEndOwnedBy(end, childThreadId);
    }
    if (join.finalAnswerEntryId() == null) {
      return;
    }
    Entry finalAnswer = tx.requireExistingEntry(join.finalAnswerEntryId());
    if (!finalAnswer.sessionId().equals(child.sessionId())) {
      throw new IllegalArgumentException("join final answer must be in the child thread's session");
    }
    if (!(finalAnswer.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      return;
    }
    ThreadCommand source =
        tx.commands().find(childThreadId, join.sourceCommandSequence()).orElse(null);
    UUID appliedStart = source == null ? null : source.appliedEntryId();
    if (appliedStart == null) {
      throw new IllegalArgumentException("join final answer requires an applied source command");
    }
    int startIndex = indexOfEntry(terminalPath, appliedStart);
    if (startIndex < 0) {
      throw new IllegalArgumentException(
          "join source applied entry must be on the terminal path: " + appliedStart);
    }
    int finalIndex = indexOfEntry(terminalPath, join.finalAnswerEntryId());
    if (finalIndex <= startIndex) {
      throw new IllegalArgumentException(
          "join final answer entry must follow the applied source entry");
    }
  }

  /** TurnEnd 引用的 TurnStart 必须由指定 Thread 拥有。 */
  private void requireTurnEndOwnedBy(TurnEndPayload end, UUID ownerThreadId) {
    Entry turnStart = tx.requireExistingEntry(end.turnStartEntryId());
    if (!(turnStart.payload() instanceof TurnStartPayload start)
        || !ownerThreadId.equals(start.ownerThreadId())) {
      throw new IllegalArgumentException(
          "join terminal turn must be owned by the child thread " + ownerThreadId);
    }
  }

  private static int indexOfEntry(EntryPath path, UUID entryId) {
    List<Entry> entries = path.entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public int deleteByChild(UUID childThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(childThreadId, "childThreadId");
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(childThreadId));
    if (!tx.isInLockedTree(childThreadId)) {
      throw new IllegalStateException("thread " + childThreadId + " is not in a locked tree");
    }
    List<ThreadJoin> childJoins =
        state.joins.values().stream().filter(j -> j.childThreadId().equals(childThreadId)).toList();
    for (ThreadJoin join : childJoins) {
      if (join.supersededByInvocationId() != null) {
        // 已被后续续接 supersede 的 join 永远不会交付，可安全删除。
        continue;
      }
      if (!join.matched()) {
        throw new IllegalArgumentException("cannot delete unmatched join " + join.invocationId());
      }
      if (join.delivery() == JoinDelivery.NOTIFY_PARENT && join.deliveryCommandSequence() == null) {
        throw new IllegalArgumentException(
            "cannot delete join pending delivery " + join.invocationId());
      }
    }
    int count = 0;
    var it = state.joins.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().childThreadId().equals(childThreadId)) {
        it.remove();
        count++;
      }
    }
    return count;
  }

  @Override
  public int deleteForThreads(List<UUID> threadIds) {
    tx.checkOpen();
    List<UUID> copied = List.copyOf(threadIds);
    Set<UUID> deleting = Set.copyOf(copied);
    for (UUID threadId : copied) {
      tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(threadId));
      if (!tx.isInLockedTree(threadId)) {
        throw new IllegalStateException("thread " + threadId + " is not in a locked tree");
      }
    }
    int count = 0;
    var it = state.joins.entrySet().iterator();
    while (it.hasNext()) {
      ThreadJoin join = it.next().getValue();
      if (!deleting.contains(join.childThreadId())) {
        continue;
      }
      boolean parentAlsoDeleted =
          join.parentThreadId() == null || deleting.contains(join.parentThreadId());
      if (!parentAlsoDeleted
          && join.supersededByInvocationId() == null
          && (!join.matched()
              || (join.delivery() == JoinDelivery.NOTIFY_PARENT
                  && join.deliveryCommandSequence() == null))) {
        throw new IllegalArgumentException(
            "cannot delete join pending delivery " + join.invocationId());
      }
      it.remove();
      count++;
    }
    return count;
  }
}
