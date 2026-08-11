package fun.fengwk.kkstudio.harness.tool.execution;

import java.util.Objects;
import java.util.UUID;

/** 提供给 Platform Tool 执行的 durable invocation/thread 归属。 */
public record ToolExecutionContext(UUID invocationId, UUID threadId) {
  public ToolExecutionContext {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
  }
}
