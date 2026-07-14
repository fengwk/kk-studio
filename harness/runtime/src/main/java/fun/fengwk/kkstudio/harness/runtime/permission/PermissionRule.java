package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.Objects;

/** 有序 permission rule。 */
public record PermissionRule(String pattern, PermissionAction action) {
  public PermissionRule {
    if (pattern == null || pattern.isBlank()) {
      throw new IllegalArgumentException("permission pattern must not be blank");
    }
    action = Objects.requireNonNull(action, "action");
  }
}
