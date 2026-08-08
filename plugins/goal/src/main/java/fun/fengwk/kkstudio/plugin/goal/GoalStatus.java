package fun.fengwk.kkstudio.plugin.goal;

/** Goal 的 active 与 terminal 状态。 */
public enum GoalStatus {
  ACTIVE("active"),
  COMPLETE("complete"),
  BLOCKED("blocked");

  private final String wireValue;

  GoalStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public boolean terminal() {
    return this != ACTIVE;
  }

  public static GoalStatus parse(String value) {
    return switch (value) {
      case "active" -> ACTIVE;
      case "complete" -> COMPLETE;
      case "blocked" -> BLOCKED;
      default -> throw new IllegalArgumentException("unknown goal status: " + value);
    };
  }

  public static GoalStatus parseTerminal(String value) {
    GoalStatus status = parse(value);
    if (!status.terminal()) {
      throw new IllegalArgumentException("status must be complete or blocked");
    }
    return status;
  }
}
