package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.StopReceiptStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stop 回执聚合的 PostgreSQL 实现。 */
final class PostgresqlStopReceiptStore extends PostgresqlHarnessView implements StopReceiptStore {

  private record StopReceiptKey(UUID threadId, UUID stopRequestId) {}

  PostgresqlStopReceiptStore(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  @Override
  public void insertReceipts(
      UUID rootThreadId, UUID rootStopRequestId, List<StoppedThreadReceipt> receipts) {
    checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootStopRequestId, "rootStopRequestId");
    requireLocked(LockKey.thread(rootThreadId));
    List<StoppedThreadReceipt> copied = List.copyOf(receipts);
    if (copied.isEmpty()) {
      throw new IllegalArgumentException("stop receipts must not be empty");
    }
    Set<StopReceiptKey> keys = new HashSet<>();
    boolean rootReceiptPresent = false;
    for (StoppedThreadReceipt receipt : copied) {
      requireLocked(LockKey.thread(receipt.threadId()));
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
    }
    if (!rootReceiptPresent) {
      throw new IllegalArgumentException(
          "stop receipt set must contain the root thread receipt " + rootThreadId);
    }
    for (StoppedThreadReceipt receipt : copied) {
      updateJdbc(
          """
          insert into harness_thread_stop_receipt (
              root_thread_id, thread_id, stop_request_id, root_stop_request_id,
              stopped_turn_end_entry_id, cancelled_command_count, cancelled_inputs, created_at
          ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), date_trunc('milliseconds', statement_timestamp()))
          """,
          rootThreadId,
          receipt.threadId(),
          receipt.stopRequestId(),
          rootStopRequestId,
          receipt.stoppedTurnEndEntryId(),
          receipt.cancelledCommandCount(),
          PostgresqlHarnessRows.encodeCancelledInputs(receipt.cancelledInputs()));
    }
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
    Entry turnEnd = requireExistingEntry(stoppedTurnEndEntryId);
    if (!turnEnd.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "stopped turn end must be in the receipt thread's session");
    }
    if (!(turnEnd.payload() instanceof TurnEndPayload end)
        || end.outcome() != TurnEndOutcome.STOPPED) {
      throw new IllegalArgumentException(
          "stopped turn end must be a STOPPED TurnEnd entry: " + stoppedTurnEndEntryId);
    }
    Entry turnStart = requireExistingEntry(end.turnStartEntryId());
    if (!(turnStart.payload() instanceof TurnStartPayload start)
        || !threadId.equals(start.ownerThreadId())) {
      throw new IllegalArgumentException(
          "stopped turn end must close a turn owned by thread " + threadId);
    }
  }

  @Override
  public Optional<StoppedThreadReceipt> findReceipt(UUID threadId, UUID stopRequestId) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(stopRequestId, "stopRequestId");
    return queryOne(
        """
        select * from harness_thread_stop_receipt
        where thread_id = ? and stop_request_id = ?
        """,
        PostgresqlHarnessRows.STOPPED_THREAD_RECEIPT,
        threadId,
        stopRequestId);
  }

  @Override
  public List<StoppedThreadReceipt> loadByThread(UUID threadId) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return queryList(
        """
        select * from harness_thread_stop_receipt
        where thread_id = ?
        order by created_at, stop_request_id
        """,
        PostgresqlHarnessRows.STOPPED_THREAD_RECEIPT,
        threadId);
  }

  @Override
  public List<StoppedThreadReceipt> loadByRootRequest(UUID rootThreadId, UUID rootStopRequestId) {
    checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootStopRequestId, "rootStopRequestId");
    return queryList(
        """
        select * from harness_thread_stop_receipt
        where root_thread_id = ? and root_stop_request_id = ?
        order by thread_id
        """,
        PostgresqlHarnessRows.STOPPED_THREAD_RECEIPT,
        rootThreadId,
        rootStopRequestId);
  }
}
