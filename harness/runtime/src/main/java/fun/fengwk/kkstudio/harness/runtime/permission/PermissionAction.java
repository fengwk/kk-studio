package fun.fengwk.kkstudio.harness.runtime.permission;

/** Tool permission 规则动作。 */
public enum PermissionAction {
  ALLOW,
  ASK,
  DENY;

  public static PermissionAction fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("permission action must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("permission action must be allow, ask or deny", error);
    }
  }

  public String value() {
    return name().toLowerCase();
  }
}
