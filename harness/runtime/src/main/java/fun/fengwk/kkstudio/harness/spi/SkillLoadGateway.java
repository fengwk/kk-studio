package fun.fengwk.kkstudio.harness.spi;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.function.Consumer;

/** Platform implementation of the internal skill-loading operation. */
@FunctionalInterface
public interface SkillLoadGateway {

  ToolResult load(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;
}
