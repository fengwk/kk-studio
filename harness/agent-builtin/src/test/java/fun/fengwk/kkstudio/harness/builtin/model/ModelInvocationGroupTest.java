package fun.fengwk.kkstudio.harness.builtin.model;

import static fun.fengwk.kkstudio.harness.builtin.model.InvocationTestData.error;
import static fun.fengwk.kkstudio.harness.builtin.model.InvocationTestData.requestSpec;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

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
            ModelSelection.model("model", "v1"),
            null,
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
        new ModelAttemptFailure(
            1,
            0,
            "",
            "",
            ModelSelection.model("model", "v1"),
            null,
            error(),
            CREATED,
            CREATED.plusSeconds(1));

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
    ModelInvocation running = ready().markRunning(CREATED.plusSeconds(2));
    assertEquals(CREATED.plusSeconds(2), running.requestedAt());
    assertTrue(running.requestedAt().isAfter(CREATED));
  }

  @Test
  void progressMustStayOnFrozenGroupAndAdvanceMonotonically() {
    ModelInvocation initial = ready();
    ModelInvocation budget =
        initial.withGroupProgress(new ModelGroupProgress("group", 7L, 0, 2), CREATED);
    ModelInvocation next =
        budget.withGroupProgress(new ModelGroupProgress("group", 7L, 1, 0), CREATED);
    ModelInvocation.validateTransition(initial, budget);
    ModelInvocation.validateTransition(budget, next);

    // 同一成员的预算不可倒退；切换成员必须清零，且绝不能换用新的配置。
    for (ModelGroupProgress invalid :
        List.of(
            new ModelGroupProgress("other", 7L, 0, 2),
            new ModelGroupProgress("group", 8L, 0, 2),
            new ModelGroupProgress("group", 7L, 0, 1),
            new ModelGroupProgress("group", 7L, 1, 1))) {
      assertThrows(
          IllegalArgumentException.class, () -> budget.withGroupProgress(invalid, CREATED));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> next.withGroupProgress(new ModelGroupProgress("group", 7L, 0, 2), CREATED));
    ModelInvocation running = initial.markRunning(CREATED);
    assertThrows(
        IllegalArgumentException.class,
        () -> running.withGroupProgress(new ModelGroupProgress("group", 7L, 0, 1), CREATED));
  }

  @Test
  void requestedAtCanOnlyChangeAtConfirmedStart() {
    ModelInvocation initial = ready();
    ModelInvocation running = initial.markRunning(CREATED.plusSeconds(1));
    ModelInvocation.validateTransition(initial, running);
    ModelInvocation checkpoint =
        running.checkpoint(new StreamCheckpoint(1, 1, "a", ""), CREATED.plusSeconds(2));
    ModelInvocation terminal = running.fail(error(), CREATED.plusSeconds(2));
    ModelInvocation.validateTransition(running, checkpoint);
    ModelInvocation.validateTransition(running, terminal);
    assertEquals(running.requestedAt(), checkpoint.requestedAt());
    assertEquals(running.requestedAt(), terminal.requestedAt());

    // READY 重排、RUNNING partial 和终态链接均不能伪造或清除真实请求起点。
    for (ModelInvocation state : List.of(initial, running, terminal)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ModelInvocation.validateTransition(
                  state, withRequestedAt(state, CREATED.plusSeconds(3))));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(running, withRequestedAt(running, null)));
  }

  private static ModelInvocation withRequestedAt(ModelInvocation value, Instant requestedAt) {
    return new ModelInvocation(
        value.id(),
        value.threadId(),
        value.turnStartEntryId(),
        value.requestHeadEntryId(),
        value.requestSpec(),
        value.status(),
        value.attempt(),
        value.streamCheckpoint(),
        value.result(),
        value.error(),
        value.resultEntryId(),
        value.failedAttempts(),
        value.createdAt(),
        value.updatedAt(),
        value.providerReplayState(),
        value.groupProgress(),
        requestedAt);
  }
}
