package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 最终回答只由 TURN_END 结局、续作义务与紧邻 Entry 决定；普通物化与退出补结算共用该规则。 */
class FinalAnswerEntryTest {

  private static final UUID SESSION_ID = id(1);
  private static final UUID PARENT_ID = id(2);
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

  @Test
  void completedAssistantTailFreezesFinalAnswer() {
    Entry assistant = assistant(id(3));

    assertEquals(
        assistant.id(), FinalAnswerEntry.resolve(TurnEndOutcome.COMPLETED, false, assistant));
  }

  @Test
  void failedOrContinuingTurnFreezesNoFinalAnswer() {
    Entry assistant = assistant(id(3));

    assertNull(FinalAnswerEntry.resolve(TurnEndOutcome.FAILED, false, assistant));
    assertNull(FinalAnswerEntry.resolve(TurnEndOutcome.COMPLETED, true, assistant));
  }

  @Test
  void nonAssistantTailOrMissingPrecedingEntryFreezesNoFinalAnswer() {
    Entry user = user(id(3));

    assertNull(FinalAnswerEntry.resolve(TurnEndOutcome.COMPLETED, false, user));
    assertNull(FinalAnswerEntry.resolve(TurnEndOutcome.COMPLETED, false, null));
  }

  private static Entry assistant(UUID entryId) {
    return new Entry(
        entryId,
        SESSION_ID,
        PARENT_ID,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("final answer"))),
            new AssistantMessageMetadata(
                GenerationStopReason.COMPLETE, new ModelUsage(10L, 2L, 0L, 0L, Map.of(), 0L, 12L)),
            null),
        NOW);
  }

  private static Entry user(UUID entryId) {
    return new Entry(
        entryId,
        SESSION_ID,
        PARENT_ID,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("question"))),
            null,
            null),
        NOW);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
