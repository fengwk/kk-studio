package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.Locale;

/** Working Copy visibility/isolation policy frozen for one subagent task. */
public enum WorkingCopyPolicy {
  NONE,
  SHARE_READ_ONLY,
  FORK,
  EXCLUSIVE;

  public static WorkingCopyPolicy parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("working_copy_policy must not be blank");
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unknown working_copy_policy: " + value, error);
    }
  }

  public static WorkingCopyPolicy defaultFor(String agentName) {
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("subagent_type must not be blank");
    }
    return agentName.toLowerCase(Locale.ROOT).contains("explorer") ? SHARE_READ_ONLY : FORK;
  }
}
