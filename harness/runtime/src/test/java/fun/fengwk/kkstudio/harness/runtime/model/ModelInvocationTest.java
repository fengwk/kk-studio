package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class ModelInvocationTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant STARTED = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant ACTIVITY = Instant.parse("2026-01-01T00:00:02Z");
  private static final Instant FINISHED = Instant.parse("2026-01-01T00:00:03Z");
  private static final Instant RETRY_AT = Instant.parse("2026-01-01T00:00:04Z");
  private static final Instant LEASE_UNTIL = Instant.parse("2026-01-01T00:00:05Z");
  private static final Instant APPLIED = Instant.parse("2026-01-01T00:00:06Z");
  private static final Instant DEADLINE = Instant.parse("2026-01-01T00:00:10Z");
  private static final ModelInvocationRequest REQUEST =
      new ModelInvocationRequest(request(), List.of(), List.of(), false);
  private static final ProviderResponse RESPONSE = response();
  private static final ModelInvocationError ERROR =
      new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider unavailable");

  /**
   * Every schema lifecycle shape is constructible, including both valid cancellation clock forms.
   */
  @Test
  void constructsEveryLegalLifecycleShape() {
    List<ModelInvocation> invocations =
        List.of(
            queuedBuilder().build(),
            runningBuilder().build(),
            retryBuilder().build(),
            succeededBuilder().build(),
            failedBuilder().build(),
            cancelledBeforeStartBuilder().build(),
            cancelledAfterStartBuilder().build(),
            unknownBuilder().build());

    assertEquals(
        Arrays.stream(InvocationStatus.values())
            .filter(s -> s != InvocationStatus.WAITING_INTERACTION)
            .collect(Collectors.toSet()),
        invocations.stream().map(ModelInvocation::status).collect(Collectors.toSet()));
    assertEquals(0, invocations.get(0).executionEpoch());
    assertEquals(3, retryBuilder().attempt(3).build().attempt());
    assertEquals(CREATED, cancelledBeforeStartBuilder().build().finishedAt());
  }

  /**
   * Reflection guards the architecture boundary: the aggregate uses only the shared runtime
   * execution types.
   */
  @Test
  void usesSharedInvocationStatusAndLeaseWithoutDuplicates() {
    assertEquals(
        InvocationStatus.class,
        Arrays.stream(ModelInvocation.class.getRecordComponents())
            .filter(component -> component.getName().equals("status"))
            .findFirst()
            .orElseThrow()
            .getType());
    assertEquals(
        Lease.class,
        Arrays.stream(ModelInvocation.class.getRecordComponents())
            .filter(component -> component.getName().equals("workerLease"))
            .findFirst()
            .orElseThrow()
            .getType());
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName(ModelInvocation.class.getPackageName() + ".InvocationStatus"));
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName(ModelInvocation.class.getPackageName() + ".WorkerLease"));
  }

  /** Durable identity, epoch, attempt, request, status, and creation time reject invalid values. */
  @Test
  void rejectsInvalidIdentityAndRequiredFields() {
    assertIllegal(queuedBuilder(), builder -> builder.id = 0);
    assertIllegal(queuedBuilder(), builder -> builder.threadId = 0);
    assertIllegal(queuedBuilder(), builder -> builder.sourceHeadEntryId = 0);
    assertIllegal(queuedBuilder(), builder -> builder.executionEpoch = -1);
    assertIllegal(queuedBuilder(), builder -> builder.attempt = 0);
    assertNullRejected(queuedBuilder(), builder -> builder.request = null);
    assertNullRejected(queuedBuilder(), builder -> builder.status = null);
    assertNullRejected(queuedBuilder(), builder -> builder.createdAt = null);
  }

  /** Generic timestamp and lease ordering exactly mirror the PostgreSQL time-order constraint. */
  @Test
  void rejectsInvalidTimeLeaseAndRetryOrdering() {
    assertIllegal(runningBuilder(), builder -> builder.startedAt = CREATED.minusSeconds(1));
    assertIllegal(cancelledBeforeStartBuilder(), builder -> builder.deadlineAt = DEADLINE);
    assertIllegal(runningBuilder(), builder -> builder.deadlineAt = STARTED);
    assertIllegal(
        cancelledBeforeStartBuilder(),
        builder -> builder.workerLease = new Lease("worker", LEASE_UNTIL));
    assertIllegal(runningBuilder(), builder -> builder.workerLease = new Lease("worker", STARTED));
    assertIllegal(cancelledBeforeStartBuilder(), builder -> builder.lastActivityAt = ACTIVITY);
    assertIllegal(runningBuilder(), builder -> builder.lastActivityAt = CREATED);
    assertIllegal(cancelledAfterStartBuilder(), builder -> builder.finishedAt = CREATED);
    assertIllegal(
        cancelledBeforeStartBuilder(), builder -> builder.finishedAt = CREATED.minusSeconds(1));
    assertIllegal(succeededBuilder(), builder -> builder.lastActivityAt = FINISHED.plusSeconds(1));
    assertIllegal(retryBuilder(), builder -> builder.deadlineAt = null);
    assertIllegal(retryBuilder(), builder -> builder.lastActivityAt = null);
    assertIllegal(retryBuilder(), builder -> builder.nextAttemptAt = ACTIVITY);
    assertIllegal(retryBuilder(), builder -> builder.nextAttemptAt = DEADLINE);
  }

  /** QUEUED, RUNNING, and RETRY_WAIT each reject payloads or clocks owned by another shape. */
  @Test
  void rejectsInvalidNonTerminalShapes() {
    assertIllegal(
        queuedBuilder(),
        builder -> {
          builder.startedAt = STARTED;
          builder.deadlineAt = DEADLINE;
          builder.lastActivityAt = ACTIVITY;
          builder.nextAttemptAt = RETRY_AT;
        });
    assertIllegal(
        queuedBuilder(),
        builder -> {
          builder.startedAt = STARTED;
          builder.workerLease = new Lease("worker", LEASE_UNTIL);
        });
    assertIllegal(queuedBuilder(), builder -> builder.startedAt = STARTED);
    assertIllegal(queuedBuilder(), builder -> builder.finishedAt = FINISHED);
    assertIllegal(queuedBuilder(), builder -> builder.result = RESPONSE);
    assertIllegal(queuedBuilder(), builder -> builder.error = ERROR);

    assertIllegal(
        runningBuilder(),
        builder -> {
          builder.nextAttemptAt = RETRY_AT;
          builder.workerLease = null;
        });
    assertIllegal(runningBuilder(), builder -> builder.finishedAt = FINISHED);
    assertIllegal(runningBuilder(), builder -> builder.result = RESPONSE);
    assertIllegal(runningBuilder(), builder -> builder.error = ERROR);
    assertIllegal(runningBuilder(), builder -> builder.workerLease = null);
    assertIllegal(runningBuilder(), builder -> builder.deadlineAt = null);
    assertIllegal(runningBuilder(), builder -> builder.lastActivityAt = null);

    assertIllegal(
        retryBuilder(), builder -> builder.workerLease = new Lease("worker", LEASE_UNTIL));
    assertIllegal(retryBuilder(), builder -> builder.finishedAt = FINISHED);
    assertIllegal(retryBuilder(), builder -> builder.result = RESPONSE);
    assertIllegal(retryBuilder(), builder -> builder.error = ERROR);
    assertIllegal(retryBuilder(), builder -> builder.nextAttemptAt = null);
  }

  /** Terminal statuses enforce their distinct result/error payload and complete-clock contracts. */
  @Test
  void rejectsInvalidTerminalPayloadAndClockShapes() {
    assertTerminalCommonViolations(succeededBuilder());
    assertIllegal(succeededBuilder(), builder -> builder.result = null);
    assertIllegal(succeededBuilder(), builder -> builder.error = ERROR);
    assertIllegal(succeededBuilder(), builder -> builder.deadlineAt = null);

    assertTerminalCommonViolations(failedBuilder());
    assertIllegal(failedBuilder(), builder -> builder.error = null);
    assertIllegal(failedBuilder(), builder -> builder.result = RESPONSE);
    assertIllegal(failedBuilder(), builder -> builder.lastActivityAt = null);

    assertTerminalCommonViolations(unknownBuilder());
    assertIllegal(unknownBuilder(), builder -> builder.error = null);
    assertIllegal(unknownBuilder(), builder -> builder.result = RESPONSE);
    assertIllegal(unknownBuilder(), builder -> builder.deadlineAt = null);

    assertTerminalCommonViolations(cancelledAfterStartBuilder());
    assertIllegal(cancelledAfterStartBuilder(), builder -> builder.result = RESPONSE);
    assertIllegal(cancelledAfterStartBuilder(), builder -> builder.error = ERROR);
    assertIllegal(cancelledAfterStartBuilder(), builder -> builder.deadlineAt = null);
    assertIllegal(
        cancelledBeforeStartBuilder(),
        builder -> {
          builder.startedAt = STARTED;
          builder.deadlineAt = null;
          builder.lastActivityAt = null;
        });
  }

  /** appliedAt is a post-terminal marker and may equal, but never precede, finishedAt. */
  @Test
  void validatesAppliedAtBoundary() {
    assertIllegal(
        queuedBuilder(),
        builder -> {
          builder.finishedAt = FINISHED;
          builder.appliedAt = APPLIED;
        });
    assertIllegal(
        succeededBuilder(),
        builder -> {
          builder.finishedAt = null;
          builder.appliedAt = APPLIED;
        });
    assertIllegal(succeededBuilder(), builder -> builder.appliedAt = FINISHED.minusMillis(1));

    ModelInvocation applied = succeededBuilder().appliedAt(FINISHED).build();
    assertEquals(FINISHED, applied.appliedAt());
    assertFalse(applied.isTerminalUnapplied());
  }

  /** Equal lower bounds accepted by the schema remain valid at the aggregate boundary. */
  @Test
  void acceptsInclusiveTimestampBoundaries() {
    InvocationBuilder builder = succeededBuilder();
    builder.startedAt = CREATED;
    builder.lastActivityAt = CREATED;
    builder.finishedAt = CREATED;
    builder.appliedAt = CREATED;
    builder.deadlineAt = CREATED.plusSeconds(1);

    ModelInvocation invocation = builder.build();

    assertEquals(CREATED, invocation.startedAt());
    assertEquals(CREATED, invocation.lastActivityAt());
    assertEquals(CREATED, invocation.finishedAt());
    assertEquals(CREATED, invocation.appliedAt());
  }

  /** Terminal-unapplied detection delegates to the shared InvocationStatus terminal definition. */
  @Test
  void identifiesOnlyUnappliedTerminalInvocations() {
    assertFalse(queuedBuilder().build().isTerminalUnapplied());
    assertFalse(runningBuilder().build().isTerminalUnapplied());
    assertFalse(retryBuilder().build().isTerminalUnapplied());
    assertTrue(succeededBuilder().build().isTerminalUnapplied());
    assertTrue(failedBuilder().build().isTerminalUnapplied());
    assertTrue(cancelledBeforeStartBuilder().build().isTerminalUnapplied());
    assertTrue(unknownBuilder().build().isTerminalUnapplied());
  }

  /** Worker lease activity uses the shared Lease strict observedAt-before-until boundary. */
  @Test
  void reportsActiveWorkerWithStrictLeaseBoundary() {
    ModelInvocation running = runningBuilder().build();

    assertTrue(running.hasActiveWorkerAt(STARTED));
    assertTrue(running.hasActiveWorkerAt(LEASE_UNTIL.minusMillis(1)));
    assertFalse(running.hasActiveWorkerAt(LEASE_UNTIL));
    assertFalse(running.hasActiveWorkerAt(LEASE_UNTIL.plusMillis(1)));
    assertFalse(retryBuilder().build().hasActiveWorkerAt(STARTED));
    assertThrows(NullPointerException.class, () -> running.hasActiveWorkerAt(null));
  }

  /** Dispatchability is immediate for QUEUED and inclusive at nextAttemptAt for RETRY_WAIT. */
  @Test
  void reportsDispatchableBoundary() {
    ModelInvocation queued = queuedBuilder().build();
    ModelInvocation retry = retryBuilder().build();

    assertTrue(queued.isDispatchableAt(CREATED));
    assertFalse(retry.isDispatchableAt(RETRY_AT.minusMillis(1)));
    assertTrue(retry.isDispatchableAt(RETRY_AT));
    assertTrue(retry.isDispatchableAt(RETRY_AT.plusMillis(1)));
    assertFalse(runningBuilder().build().isDispatchableAt(RETRY_AT));
    assertFalse(succeededBuilder().build().isDispatchableAt(RETRY_AT));
    assertThrows(NullPointerException.class, () -> retry.isDispatchableAt(null));
  }

  private static void assertTerminalCommonViolations(InvocationBuilder source) {
    assertIllegal(source.copy(), builder -> builder.finishedAt = null);
    assertIllegal(
        source.copy(),
        builder -> {
          builder.nextAttemptAt = RETRY_AT;
          builder.workerLease = null;
        });
    assertIllegal(source.copy(), builder -> builder.workerLease = new Lease("worker", LEASE_UNTIL));
  }

  private static void assertIllegal(
      InvocationBuilder builder, Consumer<InvocationBuilder> mutation) {
    mutation.accept(builder);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  private static void assertNullRejected(
      InvocationBuilder builder, Consumer<InvocationBuilder> mutation) {
    mutation.accept(builder);
    assertThrows(NullPointerException.class, builder::build);
  }

  private static InvocationBuilder queuedBuilder() {
    return new InvocationBuilder();
  }

  private static InvocationBuilder runningBuilder() {
    InvocationBuilder builder = new InvocationBuilder();
    builder.status = InvocationStatus.RUNNING;
    builder.workerLease = new Lease("worker", LEASE_UNTIL);
    builder.deadlineAt = DEADLINE;
    builder.lastActivityAt = ACTIVITY;
    builder.startedAt = STARTED;
    return builder;
  }

  private static InvocationBuilder retryBuilder() {
    InvocationBuilder builder = runningBuilder();
    builder.status = InvocationStatus.RETRY_WAIT;
    builder.attempt = 2;
    builder.nextAttemptAt = RETRY_AT;
    builder.workerLease = null;
    return builder;
  }

  private static InvocationBuilder succeededBuilder() {
    InvocationBuilder builder = runningBuilder();
    builder.status = InvocationStatus.SUCCEEDED;
    builder.workerLease = null;
    builder.result = RESPONSE;
    builder.finishedAt = FINISHED;
    return builder;
  }

  private static InvocationBuilder failedBuilder() {
    InvocationBuilder builder = runningBuilder();
    builder.status = InvocationStatus.FAILED;
    builder.workerLease = null;
    builder.error = ERROR;
    builder.finishedAt = FINISHED;
    return builder;
  }

  private static InvocationBuilder unknownBuilder() {
    InvocationBuilder builder = failedBuilder();
    builder.status = InvocationStatus.UNKNOWN;
    return builder;
  }

  private static InvocationBuilder cancelledBeforeStartBuilder() {
    InvocationBuilder builder = new InvocationBuilder();
    builder.status = InvocationStatus.CANCELLED;
    builder.finishedAt = CREATED;
    return builder;
  }

  private static InvocationBuilder cancelledAfterStartBuilder() {
    InvocationBuilder builder = runningBuilder();
    builder.status = InvocationStatus.CANCELLED;
    builder.workerLease = null;
    builder.finishedAt = FINISHED;
    return builder;
  }

  private static ProviderRequest request() {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "default",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            0L,
            "model",
            ProviderType.OPENAI,
            false,
            false,
            pricing,
            PromptCachePolicy.disabled());
    return new ProviderRequest(
        model,
        variant,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("question")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderResponse response() {
    return new ProviderResponse(
        "answer",
        "",
        List.of(),
        ProviderStopReason.COMPLETED,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        null,
        null,
        "{}");
  }

  private static final class InvocationBuilder {
    private long id = 11;
    private long threadId = 22;
    private long sourceHeadEntryId = 33;
    private long executionEpoch;
    private ModelInvocationRequest request = REQUEST;
    private InvocationStatus status = InvocationStatus.QUEUED;
    private int attempt = 1;
    private Instant nextAttemptAt;
    private Lease workerLease;
    private Instant deadlineAt;
    private Instant lastActivityAt;
    private ProviderResponse result;
    private ModelInvocationError error;
    private Instant appliedAt;
    private Instant createdAt = CREATED;
    private Instant startedAt;
    private Instant finishedAt;
    private SafeStreamSnapshot safeStreamSnapshot;

    private InvocationBuilder attempt(int value) {
      attempt = value;
      return this;
    }

    private InvocationBuilder appliedAt(Instant value) {
      appliedAt = value;
      return this;
    }

    private InvocationBuilder copy() {
      InvocationBuilder copy = new InvocationBuilder();
      copy.id = id;
      copy.threadId = threadId;
      copy.sourceHeadEntryId = sourceHeadEntryId;
      copy.executionEpoch = executionEpoch;
      copy.request = request;
      copy.status = status;
      copy.attempt = attempt;
      copy.nextAttemptAt = nextAttemptAt;
      copy.workerLease = workerLease;
      copy.deadlineAt = deadlineAt;
      copy.lastActivityAt = lastActivityAt;
      copy.result = result;
      copy.error = error;
      copy.appliedAt = appliedAt;
      copy.createdAt = createdAt;
      copy.startedAt = startedAt;
      copy.finishedAt = finishedAt;
      copy.safeStreamSnapshot = safeStreamSnapshot;
      return copy;
    }

    private ModelInvocation build() {
      return new ModelInvocation(
          id,
          threadId,
          sourceHeadEntryId,
          executionEpoch,
          request,
          status,
          attempt,
          nextAttemptAt,
          workerLease,
          deadlineAt,
          lastActivityAt,
          result,
          error,
          appliedAt,
          createdAt,
          startedAt,
          finishedAt,
          safeStreamSnapshot);
    }
  }
}
