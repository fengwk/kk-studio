package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;

import java.util.Objects;

/** A persistence-owned invocation lease plus the pre-claim status for retry-release routing. */
public record ClaimedToolInvocation(
    ToolInvocation invocation, InvocationStatus previousStatus, boolean recoveredLease) {
  public ClaimedToolInvocation {
    invocation = Objects.requireNonNull(invocation, "invocation");
    previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
  }
}
