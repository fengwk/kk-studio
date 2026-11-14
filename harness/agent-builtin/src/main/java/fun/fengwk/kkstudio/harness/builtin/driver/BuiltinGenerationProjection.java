package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.harness.api.generation.FailureProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationStatus;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;

import java.util.ArrayList;
import java.util.List;

/** terminal / 进行中 ModelInvocation 到公共 generation 投影的只读映射。 */
final class BuiltinGenerationProjection {

  private BuiltinGenerationProjection() {}

  static GenerationProjection of(ModelInvocation model) {
    List<FailureProjection> failures = new ArrayList<>(model.failedAttempts().size());
    for (ModelAttemptFailure failure : model.failedAttempts()) {
      failures.add(
          new FailureProjection(
              failure.attempt(),
              failure.sequence(),
              failure.text(),
              failure.thinking(),
              failure.error().kind().name(),
              failure.error().message(),
              failure.failedAt(),
              failure.retryAt()));
    }
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    return new GenerationProjection(
        model.id(),
        model.turnStartEntryId(),
        model.requestHeadEntryId(),
        status(model),
        model.attempt(),
        checkpoint == null ? 0L : checkpoint.sequence(),
        outputContents(checkpoint),
        model.error() == null
            ? null
            : new AssistantError(model.error().kind().name(), model.error().message()),
        model.resultEntryId(),
        model.createdAt(),
        model.updatedAt(),
        failures);
  }

  private static GenerationStatus status(ModelInvocation model) {
    return switch (model.status()) {
      case READY -> model.failedAttempts().isEmpty()
          ? GenerationStatus.READY
          : GenerationStatus.WAITING_RETRY;
      case RUNNING -> GenerationStatus.RUNNING;
      case SUCCEEDED -> GenerationStatus.SUCCEEDED;
      case FAILED -> GenerationStatus.FAILED;
      case CANCELLED -> GenerationStatus.CANCELLED;
      case UNKNOWN -> GenerationStatus.BLOCKED;
    };
  }

  private static List<AgentMessageContent> outputContents(StreamCheckpoint checkpoint) {
    if (checkpoint == null) {
      return List.of();
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    return List.copyOf(contents);
  }
}
