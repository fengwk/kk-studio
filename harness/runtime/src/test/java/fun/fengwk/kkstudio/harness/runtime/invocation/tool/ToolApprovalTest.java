package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** ToolApproval exact required/undecided/decided invariants. */
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
    assertEquals(null, withoutReason.reason());

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
}
