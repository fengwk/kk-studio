package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactToolName;
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
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link CompactionGate#compactTool} 的直接判定：只有「最新已关闭 turn 内 {@code compact} 工具成功返回」的手动压缩排队才启动压缩。
 *
 * <p>正向断言真实切点（最新 turn 留在主线程），负向覆盖每个提前返回的边界：结果不在最新 turn、结果失败、压缩线程自身、约束禁止、上下文窗口未解析、owner 不匹配与没有可摘要前缀。
 */
class CompactionGateTest {

  private static final BranchSettings SETTINGS =
      new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final UUID SESSION_ID = id(100L);
  private static final UUID THREAD_ID = id(200L);
  private static final int CONTEXT_WINDOW = 200;
  private static final int MAX_OUTPUT_TOKENS = 4_096;

  /** keepRecent 取 1，使「压缩最新 turn 之前的边界」成为唯一可用切点，与 turn 文本长度无关。 */
  private final CompactionGate gate = new CompactionGate(() -> new CompactionConfig(1));

  /** 测试意图：最新已关闭 turn 内的 compact 成功排队一次 MANUAL 压缩，切点是该 turn 之前的完整 turn 边界。 */
  @Test
  void manualCompactionCutsAtTheTurnBoundaryBeforeTheLatestTurn() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);
    path.compactToolTurn(CONTEXT_WINDOW, THREAD_ID, ToolResultStatus.SUCCEEDED);

    CompactionPreparation preparation =
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path());

    assertNotNull(preparation);
    assertEquals(CompactionTrigger.MANUAL, preparation.trigger());
    assertEquals(path.turnEndId(1), preparation.cutEntryId());
  }

  /** 测试意图：排队请求只属于最新已关闭 turn；更早 turn 的历史 compact 成功不再触发压缩。 */
  @Test
  void compactResultBeforeTheLatestTurnDoesNotQueueCompaction() {
    PathBuilder path = new PathBuilder();
    path.compactToolTurn(CONTEXT_WINDOW, THREAD_ID, ToolResultStatus.SUCCEEDED);
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);

    assertNull(
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path()));
  }

  /** 测试意图：compact 工具失败不是排队请求，继续普通续作。 */
  @Test
  void failedCompactToolResultDoesNotQueueCompaction() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);
    path.compactToolTurn(CONTEXT_WINDOW, THREAD_ID, ToolResultStatus.FAILED);

    assertNull(
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path()));
  }

  /** 测试意图：COMPACTION 线程的摘要执行历史里可能出现 compact 结果，但其自身绝不再自动压缩。 */
  @Test
  void compactionThreadNeverQueuesAnotherCompaction() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);
    path.compactToolTurn(CONTEXT_WINDOW, THREAD_ID, ToolResultStatus.SUCCEEDED);

    assertNull(
        gate.compactTool(
            thread(THREAD_ID, ThreadKind.COMPACTION, ThreadConstraints.NONE), path.path()));
  }

  /** 测试意图：冻结约束禁止自动压缩的 Thread 即使调用 compact 也不自动规划压缩。 */
  @Test
  void constraintsForbiddingAutoCompactionSuppressManualCompaction() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);
    path.compactToolTurn(CONTEXT_WINDOW, THREAD_ID, ToolResultStatus.SUCCEEDED);

    assertNull(
        gate.compactTool(
            thread(THREAD_ID, ThreadKind.USER, new ThreadConstraints(null, false)), path.path()));
  }

  /** 测试意图：turn 未冻结 contextWindow（resolver 拒绝/候补）时无法确定保留量，不得启动压缩。 */
  @Test
  void turnWithoutResolvedContextWindowDoesNotQueueCompaction() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(null, THREAD_ID);
    path.compactToolTurn(null, THREAD_ID, ToolResultStatus.SUCCEEDED);

    assertNull(
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path()));
  }

  /** 测试意图：切点回溯会跳过未冻结 contextWindow 的最新 turn，改用最近一个已解析 turn 的窗口。 */
  @Test
  void manualCompactionReusesTheLatestResolvedTurnWindow() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);
    path.compactToolTurn(null, THREAD_ID, ToolResultStatus.SUCCEEDED);

    CompactionPreparation preparation =
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path());

    assertNotNull(preparation);
    assertEquals(CompactionTrigger.MANUAL, preparation.trigger());
    assertEquals(path.turnEndId(1), preparation.cutEntryId());
  }

  /** 测试意图：owner 不匹配的历史（fork 分支）不属于本 Thread 的手动压缩排队。 */
  @Test
  void turnOwnedByAnotherThreadDoesNotQueueCompaction() {
    PathBuilder path = new PathBuilder();
    path.inputTurn(CONTEXT_WINDOW, THREAD_ID);
    path.compactToolTurn(CONTEXT_WINDOW, id(201L), ToolResultStatus.SUCCEEDED);

    assertNull(
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path()));
  }

  /** 测试意图：没有可摘要前缀（单 turn）时压缩无法切分，返回 null 让回合继续普通续作。 */
  @Test
  void noSummarizablePrefixLeavesTheTurnContinuing() {
    PathBuilder path = new PathBuilder();
    path.compactToolTurn(CONTEXT_WINDOW, THREAD_ID, ToolResultStatus.SUCCEEDED);

    assertNull(
        gate.compactTool(thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), path.path()));
  }

  /** 测试意图：没有已关闭 turn 的历史无法排队压缩，即使 Thread 允许自动压缩。 */
  @Test
  void pathWithoutClosedTurnDoesNotQueueCompaction() {
    assertNull(
        gate.compactTool(
            thread(THREAD_ID, ThreadKind.USER, ThreadConstraints.NONE), new PathBuilder().path()));
  }

  private static ThreadState thread(UUID threadId, ThreadKind kind, ThreadConstraints constraints) {
    return new ThreadState(
        threadId,
        SESSION_ID,
        null,
        id(990L),
        HASH,
        "main",
        threadId,
        false,
        kind,
        constraints,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        NOW,
        NOW);
  }

  /** 最小合法 root-to-head 历史构造器：只拼装 compactTool 判定的输入形状，不经过 Store。 */
  private static final class PathBuilder {

    private final List<Entry> entries = new ArrayList<>();
    private final List<UUID> turnEndIds = new ArrayList<>();
    private long nextId = 1L;
    private UUID openTurnStartId;

    PathBuilder() {
      entries.add(new Entry(id(nextId++), SESSION_ID, null, new RootPayload(SETTINGS), NOW));
    }

    PathBuilder inputTurn(Integer contextWindow, UUID ownerThreadId) {
      startTurn(contextWindow, ownerThreadId);
      append(userMessage("queued question " + nextId));
      append(assistantMessage("previous answer", List.of()));
      return endTurn();
    }

    /** 最新 turn 内先由模型调用 {@code compact}，再写入该调用的终态结果。 */
    PathBuilder compactToolTurn(
        Integer contextWindow, UUID ownerThreadId, ToolResultStatus status) {
      startTurn(contextWindow, ownerThreadId);
      append(userMessage("please compact this conversation"));
      UUID assistantEntryId =
          append(
              assistantMessage(
                  "compacting",
                  List.of(
                      new ToolCallMessageContent(
                          "call-compact", CompactToolName.NAME, CompactToolName.NAME, "{}"))));
      append(toolResultMessage(assistantEntryId, status));
      return endTurn();
    }

    EntryPath path() {
      return new EntryPath(entries);
    }

    UUID turnEndId(int oneBasedTurn) {
      return turnEndIds.get(oneBasedTurn - 1);
    }

    private void startTurn(Integer contextWindow, UUID ownerThreadId) {
      UUID startId = id(nextId++);
      openTurnStartId = startId;
      entries.add(
          new Entry(
              startId,
              SESSION_ID,
              parentId(),
              new TurnStartPayload(
                  TurnStartReason.INPUT,
                  SETTINGS,
                  ownerThreadId,
                  contextWindow,
                  contextWindow == null ? null : MAX_OUTPUT_TOKENS,
                  null,
                  null),
              NOW));
    }

    private PathBuilder endTurn() {
      UUID endId = id(nextId++);
      entries.add(
          new Entry(
              endId,
              SESSION_ID,
              parentId(),
              new TurnEndPayload(openTurnStartId, TurnEndOutcome.COMPLETED, false, null, null),
              NOW));
      turnEndIds.add(endId);
      return this;
    }

    private UUID append(MessagePayload payload) {
      UUID entryId = id(nextId++);
      entries.add(new Entry(entryId, SESSION_ID, parentId(), payload, NOW));
      return entryId;
    }

    private UUID parentId() {
      return entries.getLast().id();
    }

    private static MessagePayload userMessage(String text) {
      return new MessagePayload(
          new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
          null,
          null);
    }

    private static MessagePayload assistantMessage(
        String text, List<ToolCallMessageContent> calls) {
      List<AgentMessageContent> contents = new ArrayList<>();
      contents.add(new TextMessageContent(text));
      contents.addAll(calls);
      return new MessagePayload(
          new AgentMessage(AgentMessageRole.ASSISTANT, contents),
          new AssistantMessageMetadata(
              GenerationStopReason.COMPLETE, new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L)),
          null);
    }

    private MessagePayload toolResultMessage(UUID assistantEntryId, ToolResultStatus status) {
      return new MessagePayload(
          new AgentMessage(
              AgentMessageRole.TOOL,
              List.of(
                  new ToolResultMessageContent(
                      "call-compact",
                      CompactToolName.NAME,
                      CompactToolName.NAME,
                      List.of(new TextMessageContent("compaction queued")),
                      status != ToolResultStatus.SUCCEEDED,
                      "{}"))),
          null,
          new ToolResultMetadata(
              id(900L + nextId),
              assistantEntryId,
              "call-compact",
              0,
              status,
              false,
              null,
              null,
              CompactToolName.NAME));
    }
  }
}
