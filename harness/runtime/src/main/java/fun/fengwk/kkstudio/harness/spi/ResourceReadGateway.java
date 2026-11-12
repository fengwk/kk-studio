package fun.fengwk.kkstudio.harness.spi;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.function.Consumer;

/** Platform resource read port; caller supplies the Session-bound invocation context. */
@FunctionalInterface
public interface ResourceReadGateway {

  ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;
}
