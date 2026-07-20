package fun.fengwk.kkstudio.harness.runtime.goal;

import java.time.Instant;
import java.util.Optional;

/** Durable store for the current Thread-scoped goal. */
public interface GoalStore {

  Optional<ThreadGoal> find(long threadId);

  /** Creates or replaces the current Thread goal as {@link GoalStatus#active}. */
  ThreadGoal createOrReplace(long threadId, String objective, Long tokenBudget, Instant now);

  /**
   * Terminal update for an active goal.
   *
   * @throws IllegalStateException when no active goal exists
   * @throws IllegalArgumentException for invalid status/reason
   */
  ThreadGoal updateTerminal(long threadId, GoalStatus status, String reason, Instant now);
}
