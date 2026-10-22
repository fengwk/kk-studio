package fun.fengwk.kkstudio.harness.contributor.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 提供给 Tool 执行的 durable 上下文环境。 */
public record ToolExecutionContext(
    UUID invocationId,
    UUID threadId,
    Instant executedAt,
    BranchView branch,
    Optional<BoundEnvironment> environment) {

  public ToolExecutionContext {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(executedAt, "executedAt");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(environment, "environment");
  }

  public ToolExecutionContext(
      UUID invocationId,
      UUID threadId,
      Instant executedAt,
      BranchView branch,
      BoundEnvironment environment) {
    this(invocationId, threadId, executedAt, branch, Optional.ofNullable(environment));
  }

  public ToolExecutionContext(
      UUID invocationId, UUID threadId, Instant executedAt, BranchView branch) {
    this(invocationId, threadId, executedAt, branch, Optional.empty());
  }
}
