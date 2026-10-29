package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Driver 当前 generation 的公共快照投影。
 *
 * <p>字段只承载稳定身份、生命周期状态、已完成的文本/思考片段与结构化错误；不包含 ModelInvocation、ProviderRequest/Response、
 * StreamCheckpoint 或任何 Driver 实现类型。Provider raw 仅保留在 Driver 私有状态中。
 */
public record GenerationProjection(
    UUID generationId,
    UUID turnStartEntryId,
    UUID requestHeadEntryId,
    GenerationStatus status,
    int attempt,
    long sequence,
    List<AgentMessageContent> outputContents,
    AssistantError error,
    UUID resultEntryId,
    Instant createdAt,
    Instant updatedAt,
    List<FailureProjection> failures) {

  public GenerationProjection {
    generationId = Objects.requireNonNull(generationId, "generationId");
    turnStartEntryId = Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    requestHeadEntryId = Objects.requireNonNull(requestHeadEntryId, "requestHeadEntryId");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative");
    }
    outputContents = List.copyOf(Objects.requireNonNull(outputContents, "outputContents"));
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
  }
}
