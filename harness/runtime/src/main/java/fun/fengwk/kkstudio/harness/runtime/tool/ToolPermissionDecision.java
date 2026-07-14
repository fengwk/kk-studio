package fun.fengwk.kkstudio.harness.runtime.tool;

/** 用户对 WAITING_APPROVAL Invocation 的持久决定。 */
public enum ToolPermissionDecision {
  ALLOW,
  DENY;

  public static ToolPermissionDecision fromApiValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("decision must not be null");
    }
    return switch (value.trim().toLowerCase()) {
      case "allow" -> ALLOW;
      case "deny" -> DENY;
      default -> throw new IllegalArgumentException("decision must be allow or deny");
    };
  }
}
