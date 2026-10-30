package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ThreadJoin 聚合的 PostgreSQL 实现。 */
final class PostgresqlJoinStore extends PostgresqlHarnessView implements JoinStore {

  PostgresqlJoinStore(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  @Override
  public void lockAdmission() {
    checkOpen();
    acquireJoinAdmissionLock();
  }

  @Override
  public void insert(ThreadJoin join) {
    checkOpen();
    Objects.requireNonNull(join, "join");
    if (tx.threads().find(join.childThreadId()).isEmpty()) {
      throw new IllegalArgumentException("join child thread does not exist");
    }
    if (join.parentThreadId() != null && tx.threads().find(join.parentThreadId()).isEmpty()) {
      throw new IllegalArgumentException("join parent thread does not exist");
    }
    if (join.matched() || join.deliveryCommandSequence() != null || join.reminderTurn() != 0) {
      throw new IllegalArgumentException("new join must be unmatched");
    }
    requireLocked(LockKey.thread(join.childThreadId()));
    updateJdbc(
        """
        insert into harness_thread_join (
            invocation_id, request_hash, parent_thread_id, child_thread_id,
            source_command_sequence, agent, max_turns, reminder_turn,
            terminal_entry_id, final_answer_entry_id, delivery_command_sequence,
            created_at, updated_at, delivery, superseded_by_invocation_id
        ) values (?, ?, ?, ?, ?, ?, ?, ?, null, null, null, ?, ?, ?, null)
        """,
        join.invocationId(),
        join.requestHash(),
        join.parentThreadId(),
        join.childThreadId(),
        join.sourceCommandSequence(),
        join.agent(),
        join.maxTurns(),
        join.reminderTurn(),
        PostgresqlHarnessRows.timestamp(join.createdAt()),
        PostgresqlHarnessRows.timestamp(join.updatedAt()),
        join.delivery().name());
  }

  @Override
  public Optional<ThreadJoin> find(UUID invocationId) {
    checkOpen();
    Objects.requireNonNull(invocationId, "invocationId");
    return queryOne(
        "select * from harness_thread_join where invocation_id = ?",
        PostgresqlHarnessRows.JOIN,
        invocationId);
  }

  @Override
  public List<ThreadJoin> loadIncomplete(UUID childThreadId) {
    checkOpen();
    Objects.requireNonNull(childThreadId, "childThreadId");
    return queryList(
        """
        select * from harness_thread_join
        where child_thread_id = ? and terminal_entry_id is null
          and superseded_by_invocation_id is null
        order by created_at, invocation_id
        """,
        PostgresqlHarnessRows.JOIN,
        childThreadId);
  }

  @Override
  public int countIncompleteChildren(UUID parentThreadId) {
    checkOpen();
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    Integer count =
        queryForObject(
            """
            select count(*)
            from harness_thread_join
            where parent_thread_id = ? and terminal_entry_id is null
              and superseded_by_invocation_id is null and delivery = 'NOTIFY_PARENT'
            """,
            Integer.class,
            parentThreadId);
    return count != null ? count : 0;
  }

  @Override
  public boolean hasIncompleteOwnerChild(UUID parentThreadId) {
    checkOpen();
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    Boolean found =
        queryForObject(
            """
            select exists(
              select 1
              from harness_thread_join
              where parent_thread_id = ? and terminal_entry_id is null
                and superseded_by_invocation_id is null and delivery = 'SETTLE_OWNER_TURN'
            )
            """,
            Boolean.class,
            parentThreadId);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public int countIncompleteSubagent() {
    checkOpen();
    Integer count =
        queryForObject(
            """
            select count(*)
            from harness_thread_join
            where parent_thread_id is not null and terminal_entry_id is null
              and superseded_by_invocation_id is null and delivery = 'NOTIFY_PARENT'
            """,
            Integer.class);
    return count != null ? count : 0;
  }

  /**
   * 已冻结的 Join 回执必须与子 Thread 历史一致：terminal 与子 Thread 当前 head 必须在同一 branch（互为祖先）； terminal 是 TurnEnd
   * 时其 Turn 必须由子 Thread 拥有；final answer 是 ASSISTANT MESSAGE 时必须位于源命令应用 Entry 之后、terminal 路径上。共享
   * store 契约允许 opaque 占位 Entry，因此非 TurnEnd/非 ASSISTANT 的占位仅做同 Session 校验。
   */
  private void requireValidJoinReceipt(ThreadJoin join) {
    if (join.terminalEntryId() == null) {
      return;
    }
    UUID childThreadId = join.childThreadId();
    ThreadState child =
        tx.threads()
            .find(childThreadId)
            .orElseThrow(
                () -> new IllegalArgumentException("thread " + childThreadId + " does not exist"));
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
    Entry finalAnswer = requireExistingEntry(join.finalAnswerEntryId());
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
    Entry turnStart = requireExistingEntry(end.turnStartEntryId());
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
  public void update(ThreadJoin join) {
    checkOpen();
    Objects.requireNonNull(join, "join");
    requireLocked(LockKey.thread(join.childThreadId()));
    ThreadJoin old =
        tx.joins()
            .find(join.invocationId())
            .orElseThrow(
                () -> new IllegalArgumentException("join not found: " + join.invocationId()));
    ThreadJoin.validateTransition(old, join);
    requireValidJoinReceipt(join);
    int updated =
        updateJdbc(
            """
        update harness_thread_join
        set terminal_entry_id = ?, final_answer_entry_id = ?,
            delivery_command_sequence = ?, reminder_turn = ?,
            superseded_by_invocation_id = ?, updated_at = ?
        where invocation_id = ? and terminal_entry_id is not distinct from ?
          and delivery_command_sequence is not distinct from ?
          and superseded_by_invocation_id is not distinct from ?
        """,
            join.terminalEntryId(),
            join.finalAnswerEntryId(),
            join.deliveryCommandSequence(),
            join.reminderTurn(),
            join.supersededByInvocationId(),
            PostgresqlHarnessRows.timestamp(join.updatedAt()),
            join.invocationId(),
            old.terminalEntryId(),
            old.deliveryCommandSequence(),
            old.supersededByInvocationId());
    requireSingleUpdate(updated, "join", join.invocationId());
  }

  @Override
  public int deleteByChild(UUID childThreadId) {
    checkOpen();
    Objects.requireNonNull(childThreadId, "childThreadId");
    requireLocked(LockKey.thread(childThreadId));
    if (!isInLockedTree(childThreadId)) {
      throw new IllegalStateException("thread " + childThreadId + " is not in a locked tree");
    }
    List<ThreadJoin> joins =
        queryList(
            "select * from harness_thread_join where child_thread_id = ?",
            PostgresqlHarnessRows.JOIN,
            childThreadId);
    for (ThreadJoin join : joins) {
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
    return updateJdbc("delete from harness_thread_join where child_thread_id = ?", childThreadId);
  }

  @Override
  public int deleteForThreads(List<UUID> threadIds) {
    checkOpen();
    Objects.requireNonNull(threadIds, "threadIds");
    List<UUID> copied = List.copyOf(threadIds);
    Set<UUID> deleting = Set.copyOf(copied);
    for (UUID threadId : copied) {
      requireLocked(LockKey.thread(threadId));
      if (!isInLockedTree(threadId)) {
        throw new IllegalStateException("thread " + threadId + " is not in a locked tree");
      }
    }
    List<ThreadJoin> joins =
        queryList(
            "select * from harness_thread_join where child_thread_id in ("
                + placeholders(copied.size())
                + ")",
            PostgresqlHarnessRows.JOIN,
            copied.toArray());
    List<UUID> pendingDelete = new ArrayList<>();
    for (ThreadJoin join : joins) {
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
      pendingDelete.add(join.invocationId());
    }
    if (pendingDelete.isEmpty()) {
      return 0;
    }
    return updateJdbc(
        "delete from harness_thread_join where invocation_id in ("
            + placeholders(pendingDelete.size())
            + ")",
        pendingDelete.toArray());
  }
}
