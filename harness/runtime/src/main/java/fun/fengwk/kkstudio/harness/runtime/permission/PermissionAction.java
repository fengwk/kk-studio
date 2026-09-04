package fun.fengwk.kkstudio.harness.runtime.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tool permission 规则动作。 */
public enum PermissionAction {
  ALLOW,
  ASK,
  DENY;

  @JsonCreator
  public static PermissionAction fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("permission action must be allow, ask or deny");
    }
    return switch (value) {
      case "allow" -> ALLOW;
      case "ask" -> ASK;
      case "deny" -> DENY;
      default -> throw new IllegalArgumentException("permission action must be allow, ask or deny");
    };
  }

  @JsonValue
  public String value() {
    return switch (this) {
      case ALLOW -> "allow";
      case ASK -> "ask";
      case DENY -> "deny";
    };
  }
}
