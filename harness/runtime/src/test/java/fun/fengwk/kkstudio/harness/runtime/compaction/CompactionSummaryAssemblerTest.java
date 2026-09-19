package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** CompactionSummaryAssembler 只持久化 summaryText，并从 enclosing CompactionStart 重建阶段语义。 */
class CompactionSummaryAssemblerTest {

  private static final UUID SESSION_ID = id(100L);
  private static final UUID OWNER_THREAD_ID = id(101L);
  private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);

  @Test
  void historyPhaseStoresOnlyCanonicalPartialSummary() {
    // HISTORY 是 split 的中间 checkpoint；不追加最终文件清单，也不把 phase/cut 复制到 payload。
    EntryPath path = simpleTurnWithOpenCompaction(historyStart(id(5L), id(2L)));

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(
            path,
            historyStart(id(5L), id(2L)),
            "partial text\n\n<read-files>\nstale.txt\n</read-files>");

    assertEquals("partial text", payload.summaryText());
    assertFalse(payload.summaryText().contains("<read-files>"));
  }

  @Test
  void fullPhaseRecomputesRuntimeOwnedFileSections() {
    // 模型返回的 stale sections 被剥离，最终 section 从 durable tool calls 重新计算。
    EntryPath path = fileTurnWithOpenCompaction(fullStart(id(7L)));

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(
            path,
            fullStart(id(7L)),
            "full summary\n\n<read-files>\nstale.txt\n</read-files>\n\n"
                + "<modified-files>\nstale-write.txt\n</modified-files>");

    assertEquals(
        "full summary\n\n<read-files>\na.txt\n</read-files>\n\n"
            + "<modified-files>\nb.txt\n</modified-files>",
        payload.summaryText());
  }

  @Test
  void turnPrefixMergesExactlyReferencedHistoryResult() {
    // TURN_PREFIX 只读取 CompactionStart.historyCompactionEntryId，不扫描任意 stale partial。
    SplitPath split = splitPath("history summary");
    CompactionStart prefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(5L),
            id(2L),
            split.historyResultEntryId());

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(split.path(), prefix, "prefix summary");

    assertEquals(
        "history summary" + CompactionSummaryAssembler.TURN_PREFIX_SEPARATOR + "prefix summary",
        payload.summaryText());
  }

  @Test
  void directTurnPrefixUsesFixedNoPriorHistorySegment() {
    // direct split 必须保留固定 history 段（HISTORY.summaryText 或固定 "No prior history."），
    // 不能只保存 prefix 文本而丢失两段式形状。
    CompactionStart prefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(4L),
            id(3L),
            null);
    EntryPath path = simpleTurnWithOpenCompaction(prefix);

    CompactionPayload payload =
        CompactionSummaryAssembler.resultPayload(path, prefix, "prefix summary");

    assertEquals(
        CompactionSummaryAssembler.NO_PRIOR_HISTORY
            + CompactionSummaryAssembler.TURN_PREFIX_SEPARATOR
            + "prefix summary",
        payload.summaryText());
  }

  @Test
  void turnPrefixMissingReferencedHistoryFailsClosed() {
    // 丢失精确 HISTORY result 引用是 durable branch 损坏，不能回退扫描更早摘要。
    SplitPath split = splitPath("history summary");
    CompactionStart missing =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(5L),
            id(2L),
            id(999L));

    assertThrows(
        IllegalStateException.class,
        () -> CompactionSummaryAssembler.resultPayload(split.path(), missing, "prefix summary"));
  }

  private static CompactionStart fullStart(UUID cutEntryId) {
    return new CompactionStart(
        CompactionPhase.FULL,
        CompactionTrigger.THRESHOLD,
        SETTINGS.model(),
        cutEntryId,
        null,
        null);
  }

  private static CompactionStart historyStart(UUID cutEntryId, UUID turnPrefixStartEntryId) {
    return new CompactionStart(
        CompactionPhase.HISTORY,
        CompactionTrigger.THRESHOLD,
        SETTINGS.model(),
        cutEntryId,
        turnPrefixStartEntryId,
        null);
  }

  private static EntryPath simpleTurnWithOpenCompaction(CompactionStart start) {
    List<Entry> entries = normalTurn();
    entries.add(new Entry(id(6L), SESSION_ID, id(5L), compactionTurnStart(start), NOW));
    return new EntryPath(entries);
  }

  private static EntryPath fileTurnWithOpenCompaction(CompactionStart start) {
    List<Entry> entries = new ArrayList<>();
    entries.add(new Entry(id(1L), SESSION_ID, null, new RootPayload(SETTINGS), NOW));
    entries.add(
        new Entry(
            id(2L),
            SESSION_ID,
            id(1L),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
            NOW));
    entries.add(new Entry(id(3L), SESSION_ID, id(2L), user("user"), NOW));
    List<AgentMessageContent> contents =
        List.of(
            new ToolCallMessageContent("read-1", "read", "read", "{\"path\":\"a.txt\"}"),
            new ToolCallMessageContent("write-1", "write", "write", "{\"path\":\"b.txt\"}"),
            new TextMessageContent("assistant reply"));
    entries.add(
        new Entry(
            id(4L),
            SESSION_ID,
            id(3L),
            new MessagePayload(
                new AgentMessage(AgentMessageRole.ASSISTANT, contents), metadata(), null),
            NOW));
    entries.add(
        new Entry(id(5L), SESSION_ID, id(4L), toolResult(id(4L), 0, "read-1", "read"), NOW));
    entries.add(
        new Entry(id(6L), SESSION_ID, id(5L), toolResult(id(4L), 1, "write-1", "write"), NOW));
    entries.add(
        new Entry(
            id(7L),
            SESSION_ID,
            id(6L),
            new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, false, null, null),
            NOW));
    entries.add(new Entry(id(8L), SESSION_ID, id(7L), compactionTurnStart(start), NOW));
    return new EntryPath(entries);
  }

  private static SplitPath splitPath(String historySummary) {
    List<Entry> entries = normalTurn();
    CompactionStart history = historyStart(id(5L), id(2L));
    entries.add(new Entry(id(6L), SESSION_ID, id(5L), compactionTurnStart(history), NOW));
    UUID historyResultEntryId = id(7L);
    entries.add(
        new Entry(
            historyResultEntryId, SESSION_ID, id(6L), new CompactionPayload(historySummary), NOW));
    entries.add(
        new Entry(
            id(8L),
            SESSION_ID,
            historyResultEntryId,
            new TurnEndPayload(id(6L), TurnEndOutcome.COMPLETED, true, null, null),
            NOW));
    CompactionStart prefix =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.THRESHOLD,
            SETTINGS.model(),
            id(5L),
            id(2L),
            historyResultEntryId);
    entries.add(new Entry(id(9L), SESSION_ID, id(8L), compactionTurnStart(prefix), NOW));
    return new SplitPath(new EntryPath(entries), historyResultEntryId);
  }

  private static List<Entry> normalTurn() {
    List<Entry> entries = new ArrayList<>();
    entries.add(new Entry(id(1L), SESSION_ID, null, new RootPayload(SETTINGS), NOW));
    entries.add(
        new Entry(
            id(2L),
            SESSION_ID,
            id(1L),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
            NOW));
    entries.add(new Entry(id(3L), SESSION_ID, id(2L), user("user"), NOW));
    entries.add(
        new Entry(
            id(4L),
            SESSION_ID,
            id(3L),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("reply"))),
                metadata(),
                null),
            NOW));
    entries.add(
        new Entry(
            id(5L),
            SESSION_ID,
            id(4L),
            new TurnEndPayload(id(2L), TurnEndOutcome.COMPLETED, false, null, null),
            NOW));
    return entries;
  }

  private static TurnStartPayload compactionTurnStart(CompactionStart start) {
    return new TurnStartPayload(
        TurnStartReason.COMPACTION, SETTINGS, OWNER_THREAD_ID, 100_000, 16_384, start);
  }

  private static MessagePayload user(String text) {
    return new MessagePayload(AgentMessage.user(text), null, null);
  }

  private static MessagePayload toolResult(
      UUID assistantEntryId, int callIndex, String callId, String toolName) {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    callId,
                    toolName,
                    toolName,
                    List.of(new TextMessageContent("ok")),
                    false,
                    "{}"))),
        null,
        new ToolResultMetadata(
            assistantEntryId, callId, callIndex, ToolResultStatus.SUCCEEDED, false, null));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(
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
            BigDecimal.ZERO));
  }

  private record SplitPath(EntryPath path, UUID historyResultEntryId) {}
}
