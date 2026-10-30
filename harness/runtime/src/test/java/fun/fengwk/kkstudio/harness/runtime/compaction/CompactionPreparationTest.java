package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.List;
import java.util.UUID;

/** Transient CompactionPreparation 的非空摘要范围与 phase anchor 不变量。 */
class CompactionPreparationTest {

  private static final List<AgentMessage> MESSAGES = List.of(AgentMessage.user("message"));

  @Test
  void pendingStartContainsOnlyDurableFacts() {
    CompactionPreparation preparation =
        new CompactionPreparation(
            CompactionPhase.FULL,
            CompactionTrigger.MANUAL,
            id(4L),
            null,
            null,
            "previous",
            MESSAGES,
            10);

    assertEquals(
        CompactionStart.pending(CompactionPhase.FULL, CompactionTrigger.MANUAL, id(4L), null, null),
        preparation.pendingStart());
  }

  @Test
  void rejectsInvalidPhaseAnchorsAndEmptyRanges() {
    assertInvalid(CompactionPhase.FULL, id(3L), null, MESSAGES, 10);
    assertInvalid(CompactionPhase.HISTORY, null, null, MESSAGES, 10);
    assertInvalid(CompactionPhase.HISTORY, id(3L), id(8L), MESSAGES, 10);
    assertInvalid(CompactionPhase.TURN_PREFIX, null, null, MESSAGES, 10);
    assertInvalid(CompactionPhase.FULL, null, null, List.of(), 10);
    assertInvalid(CompactionPhase.FULL, null, null, MESSAGES, 0);
  }

  private static void assertInvalid(
      CompactionPhase phase,
      UUID turnPrefixStartEntryId,
      UUID historyCompactionEntryId,
      List<AgentMessage> messages,
      long removedPrefixTokens) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompactionPreparation(
                phase,
                CompactionTrigger.THRESHOLD,
                id(4L),
                turnPrefixStartEntryId,
                historyCompactionEntryId,
                null,
                messages,
                removedPrefixTokens));
  }
}
