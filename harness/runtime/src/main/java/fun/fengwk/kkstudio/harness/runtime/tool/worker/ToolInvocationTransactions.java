package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** Durable result journal and source-order coordinator boundary. */
public interface ToolInvocationTransactions {
  boolean start(ClaimedToolInvocation claimed, Instant now);

  boolean appendPartial(ClaimedToolInvocation claimed, List<ToolResult> partials, Instant now);

  boolean terminate(
      ClaimedToolInvocation claimed,
      ToolInvocationStatus terminalStatus,
      ToolResult result,
      String errorMessage,
      Instant now);

  /** Coordinates already terminal invocations after a crash or preparation-only failure. */
  int coordinateReadyRuns(Instant now);
}
