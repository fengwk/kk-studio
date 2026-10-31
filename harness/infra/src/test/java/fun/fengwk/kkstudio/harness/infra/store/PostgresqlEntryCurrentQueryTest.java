package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PostgreSQL 当前状态、祖先与计数的真实查询边界。
 *
 * <p>计划文本来自生产语句自身。冷读取不得先执行完整 entry_path，窄结果也不得进入完整路径缓存。
 */
class PostgresqlEntryCurrentQueryTest {

  private final AtomicInteger fullPathCounter = new AtomicInteger();
  private final AtomicReference<String> plan = new AtomicReference<>("");
  private HarnessStore store;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    fullPathCounter.set(0);
    plan.set("");
    DataSource counting =
        PostgresqlEntryPathCteCounter.countingDataSource(
            PostgresqlHarnessStoreFixture.dataSource(), fullPathCounter);
    store =
        PostgresqlHarnessStoreFixture.create(
            PostgresqlEntrySliceProbe.probingDataSource(
                counting, plan, "with recursive current_anchor", 1));
  }

  @AfterEach
  void tearDown() {
    PostgresqlHarnessStoreFixture.reset();
  }

  /** 测试意图：尾部控制 Entry 后，冷递归停在 settings、closed turn 与其引用，不读取更老 ROOT。 */
  @Test
  void coldCurrentStopsAtClosedAnchorsAndDoesNotCachePartialPath() {
    Baseline baseline = seedThreadBaseline(store);
    BranchSettings live = settings("live");
    BranchSettings applied = settings("applied");
    UUID start =
        insert(baseline, baseline.rootEntryId(), turn(baseline, TurnStartReason.INPUT, live), 1);
    UUID user = insert(baseline, start, userPayload(), 2);
    UUID end = insert(baseline, user, closed(start), 3);
    UUID settingsEntry =
        insert(baseline, end, new SettingsPayload(applied, baseline.threadId()), 4);
    UUID head = insert(baseline, settingsEntry, ForkPayload.branch(baseline.rootEntryId()), 5);
    fullPathCounter.set(0);

    EntryCurrentState state = store.transaction(tx -> tx.entries().loadCurrent(head));

    assertEquals(head, state.head().id());
    assertEquals(end, state.headIgnoringTrailingControlEntries().id());
    assertTrue(state.openTurnStart().isEmpty());
    assertEquals(start, state.referencedTurnStartEntry().id());
    assertEquals(applied, state.baseSettings());
    assertEquals(0, fullPathCounter.get(), "冷 current 不得先读取完整路径");
    assertRecursiveRows(6, "控制尾部读到被引用 TURN_START 后停止，不读 ROOT");
    UUID missing = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.entries().loadCurrent(missing)));
    store.transaction(
        tx -> {
          tx.entries().loadPath(head);
          EntryCurrentState warm = tx.entries().loadCurrent(head);
          assertEquals(applied, warm.baseSettings());
          assertEquals(1, fullPathCounter.get(), "已缓存完整路径直接派生，不再查询");
          return null;
        });
    fullPathCounter.set(0);
    store.transaction(
        tx -> {
          assertEquals(baseline.rootEntryId(), tx.entries().loadPath(head).root().id());
          assertEquals(1, fullPathCounter.get(), "冷 current 的锚点不得进入完整路径缓存");
          return null;
        });
  }

  /** 测试意图：open compaction 的 settings 不替换真实模型，且递归在该 TURN_START 停止。 */
  @Test
  void compactionTurnDoesNotPolluteSettingsAndStopsAtOpenTurn() {
    Baseline baseline = seedThreadBaseline(store);
    BranchSettings live = settings("live");
    UUID start =
        insert(baseline, baseline.rootEntryId(), turn(baseline, TurnStartReason.INPUT, live), 1);
    UUID user = insert(baseline, start, userPayload(), 2);
    UUID end = insert(baseline, user, closed(start), 3);
    UUID compaction =
        insert(
            baseline,
            end,
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                settings("poison"),
                baseline.threadId(),
                null,
                null,
                CompactionStart.pending(
                    CompactionPhase.FULL,
                    CompactionTrigger.MANUAL,
                    baseline.rootEntryId(),
                    null,
                    null),
                null),
            4);
    fullPathCounter.set(0);

    EntryCurrentState open = store.transaction(tx -> tx.entries().loadCurrent(compaction));

    assertEquals(compaction, open.openTurnStart().orElseThrow().id());
    assertNull(open.referencedTurnStartEntry());
    assertEquals(live, open.baseSettings());
    assertEquals(0, fullPathCounter.get());
    assertRecursiveRows(5, "open compaction 回溯到最近真实 settings 后停止，不读 ROOT");
  }

  /** 测试意图：已缓存完整路径直接派生；另一分支与未知目标不是祖先，head 缺失被拒绝。 */
  @Test
  void ancestorIncludesHeadAndRejectsOtherBranchWithoutReadingPayload() {
    DataSource counting =
        PostgresqlEntryPathCteCounter.countingDataSource(
            PostgresqlHarnessStoreFixture.dataSource(), fullPathCounter);
    store =
        PostgresqlHarnessStoreFixture.create(
            PostgresqlEntrySliceProbe.probingDataSource(
                counting, plan, "with recursive ancestor_path", 3));
    Baseline left = seedThreadBaseline(store);
    UUID start =
        insert(left, left.rootEntryId(), turn(left, TurnStartReason.INPUT, settings("live")), 1);
    UUID head = insert(left, start, userPayload(), 2);
    UUID otherRoot =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              tx.sessions().insert(session(sessionId));
              tx.entries().insert(rootEntry(rootId, sessionId));
              return rootId;
            });
    UUID unknown = UUID.randomUUID();
    fullPathCounter.set(0);

    store.transaction(
        tx -> {
          tx.entries().loadPath(head);
          assertTrue(tx.entries().isAncestor(head, head));
          assertTrue(tx.entries().isAncestor(head, left.rootEntryId()));
          assertFalse(tx.entries().isAncestor(head, otherRoot));
          assertFalse(tx.entries().isAncestor(head, unknown));
          assertEquals(1, fullPathCounter.get(), "已缓存路径不得再执行祖先查询");
          assertTrue(plan.get().isBlank(), "缓存命中不得解释祖先 SQL");
          assertThrows(
              IllegalArgumentException.class, () -> tx.entries().isAncestor(unknown, head));
          return null;
        });

    fullPathCounter.set(0);
    assertTrue(
        Boolean.TRUE.equals(
            store.transaction(tx -> Boolean.valueOf(tx.entries().isAncestor(head, head)))));
    assertEquals(0, fullPathCounter.get(), "冷祖先查询自身直接命中，不读取完整路径");
    assertRecursiveRows(1, "命中自身立即停止");

    fullPathCounter.set(0);
    assertTrue(
        Boolean.TRUE.equals(
            store.transaction(tx -> Boolean.valueOf(tx.entries().isAncestor(head, start)))));
    assertEquals(0, fullPathCounter.get(), "冷祖先查询直接父命中，不读取完整路径");
    assertRecursiveRows(2, "命中直接父立即停止");

    fullPathCounter.set(0);
    boolean coldOtherBranch =
        store.transaction(tx -> Boolean.valueOf(tx.entries().isAncestor(head, otherRoot)));
    assertFalse(coldOtherBranch);
    assertEquals(0, fullPathCounter.get(), "冷祖先查询不得先读取完整路径");
    assertRecursiveRows(3, "其它分支必须走到当前 ROOT 才能返回 false");
    fullPathCounter.set(0);
    store.transaction(
        tx -> {
          tx.entries().loadPath(head);
          assertEquals(1, fullPathCounter.get(), "冷祖先结果不得进入完整路径缓存");
          return null;
        });
  }

  /** 测试意图：计数只含 INPUT/CONTINUATION 与 ASSISTANT tool_call，且 SQL 不返回历史正文。 */
  @Test
  void metricsCountExactTurnsAndAssistantToolCallsWithoutMaterializingPayload() {
    DataSource counting =
        PostgresqlEntryPathCteCounter.countingDataSource(
            PostgresqlHarnessStoreFixture.dataSource(), fullPathCounter);
    store =
        PostgresqlHarnessStoreFixture.create(
            PostgresqlEntrySliceProbe.probingDataSource(
                counting, plan, "with recursive metric_path", 1));
    Baseline baseline = seedThreadBaseline(store);
    UUID input =
        insert(
            baseline,
            baseline.rootEntryId(),
            turn(baseline, TurnStartReason.INPUT, settings("live")),
            1);
    UUID user = insert(baseline, input, userPayload(), 2);
    UUID assistant = insert(baseline, user, assistantPayload(), 3);
    UUID inputEnd = insert(baseline, assistant, closed(input), 4);
    UUID continuation =
        insert(
            baseline, inputEnd, turn(baseline, TurnStartReason.CONTINUATION, settings("live")), 5);
    UUID continuationEnd = insert(baseline, continuation, closed(continuation), 6);
    UUID compaction =
        insert(
            baseline,
            continuationEnd,
            new TurnStartPayload(
                TurnStartReason.COMPACTION,
                settings("poison"),
                baseline.threadId(),
                null,
                null,
                CompactionStart.pending(
                    CompactionPhase.FULL,
                    CompactionTrigger.MANUAL,
                    baseline.rootEntryId(),
                    null,
                    null),
                null),
            7);
    UUID summary = insert(baseline, compaction, new CompactionPayload("summary", null), 8);
    UUID compactionEnd = insert(baseline, summary, closed(compaction), 9);
    UUID stop =
        insert(baseline, compactionEnd, turn(baseline, TurnStartReason.STOP, settings("live")), 10);
    fullPathCounter.set(0);

    EntryPathMetrics metrics = store.transaction(tx -> tx.entries().loadPathMetrics(stop));

    assertEquals(new EntryPathMetrics(2, 1), metrics);
    assertEquals(0, fullPathCounter.get(), "冷计数不得先读取完整路径");
    assertRecursiveRows(12, "计数遍历祖先元数据，但不先读取完整路径");
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.entries().loadPathMetrics(UUID.randomUUID())));
    store.transaction(
        tx -> {
          tx.entries().loadPath(stop);
          assertEquals(metrics, tx.entries().loadPathMetrics(stop));
          return null;
        });
    fullPathCounter.set(0);
    store.transaction(
        tx -> {
          assertEquals(metrics, EntryPathMetrics.from(tx.entries().loadPath(stop).entries()));
          assertEquals(1, fullPathCounter.get(), "冷计数不得进入完整路径缓存");
          return null;
        });
  }

  private void assertRecursiveRows(int rows, String reason) {
    assertTrue(
        plan.get()
            .lines()
            .anyMatch(
                line ->
                    line.contains("Recursive Union")
                        && line.matches(".*\\(actual [^)]*rows=" + rows + " loops=1\\).*")),
        () -> reason + "：\n" + plan.get());
  }

  private UUID insert(Baseline baseline, UUID parentId, EntryPayload payload, int seconds) {
    return store.transaction(
        tx -> {
          UUID id = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      id,
                      baseline.sessionId(),
                      parentId,
                      payload,
                      Instant.ofEpochMilli(1000L + seconds * 1000L)));
          return id;
        });
  }

  private static TurnStartPayload turn(
      Baseline baseline, TurnStartReason reason, BranchSettings settings) {
    return new TurnStartPayload(reason, settings, baseline.threadId());
  }

  private static TurnEndPayload closed(UUID turnStartId) {
    return new TurnEndPayload(
        turnStartId, TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null);
  }

  private static MessagePayload userPayload() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  private static MessagePayload assistantPayload() {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "bash", "bash", "{}"))),
        new AssistantMessageMetadata(
            GenerationStopReason.COMPLETE, new ModelUsage(1L, 1L, 0L, 0L, Map.of(), 0L, 2L)),
        null);
  }

  private static BranchSettings settings(String agent) {
    return new BranchSettings(agent, ModelSelection.model("claude", "default"), null);
  }
}
