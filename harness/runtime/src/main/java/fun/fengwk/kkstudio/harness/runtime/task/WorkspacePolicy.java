package fun.fengwk.kkstudio.harness.runtime.task;

import java.util.Locale;

/** Workspace visibility/isolation policy frozen for one subagent task. */
public enum WorkspacePolicy {
  NONE,
  SHARE_READ_ONLY,
  FORK,
  EXCLUSIVE;

  public static WorkspacePolicy parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("workspace_policy must not be blank");
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unknown workspace_policy: " + value, error);
    }
  }

  public static WorkspacePolicy defaultFor(String agentName) {
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("subagent_type must not be blank");
    }
    return agentName.toLowerCase(Locale.ROOT).contains("explorer") ? SHARE_READ_ONLY : FORK;
  }
}
