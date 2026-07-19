package fun.fengwk.kkstudio.harness.runtime.goal;

import java.time.Instant;
import java.util.Objects;

/** Durable Thread-scoped goal state. */
public record ThreadGoal(
    long threadId,
    String objective,
    Long tokenBudget,
    GoalStatus status,
    String reason,
    Instant createdAt,
    Instant updatedAt) {

  public ThreadGoal {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    objective = requireNonBlank(objective, "objective");
    if (tokenBudget != null && tokenBudget <= 0) {
      throw new IllegalArgumentException("tokenBudget must be positive when present");
    }
    status = Objects.requireNonNull(status, "status");
    if (reason != null && reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank when present");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
