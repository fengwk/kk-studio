package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CompactionPreparation(
    CompactionPhase phase,
    CompactionTrigger trigger,
    UUID cutEntryId,
    UUID turnPrefixStartEntryId,
    UUID historyCompactionEntryId,
    String previousSummary,
    List<AgentMessage> messagesToSummarize,
    long removedPrefixTokens) {

  public CompactionPreparation {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    if (phase == CompactionPhase.FULL) {
      if (turnPrefixStartEntryId != null || historyCompactionEntryId != null) {
        throw new IllegalArgumentException(
            "FULL preparation must not carry turnPrefixStartEntryId or historyCompactionEntryId");
      }
    } else if (phase == CompactionPhase.HISTORY) {
      if (turnPrefixStartEntryId == null) {
        throw new IllegalArgumentException("HISTORY preparation requires turnPrefixStartEntryId");
      }
      if (historyCompactionEntryId != null) {
        throw new IllegalArgumentException(
            "HISTORY preparation must not carry historyCompactionEntryId");
      }
    } else if (turnPrefixStartEntryId == null) {
      throw new IllegalArgumentException("TURN_PREFIX preparation requires turnPrefixStartEntryId");
    }
    messagesToSummarize =
        List.copyOf(Objects.requireNonNull(messagesToSummarize, "messagesToSummarize"));
    if (messagesToSummarize.isEmpty()) {
      throw new IllegalArgumentException("messagesToSummarize must not be empty");
    }
    if (removedPrefixTokens <= 0) {
      throw new IllegalArgumentException("removedPrefixTokens must be positive");
    }
  }

  /** 切分事实（phase/trigger/cut/turnPrefixStart/historyCompactionEntryId）的待解析视图。 */
  public CompactionStart pendingStart() {
    return CompactionStart.pending(
        phase, trigger, cutEntryId, turnPrefixStartEntryId, historyCompactionEntryId);
  }
}
