package fun.fengwk.kkstudio.harness.runtime.goal;

/** Terminal and active statuses for a durable Thread goal. */
public enum GoalStatus {
  active,
  complete,
  blocked;

  public boolean terminal() {
    return this == complete || this == blocked;
  }

  public static GoalStatus parseUpdateStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("status is required");
    }
    return switch (raw.trim()) {
      case "complete" -> complete;
      case "blocked" -> blocked;
      default -> throw new IllegalArgumentException(
          "status must be complete or blocked, got: " + raw);
    };
  }

  public static GoalStatus parseStored(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("stored goal status must not be blank");
    }
    return switch (raw.trim()) {
      case "active" -> active;
      case "complete" -> complete;
      case "blocked" -> blocked;
      default -> throw new IllegalArgumentException("unknown stored goal status: " + raw);
    };
  }
}
