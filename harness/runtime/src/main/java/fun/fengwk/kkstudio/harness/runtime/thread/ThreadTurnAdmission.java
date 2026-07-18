package fun.fengwk.kkstudio.harness.runtime.thread;

import java.util.Optional;

/**
 * Turn admission evaluated inside {@link ThreadTransactions#beginTurn} while the Thread row is
 * locked.
 *
 * <p>Default is allow. Implementations may reject with a policy reason; rejection is atomic with
 * failure-event append and does not interrupt an already-admitted in-flight provider call.
 */
@FunctionalInterface
public interface ThreadTurnAdmission {

  /** Always admits every Thread. */
  ThreadTurnAdmission ALLOW_ALL = threadId -> Optional.empty();

  /**
   * Evaluates whether a model Turn may start for {@code threadId}.
   *
   * <p>Must be called inside the {@code beginTurn} transaction so locking lookups (e.g. task {@code
   * FOR UPDATE}) serialize with {@code cancelTree}.
   *
   * @return empty to allow; non-empty rejection reason to block the Turn
   */
  Optional<String> evaluate(long threadId);
}
