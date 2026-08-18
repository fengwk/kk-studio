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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
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

  private static final CompactionConfig CONFIG = new CompactionConfig(true, 16_384, 20_000);
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
    assertEquals(id(5L), preparation.firstKeptEntryId()); // 重绕包含 TURN_END(5)，止于 ASSISTANT(4)
    assertNull(preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
    assertTrue(preparation.tokensBefore() > 0);
    assertEquals(80L, preparation.contextWindow());
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

    // TURN_PREFIX 延续冻结复用同一 payload 事实，绝不重新选 cut。
    CompactionPayload partial =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            history.tokensBefore(),
            false,
            "history summary",
            history.firstKeptEntryId(),
            history.cutEntryId(),
            history.turnPrefixStartEntryId());
    CompactionPreparation prefix = planner().prepareTurnPrefix(path.path(), partial, 100_000L);
    assertEquals(CompactionPhase.TURN_PREFIX, prefix.phase());
    assertEquals(history.firstKeptEntryId(), prefix.firstKeptEntryId());
    assertEquals(history.cutEntryId(), prefix.cutEntryId());
    assertEquals(history.turnPrefixStartEntryId(), prefix.turnPrefixStartEntryId());
    assertEquals(history.tokensBefore(), prefix.tokensBefore());
    assertEquals(CompactionTrigger.THRESHOLD, prefix.trigger());
    assertEquals(1, prefix.messagesToSummarize().size());
    assertTrue(contentText(prefix.messagesToSummarize().get(0)).contains("split turn"));
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
  void continuationAssistantNeverBorrowsUserFromPreviousTurnAsSplitPrefix() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("original user");
    path.assistant("tool-free answer");
    path.closeTurn(true);
    path.continuation();
    path.assistant("continuation answer");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    assertEquals(CompactionPhase.FULL, preparation.phase());
    assertEquals(id(7L), preparation.cutEntryId());
    assertNull(preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
  }

  @Test
  void emptyMessagesReturnsNoPreparationEvenWithPreviousSummary() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.compaction(
        CompactionPhase.FULL,
        CompactionTrigger.THRESHOLD,
        500L,
        true,
        "previous summary",
        id(1L),
        id(1L),
        null);
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
    assertEquals(id(5L), preparation.firstKeptEntryId());
    assertEquals(2, preparation.messagesToSummarize().size());
  }

  @Test
  void missingPreviousFirstKeptFailsClosed() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("hi");
    path.assistant("reply");
    path.closeTurn();
    // complete 压缩引用了不存在的 firstKeptEntryId -> 分支损坏。
    path.compaction(
        CompactionPhase.FULL,
        CompactionTrigger.THRESHOLD,
        500L,
        true,
        "summary",
        id(999L),
        id(4L),
        null);
    path.turn("later");

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> plan(path, 100_000L));
    assertTrue(error.getMessage().contains("firstKeptEntryId"), error.getMessage());
  }

  @Test
  void frozenContinuationFailsClosedOnMissingOrCorruptReferences() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("hi");
    path.assistant("reply");
    path.closeTurn();

    CompactionPayload partial =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            100L,
            false,
            "history",
            id(2L),
            id(999L), // cut 不在当前路径
            id(3L));
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(path.path(), partial, 100_000L));

    CompactionPayload emptyPrefix =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            100L,
            false,
            "history",
            id(2L),
            id(4L),
            id(4L)); // turnPrefixStart == cut -> 空前缀
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(path.path(), emptyPrefix, 100_000L));

    CompactionPayload inverted =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            100L,
            false,
            "history",
            id(5L),
            id(4L),
            id(3L)); // firstKept 在 cut 之后
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(path.path(), inverted, 100_000L));

    CompactionPayload complete =
        new CompactionPayload(
            CompactionPhase.FULL,
            CompactionTrigger.THRESHOLD,
            100L,
            true,
            "complete",
            id(2L),
            id(4L),
            null);
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(path.path(), complete, 100_000L));
  }

  @Test
  void previousSummaryPropagatesToHistoryPhase() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first");
    path.assistant("first reply");
    path.closeTurn();
    path.compaction(
        CompactionPhase.FULL,
        CompactionTrigger.THRESHOLD,
        100L,
        true,
        "carried summary\n\n<read-files>\nold.txt\n</read-files>",
        id(1L),
        id(4L),
        null);
    path.turn("new messages");
    path.assistant("new reply");

    CompactionPreparation preparation = plan(path, 40L).orElseThrow();

    // cut 落在新 ASSISTANT 上且前缀非空 -> HISTORY 阶段；previousSummary 沿用于 update 提示。
    assertEquals(CompactionPhase.HISTORY, preparation.phase());
    assertEquals("carried summary", preparation.previousSummary());
    // tokensBefore 是压缩感知估计：完整 wrapper summary + 上次 cut 后的 3 条可见消息。
    long wrapperTokens =
        (CompactionPrompts.compactedContext(
                        "carried summary\n\n<read-files>\nold.txt\n</read-files>")
                    .length()
                + 3L)
            / 4L;
    assertEquals(wrapperTokens + 75L, preparation.tokensBefore());
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
    assertEquals(id(5L), preparation.firstKeptEntryId()); // 重绕穿过 6..8，止于 ASST1(4)
    assertNull(preparation.turnPrefixStartEntryId());
    assertEquals(2, preparation.messagesToSummarize().size()); // 只有 USER1+ASST1
    assertFalse(
        preparation.messagesToSummarize().stream()
            .anyMatch(m -> contentText(m).contains("aborted")));
    // 4 条可见消息 x 25 token；内部 ABORTED 不计入（否则 125）。
    assertEquals(100L, preparation.tokensBefore());
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

  @Test
  void prepareTurnPrefixExcludesStoppedCompactionMessagesAndFailsClosedWhenEmpty() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first user");
    path.assistant("first reply");
    path.closeTurn();
    path.stoppedCompaction("internal aborted");
    path.turn("second user");
    path.assistant("second reply");

    // 前缀范围 [6,11) 内被停止压缩 turn 的 ABORTED 不可见，USER2(10) 保留。
    CompactionPayload partial =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            500L,
            false,
            "history",
            id(5L),
            id(11L),
            id(6L));
    CompactionPreparation prefix = planner().prepareTurnPrefix(path.path(), partial, 100_000L);
    assertEquals(1, prefix.messagesToSummarize().size());
    assertTrue(contentText(prefix.messagesToSummarize().get(0)).contains("second user"));

    // 前缀范围 [6,8) 全部被掩码 -> 分支损坏 fail closed。
    CompactionPayload empty =
        new CompactionPayload(
            CompactionPhase.HISTORY,
            CompactionTrigger.THRESHOLD,
            500L,
            false,
            "history",
            id(5L),
            id(8L),
            id(6L));
    assertThrows(
        IllegalStateException.class,
        () -> planner().prepareTurnPrefix(path.path(), empty, 100_000L));
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
        new BranchSettings(
            EnvironmentBindings.binding("env-1"),
            "agent",
            new ModelSelection("provider", "model", "v1"),
            List.of());
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
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS),
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
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS),
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
      ProviderStopReason stopReason =
          callIds.length == 0 ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
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
              new TurnStartPayload(TurnStartReason.CONTINUATION, SETTINGS),
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

    /** 完成压缩 turn：[TURN_START(COMPACTION), COMPACTION payload, TURN_END]。 */
    long compactionComplete(UUID firstKeptEntryId, UUID cutEntryId, UUID turnPrefixStartEntryId) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
              BASE));
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              parentId(),
              new CompactionPayload(
                  CompactionPhase.FULL,
                  CompactionTrigger.THRESHOLD,
                  500L,
                  true,
                  "summary",
                  firstKeptEntryId,
                  cutEntryId,
                  turnPrefixStartEntryId),
              BASE));
      long endId = nextId++;
      entries.add(
          new Entry(
              id(endId),
              id(100L),
              parentId(),
              new TurnEndPayload(id(startId), TurnEndOutcome.COMPLETED, false, null, null),
              BASE));
      return endId;
    }

    /** HISTORY incomplete compaction turn。 */
    PathBuilder compaction(
        CompactionPhase phase,
        CompactionTrigger trigger,
        long tokens,
        boolean complete,
        String summary,
        UUID firstKeptEntryId,
        UUID cutEntryId,
        UUID turnPrefixStartEntryId) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
              BASE));
      entries.add(
          new Entry(
              id(nextId++),
              id(100L),
              parentId(),
              new CompactionPayload(
                  phase,
                  trigger,
                  tokens,
                  complete,
                  summary,
                  firstKeptEntryId,
                  cutEntryId,
                  turnPrefixStartEntryId),
              BASE));
      long endId = nextId++;
      entries.add(
          new Entry(
              id(endId),
              id(100L),
              parentId(),
              new TurnEndPayload(id(startId), TurnEndOutcome.COMPLETED, false, null, null),
              BASE));
      return this;
    }

    PathBuilder stoppedCompaction(String abortedText) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              id(100L),
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
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

    PathBuilder custom(String plugin, String customType) {
      long cur = nextId++;
      entries.add(
          new Entry(
              id(cur),
              id(100L),
              parentId(),
              new CustomEntryPayload(plugin, customType, 1, "{\"s\":1}"),
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
