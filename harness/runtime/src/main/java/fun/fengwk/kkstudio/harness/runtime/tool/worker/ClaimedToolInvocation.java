package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import java.util.Objects;

/** A persistence-owned invocation lease. */
public record ClaimedToolInvocation(
    ToolInvocation invocation, long workspaceId, boolean recoveredLease) {
  public ClaimedToolInvocation {
    invocation = Objects.requireNonNull(invocation, "invocation");
    if (workspaceId <= 0) {
      throw new IllegalArgumentException("workspaceId must be positive");
    }
  }
}
