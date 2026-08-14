package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** terminal Model attempt 状态清空前的 EntryPath 精确物化契约。 */
class ModelAttemptMaterializationTest {

  private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);
  private static final Instant T2 = T0.plusSeconds(2);
  private static final Instant T3 = T0.plusSeconds(3);
  private static final Instant T4 = T0.plusSeconds(4);
  private static final Instant T6 = T0.plusSeconds(6);
  private static final EnvironmentBinding ENVIRONMENT = EnvironmentBindings.binding("env-1");
  private static final BranchSettings SETTINGS =
      new BranchSettings(
          ENVIRONMENT, "agent", new ModelSelection("provider", "model", "v1"), List.of());

  @Test
  void acceptsExactFailedAttemptAndTerminalPartial() {
    ModelAttemptFailure failure = failure();
    ModelInvocation stored = failedInvocation(List.of(failure));
    Entry failureEntry = failureEntry(id(4L), id(3L), failure, failure.failedAt(), failure.text());
    Entry result =
        assistantError(
            id(5L),
            failureEntry.id(),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);
    EntryPath path = inputPath(List.of(failureEntry, result));

    assertDoesNotThrow(
        () ->
            ModelAttemptMaterialization.validate(
                stored, stored.attachResultEntry(result.id(), T6), path));
  }

  @Test
  void rejectsMissingTamperedOrMisdatedFailedAttempt() {
    ModelAttemptFailure failure = failure();
    ModelInvocation stored = failedInvocation(List.of(failure));
    Entry exactResult =
        assistantError(
            id(5L),
            id(4L),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);

    Entry missingResult =
        assistantError(
            id(5L),
            id(3L),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(missingResult.id(), T6),
                inputPath(missingResult)));

    Entry tampered = failureEntry(id(4L), id(3L), failure, failure.failedAt(), "different partial");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(exactResult.id(), T6),
                inputPath(List.of(tampered, exactResult))));

    Entry misdated =
        failureEntry(id(4L), id(3L), failure, failure.failedAt().plusMillis(1), failure.text());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(exactResult.id(), T6),
                inputPath(List.of(misdated, exactResult))));
  }

  @Test
  void rejectsTerminalErrorOrPartialThatDoesNotMatchInvocation() {
    ModelInvocation stored = failedInvocation(List.of(failure()));
    Entry failureEntry =
        failureEntry(
            id(4L),
            id(3L),
            stored.failedAttempts().get(0),
            stored.failedAttempts().get(0).failedAt(),
            stored.failedAttempts().get(0).text());
    Entry wrongError =
        assistantError(
            id(5L),
            failureEntry.id(),
            new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "wrong error"),
            new ModelAttemptSnapshot(2, 7, "terminal partial", "terminal thinking"),
            T6);
    Entry wrongPartial =
        assistantError(
            id(6L),
            failureEntry.id(),
            stored.error(),
            new ModelAttemptSnapshot(2, 7, "different partial", "terminal thinking"),
            T6);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(wrongError.id(), T6),
                inputPath(List.of(failureEntry, wrongError))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelAttemptMaterialization.validate(
                stored,
                stored.attachResultEntry(wrongPartial.id(), T6),
                inputPath(List.of(failureEntry, wrongPartial))));
  }

  @Test
  void acceptsOnlyTheExactDirectStopBarrier() {
    StreamCheckpoint checkpoint = new StreamCheckpoint(1, 4, "partial", "thinking");
    ModelInvocation running =
        invocation(request(null), ModelInvocationStatus.RUNNING, 1, checkpoint, null, List.of());
    ModelInvocationError cancellation =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled");
    Entry aborted =
        new Entry(
            id(4L),
            id(100L),
            id(3L),
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ThinkingMessageContent("thinking"),
                        new TextMessageContent("partial")))),
            T6);
    ModelInvocation attached = running.cancel(cancellation, T6).attachResultEntry(aborted.id(), T6);
    assertDoesNotThrow(
        () -> ModelAttemptMaterialization.validate(running, attached, inputPath(aborted)));

    Entry droppedPartial = assistantError(id(4L), id(3L), cancellation, null, T6);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelAttemptMaterialization.validate(running, attached, inputPath(droppedPartial)));

    ModelInvocation ready =
        invocation(request(null), ModelInvocationStatus.READY, 0, null, null, List.of());
    Entry cancellationBarrier = assistantError(id(4L), id(3L), cancellation, null, T6);
    ModelInvocation stopped =
        ready.cancel(cancellation, T6).attachResultEntry(cancellationBarrier.id(), T6);
    assertDoesNotThrow(
        () -> ModelAttemptMaterialization.validate(ready, stopped, inputPath(cancellationBarrier)));
  }

  @Test
  void compactionRetriesRemainInvocationOnlyUntilTerminalErrorMaterialization() {
    ModelAttemptFailure failure = failure();
    ModelInvocationError terminalError =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "summarization failed");
    ModelInvocation stored =
        invocation(
            request(
                new CompactionRequest(
                    CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 100, id(1L), id(1L), null)),
            ModelInvocationStatus.FAILED,
            2,
            null,
            terminalError,
            List.of(failure));
    Entry result =
        assistantError(id(3L), id(2L), terminalError, new ModelAttemptSnapshot(2, 0, "", ""), T6);
    EntryPath path =
        new EntryPath(
            List.of(
                root(),
                new Entry(
                    id(2L),
                    id(100L),
                    id(1L),
                    new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
                    T1),
                result));

    assertDoesNotThrow(
        () ->
            ModelAttemptMaterialization.validate(
                stored, stored.attachResultEntry(result.id(), T6), path));
  }

  private static ModelInvocation failedInvocation(List<ModelAttemptFailure> failures) {
    return invocation(
        request(null),
        ModelInvocationStatus.FAILED,
        2,
        new StreamCheckpoint(2, 7, "terminal partial", "terminal thinking"),
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "provider exploded"),
        failures);
  }

  private static ModelInvocation invocation(
      ModelInvocationRequest request,
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint checkpoint,
      ModelInvocationError error,
      List<ModelAttemptFailure> failures) {
    return new ModelInvocation(
        id(10L),
        id(20L),
        id(2L),
        status == ModelInvocationStatus.FAILED && request.compaction() != null ? id(2L) : id(3L),
        request,
        status,
        attempt,
        checkpoint,
        null,
        error,
        null,
        failures,
        T2,
        T6);
  }

  private static ModelAttemptFailure failure() {
    return new ModelAttemptFailure(
        1,
        3,
        "retry partial",
        "retry thinking",
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "temporary outage"),
        T3,
        T4);
  }

  private static Entry failureEntry(
      UUID entryId, UUID parentId, ModelAttemptFailure failure, Instant createdAt, String text) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new ModelAttemptFailurePayload(
            new ModelAttemptSnapshot(
                failure.attempt(), failure.sequence(), text, failure.thinking()),
            new AssistantError(failure.error().kind().name(), failure.error().message()),
            failure.retryAt()),
        createdAt);
  }

  private static Entry assistantError(
      UUID entryId,
      UUID parentId,
      ModelInvocationError error,
      ModelAttemptSnapshot attempt,
      Instant createdAt) {
    return new Entry(
        entryId,
        id(100L),
        parentId,
        new AssistantErrorPayload(
            new AssistantError(error.kind().name(), error.message()), attempt),
        createdAt);
  }

  private static EntryPath inputPath(Entry suffix) {
    return inputPath(List.of(suffix));
  }

  private static EntryPath inputPath(List<Entry> suffix) {
    return new EntryPath(
        Stream.concat(
                List.of(
                    root(),
                    new Entry(
                        id(2L),
                        id(100L),
                        id(1L),
                        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS),
                        T1),
                    new Entry(
                        id(3L),
                        id(100L),
                        id(2L),
                        new MessagePayload(
                            new AgentMessage(
                                AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                            null,
                            null),
                        T2))
                    .stream(),
                suffix.stream())
            .toList());
  }

  private static Entry root() {
    return new Entry(id(1L), id(100L), null, new RootPayload(SETTINGS), T0);
  }

  private static ModelInvocationRequest request(CompactionRequest compaction) {
    return new ModelInvocationRequest(
        ENVIRONMENT,
        new ProviderRequest(
            modelDescriptor(),
            new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
            List.of(),
            List.of(),
            ProviderCacheControl.none()),
        List.of(),
        List.of(),
        false,
        100_000,
        compaction);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }
}
