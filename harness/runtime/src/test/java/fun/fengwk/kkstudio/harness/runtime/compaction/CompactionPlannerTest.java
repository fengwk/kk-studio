package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CompactionPlanner 纯切分逻辑。测试消息文本固定 100 字符（估计 25 token）， keepRecentTokens = min(floor(contextWindow
 * / 2), 20_000)，因此用窗口控制 cut 位置。
 */
class CompactionPlannerTest {

  private static final CompactionConfig CONFIG = CompactionConfig.DEFAULTS;
  private static final long SESSION = 100L;
  private static final Instant BASE = Instant.ofEpochSecond(1000L);

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
    assertEquals(7L, preparation.cutEntryId()); // second turn 的 USER
    assertEquals(5L, preparation.firstKeptEntryId()); // 重绕包含 TURN_END(5)，止于 ASSISTANT(4)
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
    path.toolResult(4L, "call-1"); // assistantEntryId = ASSISTANT(4)
    path.closeTurn();

    CompactionPreparation preparation = plan(path, 30L).orElseThrow();

    assertEquals(4L, preparation.cutEntryId()); // ASSISTANT，绝不切在 TOOL(5)
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
    assertEquals(8L, history.cutEntryId()); // split ASSISTANT 是 cut
    assertEquals(7L, history.turnPrefixStartEntryId()); // split turn 的 USER
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
    assertEquals(4L, preparation.cutEntryId());
    assertEquals(3L, preparation.turnPrefixStartEntryId());
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
    assertEquals(5L, preparation.cutEntryId());
    assertEquals(3L, preparation.turnPrefixStartEntryId());
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
    assertEquals(7L, preparation.cutEntryId());
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
        1L,
        1L,
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
    assertEquals(4L, preparation.cutEntryId());
    assertEquals(3L, preparation.turnPrefixStartEntryId());
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
    assertEquals(8L, preparation.cutEntryId());
    assertEquals(5L, preparation.firstKeptEntryId());
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
        CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, true, "summary", 999L, 4L, null);
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
            2L,
            999L, // cut 不在当前路径
            3L);
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
            2L,
            4L,
            4L); // turnPrefixStart == cut -> 空前缀
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
            5L,
            4L,
            3L); // firstKept 在 cut 之后
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
            2L,
            4L,
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
        1L,
        4L,
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
    assertEquals(10L, preparation.cutEntryId()); // 绝不落在被停止压缩 turn 内部
    assertEquals(5L, preparation.firstKeptEntryId()); // 重绕穿过 6..8，止于 ASST1(4)
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
    assertEquals(11L, preparation.cutEntryId());
    assertEquals(10L, preparation.turnPrefixStartEntryId());
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
            5L,
            11L,
            6L);
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
            5L,
            8L,
            6L);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> planner().prepareTurnPrefix(path.path(), empty, 100_000L));
    assertTrue(error.getMessage().contains("no context messages"), error.getMessage());
  }

  @Test
  void firstKeptRewindStopsAtCompleteCompactionPayload() {
    // 对齐 Pi prevEntry.type === "compaction"：重绕绝不跨过 complete 压缩 payload（止于其后的 TURN_END）。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first user");
    path.assistant("first reply");
    path.closeTurn();
    path.compaction(
        CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 100L, true, "summary", 2L, 4L, null);
    path.turn("second user");
    path.assistant("second reply");

    CompactionPreparation preparation = plan(path, 100L).orElseThrow();

    assertEquals(CompactionPhase.FULL, preparation.phase());
    assertEquals(10L, preparation.cutEntryId()); // USER2
    assertEquals(8L, preparation.firstKeptEntryId()); // TURN_END 之后即 payload 边界
    assertEquals(2, preparation.messagesToSummarize().size());
  }

  @Test
  void firstKeptRewindStopsAtIncompleteHistoryPayload() {
    // incomplete HISTORY payload 同样是重绕边界：绝不跨到更早的完整/不完整压缩 Entry。
    PathBuilder path = new PathBuilder();
    path.root();
    path.turn("first user");
    path.assistant("first reply");
    path.closeTurn();
    path.compaction(
        CompactionPhase.HISTORY, CompactionTrigger.THRESHOLD, 100L, false, "history", 2L, 4L, 3L);
    path.turn("second user");
    path.assistant("second reply");

    CompactionPreparation preparation = plan(path, 100L).orElseThrow();

    assertEquals(10L, preparation.cutEntryId());
    assertEquals(8L, preparation.firstKeptEntryId()); // 止于 incomplete payload 后的 TURN_END
  }

  private static String contentText(AgentMessage message) {
    return ((TextMessageContent) message.contents().get(0)).text();
  }

  private CompactionPlanner planner() {
    return new CompactionPlanner(CONFIG);
  }

  private Optional<CompactionPreparation> plan(PathBuilder path, long contextWindow) {
    return planner().prepare(path.path(), CompactionTrigger.THRESHOLD, contextWindow);
  }

  /** 自包含的合法 EntryPath 构造器：顺序 id、parent 链、开/关 turn 与 COMPACTION 语法自动维护。 */
  private static final class PathBuilder {
    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1L;
    private long openTurnStartId = -1L;

    PathBuilder root() {
      entries.add(new Entry(nextId++, SESSION, null, new RootPayload(settings()), BASE));
      return this;
    }

    PathBuilder turn(String userText) {
      long startId = nextId++;
      entries.add(
          new Entry(
              startId,
              SESSION,
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, settings()),
              BASE));
      openTurnStartId = startId;
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              startId,
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.USER, List.of(new TextMessageContent(text(userText)))),
                  null,
                  null),
              BASE));
      return this;
    }

    PathBuilder user(String userText) {
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.USER, List.of(new TextMessageContent(text(userText)))),
                  null,
                  null),
              BASE));
      return this;
    }

    PathBuilder continuation() {
      long startId = nextId++;
      entries.add(
          new Entry(
              startId,
              SESSION,
              parentId(),
              new TurnStartPayload(TurnStartReason.CONTINUATION, settings()),
              BASE));
      openTurnStartId = startId;
      return this;
    }

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
              nextId++,
              SESSION,
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

    PathBuilder toolResult(long assistantEntryId, String callId) {
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.TOOL,
                      List.of(
                          new ToolResultMessageContent(
                              callId,
                              "read",
                              "read",
                              List.of(new TextMessageContent(text("file content"))),
                              false,
                              "{}"))),
                  null,
                  new ToolResultMetadata(
                      assistantEntryId, callId, 0, ToolResultStatus.SUCCEEDED, false, null)),
              BASE));
      return this;
    }

    PathBuilder aborted(String abortedText) {
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new AssistantAbortedPayload(
                  new AgentMessage(
                      AgentMessageRole.ASSISTANT,
                      List.of(new TextMessageContent(text(abortedText))))),
              BASE));
      return this;
    }

    /** 被停止的 COMPACTION turn：以停止 barrier 关闭（无 payload 结果），语法与 ThreadProcessor 停止路径一致。 */
    PathBuilder stoppedCompaction(String abortedText) {
      long startId = nextId++;
      entries.add(
          new Entry(
              startId,
              SESSION,
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, settings()),
              BASE));
      openTurnStartId = startId;
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new AssistantAbortedPayload(
                  new AgentMessage(
                      AgentMessageRole.ASSISTANT,
                      List.of(new TextMessageContent(text(abortedText))))),
              BASE));
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new TurnEndPayload(
                  openTurnStartId,
                  TurnEndOutcome.STOPPED,
                  false,
                  TurnEndReason.USER_STOP,
                  "stop-1"),
              BASE));
      openTurnStartId = -1L;
      return this;
    }

    PathBuilder custom(String pluginId, String customType) {
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new CustomEntryPayload(pluginId, customType, 1, "{\"s\":1}"),
              BASE));
      return this;
    }

    PathBuilder compaction(
        CompactionPhase phase,
        CompactionTrigger trigger,
        long tokensBefore,
        boolean complete,
        String summary,
        long firstKeptEntryId,
        long cutEntryId,
        Long turnPrefixStartEntryId) {
      long startId = nextId++;
      entries.add(
          new Entry(
              startId,
              SESSION,
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, settings()),
              BASE));
      openTurnStartId = startId;
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              startId,
              new CompactionPayload(
                  phase,
                  trigger,
                  tokensBefore,
                  complete,
                  summary,
                  firstKeptEntryId,
                  cutEntryId,
                  turnPrefixStartEntryId),
              BASE));
      closeTurn();
      return this;
    }

    PathBuilder closeTurn() {
      return closeTurn(false);
    }

    PathBuilder closeTurn(boolean continueModel) {
      entries.add(
          new Entry(
              nextId++,
              SESSION,
              parentId(),
              new TurnEndPayload(
                  openTurnStartId, TurnEndOutcome.COMPLETED, continueModel, null, null),
              BASE));
      openTurnStartId = -1L;
      return this;
    }

    EntryPath path() {
      return new EntryPath(List.copyOf(entries));
    }

    private long parentId() {
      return entries.get(entries.size() - 1).id();
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
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        new EnvironmentName("env-1"),
        "agent",
        new ModelSelection("provider", "model", "v1"),
        List.of());
  }
}
