package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.StopReceiptStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stop 回执聚合原语：一次 Stop 的完整范围由 {@code (rootThreadId, rootStopRequestId)} 标识。
 *
 * <p>回执身份 {@code (threadId, stopRequestId)} 不可重用；集合必须包含 root Thread 自己的回执，每个回执 Thread 必须位于停止子树内，
 * 停止边界必须是该 Thread 自己 Session 内、由其自有 Turn 关闭的 STOPPED TurnEnd。
 */
final class InMemoryStopReceiptStore implements StopReceiptStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryStopReceiptStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public void insertReceipts(
      UUID rootThreadId, UUID rootStopRequestId, List<StoppedThreadReceipt> receipts) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootStopRequestId, "rootStopRequestId");
    Objects.requireNonNull(receipts, "receipts");
    if (receipts.isEmpty()) {
      throw new IllegalArgumentException("stop receipts must not be empty");
    }
    InMemoryState.StopRootKey root = new InMemoryState.StopRootKey(rootThreadId, rootStopRequestId);
    List<InMemoryState.StopReceiptKey> keys = new ArrayList<>(receipts.size());
    boolean rootReceiptPresent = false;
    for (StoppedThreadReceipt receipt : receipts) {
      tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(receipt.threadId()));
      InMemoryState.StopReceiptKey key =
          new InMemoryState.StopReceiptKey(receipt.threadId(), receipt.stopRequestId());
      if (state.stopReceipts.containsKey(key)) {
        throw new IllegalArgumentException("stop receipt already exists: " + key);
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
      keys.add(key);
    }
    if (!rootReceiptPresent) {
      throw new IllegalArgumentException(
          "stop receipt set must contain the root thread receipt " + rootThreadId);
    }
    for (int i = 0; i < receipts.size(); i++) {
      state.stopReceipts.put(
          keys.get(i), new InMemoryState.StoredStopReceipt(receipts.get(i), root));
    }
    state.stopReceiptsByRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).addAll(keys);
  }

  /** 回执所属 Thread 必须位于 rootThreadId 的停止子树内（rootThreadId 是其祖先或自身）。 */
  private void requireThreadInStopSubtree(UUID threadId, UUID rootThreadId) {
    if (!tx.threads().findAncestorChain(threadId).contains(rootThreadId)) {
      throw new IllegalArgumentException(
          "thread " + threadId + " is not in the stop subtree rooted at " + rootThreadId);
    }
  }

  /** 停止边界必须是该 Thread 自己 Session 内、由其自有 Turn 关闭的 STOPPED TurnEnd。 */
  private void requireStoppedTurnEnd(UUID threadId, UUID stoppedTurnEndEntryId) {
    ThreadState thread = state.threads.get(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("thread " + threadId + " does not exist");
    }
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
    return Optional.ofNullable(
            state.stopReceipts.get(new InMemoryState.StopReceiptKey(threadId, stopRequestId)))
        .map(InMemoryState.StoredStopReceipt::receipt);
  }

  @Override
  public List<StoppedThreadReceipt> loadByThread(UUID threadId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return state.stopReceipts.values().stream()
        .map(InMemoryState.StoredStopReceipt::receipt)
        .filter(receipt -> receipt.threadId().equals(threadId))
        .toList();
  }

  @Override
  public List<StoppedThreadReceipt> loadByRootRequest(UUID rootThreadId, UUID rootStopRequestId) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    Objects.requireNonNull(rootStopRequestId, "rootStopRequestId");
    List<InMemoryState.StopReceiptKey> keys =
        state.stopReceiptsByRoot.get(
            new InMemoryState.StopRootKey(rootThreadId, rootStopRequestId));
    if (keys == null) {
      return List.of();
    }
    return keys.stream()
        .map(state.stopReceipts::get)
        .map(InMemoryState.StoredStopReceipt::receipt)
        .sorted(Comparator.comparing(StoppedThreadReceipt::threadId, UuidOrder.COMPARATOR))
        .toList();
  }
}
