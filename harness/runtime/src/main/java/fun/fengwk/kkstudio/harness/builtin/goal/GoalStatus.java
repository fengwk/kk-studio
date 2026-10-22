package fun.fengwk.kkstudio.harness.builtin.goal;

/** Goal 的 active 与 terminal 状态。 */
public enum GoalStatus {
  /** 目标仍在推进，尚未进入终态。 */
  ACTIVE("active"),

  /** 目标已达成并进入完成终态。 */
  COMPLETE("complete"),

  /** 目标受阻且无法继续，进入阻塞终态。 */
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
