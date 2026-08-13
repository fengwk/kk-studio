package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 校验 terminal ModelInvocation 的 attempt 审计已经精确物化到结果 EntryPath。 */
public final class ModelAttemptMaterialization {

  private ModelAttemptMaterialization() {}

  public static void validate(
      ModelInvocation stored, ModelInvocation attached, EntryPath resultPath) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(attached, "attached");
    Objects.requireNonNull(resultPath, "resultPath");
    if (stored.resultEntryId() != null
        || attached.resultEntryId() == null
        || !attached.status().isTerminal()
        || !attached.failedAttempts().isEmpty()
        || attached.streamCheckpoint() != null) {
      throw new IllegalArgumentException(
          "attempt materialization validation requires a pending terminal attach transition");
    }
    List<Entry> materialized = failuresAfterBasis(stored, resultPath);
    if (stored.request().compaction() == null) {
      if (materialized.size() != stored.failedAttempts().size()) {
        throw new IllegalArgumentException(
            "model result path must materialize every failed attempt exactly once");
      }
      for (int index = 0; index < materialized.size(); index++) {
        requireSameFailure(stored.failedAttempts().get(index), materialized.get(index));
      }
    } else if (!materialized.isEmpty()) {
      throw new IllegalArgumentException(
          "compaction result paths must not materialize model attempt failures");
    }
    if (stored.status().isTerminal()) {
      requireTerminalResult(stored, resultPath.head().payload());
    } else {
      requireDirectStopResult(stored, attached, resultPath.head().payload());
    }
  }

  private static List<Entry> failuresAfterBasis(ModelInvocation invocation, EntryPath path) {
    boolean afterBasis = false;
    List<Entry> failures = new ArrayList<>();
    for (Entry entry : path.entries()) {
      if (entry.id().equals(invocation.basisHeadEntryId())) {
        afterBasis = true;
        continue;
      }
      if (afterBasis && entry.payload() instanceof ModelAttemptFailurePayload) {
        failures.add(entry);
      }
    }
    if (!afterBasis) {
      throw new IllegalArgumentException("model result path must contain basisHeadEntryId");
    }
    return List.copyOf(failures);
  }

  private static void requireSameFailure(ModelAttemptFailure expected, Entry entry) {
    ModelAttemptFailurePayload actual = (ModelAttemptFailurePayload) entry.payload();
    ModelAttemptSnapshot attempt = actual.attempt();
    if (attempt.attempt() != expected.attempt()
        || attempt.sequence() != expected.sequence()
        || !attempt.text().equals(expected.text())
        || !attempt.thinking().equals(expected.thinking())
        || !entry.createdAt().equals(expected.failedAt())
        || !actual.retryAt().equals(expected.retryAt())
        || !actual.error().code().equals(expected.error().kind().name())
        || !actual.error().message().equals(expected.error().message())) {
      throw new IllegalArgumentException(
          "materialized model attempt failure must match the invocation audit fact");
    }
  }

  private static void requireTerminalResult(
      ModelInvocation invocation, EntryPayload resultPayload) {
    if (invocation.status() == ModelInvocationStatus.SUCCEEDED) {
      return;
    }
    if (!(resultPayload instanceof AssistantErrorPayload actual)
        || !sameError(invocation, actual)
        || !Objects.equals(terminalAttempt(invocation), actual.attempt())) {
      throw new IllegalArgumentException(
          "materialized terminal error must match the invocation error and checkpoint");
    }
  }

  private static void requireDirectStopResult(
      ModelInvocation stored, ModelInvocation attached, EntryPayload resultPayload) {
    if (attached.status() != ModelInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException(
          "only a direct Stop may attach a result while the stored invocation is active");
    }
    StreamCheckpoint checkpoint = stored.streamCheckpoint();
    if (checkpoint == null) {
      if (!(resultPayload instanceof AssistantErrorPayload actual)
          || !sameError(attached, actual)
          || actual.attempt() != null) {
        throw new IllegalArgumentException(
            "a stopped model without partial output requires its exact cancellation error");
      }
      return;
    }
    AssistantAbortedPayload expected = aborted(checkpoint);
    if (!expected.equals(resultPayload)) {
      throw new IllegalArgumentException(
          "a stopped model with partial output must materialize the exact durable checkpoint");
    }
  }

  private static boolean sameError(ModelInvocation invocation, AssistantErrorPayload payload) {
    return payload.error().code().equals(invocation.error().kind().name())
        && payload.error().message().equals(invocation.error().message());
  }

  private static AssistantAbortedPayload aborted(StreamCheckpoint checkpoint) {
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    return new AssistantAbortedPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }

  private static ModelAttemptSnapshot terminalAttempt(ModelInvocation invocation) {
    if (invocation.error() == null || invocation.failedAttempts().size() == invocation.attempt()) {
      return null;
    }
    if (invocation.streamCheckpoint() == null) {
      return new ModelAttemptSnapshot(invocation.attempt(), 0, "", "");
    }
    return new ModelAttemptSnapshot(
        invocation.streamCheckpoint().attempt(),
        invocation.streamCheckpoint().sequence(),
        invocation.streamCheckpoint().text(),
        invocation.streamCheckpoint().thinking());
  }
}
