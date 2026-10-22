package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.error;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.requestSpec;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 组进度只允许单调推进；UNKNOWN 保留当前成员，绝不降级。 */
class ModelInvocationGroupTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  private static ModelRequestSpec groupRequest() {
    ModelRequestSpec single = requestSpec();
    ModelGroupMemberExecution first =
        new ModelGroupMemberExecution(
            single.providerType(),
            single.providerConnectionGenerationId(),
            single.model(),
            single.variant());
    ModelGroupMemberExecution second =
        new ModelGroupMemberExecution(
            single.providerType(),
            single.providerConnectionGenerationId(),
            single.model(),
            single.variant());
    ModelGroupExecution group =
        new ModelGroupExecution("group", 7L, List.of(first, second), Set.of(429), 30);
    return new ModelRequestSpec(
        single.catalogEpoch(),
        single.providerType(),
        single.providerConnectionGenerationId(),
        single.model(),
        single.variant(),
        single.outputTokens(),
        single.systemInstruction(),
        single.toolBindings(),
        single.subagentBindings(),
        single.cacheControl(),
        group);
  }

  private static ModelInvocation ready() {
    return new ModelInvocation(
        id(1L),
        id(1L),
        id(1L),
        id(1L),
        groupRequest(),
        ModelInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        CREATED,
        CREATED,
        null,
        new ModelGroupProgress("group", 7L, 0, 0));
  }

  @Test
  void advanceRecordsFailureAndResetsMemberAttempts() {
    ModelInvocation running =
        new ModelInvocation(
            id(1L),
            id(1L),
            id(1L),
            id(1L),
            groupRequest(),
            ModelInvocationStatus.RUNNING,
            1,
            null,
            null,
            null,
            null,
            List.of(),
            CREATED,
            CREATED,
            null,
            new ModelGroupProgress("group", 7L, 0, 2));
    ModelAttemptFailure failure =
        new ModelAttemptFailure(
            1,
            0,
            "",
            "",
            new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "denied"),
            CREATED,
            CREATED);

    ModelInvocation advanced =
        running.advanceGroupReady(
            failure, new ModelGroupProgress("group", 7L, 1, 0), CREATED.plusSeconds(1));

    assertEquals(ModelInvocationStatus.READY, advanced.status());
    assertEquals(new ModelGroupProgress("group", 7L, 1, 0), advanced.groupProgress());
    assertEquals(List.of(failure), advanced.failedAttempts());
    assertEquals(ModelSelection.model("model", "v1"), advanced.actualSelection());
  }

  @Test
  void retryStaysOnCurrentMemberAndAdvancesMemberAttemptBudget() {
    ModelInvocation running =
        new ModelInvocation(
            id(1L),
            id(1L),
            id(1L),
            id(1L),
            groupRequest(),
            ModelInvocationStatus.RUNNING,
            1,
            null,
            null,
            null,
            null,
            List.of(),
            CREATED,
            CREATED,
            null,
            new ModelGroupProgress("group", 7L, 0, 0));
    ModelAttemptFailure failure =
        new ModelAttemptFailure(1, 0, "", "", error(), CREATED, CREATED.plusSeconds(1));

    ModelInvocation retried =
        running
            .retryReady(failure, CREATED.plusSeconds(1))
            .withGroupProgress(new ModelGroupProgress("group", 7L, 0, 1), CREATED.plusSeconds(1));

    assertEquals(ModelInvocationStatus.READY, retried.status());
    assertEquals(new ModelGroupProgress("group", 7L, 0, 1), retried.groupProgress());
    assertEquals(ModelSelection.model("model", "v1"), retried.actualSelection());
  }

  @Test
  void unknownIsTerminalForTheSameMember() {
    ModelInvocation running =
        new ModelInvocation(
            id(1L),
            id(1L),
            id(1L),
            id(1L),
            groupRequest(),
            ModelInvocationStatus.RUNNING,
            1,
            null,
            null,
            null,
            null,
            List.of(),
            CREATED,
            CREATED,
            null,
            new ModelGroupProgress("group", 7L, 1, 0));

    ModelInvocation unknown =
        running.unknown(
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unknown"),
            CREATED.plusSeconds(1));

    assertEquals(ModelInvocationStatus.UNKNOWN, unknown.status());
    assertEquals(new ModelGroupProgress("group", 7L, 1, 0), unknown.groupProgress());
    assertEquals(ModelSelection.model("model", "v1"), unknown.actualSelection());
  }

  @Test
  void groupProgressCannotRegress() {
    ModelInvocation ready = ready();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ready.withGroupProgress(
                new ModelGroupProgress("group", 7L, 0, -1), CREATED.plusSeconds(1)));
  }

  @Test
  void confirmedStartFreezesRequestedAt() {
    ModelInvocation running =
        ready().beginDispatch(CREATED.plusSeconds(1)).markRunning(CREATED.plusSeconds(2));
    assertEquals(CREATED.plusSeconds(2), running.requestedAt());
    assertTrue(running.requestedAt().isAfter(CREATED));
  }
}
