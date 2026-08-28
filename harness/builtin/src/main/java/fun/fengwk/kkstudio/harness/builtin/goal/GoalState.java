package fun.fengwk.kkstudio.harness.builtin.goal;

import java.time.Instant;
import java.util.Objects;

/** 一条 Goal CUSTOM Entry 持有的完整 branch state 快照。 */
public record GoalState(
    String objective,
    Long tokenBudget,
    GoalStatus status,
    String reason,
    Instant createdAt,
    Instant updatedAt) {

  public GoalState {
    objective = requireNonBlank(objective, "objective");
    if (tokenBudget != null && tokenBudget <= 0) {
      throw new IllegalArgumentException("tokenBudget must be positive when present");
    }
    status = Objects.requireNonNull(status, "status");
    if (reason != null) {
      reason = requireNonBlank(reason, "reason");
    }
    if (!status.terminal() && reason != null) {
      throw new IllegalArgumentException("an active goal must not carry a terminal reason");
    }
    if (status.terminal() && reason == null) {
      throw new IllegalArgumentException("a terminal goal requires a reason");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}
