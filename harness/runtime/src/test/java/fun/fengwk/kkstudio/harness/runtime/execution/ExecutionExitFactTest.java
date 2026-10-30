package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** 执行退出事实的外部边界：完整 identity、kind/state 证据、远端父代与 canonical 持久字段。 */
class ExecutionExitFactTest {

  private static final UUID TOOL_EXECUTION_ID = new UUID(0L, 101L);
  private static final UUID MODEL_EXECUTION_ID = new UUID(0L, 102L);
  private static final UUID REMOTE_EXECUTION_ID = new UUID(0L, 103L);
  private static final UUID OTHER_EXECUTION_ID = new UUID(0L, 105L);
  private static final UUID OTHER_PARENT_ID = new UUID(0L, 104L);
  private static final UUID THREAD_ID = new UUID(0L, 201L);
  private static final UUID OTHER_THREAD_ID = new UUID(0L, 202L);
  private static final UUID INVOCATION_ID = new UUID(0L, 301L);
  private static final UUID OTHER_INVOCATION_ID = new UUID(0L, 302L);
  private static final String LEASE = "lease-1";
  private static final String OTHER_LEASE = "lease-2";
  private static final Instant REGISTERED_AT = Instant.parse("2026-07-01T00:00:00.123Z");
  private static final Instant CONFIRMED_AT = REGISTERED_AT.plusSeconds(1);

  @Test
  void buildsValidModelToolAndRemoteFacts() {
    assertEquals(ExecutionExitKind.MODEL, model().kind());
    assertEquals(ExecutionExitKind.TOOL, tool().kind());
    assertEquals(ExecutionExitKind.REMOTE, remote().kind());
    assertEquals(TOOL_EXECUTION_ID, remote().parentExecutionId());
  }

  @Test
  void sameIdentityIgnoresRegistrationAndConfirmationEvidence() {
    ExecutionExitFact tool = tool();
    ExecutionExitFact laterRegistration =
        toolAt(ExecutionExitState.UNCONFIRMED, REGISTERED_AT.plusMillis(1), null);
    ExecutionExitFact confirmed = tool.confirm(ExecutionExitState.STUDIO_EXITED, CONFIRMED_AT);

    assertTrue(tool.sameIdentity(laterRegistration));
    assertTrue(laterRegistration.sameIdentity(tool));
    assertTrue(tool.sameIdentity(confirmed));
    assertTrue(confirmed.sameIdentity(tool));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("identityMismatches")
  void sameIdentityRejectsEveryIdentityMismatch(
      String field, ExecutionExitFact fact, ExecutionExitFact mismatched) {
    assertFalse(fact.sameIdentity(mismatched), field);
    assertFalse(mismatched.sameIdentity(fact), field);
  }

  static Stream<Arguments> identityMismatches() {
    ExecutionExitFact remote = remote();
    return Stream.of(
        Arguments.of(
            "executionId",
            remote,
            unconfirmed(
                OTHER_EXECUTION_ID,
                THREAD_ID,
                INVOCATION_ID,
                1,
                LEASE,
                ExecutionExitKind.REMOTE,
                TOOL_EXECUTION_ID)),
        Arguments.of(
            "threadId",
            remote,
            unconfirmed(
                REMOTE_EXECUTION_ID,
                OTHER_THREAD_ID,
                INVOCATION_ID,
                1,
                LEASE,
                ExecutionExitKind.REMOTE,
                TOOL_EXECUTION_ID)),
        Arguments.of(
            "invocationId",
            remote,
            unconfirmed(
                REMOTE_EXECUTION_ID,
                THREAD_ID,
                OTHER_INVOCATION_ID,
                1,
                LEASE,
                ExecutionExitKind.REMOTE,
                TOOL_EXECUTION_ID)),
        Arguments.of(
            "attempt",
            remote,
            unconfirmed(
                REMOTE_EXECUTION_ID,
                THREAD_ID,
                INVOCATION_ID,
                2,
                LEASE,
                ExecutionExitKind.REMOTE,
                TOOL_EXECUTION_ID)),
        Arguments.of(
            "ownerLeaseToken",
            remote,
            unconfirmed(
                REMOTE_EXECUTION_ID,
                THREAD_ID,
                INVOCATION_ID,
                1,
                OTHER_LEASE,
                ExecutionExitKind.REMOTE,
                TOOL_EXECUTION_ID)),
        // MODEL/TOOL 都是合法的无父代事实；kind 负例不能同时改变 parent，否则会掩盖漏掉 kind 的比较。
        Arguments.of(
            "kind",
            tool(),
            unconfirmed(
                TOOL_EXECUTION_ID,
                THREAD_ID,
                INVOCATION_ID,
                1,
                LEASE,
                ExecutionExitKind.MODEL,
                null)),
        Arguments.of(
            "parentExecutionId",
            remote,
            unconfirmed(
                REMOTE_EXECUTION_ID,
                THREAD_ID,
                INVOCATION_ID,
                1,
                LEASE,
                ExecutionExitKind.REMOTE,
                OTHER_PARENT_ID)));
  }

  @Test
  void rejectsKindStateCombinationsInBothDirections() {
    assertThrows(
        IllegalArgumentException.class,
        () -> toolAt(ExecutionExitState.REMOTE_ACKNOWLEDGED, REGISTERED_AT, CONFIRMED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> model().confirm(ExecutionExitState.REMOTE_ACKNOWLEDGED, CONFIRMED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> remote().confirm(ExecutionExitState.STUDIO_EXITED, CONFIRMED_AT));
  }

  @Test
  void acceptsNotStartedForLocalAndRemoteGenerations() {
    ExecutionExitFact local = toolAt(ExecutionExitState.NOT_STARTED, REGISTERED_AT, CONFIRMED_AT);
    ExecutionExitFact remote =
        remoteAt(ExecutionExitState.NOT_STARTED, REGISTERED_AT, CONFIRMED_AT);

    assertEquals(ExecutionExitState.NOT_STARTED, local.state());
    assertEquals(ExecutionExitState.NOT_STARTED, remote.state());
  }

  @Test
  void confirmedEvidenceIsIdempotentAndCannotBeRewritten() {
    ExecutionExitFact confirmed = tool().confirm(ExecutionExitState.STUDIO_EXITED, CONFIRMED_AT);

    assertSame(confirmed, confirmed.confirm(ExecutionExitState.STUDIO_EXITED, CONFIRMED_AT));
    assertSame(
        confirmed,
        confirmed.confirm(ExecutionExitState.STUDIO_EXITED, CONFIRMED_AT.plusSeconds(30)));
    assertEquals(CONFIRMED_AT, confirmed.confirmedAt());
    assertThrows(
        IllegalArgumentException.class,
        () -> confirmed.confirm(ExecutionExitState.NOT_STARTED, CONFIRMED_AT.plusSeconds(1)));
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertThrows(
        IllegalArgumentException.class,
        () -> confirmed.confirm(ExecutionExitState.UNCONFIRMED, CONFIRMED_AT.plusSeconds(1)));
  }

  @Test
  void abandonmentIsExplicitIdempotentAndUpgradeableByRealEvidence() {
    ExecutionExitFact abandoned = tool().abandon(REGISTERED_AT.plusMillis(10));
    assertEquals(ExecutionExitState.ABANDONED, abandoned.state());
    assertFalse(abandoned.state().confirmed());
    assertNull(abandoned.confirmedAt());
    assertEquals(REGISTERED_AT.plusMillis(10), abandoned.abandonedAt());
    assertSame(abandoned, abandoned.abandon(REGISTERED_AT.plusMillis(20)));

    ExecutionExitFact upgraded = abandoned.confirm(ExecutionExitState.STUDIO_EXITED, CONFIRMED_AT);
    assertEquals(ExecutionExitState.STUDIO_EXITED, upgraded.state());
    assertEquals(CONFIRMED_AT, upgraded.confirmedAt());
    assertEquals(abandoned.abandonedAt(), upgraded.abandonedAt());
    assertThrows(
        IllegalArgumentException.class, () -> upgraded.abandon(CONFIRMED_AT.plusMillis(1)));
  }

  @Test
  void realEvidenceObservedBeforeAbandonmentIsStillValidAndNotRewritten() {
    // 测试意图：观测时间可以早于用户放弃时刻；两者各自只需不早于登记，不能为排序伪造时间。
    ExecutionExitFact abandoned = tool().abandon(REGISTERED_AT.plusMillis(10));
    ExecutionExitFact upgraded =
        abandoned.confirm(ExecutionExitState.STUDIO_EXITED, REGISTERED_AT.plusMillis(5));
    assertEquals(REGISTERED_AT.plusMillis(5), upgraded.confirmedAt());
    assertEquals(REGISTERED_AT.plusMillis(10), upgraded.abandonedAt());
    assertEquals(ExecutionExitState.STUDIO_EXITED, upgraded.state());
  }

  @Test
  void remoteParentMustBeTheSameGenerationLocalTool() {
    ExecutionExitFact remote = remote();
    ExecutionExitFact tool = tool();

    assertDoesNotThrow(() -> remote.requireRemoteParent(tool));
    // 除 kind 外与合法 TOOL 父代完全相同，确保拒绝原因不是另一执行 id。
    ExecutionExitFact modelParent =
        unconfirmed(
            TOOL_EXECUTION_ID, THREAD_ID, INVOCATION_ID, 1, LEASE, ExecutionExitKind.MODEL, null);
    assertEquals(
        "remote execution parent must be the same generation's TOOL execution",
        assertThrows(IllegalArgumentException.class, () -> remote.requireRemoteParent(modelParent))
            .getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            remote.requireRemoteParent(
                unconfirmed(
                    TOOL_EXECUTION_ID,
                    OTHER_THREAD_ID,
                    INVOCATION_ID,
                    1,
                    LEASE,
                    ExecutionExitKind.TOOL,
                    null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            remote.requireRemoteParent(
                unconfirmed(
                    TOOL_EXECUTION_ID,
                    THREAD_ID,
                    INVOCATION_ID,
                    2,
                    LEASE,
                    ExecutionExitKind.TOOL,
                    null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            remote.requireRemoteParent(
                unconfirmed(
                    TOOL_EXECUTION_ID,
                    THREAD_ID,
                    INVOCATION_ID,
                    1,
                    OTHER_LEASE,
                    ExecutionExitKind.TOOL,
                    null)));
  }

  @Test
  void rejectsNonCanonicalLeaseAndNonPositiveAttempt() {
    for (String lease : List.of("", " lease-1", "lease-1 ", "a".repeat(129))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              unconfirmed(
                  TOOL_EXECUTION_ID,
                  THREAD_ID,
                  INVOCATION_ID,
                  1,
                  lease,
                  ExecutionExitKind.TOOL,
                  null));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            unconfirmed(
                TOOL_EXECUTION_ID,
                THREAD_ID,
                INVOCATION_ID,
                0,
                LEASE,
                ExecutionExitKind.TOOL,
                null));
  }

  @Test
  void rejectsNonMillisecondAndContradictoryTimestamps() {
    Instant subMillisecond = REGISTERED_AT.plusNanos(1);

    assertThrows(
        IllegalArgumentException.class,
        () -> toolAt(ExecutionExitState.UNCONFIRMED, subMillisecond, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> toolAt(ExecutionExitState.STUDIO_EXITED, REGISTERED_AT, subMillisecond));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolAt(ExecutionExitState.STUDIO_EXITED, REGISTERED_AT, REGISTERED_AT.minusMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> toolAt(ExecutionExitState.UNCONFIRMED, REGISTERED_AT, CONFIRMED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> tool().confirm(ExecutionExitState.STUDIO_EXITED, subMillisecond));
  }

  private static ExecutionExitFact model() {
    return unconfirmed(
        MODEL_EXECUTION_ID, THREAD_ID, INVOCATION_ID, 1, LEASE, ExecutionExitKind.MODEL, null);
  }

  private static ExecutionExitFact tool() {
    return unconfirmed(
        TOOL_EXECUTION_ID, THREAD_ID, INVOCATION_ID, 1, LEASE, ExecutionExitKind.TOOL, null);
  }

  private static ExecutionExitFact remote() {
    return unconfirmed(
        REMOTE_EXECUTION_ID,
        THREAD_ID,
        INVOCATION_ID,
        1,
        LEASE,
        ExecutionExitKind.REMOTE,
        TOOL_EXECUTION_ID);
  }

  private static ExecutionExitFact toolAt(
      ExecutionExitState state, Instant registeredAt, Instant confirmedAt) {
    return fact(
        TOOL_EXECUTION_ID,
        THREAD_ID,
        INVOCATION_ID,
        1,
        LEASE,
        ExecutionExitKind.TOOL,
        null,
        state,
        registeredAt,
        confirmedAt);
  }

  private static ExecutionExitFact remoteAt(
      ExecutionExitState state, Instant registeredAt, Instant confirmedAt) {
    return fact(
        REMOTE_EXECUTION_ID,
        THREAD_ID,
        INVOCATION_ID,
        1,
        LEASE,
        ExecutionExitKind.REMOTE,
        TOOL_EXECUTION_ID,
        state,
        registeredAt,
        confirmedAt);
  }

  private static ExecutionExitFact unconfirmed(
      UUID executionId,
      UUID threadId,
      UUID invocationId,
      int attempt,
      String ownerLeaseToken,
      ExecutionExitKind kind,
      UUID parentExecutionId) {
    return fact(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        ExecutionExitState.UNCONFIRMED,
        REGISTERED_AT,
        null);
  }

  private static ExecutionExitFact fact(
      UUID executionId,
      UUID threadId,
      UUID invocationId,
      int attempt,
      String ownerLeaseToken,
      ExecutionExitKind kind,
      UUID parentExecutionId,
      ExecutionExitState state,
      Instant registeredAt,
      Instant confirmedAt) {
    return new ExecutionExitFact(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        state,
        registeredAt,
        confirmedAt,
        null);
  }
}
