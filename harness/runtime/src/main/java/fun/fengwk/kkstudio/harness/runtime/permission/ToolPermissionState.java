package fun.fengwk.kkstudio.harness.runtime.permission;

import java.util.Locale;

/**
 * Durable Tool permission state.
 *
 * <p>State machine:
 *
 * <ul>
 *   <li>{@link #PENDING} initial state for every QUEUED Tool invocation; no decision recorded.
 *   <li>{@link #ALLOWED} persisted before any external Tool I/O; required for {@code RETRY_WAIT}
 *       and successful terminals.
 *   <li>{@link #ASKED} only valid for {@code WAITING_INTERACTION} status; pairs with one OPEN
 *       Interaction that owns the decision.
 *   <li>{@link #DENIED} only valid for terminal {@code FAILED}; eliminates the Tool target and
 *       wakes the owning Thread.
 * </ul>
 */
public enum ToolPermissionState {
  PENDING,
  ALLOWED,
  ASKED,
  DENIED;

  public static ToolPermissionState fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("permission state must not be null");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "permission state must be pending, allowed, asked or denied", error);
    }
  }

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
