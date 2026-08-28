package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CompactionPlanner 纯切分逻辑。测试消息文本固定 100 字符（估计 25 token）， keepRecentTokens = min(floor(contextWindow
 * / 2), 20_000)，因此用窗口控制 cut 位置。
 */
class CompactionPlannerTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final ModelSelection SETTINGS_MODEL =
      new ModelSelection("provider", "model", "v1");
  private static final CompactionConfig CONFIG = new CompactionConfig(20_000, null);
  private static final Instant BASE = Instant.ofEpochSecond(1000L);

  @Test
  void estimatesVideoWithTheDeterministicMediaPlaceholderBudget() {
    Entry video =
        new Entry(
            id(2L),
            id(100L),
            id(1L),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(new VideoMessageContent("video/mp4", "video-source"))),
                null,
                null),
            BASE);
    Entry nestedVideo =
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
                            "read",
                            "read",
                            List.of(new VideoMessageContent("video/mp4", "video-source")),
                            false,
                            "{}"))),
                null,
                new ToolResultMetadata(
                    id(1L), "call-1", 0, ToolResultStatus.SUCCEEDED, false, null)),
            BASE);

    assertEquals(1_200L, CompactionPlanner.estimateTokens(video));
    assertEquals(1_200L, CompactionPlanner.estimateTokens(nestedVideo));
  }

  /** 所有可持久化内容类型都必须进入同一个确定性 token 估算公式。 */
  @Test
  void estimatesStructuredAndMediaContentsDeterministically() {
    AgentMessageContent image = new ImageMessageContent("image/png", "image-source");
    AgentMessageContent audio = new AudioMessageContent("audio/mpeg", "audio-source");
    AgentMessageContent video = new VideoMessageContent("video/mp4", "video-source");
    AgentMessageContent resource = new ResourceMessageContent(id(200L), "resource.bin", "preview");
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
                    new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
                    new ModelCost(
                        "USD",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO)),
                null),
            BASE);
    Entry tool =
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
                    id(2L), "call-1", 0, ToolResultStatus.SUCCEEDED, false, null)),
            BASE);

    assertEquals(4_806L, CompactionPlanner.estimateTokens(assistant));
    assertEquals(4_803L, CompactionPlanner.estimateTokens(tool));
  }

  @Test
  void estimatesOnlyVisibleMessagesAfterTheUsageEntry() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("user");
    path.assistant("call", "call-1");
    path.toolResult(id(4L), "call-1");
    path.closeTurn(true);

    assertEquals(1L, CompactionPlanner.estimateVisibleTokensAfter(path.path(), id(4L)));
    assertThrows(
        IllegalStateException.class,
        () -> CompactionPlanner.estimateVisibleTokensAfter(path.path(), id(999L)));
  }

  @Test
  void neverExceedingBudgetYieldsNoPreparation() {
    // 预算远大于全部消息（窗口巨大）-> 无合法 cut 位置可产生非空摘要范围。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.turn("second");
    path.assistant("second reply");

    assertTrue(plan(path, 1_000_000L).isEmpty());
  }

  @Test
  void budgetCrossingSelectsNextCutPointAndExtractsMessages() {
    // 从尾部累计：最后一个 ASSISTANT(25) 不达标，加上其前 USER(25) 达标 -> cut 落在该 USER 上（FULL，非切分）。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.turn("second");
    path.assistant("second reply");

    CompactionPreparation preparation = plan(path, 80L).orElseThrow();

    assertEquals(CompactionPhase.FULL, preparation.phase());
    assertEquals(id(7L), preparation.cutEntryId()); // second turn 的 USER
    assertNull(preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
    assertTrue(preparation.removedPrefixTokens() > 0);
    assertEquals(SETTINGS_MODEL, preparation.executionModel());
  }

  @Test
  void toolResultIsNeverACutPoint() {
    // 预算在 TOOL 处才被越过：TOOL 不是合法 cut point -> 回退到其前最近的 cut point（ASSISTANT），绝不切在 TOOL。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("hi");
    path.assistant("call", "call-1");
    path.toolResult(id(4L), "call-1"); // assistantEntryId = ASSISTANT(4)
    path.closeTurn();

    CompactionPreparation preparation = plan(path, 30L).orElseThrow();

    assertEquals(id(4L), preparation.cutEntryId()); // ASSISTANT，绝不切在 TOOL(5)
    assertEquals(1, preparation.messagesToSummarize().size());
    assertTrue(contentText(preparation.messagesToSummarize().get(0)).contains("hi"));
  }

  @Test
  void splitTurnDerivesHistoryThenIndependentFrozenTurnPrefix() {
    // 单个 ASSISTANT(25) 达标 -> cut 落在 ASSISTANT 上，前缀 [USER, ASSISTANT) 非空 -> HISTORY。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.turn("split turn");
    path.assistant("split assistant");

    CompactionPreparation history = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.HISTORY, history.phase());
    assertEquals(id(8L), history.cutEntryId()); // split ASSISTANT 是 cut
    assertEquals(id(7L), history.turnPrefixStartEntryId()); // split turn 的 USER
    assertEquals(2, history.messagesToSummarize().size()); // first turn 内容
    assertFalse(
        history.messagesToSummarize().stream()
            .anyMatch(m -> contentText(m).contains("split assistant")));
    assertFalse(
        history.messagesToSummarize().stream()
            .anyMatch(m -> contentText(m).contains("split turn")));

    // TURN_PREFIX 延续冻结复用同一 enclosing TURN_START 事实，绝不重新选 cut。
    path.closeTurn();
    CompactionTurns.CompactionTurn historyTurn =
        path.completedCompaction(history.frozenStart(), "history summary");
    CompactionPreparation prefix = planner().prepareTurnPrefix(path.path(), historyTurn);
    assertEquals(CompactionPhase.TURN_PREFIX, prefix.phase());
    assertEquals(history.cutEntryId(), prefix.cutEntryId());
    assertEquals(history.turnPrefixStartEntryId(), prefix.turnPrefixStartEntryId());
    assertEquals(
        CompactionTurns.entryAt(path.path(), historyTurn.resultIndex()).id(),
        prefix.historyCompactionEntryId());
    assertEquals(CompactionTrigger.THRESHOLD, prefix.trigger());
    assertEquals(1, prefix.messagesToSummarize().size());
    assertTrue(contentText(prefix.messagesToSummarize().get(0)).contains("split turn"));

    CompactionSummaryInput reconstructedHistory =
        CompactionPlanner.reconstructSummaryInput(path.path(), history.frozenStart());
    assertEquals(history.messagesToSummarize(), reconstructedHistory.messages());
    assertNull(reconstructedHistory.previousSummary());
    CompactionSummaryInput reconstructedPrefix =
        CompactionPlanner.reconstructSummaryInput(path.path(), prefix.frozenStart());
    assertEquals(prefix.messagesToSummarize(), reconstructedPrefix.messages());
    assertNull(reconstructedPrefix.previousSummary());
  }

  @Test
  void splitWithNoPriorHistoryDerivesDirectTurnPrefix() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("only turn");
    path.assistant("split assistant");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.TURN_PREFIX, preparation.phase());
    assertEquals(id(4L), preparation.cutEntryId());
    assertEquals(id(3L), preparation.turnPrefixStartEntryId());
    assertEquals(1, preparation.messagesToSummarize().size());
  }

  @Test
  void splitTurnStartsAtFirstUserMessageOfTheSameInputTurn() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first queued user");
    path.user("second queued user");
    path.assistant("split assistant");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.TURN_PREFIX, preparation.phase());
    assertEquals(id(5L), preparation.cutEntryId());
    assertEquals(id(3L), preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
    assertTrue(contentText(preparation.messagesToSummarize().get(0)).contains("first queued"));
    assertTrue(contentText(preparation.messagesToSummarize().get(1)).contains("second queued"));
  }

  @Test
  void continuationAssistantUsesLatestInputAcrossDurableTurns() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("original user");
    path.assistant("tool-free answer");
    path.closeTurn(true);
    path.continuation();
    path.assistant("first continuation answer");
    path.closeTurn(true);
    path.continuation();
    path.assistant("second continuation answer");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.TURN_PREFIX, preparation.phase());
    assertEquals(id(10L), preparation.cutEntryId());
    assertEquals(id(3L), preparation.turnPrefixStartEntryId());
    assertEquals(3, preparation.messagesToSummarize().size());
    assertTrue(contentText(preparation.messagesToSummarize().get(0)).contains("original user"));
    assertTrue(
        contentText(preparation.messagesToSummarize().get(2)).contains("first continuation"));
  }

  @Test
  void continuationSplitUsesNewestInputSegment() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("older user");
    path.assistant("older answer");
    path.closeTurn();
    path.turn("latest user");
    path.assistant("latest answer");
    path.closeTurn(true);
    path.continuation();
    path.assistant("continuation answer");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.HISTORY, preparation.phase());
    assertEquals(id(11L), preparation.cutEntryId());
    assertEquals(id(7L), preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
    assertTrue(contentText(preparation.messagesToSummarize().get(0)).contains("older user"));
    assertFalse(
        preparation.messagesToSummarize().stream()
            .anyMatch(message -> contentText(message).contains("latest user")));
  }

  @Test
  void continuationSplitDoesNotCrossLatestCompleteBoundary() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("original user");
    path.assistant("original answer");
    path.closeTurn();
    path.completedCompaction(fullStart(id(4L)), "previous summary");
    path.continuation();
    path.assistant("continuation answer");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.FULL, preparation.phase());
    assertEquals(id(10L), preparation.cutEntryId());
    assertNull(preparation.turnPrefixStartEntryId());
    assertEquals("previous summary", preparation.previousSummary());
  }

  @Test
  void emptyMessagesReturnsNoPreparationEvenWithPreviousSummary() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.completedCompaction(fullStart(id(1L)), "previous summary");
    path.custom("com.example", "state");

    // 之后没有任何新对话消息：即使存在 previous summary 也不单独重压缩（避免空转）。
    assertTrue(plan(path, 100_000L).isEmpty());
  }

  @Test
  void assistantAbortedIsACutPointAndSummarized() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("hi");
    path.aborted("aborted assistant text");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.TURN_PREFIX, preparation.phase()); // 切分且无先前历史
    assertEquals(id(4L), preparation.cutEntryId());
    assertEquals(id(3L), preparation.turnPrefixStartEntryId());
    assertEquals(1, preparation.messagesToSummarize().size());
  }

  @Test
  void metadataRewindIncludesControlEntriesButStopsAtConversation() {
    // cut=USER(8)：重绕经过 TURN_START(7) / CUSTOM(6) / TURN_END(5)，止于 ASSISTANT(4)。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.custom("com.example", "state");
    path.turn("second");
    path.assistant("second reply");

    CompactionPreparation preparation = plan(path, 60L).orElseThrow();

    assertEquals(CompactionPhase.FULL, preparation.phase());
    assertEquals(id(8L), preparation.cutEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
  }

  @Test
  void missingPreviousCutFailsClosed() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("hi");
    path.assistant("reply");
    path.closeTurn();
    // complete 压缩引用了不存在的 cutEntryId -> 分支损坏。
    path.completedCompaction(fullStart(id(999L)), "summary");
    path.turn("later");

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> plan(path, 100_000L));
    assertTrue(error.getMessage().contains("cutEntryId"), error.getMessage());
  }

  @Test
  void frozenContinuationFailsClosedOnMissingOrCorruptReferences() {
    PathBuilder missingCut = closedTurnPath();
    CompactionTurns.CompactionTurn missingCutTurn =
        missingCut.completedCompaction(historyStart(id(999L), id(3L)), "history");
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(missingCut.path(), missingCutTurn));

    PathBuilder emptyPrefix = closedTurnPath();
    CompactionTurns.CompactionTurn emptyPrefixTurn =
        emptyPrefix.completedCompaction(
            historyStart(id(4L), id(4L)), "history"); // turnPrefixStart == cut
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(emptyPrefix.path(), emptyPrefixTurn));

    PathBuilder complete = closedTurnPath();
    CompactionTurns.CompactionTurn completeTurn =
        complete.completedCompaction(fullStart(id(4L)), "complete");
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(complete.path(), completeTurn));
  }

  @Test
  void continuationAndFallbackRequireTheirExactTerminalOutcomes() {
    PathBuilder historyPath = closedTurnPath();
    CompactionTurns.CompactionTurn completedHistory =
        historyPath.completedCompaction(historyStart(id(4L), id(3L)), "history");
    CompactionTurns.CompactionTurn stoppedHistory =
        new CompactionTurns.CompactionTurn(
            completedHistory.start(),
            completedHistory.result(),
            new TurnEndPayload(
                id(99L), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(100L)),
            completedHistory.startIndex(),
            completedHistory.resultIndex(),
            completedHistory.endIndex());
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(historyPath.path(), stoppedHistory));

    CompactionPlanner fallbackPlanner =
        new CompactionPlanner(
            new CompactionConfig(20_000, new ModelSelection("fallback", "summary", "v2")));
    assertThrows(
        IllegalStateException.class,
        () -> fallbackPlanner.prepareFallback(historyPath.path(), completedHistory));
  }

  @Test
  void previousSummaryPropagatesToHistoryPhase() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.completedCompaction(
        fullStart(id(4L)), "carried summary\n\n<read-files>\nold.txt\n</read-files>");
    path.turn("new messages");
    path.assistant("new reply");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    // cut 落在新 ASSISTANT 上且前缀非空 -> HISTORY 阶段；previousSummary 沿用于 update 提示。
    assertEquals(CompactionPhase.HISTORY, preparation.phase());
    assertEquals("carried summary", preparation.previousSummary());
    assertTrue(preparation.removedPrefixTokens() > 0);
    CompactionSummaryInput reconstructed =
        CompactionPlanner.reconstructSummaryInput(path.path(), preparation.frozenStart());
    assertEquals(preparation.messagesToSummarize(), reconstructed.messages());
    assertEquals("carried summary", reconstructed.previousSummary());
  }

  @Test
  void reconstructSummaryInputDoesNotReselectCutAfterLaterMessages() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.turn("second");
    path.assistant("second reply");
    CompactionPreparation preparation = plan(path, 80L).orElseThrow();
    CompactionStart start = preparation.frozenStart();

    path.closeTurn();
    path.turn("later user");
    path.assistant("later reply");

    CompactionSummaryInput reconstructed =
        CompactionPlanner.reconstructSummaryInput(path.path(), start);
    assertEquals(preparation.messagesToSummarize(), reconstructed.messages());
    assertEquals(preparation.cutEntryId(), start.cutEntryId());
  }

  @Test
  void stoppedCompactionTurnIsFullyInvisibleToPlanning() {
    // 被停止压缩 turn（TURN_START(COMPACTION) -> ABORTED -> TURN_END(STOPPED)）后接正常消息：其内部
    // ABORTED 不可见——不参与 cut / token / 摘要，也绝不是 firstKept 对话边界（重绕透明穿过整个被停止 turn，
    // 止于前一个可见对话消息）。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first user");
    path.assistant("first reply");
    path.closeTurn();
    path.stoppedCompaction("internal aborted");
    path.turn("second user");
    path.assistant("second reply");

    // keepRecent=40（window 80）：累计到 USER2(10) 越过 -> cut=USER2。
    CompactionPreparation preparation = plan(path, 80L).orElseThrow();

    assertEquals(CompactionPhase.FULL, preparation.phase());
    assertEquals(id(10L), preparation.cutEntryId()); // 绝不落在被停止压缩 turn 内部
    assertNull(preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size()); // 只有 USER1+ASST1
    assertFalse(
        preparation.messagesToSummarize().stream()
            .anyMatch(m -> contentText(m).contains("aborted")));
    assertEquals(50L, preparation.removedPrefixTokens());
  }

  @Test
  void stoppedCompactionAbortedIsNeverACutPointOrSummarized() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first user");
    path.assistant("first reply");
    path.closeTurn();
    path.stoppedCompaction("internal aborted");
    path.turn("second user");
    path.assistant("second reply");

    // keepRecent=25（window 50）：累计恰在 ASST2(11) 越过 -> cut=ASST2（切分）；ABORTED(7) 不是合法 cut。
    CompactionPreparation preparation = plan(path, 50L).orElseThrow();

    assertEquals(CompactionPhase.HISTORY, preparation.phase());
    assertEquals(id(11L), preparation.cutEntryId());
    assertEquals(id(10L), preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
    assertFalse(
        preparation.messagesToSummarize().stream()
            .anyMatch(m -> contentText(m).contains("aborted")));
  }

  private static PathBuilder closedTurnPath() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("hi");
    path.assistant("reply");
    path.closeTurn();
    return path;
  }

  private static CompactionStart fullStart(UUID cutEntryId) {
    return new CompactionStart(
        CompactionPhase.FULL, CompactionTrigger.THRESHOLD, SETTINGS_MODEL, cutEntryId, null, null);
  }

  private static CompactionStart historyStart(UUID cutEntryId, UUID turnPrefixStartEntryId) {
    return new CompactionStart(
        CompactionPhase.HISTORY,
        CompactionTrigger.THRESHOLD,
        SETTINGS_MODEL,
        cutEntryId,
        turnPrefixStartEntryId,
        null);
  }

  private static CompactionPlanner planner() {
    return new CompactionPlanner(CONFIG);
  }

  private static Optional<CompactionPreparation> plan(PathBuilder path, long contextWindow) {
    return planner().prepare(path.path(), CompactionTrigger.THRESHOLD, contextWindow);
  }

  private static String contentText(AgentMessage message) {
    StringBuilder sb = new StringBuilder();
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text) {
        sb.append(text.text());
      }
    }
    return sb.toString();
  }

  /** 自包含合法路径构造器：id 顺序、parent 链、TURN 语法。 */
  private static final class PathBuilder {
    private final List<Entry> entries = new ArrayList<>();
    private final BranchSettings SETTINGS =
        new BranchSettings(EnvironmentBindings.binding("env-1"), "agent", SETTINGS_MODEL);
    private long nextId = 1L;

    PathBuilder root() {
      entries.add(new Entry(id(nextId++), id(100L), null, new RootPayload(SETTINGS), BASE));
      return this;
    }

    long turnStart() {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
              BASE));
      return cur;
    }

    /** 开启新输入 turn：[TURN_START(INPUT), USER 消息]。 */
    PathBuilder turn(String userText) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
              BASE));
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              id(startId),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.USER, List.of(new TextMessageContent(text(userText)))),
                  null,
                  null),
              BASE));
      return this;
    }

    long user(String text) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new MessagePayload(
                  new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
                  null,
                  null),
              BASE));
      return cur;
    }

    /** ASSISTANT 消息：文本 + 可选的 (callId, toolName) 工具调用（无调用时 COMPLETED）。 */
    PathBuilder assistant(String assistantText, String... callIds) {
      List<AgentMessageContent> contents = new ArrayList<>();
      for (String callId : callIds) {
        contents.add(new ToolCallMessageContent(callId, "read", "read", "{\"path\":\"a.txt\"}"));
      }
      contents.add(new TextMessageContent(text(assistantText)));
      GenerationStopReason stopReason =
          callIds.length == 0 ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              parentId(),
              new MessagePayload(
                  new AgentMessage(AgentMessageRole.ASSISTANT, contents),
                  new AssistantMessageMetadata(
                      stopReason,
                      new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
                      new ModelCost(
                          "USD",
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO,
                          BigDecimal.ZERO)),
                  null),
              BASE));
      return this;
    }

    /** 为最后一个（assistant）Entry 的每个 call 追加严格 ordinal 前缀的 TOOL result。 */
    PathBuilder toolResults() {
      UUID assistantEntryId = entries.get(entries.size() - 1).id();
      int ordinal = 0;
      for (AgentMessageContent content :
          ((MessagePayload) entries.get(entries.size() - 1).payload()).message().contents()) {
        if (!(content instanceof ToolCallMessageContent call)) {
          continue;
        }
        entries.add(
            new Entry(
                id(nextId++),
                id(100L),
                parentId(),
                new MessagePayload(
                    new AgentMessage(
                        AgentMessageRole.TOOL,
                        List.of(
                            new ToolResultMessageContent(
                                call.toolCallId(),
                                call.toolName(),
                                call.rendererKey(),
                                List.of(new TextMessageContent("ok")),
                                false,
                                "{}"))),
                    null,
                    new ToolResultMetadata(
                        assistantEntryId,
                        call.toolCallId(),
                        ordinal++,
                        ToolResultStatus.SUCCEEDED,
                        false,
                        null)),
                BASE));
      }
      return this;
    }

    PathBuilder toolResult(UUID assistantEntryId, String toolCallId) {
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              parentId(),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.TOOL,
                      List.of(
                          new ToolResultMessageContent(
                              toolCallId,
                              "read",
                              "read",
                              List.of(new TextMessageContent("ok")),
                              false,
                              "{}"))),
                  null,
                  new ToolResultMetadata(
                      assistantEntryId, toolCallId, 0, ToolResultStatus.SUCCEEDED, false, null)),
              BASE));
      return this;
    }

    long turnEnd(UUID turnStartEntryId) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
              BASE));
      return cur;
    }

    PathBuilder closeTurn() {
      return closeTurn(false);
    }

    PathBuilder closeTurn(boolean continueModel) {
      long cur = nextId++;
      UUID lastTurnStart = findLastOpenTurnStart(); // find before adding
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new TurnEndPayload(
                  lastTurnStart, TurnEndOutcome.COMPLETED, continueModel, null, null),
              BASE));
      return this;
    }

    long continuation() {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.CONTINUATION, SETTINGS, OWNER_THREAD_ID),
              BASE));
      return cur;
    }

    PathBuilder aborted(String abortedText) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new AssistantAbortedPayload(
                  new AgentMessage(
                      AgentMessageRole.ASSISTANT,
                      List.of(new TextMessageContent(text(abortedText))))),
              BASE));
      return this;
    }

    /** 完成压缩 turn，并返回从当前 immutable path 派生的 enclosing facts。 */
    CompactionTurns.CompactionTurn completedCompaction(CompactionStart start, String summary) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              id(100L),
              parentId(),
              new TurnStartPayload(
                  TurnStartReason.COMPACTION, SETTINGS, OWNER_THREAD_ID, 100_000, 16_384, start),
              BASE));
      entries.add(
          new Entry(id(nextId++), id(100L), parentId(), new CompactionPayload(summary), BASE));
      long endId = nextId++;
      boolean continueModel =
          start.phase() == CompactionPhase.HISTORY || start.trigger() == CompactionTrigger.OVERFLOW;
      entries.add(
          new Entry(
              id(endId),
              id(100L),
              parentId(),
              new TurnEndPayload(id(startId), TurnEndOutcome.COMPLETED, continueModel, null, null),
              BASE));
      return CompactionTurns.scan(path()).getLast();
    }

    PathBuilder stoppedCompaction(String abortedText) {
      long startId = nextId++;
      CompactionStart start = fullStart(parentId());
      entries.add(
          new Entry(
              id(startId),
              id(100L),
              parentId(),
              new TurnStartPayload(
                  TurnStartReason.COMPACTION, SETTINGS, OWNER_THREAD_ID, null, null, start),
              BASE));
      aborted(abortedText);
      long endId = nextId++;
      entries.add(
          new Entry(
              id(endId),
              id(100L),
              parentId(),
              new TurnEndPayload(
                  id(startId), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(1L)),
              BASE));
      return this;
    }

    PathBuilder custom(String contributorId, String customType) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new CustomEntryPayload(contributorId, customType, 1, "{\"s\":1}"),
              BASE));
      return this;
    }

    EntryPath path() {
      return new EntryPath(List.copyOf(entries));
    }

    /** 固定 100 字符的消息文本 -> 估计 25 token，便于精确控制 keepRecentTokens 累计。 */
    private static String text(String seed) {
      StringBuilder builder = new StringBuilder(100);
      builder.append(seed);
      while (builder.length() < 100) {
        builder.append('x');
      }
      return builder.toString();
    }

    private UUID parentId() {
      return entries.get(entries.size() - 1).id();
    }

    private UUID findLastOpenTurnStart() {
      for (int i = entries.size() - 1; i >= 0; i--) {
        if (entries.get(i).payload() instanceof TurnStartPayload) {
          return entries.get(i).id();
        }
      }
      throw new IllegalStateException("no open TURN_START");
    }
  }
}
