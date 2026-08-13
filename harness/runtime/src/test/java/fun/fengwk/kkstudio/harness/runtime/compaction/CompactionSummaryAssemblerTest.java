package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
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
import java.util.UUID;

/**
 * CompactionSummaryAssembler 组装契约：HISTORY partial 不追加文件 section；FULL 使用响应文本；机械 TURN_PREFIX 只合并
 * 紧邻前一个已关闭 turn 的 incomplete HISTORY payload；direct TURN_PREFIX 即使存在更早陈旧 partial 也固定 "No prior
 * history."。
 */
class CompactionSummaryAssemblerTest {

  private static final Instant BASE = Instant.ofEpochSecond(1000L);
  private static final BranchSettings SETTINGS =
      new BranchSettings(
          new EnvironmentName("env-1"),
          "agent",
          new ModelSelection("provider", "model", "v1"),
          List.of());

  @Test
  void historyPhaseWritesIncompletePartialWithoutFileSections() {
    EntryPath path = turnPrefixPath("history summary");
    CompactionRequest request = historyRequest();

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(request, "partial text", path);

    assertFalse(payload.complete());
    assertEquals(CompactionPhase.HISTORY, payload.phase());
    assertEquals("partial text", payload.summaryText());
    assertFalse(payload.summaryText().contains("<read-files>"));
    assertEquals(id(2L), payload.firstKeptEntryId());
    assertEquals(id(4L), payload.cutEntryId());
    assertEquals(id(3L), payload.turnPrefixStartEntryId());
    assertEquals(500L, payload.tokensBefore());
  }

  @Test
  void fullPhaseWritesResponseTextWithFileSections() {
    EntryPath path = pathWithReadWriteAssistant();
    CompactionRequest request =
        new CompactionRequest(
            CompactionPhase.FULL, CompactionTrigger.THRESHOLD, 500L, id(2L), id(7L), null);

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(
            request,
            "full summary\n\n<read-files>\nstale.txt\n</read-files>\n\n"
                + "<modified-files>\na.txt\n</modified-files>",
            path);

    assertTrue(payload.complete());
    assertEquals(CompactionPhase.FULL, payload.phase());
    assertEquals(
        "full summary\n\n<read-files>\na.txt\n</read-files>\n\n<modified-files>\nb.txt\n</modified-files>",
        payload.summaryText());
    assertFalse(payload.summaryText().contains("stale.txt"));
  }

  @Test
  void mechanicalTurnPrefixMergesImmediatelyPrecedingIncompleteHistory() {
    // 机械延续形状：... HISTORY partial(7) -> TURN_END(8) -> TURN_START(TURN_PREFIX)(9)。
    EntryPath path = turnPrefixPath("history summary");
    CompactionRequest request = prefixRequest();

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(request, "prefix summary", path);

    assertTrue(payload.complete());
    assertEquals(
        "history summary" + CompactionSummaryAssembler.TURN_PREFIX_SEPARATOR + "prefix summary",
        payload.summaryText());
  }

  @Test
  void directTurnPrefixUsesNoPriorHistoryEvenWithOlderStalePartial() {
    // 陈旧 partial（HISTORY incomplete，随后正常 turn 继续），direct TURN_PREFIX 紧随正常 turn：
    // 绝不扫描任意更早的陈旧 partial。
    PathBuilder path = new PathBuilder();
    path.root();
    path.compactionPartial(
        "stale partial summary", id(2L), id(4L), id(3L)); // 7: HISTORY incomplete
    path.normalTurn("later user", "later reply");
    path.compactionTurnStart(); // 当前 TURN_PREFIX turn

    CompactionRequest request = prefixRequest();
    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(request, "prefix summary", path.path());

    assertEquals(
        CompactionSummaryAssembler.NO_PRIOR_HISTORY
            + CompactionSummaryAssembler.TURN_PREFIX_SEPARATOR
            + "prefix summary",
        payload.summaryText());
  }

  @Test
  void turnPrefixAfterNormalTurnWithoutAnyPartialUsesNoPriorHistory() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.normalTurn("user", "reply");
    path.compactionTurnStart();

    assertEquals(
        CompactionSummaryAssembler.NO_PRIOR_HISTORY,
        CompactionSummaryAssembler.latestIncompleteSummary(path.path(), prefixRequest()));
  }

  @Test
  void turnPrefixAfterCompleteCompactionTurnUsesNoPriorHistory() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.compactionComplete("complete summary", id(2L), id(4L), null);
    path.compactionTurnStart();

    assertEquals(
        CompactionSummaryAssembler.NO_PRIOR_HISTORY,
        CompactionSummaryAssembler.latestIncompleteSummary(path.path(), prefixRequest()));
  }

  @Test
  void turnPrefixAfterFailedCompactionTurnUsesNoPriorHistory() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.failedCompaction();
    path.compactionTurnStart();

    assertEquals(
        CompactionSummaryAssembler.NO_PRIOR_HISTORY,
        CompactionSummaryAssembler.latestIncompleteSummary(path.path(), prefixRequest()));
  }

  @Test
  void mismatchingPrecedingPartialFailsClosed() {
    // 紧邻前一个 turn 是 incomplete HISTORY partial，但其冻结事实与 TURN_PREFIX 请求不一致 -> 分支损坏。
    PathBuilder path = new PathBuilder();
    path.root();
    path.normalTurn("user", "reply");
    path.compactionPartial("stale partial summary", id(2L), id(4L), id(3L));
    path.compactionTurnStart();

    CompactionRequest driftedIds =
        new CompactionRequest(
            CompactionPhase.TURN_PREFIX, CompactionTrigger.THRESHOLD, 500L, id(5L), id(4L), id(3L));
    IllegalStateException idError =
        assertThrows(
            IllegalStateException.class,
            () -> CompactionSummaryAssembler.latestIncompleteSummary(path.path(), driftedIds));
    assertTrue(idError.getMessage().contains("must match"), idError.getMessage());

    CompactionRequest driftedTrigger =
        new CompactionRequest(
            CompactionPhase.TURN_PREFIX, CompactionTrigger.OVERFLOW, 500L, id(2L), id(4L), id(3L));
    assertThrows(
        IllegalStateException.class,
        () -> CompactionSummaryAssembler.latestIncompleteSummary(path.path(), driftedTrigger));

    CompactionRequest driftedTokens =
        new CompactionRequest(
            CompactionPhase.TURN_PREFIX, CompactionTrigger.THRESHOLD, 123L, id(2L), id(4L), id(3L));
    assertThrows(
        IllegalStateException.class,
        () -> CompactionSummaryAssembler.latestIncompleteSummary(path.path(), driftedTokens));

    CompactionRequest driftedPrefix =
        new CompactionRequest(
            CompactionPhase.TURN_PREFIX, CompactionTrigger.THRESHOLD, 500L, id(2L), id(4L), id(5L));
    assertThrows(
        IllegalStateException.class,
        () -> CompactionSummaryAssembler.latestIncompleteSummary(path.path(), driftedPrefix));
  }

  private static CompactionRequest historyRequest() {
    return new CompactionRequest(
        CompactionPhase.HISTORY, CompactionTrigger.THRESHOLD, 500L, id(2L), id(4L), id(3L));
  }

  private static CompactionRequest prefixRequest() {
    return new CompactionRequest(
        CompactionPhase.TURN_PREFIX, CompactionTrigger.THRESHOLD, 500L, id(2L), id(4L), id(3L));
  }

  /** [ROOT(1), TS(2), USER(3), ASST(4), TE(5), TS-COMP(HISTORY partial 7), TE(8), TS-COMP(9)]。 */
  private static EntryPath turnPrefixPath(String historySummary) {
    PathBuilder path = new PathBuilder();
    path.root();
    path.normalTurn("user", "reply");
    path.compactionPartial(historySummary, id(2L), id(4L), id(3L));
    path.compactionTurnStart();
    return path.path();
  }

  /**
   * [ROOT(1), TS(2), USER(3), ASST(4, read a.txt + write b.txt), TOOL(5,6), TE(7)] + open
   * TURN_START(8)；cut=TE(7) 使 ASST(4) 落入文件清单扫描范围 [0,7)。
   */
  private static EntryPath pathWithReadWriteAssistant() {
    PathBuilder path = new PathBuilder();
    path.root();
    path.turnStart(id(2L));
    path.user(id(3L), "user");
    path.assistantWithFileCalls(id(4L), "read", "a.txt", "write", "b.txt");
    path.toolResult(id(5L), id(4L), 0, "call-0", "read");
    path.toolResult(id(6L), id(4L), 1, "call-2", "write");
    path.turnEnd(id(7L), id(2L));
    path.turnStart(id(8L));
    return path.path();
  }

  /** 自包含的合法路径构造：id 顺序、parent 链、COMPACTION 语法。 */
  private static final class PathBuilder {
    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1L;
    private long openTurnStartId = -1L;

    PathBuilder root() {
      entries.add(new Entry(id(nextId++), SESSION_ID, null, new RootPayload(SETTINGS), BASE));
      return this;
    }

    PathBuilder normalTurn(String userText, String assistantText) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS),
              BASE));
      openTurnStartId = startId;
      user(id(nextId++), userText);
      assistant(id(nextId++), assistantText);
      turnEnd(id(nextId++), id(startId));
      return this;
    }

    /** 完成压缩 turn：[TURN_START(COMPACTION), COMPACTION payload, TURN_END]。 */
    PathBuilder compactionComplete(
        String summary, UUID firstKeptEntryId, UUID cutEntryId, UUID turnPrefixStartEntryId) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
              BASE));
      openTurnStartId = startId;
      entries.add(
          new Entry(
              id(nextId++),
              SESSION_ID,
              parentId(),
              new CompactionPayload(
                  CompactionPhase.FULL,
                  CompactionTrigger.THRESHOLD,
                  500L,
                  true,
                  summary,
                  firstKeptEntryId,
                  cutEntryId,
                  turnPrefixStartEntryId),
              BASE));
      turnEnd(id(nextId++), id(startId));
      return this;
    }

    /** 切分 HISTORY partial 压缩 turn：[TURN_START(COMPACTION), HISTORY incomplete, TURN_END]。 */
    PathBuilder compactionPartial(
        String summary, UUID firstKeptEntryId, UUID cutEntryId, UUID turnPrefixStartEntryId) {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
              BASE));
      openTurnStartId = startId;
      entries.add(
          new Entry(
              id(nextId++),
              SESSION_ID,
              parentId(),
              new CompactionPayload(
                  CompactionPhase.HISTORY,
                  CompactionTrigger.THRESHOLD,
                  500L,
                  false,
                  summary,
                  firstKeptEntryId,
                  cutEntryId,
                  turnPrefixStartEntryId),
              BASE));
      turnEnd(id(nextId++), id(startId));
      return this;
    }

    /** 失败压缩 turn：[TURN_START(COMPACTION), ASSISTANT_ERROR, TURN_END(FAILED)]。 */
    PathBuilder failedCompaction() {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
              BASE));
      openTurnStartId = startId;
      entries.add(
          new Entry(
              id(nextId++),
              SESSION_ID,
              parentId(),
              new AssistantErrorPayload(new AssistantError("SUMMARIZATION_FAILED", "boom"), null),
              BASE));
      entries.add(
          new Entry(
              id(nextId++),
              SESSION_ID,
              parentId(),
              new TurnEndPayload(
                  id(startId), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
              BASE));
      openTurnStartId = -1L;
      return this;
    }

    /** 只开当前（TURN_PREFIX）压缩 turn：调用方随后调用 resultPayload。 */
    PathBuilder compactionTurnStart() {
      long startId = nextId++;
      entries.add(
          new Entry(
              id(startId),
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.COMPACTION, SETTINGS),
              BASE));
      openTurnStartId = startId;
      return this;
    }

    PathBuilder turnStart(UUID id) {
      entries.add(
          new Entry(
              id,
              SESSION_ID,
              parentId(),
              new TurnStartPayload(TurnStartReason.INPUT, SETTINGS),
              BASE));
      openTurnStartId = id.getLeastSignificantBits();
      return this;
    }

    PathBuilder user(UUID id, String text) {
      entries.add(
          new Entry(
              id,
              SESSION_ID,
              parentId(),
              new MessagePayload(
                  new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
                  null,
                  null),
              BASE));
      return this;
    }

    PathBuilder assistant(UUID id, String text) {
      entries.add(
          new Entry(
              id,
              SESSION_ID,
              parentId(),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
                  assistantMetadata(),
                  null),
              BASE));
      return this;
    }

    /** ASSISTANT 消息携带 read/write 工具调用（用于文件 section 断言）。 */
    PathBuilder assistantWithFileCalls(UUID id, String... calls) {
      List<AgentMessageContent> contents = new ArrayList<>();
      for (int i = 0; i < calls.length; i += 2) {
        contents.add(
            new ToolCallMessageContent(
                "call-" + i, calls[i], calls[i], "{\"path\":\"" + calls[i + 1] + "\"}"));
      }
      contents.add(new TextMessageContent("assistant reply"));
      entries.add(
          new Entry(
              id,
              SESSION_ID,
              parentId(),
              new MessagePayload(
                  new AgentMessage(AgentMessageRole.ASSISTANT, contents),
                  assistantMetadata(ProviderStopReason.TOOL_CALLS),
                  null),
              BASE));
      return this;
    }

    PathBuilder toolResult(
        UUID id, UUID assistantEntryId, int ordinal, String toolCallId, String toolName) {
      entries.add(
          new Entry(
              id,
              SESSION_ID,
              parentId(),
              new MessagePayload(
                  new AgentMessage(
                      AgentMessageRole.TOOL,
                      List.of(
                          new ToolResultMessageContent(
                              toolCallId,
                              toolName,
                              toolName,
                              List.of(new TextMessageContent("ok")),
                              false,
                              "{}"))),
                  null,
                  new ToolResultMetadata(
                      assistantEntryId,
                      toolCallId,
                      ordinal,
                      ToolResultStatus.SUCCEEDED,
                      false,
                      null)),
              BASE));
      return this;
    }

    PathBuilder turnEnd(UUID id, UUID turnStartEntryId) {
      entries.add(
          new Entry(
              id,
              SESSION_ID,
              parentId(),
              new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
              BASE));
      openTurnStartId = -1L;
      return this;
    }

    private static AssistantMessageMetadata assistantMetadata() {
      return assistantMetadata(ProviderStopReason.COMPLETED);
    }

    private static AssistantMessageMetadata assistantMetadata(ProviderStopReason stopReason) {
      return new AssistantMessageMetadata(
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
              BigDecimal.ZERO));
    }

    private UUID parentId() {
      return entries.get(entries.size() - 1).id();
    }

    EntryPath path() {
      return new EntryPath(List.copyOf(entries));
    }
  }

  private static final UUID SESSION_ID = id(100L);
}
