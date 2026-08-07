package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** ToolApproval 在 required/undecided/decided 下的精确不变式。 */
class ToolApprovalTest {

  private static final Instant REQUESTED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant DECIDED = REQUESTED.plusSeconds(5);

  @Test
  void acceptsNotRequiredApprovalWithOnlyNullFacts() {
    ToolApproval approval = new ToolApproval(false, null, null, null, null, null, null);
    assertFalse(approval.required());
    assertFalse(approval.isUndecided());
  }

  @Test
  void acceptsUndecidedApprovalWithRequestedAtAndOptionalReason() {
    ToolApproval withoutReason = new ToolApproval(true, null, null, null, null, REQUESTED, null);
    assertTrue(withoutReason.required());
    assertTrue(withoutReason.isUndecided());
    assertEquals(REQUESTED, withoutReason.requestedAt());
    assertNull(withoutReason.reason());

    ToolApproval withReason =
        new ToolApproval(true, null, null, null, "user asked", REQUESTED, null);
    assertEquals("user asked", withReason.reason());
  }

  @Test
  void acceptsDecidedApprovalWithFullDecisionFacts() {
    ToolApproval allowed =
        new ToolApproval(
            true, ToolApprovalDecision.ALLOWED, "d-1", "actor", null, REQUESTED, DECIDED);
    assertFalse(allowed.isUndecided());
    assertEquals(ToolApprovalDecision.ALLOWED, allowed.decision());
    assertEquals("d-1", allowed.decisionId());
    assertEquals("actor", allowed.actor());
    assertEquals(DECIDED, allowed.decidedAt());

    ToolApproval denied =
        new ToolApproval(
            true, ToolApprovalDecision.DENIED, "d-2", "actor", "denied reason", REQUESTED, DECIDED);
    assertEquals(ToolApprovalDecision.DENIED, denied.decision());
    assertEquals("denied reason", denied.reason());

    ToolApproval decidedAtRequested =
        new ToolApproval(
            true, ToolApprovalDecision.ALLOWED, "d-3", "actor", null, REQUESTED, REQUESTED);
    assertEquals(REQUESTED, decidedAtRequested.decidedAt());
  }

  @Test
  void rejectsNotRequiredApprovalCarryingFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(false, ToolApprovalDecision.ALLOWED, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(false, null, "d-1", null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(false, null, null, "actor", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(false, null, null, null, "reason", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(false, null, null, null, null, REQUESTED, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(false, null, null, null, null, null, DECIDED));
  }

  @Test
  void rejectsUndecidedApprovalCarryingDecisionFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(true, null, "d-1", null, null, REQUESTED, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(true, null, null, "actor", null, REQUESTED, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolApproval(true, null, null, null, null, REQUESTED, DECIDED));
    assertThrows(
        NullPointerException.class,
        () -> new ToolApproval(true, null, null, null, null, null, null));
  }

  @Test
  void rejectsDecidedApprovalMissingOrInvalidDecisionFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, null, "actor", null, REQUESTED, DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, "d-1", null, null, REQUESTED, DECIDED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, "d-1", "actor", null, null, DECIDED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, "d-1", "actor", null, REQUESTED, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true,
                ToolApprovalDecision.ALLOWED,
                "d-1",
                "actor",
                null,
                DECIDED.plusSeconds(1),
                DECIDED));
  }

  @Test
  void rejectsNonCanonicalTexts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, " ", "actor", null, REQUESTED, DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, " d-1", "actor", null, REQUESTED, DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true,
                ToolApprovalDecision.ALLOWED,
                "d".repeat(129),
                "actor",
                null,
                REQUESTED,
                DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, "d-1", " actor", null, REQUESTED, DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true,
                ToolApprovalDecision.ALLOWED,
                "d-1",
                "a".repeat(129),
                null,
                REQUESTED,
                DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true,
                ToolApprovalDecision.ALLOWED,
                "d-1",
                "actor",
                "r".repeat(1025),
                REQUESTED,
                DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolApproval(
                true, ToolApprovalDecision.ALLOWED, "d-1", "actor", " ", REQUESTED, DECIDED));
  }

  @Test
  void staticFactoriesBuildTheCanonicalShapes() {
    ToolApproval notRequired = ToolApproval.notRequired();
    assertFalse(notRequired.required());
    assertFalse(notRequired.isUndecided());
    assertNull(notRequired.requestedAt());
    assertNull(notRequired.decidedAt());

    ToolApproval requested = ToolApproval.request(REQUESTED, "why");
    assertTrue(requested.required());
    assertTrue(requested.isUndecided());
    assertEquals(REQUESTED, requested.requestedAt());
    assertEquals("why", requested.reason());
    assertNull(requested.decisionId());
  }

  @Test
  void decideAppliesTheDecisionToAnUndecidedApproval() {
    ToolApproval requested = ToolApproval.request(REQUESTED, "why");
    ToolApproval decided =
        requested.decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED);
    assertFalse(decided.isUndecided());
    assertEquals(ToolApprovalDecision.ALLOWED, decided.decision());
    assertEquals("d-1", decided.decisionId());
    assertEquals("actor", decided.actor());
    assertEquals(REQUESTED, decided.requestedAt());
    assertEquals(DECIDED, decided.decidedAt());
  }

  @Test
  void decideIsExactIdempotentAndConflictsOnRewrites() {
    ToolApproval decided =
        ToolApproval.request(REQUESTED, null)
            .decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED);
    // 完全相同的 decision payload 以 idempotent 方式重放
    assertEquals(
        decided, decided.decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED));
    // 相同 decisionId 但不同 payload 视为冲突
    assertThrows(
        IllegalArgumentException.class,
        () -> decided.decide(ToolApprovalDecision.ALLOWED, "d-1", "other", null, DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () -> decided.decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", "new reason", DECIDED));
    // 已存在但不同的 decision 即使使用新 decisionId 仍视为冲突
    assertThrows(
        IllegalArgumentException.class,
        () -> decided.decide(ToolApprovalDecision.DENIED, "d-9", "actor", null, DECIDED));
    // 非 required 的 approval 永远不能被 decide
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolApproval.notRequired()
                .decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED));
  }

  @Test
  void decideIgnoresAFreshDecidedAtOnAnIdempotentNetworkRetry() {
    ToolApproval decided =
        ToolApproval.request(REQUESTED, null)
            .decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED);
    // 客户端不发送 decidedAt，因此重试请求携带的是新的服务端当前时间：
    // 相同 decisionId + decision + actor + reason 必须原样返回已存储的 approval
    ToolApproval replayed =
        decided.decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED.plusSeconds(30));
    assertEquals(decided, replayed);
    assertEquals(DECIDED, replayed.decidedAt());
    // 任何标识事实发生变化的 retry 都是冲突，而非 replay
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decided.decide(
                ToolApprovalDecision.ALLOWED, "d-1", "other-actor", null, DECIDED.plusSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () -> decided.decide(ToolApprovalDecision.DENIED, "d-1", "actor", null, DECIDED));
  }

  @Test
  void decideRejectsInvalidDecisionFacts() {
    ToolApproval requested = ToolApproval.request(REQUESTED, null);
    assertThrows(
        NullPointerException.class, () -> requested.decide(null, "d-1", "actor", null, DECIDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            requested.decide(
                ToolApprovalDecision.ALLOWED, "d-1", "actor", null, REQUESTED.minusSeconds(1)));
  }
}
