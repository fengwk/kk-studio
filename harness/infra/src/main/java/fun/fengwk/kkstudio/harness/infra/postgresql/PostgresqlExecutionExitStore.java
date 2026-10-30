package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.store.ExecutionExitStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** ExecutionExit 聚合的 PostgreSQL 实现。 */
final class PostgresqlExecutionExitStore extends PostgresqlHarnessView
    implements ExecutionExitStore {

  PostgresqlExecutionExitStore(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  @Override
  public ExecutionExitFact register(ExecutionExitFact fact) {
    checkOpen();
    Objects.requireNonNull(fact, "fact");
    requireLocked(LockKey.thread(fact.threadId()));
    if (tx.threads().find(fact.threadId()).isEmpty()) {
      throw new IllegalArgumentException("thread " + fact.threadId() + " does not exist");
    }
    Optional<ExecutionExitFact> stored = tx.exits().find(fact.executionId());
    if (stored.isPresent()) {
      requireSameExecutionIdentity(stored.get(), fact);
      return stored.get();
    }
    if (fact.parentExecutionId() != null) {
      ExecutionExitFact parent = tx.exits().find(fact.parentExecutionId()).orElse(null);
      if (parent == null) {
        throw new IllegalArgumentException(
            "parent execution " + fact.parentExecutionId() + " does not exist");
      }
      fact.requireRemoteParent(parent);
    }
    updateJdbc(
        """
        insert into harness_execution_exit (
            execution_id, thread_id, invocation_id, attempt, owner_lease_token, kind,
            parent_execution_id, state, registered_at, confirmed_at, abandoned_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        fact.executionId(),
        fact.threadId(),
        fact.invocationId(),
        fact.attempt(),
        fact.ownerLeaseToken(),
        fact.kind().name(),
        fact.parentExecutionId(),
        fact.state().name(),
        PostgresqlHarnessRows.timestamp(fact.registeredAt()),
        PostgresqlHarnessRows.timestamp(fact.confirmedAt()),
        PostgresqlHarnessRows.timestamp(fact.abandonedAt()));
    return fact;
  }

  @Override
  public Optional<ExecutionExitFact> confirm(
      ExecutionExitFact expected, ExecutionExitState state, Instant confirmedAt) {
    checkOpen();
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
    if (!state.confirmed()) {
      throw new IllegalArgumentException("confirmation requires real exit evidence");
    }
    ExecutionExitFact stored = tx.exits().find(expected.executionId()).orElse(null);
    if (stored == null) {
      return Optional.empty();
    }
    requireLocked(LockKey.thread(stored.threadId()));
    requireSameExecutionIdentity(stored, expected);
    if (stored.state() == state) {
      return Optional.of(stored);
    }
    if (stored.state().confirmed()) {
      throw new IllegalArgumentException(
          "execution " + stored.executionId() + " is already confirmed as " + stored.state());
    }
    ExecutionExitFact confirmed = stored.confirm(state, confirmedAt);
    updateJdbc(
        """
        update harness_execution_exit
           set state = ?, confirmed_at = ?
         where execution_id = ? and state in ('UNCONFIRMED', 'ABANDONED')
        """,
        confirmed.state().name(),
        PostgresqlHarnessRows.timestamp(confirmed.confirmedAt()),
        expected.executionId());
    return tx.exits().find(expected.executionId());
  }

  @Override
  public Optional<ExecutionExitFact> abandon(ExecutionExitFact expected, Instant abandonedAt) {
    checkOpen();
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(abandonedAt, "abandonedAt");
    ExecutionExitFact stored = tx.exits().find(expected.executionId()).orElse(null);
    if (stored == null) {
      return Optional.empty();
    }
    requireLocked(LockKey.thread(stored.threadId()));
    requireSameExecutionIdentity(stored, expected);
    ExecutionExitFact abandoned = stored.abandon(abandonedAt);
    if (abandoned == stored) {
      return Optional.of(stored);
    }
    updateJdbc(
        """
        update harness_execution_exit
           set state = 'ABANDONED', abandoned_at = ?
         where execution_id = ? and state = 'UNCONFIRMED'
        """,
        PostgresqlHarnessRows.timestamp(abandoned.abandonedAt()),
        expected.executionId());
    return tx.exits().find(expected.executionId());
  }

  @Override
  public List<ExecutionExitFact> loadUnconfirmed(UUID threadId) {
    checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return queryList(
        """
        select * from harness_execution_exit
         where thread_id = ? and state = 'UNCONFIRMED'
         order by registered_at, execution_id
        """,
        PostgresqlHarnessRows.EXECUTION_EXIT,
        threadId);
  }

  @Override
  public Optional<ExecutionExitFact> find(UUID executionId) {
    checkOpen();
    Objects.requireNonNull(executionId, "executionId");
    return queryOne(
        "select * from harness_execution_exit where execution_id = ?",
        PostgresqlHarnessRows.EXECUTION_EXIT,
        executionId);
  }

  private static void requireSameExecutionIdentity(
      ExecutionExitFact stored, ExecutionExitFact requested) {
    if (!stored.sameIdentity(requested)) {
      throw new IllegalArgumentException(
          "execution " + stored.executionId() + " identity cannot be replaced");
    }
  }
}
