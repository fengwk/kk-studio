package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** Durable tool result journal；终态后 kick 所属 Thread，不再 coordinate Run。 */
public interface ToolInvocationTransactions {
  boolean start(ClaimedToolInvocation claimed, Instant now);

  boolean appendPartial(ClaimedToolInvocation claimed, List<ToolResult> partials, Instant now);

  boolean terminate(
      ClaimedToolInvocation claimed,
      ToolInvocationStatus terminalStatus,
      ToolResult result,
      String errorMessage,
      Instant now);
}
