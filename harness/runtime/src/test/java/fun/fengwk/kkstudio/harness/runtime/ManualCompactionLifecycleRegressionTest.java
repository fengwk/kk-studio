package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 回归测试：验证后代线程手动压缩时： (a) 在同一事务中原子将后代线程生命周期状态由 IDLE 推进为 ACTIVE 并生成 ModelInvocation 与 MODEL work； (b)
 * 递归将处于 IDLE 的全部祖先线程推进为 WAITING_CHILDREN。
 */
class ManualCompactionLifecycleRegressionTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final ModelUsage USAGE = new ModelUsage(50_000L, 2L, 0L, 0L, 0L, 0L, 50_002L);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  /**
   * 测试意图：两级执行树（Root -> Child），Child 触发手动压缩： (a) Child 状态由 IDLE 翻转为 ACTIVE（version + 1），并同事务生成
   * ModelInvocation 与 MODEL work； (b) IDLE 的父级 Root 线程被推进为 WAITING_CHILDREN。
   */
  @Test
  void twoLevelDescendantManualCompactionSetsActiveAndPropagatesWaitingChildrenToRoot() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    FakeTurnResolver resolver = new FakeTurnResolver();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store, Clock.fixed(NOW, ZoneOffset.UTC), resolver, () -> CompactionConfig.DEFAULT);

    TwoLevelBaseline baseline = seedTwoLevelTree(store);

    // 初始验证：祖先 Root 与后代 Child 均处于 IDLE 状态
    assertEquals(ThreadLifecycleStatus.IDLE, thread(store, baseline.rootThreadId).status());
    assertEquals(ThreadLifecycleStatus.IDLE, thread(store, baseline.childThreadId).status());

    CompactThreadResult result =
        runtime.compactThread(new CompactThreadCommand(baseline.childThreadId, 0));

    // (a) 后代 Child 翻转为 ACTIVE，版本由 0 增加为 1
    assertEquals(ThreadLifecycleStatus.ACTIVE, result.thread().status());
    assertEquals(1L, result.thread().version());
    assertEquals(ThreadLifecycleStatus.ACTIVE, thread(store, baseline.childThreadId).status());
    assertEquals(1L, thread(store, baseline.childThreadId).version());

    // (b) 祖先 Root 翻转为 WAITING_CHILDREN
    assertEquals(
        ThreadLifecycleStatus.WAITING_CHILDREN, thread(store, baseline.rootThreadId).status());

    // (c) 同一事务中创建了 ModelInvocation 与 MODEL work，不创建 THREAD work
    assertNotNull(result.modelInvocationId());
    assertNotNull(work(store, new WorkTarget(WorkTargetType.MODEL, result.modelInvocationId())));
    assertNull(work(store, new WorkTarget(WorkTargetType.THREAD, baseline.childThreadId)));
  }

  /**
   * 测试意图：三级执行树（Root -> Middle -> Leaf），Leaf 触发手动压缩： (a) Leaf 翻转为 ACTIVE； (b) 沿祖先链上的所有处于 IDLE
   * 的祖先（Middle 与 Root）均被标记为 WAITING_CHILDREN。
   */
  @Test
  void threeLevelDescendantManualCompactionPropagatesToAllIdleAncestors() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    FakeTurnResolver resolver = new FakeTurnResolver();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store, Clock.fixed(NOW, ZoneOffset.UTC), resolver, () -> CompactionConfig.DEFAULT);

    ThreeLevelBaseline baseline = seedThreeLevelTree(store);

    assertEquals(ThreadLifecycleStatus.IDLE, thread(store, baseline.rootThreadId).status());
    assertEquals(ThreadLifecycleStatus.IDLE, thread(store, baseline.middleThreadId).status());
    assertEquals(ThreadLifecycleStatus.IDLE, thread(store, baseline.leafThreadId).status());

    CompactThreadResult result =
        runtime.compactThread(new CompactThreadCommand(baseline.leafThreadId, 0));

    // (a) 叶子线程翻转为 ACTIVE
    assertEquals(ThreadLifecycleStatus.ACTIVE, result.thread().status());
    assertEquals(1L, result.thread().version());
    assertEquals(ThreadLifecycleStatus.ACTIVE, thread(store, baseline.leafThreadId).status());

    // (b) 祖先中间节点与根节点均转为 WAITING_CHILDREN
    assertEquals(
        ThreadLifecycleStatus.WAITING_CHILDREN, thread(store, baseline.middleThreadId).status());
    assertEquals(
        ThreadLifecycleStatus.WAITING_CHILDREN, thread(store, baseline.rootThreadId).status());

    // (c) ModelInvocation 与 MODEL work 存在
    assertNotNull(result.modelInvocationId());
    assertNotNull(work(store, new WorkTarget(WorkTargetType.MODEL, result.modelInvocationId())));
  }

  // --- 测试脚手架与数据初始化 ---

  private record TwoLevelBaseline(UUID rootThreadId, UUID childThreadId, UUID childHeadEntryId) {}

  private record ThreeLevelBaseline(
      UUID rootThreadId, UUID middleThreadId, UUID leafThreadId, UUID leafHeadEntryId) {}

  private static TwoLevelBaseline seedTwoLevelTree(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID rootThreadId = tx.nextId();
          UUID childThreadId = tx.nextId();

          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(
                  rootEntryId,
                  sessionId,
                  null,
                  new RootPayload(HarnessRuntimeTestSupport.settings()),
                  NOW));

          // 插入 Root 线程（IDLE，无父级）
          tx.insertThread(
              new ThreadState(
                  rootThreadId,
                  sessionId,
                  null,
                  rootEntryId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "root",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  NOW,
                  NOW));

          // 为 Child 线程种子满足压缩条件的两个已完成 Turn（历史 token 足够）
          UUID headEntryId = seedCompactionHistory(tx, sessionId, rootEntryId, childThreadId);

          // 插入 Child 线程（IDLE，parent 指向 rootThreadId）
          tx.insertThread(
              new ThreadState(
                  childThreadId,
                  sessionId,
                  rootThreadId,
                  headEntryId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "child",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  NOW,
                  NOW));

          return new TwoLevelBaseline(rootThreadId, childThreadId, headEntryId);
        });
  }

  private static ThreeLevelBaseline seedThreeLevelTree(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID rootThreadId = tx.nextId();
          UUID middleThreadId = tx.nextId();
          UUID leafThreadId = tx.nextId();

          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(
                  rootEntryId,
                  sessionId,
                  null,
                  new RootPayload(HarnessRuntimeTestSupport.settings()),
                  NOW));

          tx.insertThread(
              new ThreadState(
                  rootThreadId,
                  sessionId,
                  null,
                  rootEntryId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "root",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  NOW,
                  NOW));

          tx.insertThread(
              new ThreadState(
                  middleThreadId,
                  sessionId,
                  rootThreadId,
                  rootEntryId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "middle",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  NOW,
                  NOW));

          UUID leafHeadEntryId = seedCompactionHistory(tx, sessionId, rootEntryId, leafThreadId);

          tx.insertThread(
              new ThreadState(
                  leafThreadId,
                  sessionId,
                  middleThreadId,
                  leafHeadEntryId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "leaf",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1,
                  0,
                  NOW,
                  NOW));

          return new ThreeLevelBaseline(
              rootThreadId, middleThreadId, leafThreadId, leafHeadEntryId);
        });
  }

  private static UUID seedCompactionHistory(
      InMemoryHarnessStore.Transaction tx, UUID sessionId, UUID initialParentId, UUID threadId) {
    UUID firstStartId = tx.nextId();
    UUID firstUserId = tx.nextId();
    UUID firstAssistantId = tx.nextId();
    UUID firstEndId = tx.nextId();
    UUID secondStartId = tx.nextId();
    UUID secondUserId = tx.nextId();
    UUID secondAssistantId = tx.nextId();
    UUID secondEndId = tx.nextId();

    tx.insertEntry(turnStart(firstStartId, sessionId, initialParentId, threadId));
    tx.insertEntry(
        message(
            firstUserId,
            sessionId,
            firstStartId,
            userMessage("historical user " + "h".repeat(50_000)),
            null));
    tx.insertEntry(
        message(
            firstAssistantId,
            sessionId,
            firstUserId,
            assistantMessage("historical assistant " + "a".repeat(50_000)),
            metadata()));
    tx.insertEntry(turnEnd(firstEndId, sessionId, firstAssistantId, firstStartId));

    tx.insertEntry(turnStart(secondStartId, sessionId, firstEndId, threadId));
    tx.insertEntry(
        message(
            secondUserId,
            sessionId,
            secondStartId,
            userMessage("user asks a very long question" + "x".repeat(90_000)),
            null));
    tx.insertEntry(
        message(
            secondAssistantId,
            sessionId,
            secondUserId,
            assistantMessage("assistant reply"),
            metadata()));
    tx.insertEntry(turnEnd(secondEndId, sessionId, secondAssistantId, secondStartId));

    return secondEndId;
  }

  private static Entry turnStart(UUID id, UUID sessionId, UUID parentId, UUID threadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.INPUT,
            HarnessRuntimeTestSupport.settings(),
            threadId,
            CONTEXT_WINDOW,
            MAX_OUTPUT_TOKENS,
            null),
        NOW);
  }

  private static Entry turnEnd(UUID id, UUID sessionId, UUID parentId, UUID turnStartEntryId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
        NOW);
  }

  private static Entry message(
      UUID id,
      UUID sessionId,
      UUID parentId,
      AgentMessage message,
      AssistantMessageMetadata metadata) {
    return new Entry(id, sessionId, parentId, new MessagePayload(message, metadata, null), NOW);
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage assistantMessage(String text) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST);
  }

  private static ThreadState thread(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }

  private static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target).orElse(null));
  }

  private static final class FakeTurnResolver implements TurnResolver {
    @Override
    public Result resolve(UUID threadId, EntryPath path, CompactionPreparation preparation) {
      return new Resolved(
          HarnessRuntimeTestSupport.modelRequest(), CONTEXT_WINDOW, MAX_OUTPUT_TOKENS);
    }
  }
}
