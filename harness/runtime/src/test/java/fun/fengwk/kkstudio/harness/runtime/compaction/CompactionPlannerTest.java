package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.session.VideoMessageContent;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class CompactionPlannerTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);
  private static final Instant NOW = Instant.ofEpochSecond(1000L);
  private static final long CONTEXT_WINDOW = 200L;

  @Test
  void plansOnlyAtAClosedTurnBoundaryAndLeavesRecentTurnsInTheMainThread() {
    PathBuilder path = new PathBuilder();
    path.turn("turn 1");
    path.turn("turn 2");
    path.turn("turn 3");

    CompactionPreparation preparation =
        new CompactionPlanner(new CompactionConfig(75))
            .prepare(path.path(), CompactionTrigger.THRESHOLD, CONTEXT_WINDOW)
            .orElseThrow();

    assertEquals(id(5L), preparation.cutEntryId());
    assertInstanceOf(
        TurnEndPayload.class,
        path.path()
            .entries()
            .get(
                new CompactionCutPlanner(new CompactionConfig(75))
                    .plan(path.path(), CONTEXT_WINDOW)
                    .orElseThrow()
                    .pathIndex())
            .payload());
  }

  @Test
  void protectsAnOversizedLatestModelCallTurn() {
    PathBuilder path = new PathBuilder();
    path.turn("older");
    path.turn("x".repeat(2_000));

    CompactionPreparation preparation =
        new CompactionPlanner(new CompactionConfig(20_000))
            .prepare(path.path(), CompactionTrigger.THRESHOLD, 100L)
            .orElseThrow();

    assertEquals(id(5L), preparation.cutEntryId());
  }

  @Test
  void returnsEmptyWhenNoCompletedTurnCanBeSummarized() {
    PathBuilder path = new PathBuilder();
    path.turn("only turn");

    assertTrue(
        new CompactionPlanner(new CompactionConfig(1))
            .prepare(path.path(), CompactionTrigger.THRESHOLD, 2L)
            .isEmpty());
  }

  @Test
  void rejectsAnOpenTurnInsteadOfChoosingAnInteriorCut() {
    PathBuilder path = new PathBuilder();
    path.turn("completed");
    path.startTurn("unfinished");

    assertThrows(
        IllegalArgumentException.class,
        () -> new CompactionCutPlanner(new CompactionConfig(1)).plan(path.path(), CONTEXT_WINDOW));
  }

  @Test
  void estimatesTextTokensDeterministically() {
    assertEquals(25L, TokenEstimator.estimateTextTokens("x".repeat(100)));
  }

  @Test
  void estimatesStructuredAndMediaContentsDeterministically() {
    AgentMessageContent image = new ImageMessageContent("image/png", "image-source");
    AgentMessageContent audio = new AudioMessageContent("audio/mpeg", "audio-source");
    AgentMessageContent video = new VideoMessageContent("video/mp4", "video-source");
    AgentMessageContent resource =
        ResourceMessageContent.media(id(200L), "resource.bin", "preview");
    Entry assistant =
        new Entry(
            id(2L),
            id(100L),
            id(1L),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new TextMessageContent("abcd"),
                        new ThinkingMessageContent("efgh"),
                        new JsonMessageContent("{\"x\":1}"),
                        new ToolCallMessageContent("call-1", "tool", "tool", "{}"),
                        image,
                        audio,
                        video,
                        resource)),
                new AssistantMessageMetadata(
                    GenerationStopReason.COMPLETE,
                    new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                null),
            NOW);
    Entry toolResult =
        new Entry(
            id(3L),
            id(100L),
            id(2L),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.TOOL,
                    List.of(
                        new ToolResultMessageContent(
                            "call-1",
                            "tool",
                            "tool",
                            List.of(
                                new TextMessageContent("ijkl"),
                                new JsonMessageContent("{\"y\":2}"),
                                image,
                                audio,
                                video,
                                resource),
                            false,
                            "{}"))),
                null,
                new ToolResultMetadata(
                    id(9302L),
                    id(2L),
                    "call-1",
                    0,
                    ToolResultStatus.SUCCEEDED,
                    false,
                    null,
                    null,
                    null)),
            NOW);

    assertEquals(4_806L, TokenEstimator.estimateTokens(assistant));
    assertEquals(4_803L, TokenEstimator.estimateTokens(toolResult));
  }

  private static final class PathBuilder {
    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1L;
    private long openTurnStart;

    PathBuilder() {
      entries.add(new Entry(id(nextId++), id(100L), null, new RootPayload(SETTINGS), NOW));
    }

    PathBuilder turn(String userText) {
      startTurn(fixedLength(userText));
      assistant(fixedLength("reply"));
      closeTurn();
      return this;
    }

    PathBuilder startTurn(String userText) {
      openTurnStart = nextId++;
      entries.add(
          new Entry(
              id(openTurnStart),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, id(200L)),
              NOW));
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              id(openTurnStart),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.USER, List.of(new TextMessageContent(userText))),
                  null,
                  null),
              NOW));
      return this;
    }

    PathBuilder assistant(String text) {
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              parentId(),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
                  new AssistantMessageMetadata(
                      GenerationStopReason.COMPLETE,
                      new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
                  null),
              NOW));
      return this;
    }

    PathBuilder closeTurn() {
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              parentId(),
              new TurnEndPayload(id(openTurnStart), TurnEndOutcome.COMPLETED, false, null, null),
              NOW));
      return this;
    }

    EntryPath path() {
      return new EntryPath(entries);
    }

    private UUID parentId() {
      return entries.getLast().id();
    }

    private static String fixedLength(String value) {
      return value + "x".repeat(Math.max(0, 100 - value.length()));
    }
  }
}
