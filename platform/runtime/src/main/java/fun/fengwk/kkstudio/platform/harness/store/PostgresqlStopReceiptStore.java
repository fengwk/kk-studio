package fun.fengwk.kkstudio.platform.harness.store;

import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.store.StopReceiptStore;
import fun.fengwk.kkstudio.platform.harness.store.PostgresqlHarnessTransaction.LockKey;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stop 回执聚合的 PostgreSQL 实现。 */
final class PostgresqlStopReceiptStore implements StopReceiptStore {

  private static final RowMapper<StopReceiptIdentity> STOP_RECEIPT_IDENTITY =
      (resultSet, rowNumber) ->
          new StopReceiptIdentity(
              PostgresqlHarnessRows.uuid(resultSet, "thread_id"),
              PostgresqlHarnessRows.uuid(resultSet, "stop_request_id"),
              PostgresqlHarnessRows.nullableUuid(resultSet, "stopped_turn_end_entry_id"),
              PostgresqlHarnessRows.uuid(resultSet, "root_stop_request_id"));

  private final PostgresqlHarnessTransaction tx;

  private record StopReceiptKey(UUID threadId, UUID stopRequestId) {}

  private record StopReceiptIdentity(
      UUID threadId, UUID stopRequestId, UUID stoppedTurnEndEntryId, UUID rootStopRequestId) {}

  PostgresqlStopReceiptStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  @Override
  public void insertReceipts(
      UUID rootThreadId, UUID rootStopRequestId, List<StoppedThreadReceipt> receipts) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootStopRequestId, "rootStopRequestId");
    tx.requireLocked(LockKey.thread(rootThreadId));
    List<StoppedThreadReceipt> copied = List.copyOf(receipts);
    if (copied.isEmpty()) {
      throw new IllegalArgumentException("stop receipts must not be empty");
    }
    Set<StopReceiptKey> keys = new HashSet<>();
    boolean rootReceiptPresent = false;
    for (StoppedThreadReceipt receipt : copied) {
      tx.requireLocked(LockKey.thread(receipt.threadId()));
      if (!keys.add(new StopReceiptKey(receipt.threadId(), receipt.stopRequestId()))) {
        throw new IllegalArgumentException(
            "duplicate stop receipt " + receipt.threadId() + "/" + receipt.stopRequestId());
      }
      requireThreadInStopSubtree(receipt.threadId(), rootThreadId);
      if (receipt.threadId().equals(rootThreadId)) {
        rootReceiptPresent = true;
        if (!receipt.stopRequestId().equals(rootStopRequestId)) {
          throw new IllegalArgumentException(
              "root stop receipt must carry the root stop request id " + rootStopRequestId);
        }
      }
      if (receipt.stoppedTurnEndEntryId() != null) {
        requireStoppedTurnEnd(receipt.threadId(), receipt.stoppedTurnEndEntryId());
      }
      requireReceiptMatchesCancelledCommands(receipt);
    }
    if (!rootReceiptPresent) {
      throw new IllegalArgumentException(
          "stop receipt set must contain the root thread receipt " + rootThreadId);
    }
    for (StoppedThreadReceipt receipt : copied) {
      tx.updateJdbc(
          """
          insert into harness_thread_stop_receipt (
              thread_id, stop_request_id, root_stop_request_id,
              stopped_turn_end_entry_id, created_at
          ) values (?, ?, ?, ?, date_trunc('milliseconds', statement_timestamp()))
          """,
          receipt.threadId(),
          receipt.stopRequestId(),
          rootStopRequestId,
          receipt.stoppedTurnEndEntryId());
    }
  }

  /** 回执上的取消计数与输入必须来自 command 表的同一 Stop 事实；回执只持久化身份与停止边界。 */
  private void requireReceiptMatchesCancelledCommands(StoppedThreadReceipt receipt) {
    List<ThreadCommand> cancelled =
        tx.commands().loadCancelledByRequest(receipt.threadId(), receipt.stopRequestId());
    if (cancelled.size() != receipt.cancelledCommandCount()) {
      throw new IllegalArgumentException(
          "stop receipt cancelledCommandCount "
              + receipt.cancelledCommandCount()
              + " does not match "
              + cancelled.size()
              + " cancelled commands on thread "
              + receipt.threadId());
    }
    List<CancelledThreadInput> derived = cancelledInputs(cancelled);
    if (derived.size() != receipt.cancelledInputs().size()) {
      throw new IllegalArgumentException(
          "stop receipt cancelledInputs do not match cancelled commands on thread "
              + receipt.threadId());
    }
    for (int index = 0; index < derived.size(); index++) {
      CancelledThreadInput expected = receipt.cancelledInputs().get(index);
      CancelledThreadInput actual = derived.get(index);
      // payload 的权威形态是 command 行序列化后的形态；这里只校验身份字段，避免把未规范化的入参误判为不一致。
      if (expected.sequence() != actual.sequence()
          || !expected.idempotencyKey().equals(actual.idempotencyKey())
          || expected.payload().type() != actual.payload().type()) {
        throw new IllegalArgumentException(
            "stop receipt cancelledInputs do not match cancelled commands on thread "
                + receipt.threadId());
      }
    }
  }

  /** 按 sequence 升序还原被取消的人工输入（USER_MESSAGE 与 GOAL）；其它类型不退草稿。 */
  private static List<CancelledThreadInput> cancelledInputs(List<ThreadCommand> cancelled) {
    return cancelled.stream()
        .filter(
            command ->
                command.payload() instanceof UserMessageCommandPayload
                    || command.payload() instanceof GoalCommandPayload)
        .map(
            command ->
                new CancelledThreadInput(
                    command.sequence(), command.idempotencyKey(), command.payload()))
        .toList();
  }

  /**
   * 回执所属 Thread 必须位于 {@code rootThreadId} 的停止子树内（{@code rootThreadId} 是其祖先或自身）。Stop 的作用域是目标
   * 子树，不要求目标是执行树的根，因此中间节点也是合法停止目标。
   */
  private void requireThreadInStopSubtree(UUID threadId, UUID rootThreadId) {
    if (!tx.threads().findAncestorChain(threadId).contains(rootThreadId)) {
      throw new IllegalArgumentException(
          "thread " + threadId + " is not in the stop subtree rooted at " + rootThreadId);
    }
  }

  /**
   * 停止边界必须是该 Thread 自己 Session 内的 STOPPED TurnEnd，且该 TurnEnd 关闭的是由该 Thread 拥有的 Turn；任意已存在 Entry
   * 或属于其它 Thread/Session 的 TurnEnd 均被拒绝。
   */
  private void requireStoppedTurnEnd(UUID threadId, UUID stoppedTurnEndEntryId) {
    ThreadState thread =
        tx.threads()
            .find(threadId)
            .orElseThrow(
                () -> new IllegalArgumentException("thread " + threadId + " does not exist"));
    Entry turnEnd = tx.requireExistingEntry(stoppedTurnEndEntryId);
    if (!turnEnd.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "stopped turn end must be in the receipt thread's session");
    }
    if (!(turnEnd.payload() instanceof TurnEndPayload end)
        || end.outcome() != TurnEndOutcome.STOPPED) {
      throw new IllegalArgumentException(
          "stopped turn end must be a STOPPED TurnEnd entry: " + stoppedTurnEndEntryId);
    }
    Entry turnStart = tx.requireExistingEntry(end.turnStartEntryId());
    if (!(turnStart.payload() instanceof TurnStartPayload start)
        || !threadId.equals(start.ownerThreadId())) {
      throw new IllegalArgumentException(
          "stopped turn end must close a turn owned by thread " + threadId);
    }
  }

  @Override
  public Optional<StoppedThreadReceipt> findReceipt(UUID threadId, UUID stopRequestId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    return tx.queryOne(
            """
            select thread_id, stop_request_id, stopped_turn_end_entry_id, root_stop_request_id
            from harness_thread_stop_receipt
            where thread_id = ? and stop_request_id = ?
            """,
            STOP_RECEIPT_IDENTITY,
            threadId,
            stopRequestId)
        .map(this::toReceipt);
  }

  @Override
  public List<StoppedThreadReceipt> loadByThread(UUID threadId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return tx
        .queryList(
            """
            select thread_id, stop_request_id, stopped_turn_end_entry_id, root_stop_request_id
            from harness_thread_stop_receipt
            where thread_id = ?
            order by created_at, stop_request_id
            """,
            STOP_RECEIPT_IDENTITY,
            threadId)
        .stream()
        .map(this::toReceipt)
        .toList();
  }

  @Override
  public List<StoppedThreadReceipt> loadByRootRequest(UUID rootThreadId, UUID rootStopRequestId) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootStopRequestId, "rootStopRequestId");
    return tx
        .queryList(
            """
            with recursive stop_subtree(id) as (
                select cast(? as uuid)
                union all
                select child.id
                from harness_thread child
                join stop_subtree parent on child.parent_thread_id = parent.id
            )
            select receipt.thread_id, receipt.stop_request_id,
                   receipt.stopped_turn_end_entry_id, receipt.root_stop_request_id
            from harness_thread_stop_receipt receipt
            join harness_thread_stop_receipt root
              on root.thread_id = ?
             and root.stop_request_id = root.root_stop_request_id
            where receipt.root_stop_request_id = ?
              and receipt.thread_id in (select id from stop_subtree)
            order by receipt.thread_id
            """,
            STOP_RECEIPT_IDENTITY,
            rootThreadId,
            rootThreadId,
            rootStopRequestId)
        .stream()
        .map(this::toReceipt)
        .toList();
  }

  private StoppedThreadReceipt toReceipt(StopReceiptIdentity identity) {
    List<ThreadCommand> cancelled =
        tx.commands().loadCancelledByRequest(identity.threadId(), identity.stopRequestId());
    return new StoppedThreadReceipt(
        identity.threadId(),
        identity.stopRequestId(),
        identity.stoppedTurnEndEntryId(),
        cancelled.size(),
        cancelledInputs(cancelled));
  }
}
