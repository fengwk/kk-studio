package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.store.ExecutionExitStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行退出账本聚合原语：注册要求 owning Thread 已锁定，确认 / 放弃以 exact identity 匹配并保留首次事实。
 *
 * <p>同一 executionId 的重复注册只接受 identity 完全一致的重放；确认必须携带真实退出证据，放弃与确认都只能从已注册行出发。
 */
final class InMemoryExecutionExitStore implements ExecutionExitStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryExecutionExitStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public ExecutionExitFact register(ExecutionExitFact fact) {
    tx.checkOpen();
    Objects.requireNonNull(fact, "fact");
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(fact.threadId()));
    if (!state.threads.containsKey(fact.threadId())) {
      throw new IllegalArgumentException("thread " + fact.threadId() + " does not exist");
    }
    ExecutionExitFact stored = state.executions.get(fact.executionId());
    if (stored != null) {
      requireSameIdentity(stored, fact);
      return stored;
    }
    if (fact.parentExecutionId() != null) {
      ExecutionExitFact parent = state.executions.get(fact.parentExecutionId());
      if (parent == null) {
        throw new IllegalArgumentException(
            "parent execution " + fact.parentExecutionId() + " does not exist");
      }
      fact.requireRemoteParent(parent);
    }
    state.executions.put(fact.executionId(), fact);
    return fact;
  }

  @Override
  public Optional<ExecutionExitFact> confirm(
      ExecutionExitFact expected, ExecutionExitState state, Instant confirmedAt) {
    tx.checkOpen();
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
    if (!state.confirmed()) {
      throw new IllegalArgumentException("confirmation requires real exit evidence");
    }
    ExecutionExitFact stored = this.state.executions.get(expected.executionId());
    if (stored == null) {
      return Optional.empty();
    }
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(stored.threadId()));
    requireSameIdentity(stored, expected);
    ExecutionExitFact confirmed = stored.confirm(state, confirmedAt);
    this.state.executions.put(expected.executionId(), confirmed);
    return Optional.of(confirmed);
  }

  @Override
  public Optional<ExecutionExitFact> abandon(ExecutionExitFact expected, Instant abandonedAt) {
    tx.checkOpen();
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(abandonedAt, "abandonedAt");
    ExecutionExitFact stored = this.state.executions.get(expected.executionId());
    if (stored == null) {
      return Optional.empty();
    }
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(stored.threadId()));
    requireSameIdentity(stored, expected);
    ExecutionExitFact abandoned = stored.abandon(abandonedAt);
    this.state.executions.put(expected.executionId(), abandoned);
    return Optional.of(abandoned);
  }

  @Override
  public List<ExecutionExitFact> loadUnconfirmed(UUID threadId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return state.executions.values().stream()
        .filter(
            fact ->
                fact.threadId().equals(threadId) && fact.state() == ExecutionExitState.UNCONFIRMED)
        .sorted(
            Comparator.comparing(ExecutionExitFact::registeredAt)
                .thenComparing(ExecutionExitFact::executionId, UuidOrder.COMPARATOR))
        .toList();
  }

  @Override
  public Optional<ExecutionExitFact> find(UUID executionId) {
    tx.checkOpen();
    Objects.requireNonNull(executionId, "executionId");
    return Optional.ofNullable(state.executions.get(executionId));
  }

  private static void requireSameIdentity(ExecutionExitFact stored, ExecutionExitFact requested) {
    if (!stored.sameIdentity(requested)) {
      throw new IllegalArgumentException(
          "execution " + stored.executionId() + " identity cannot be replaced");
    }
  }
}
