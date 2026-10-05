package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/** Thread 深删除必须在所有 Store 实现中原子移除 owned mutable facts，并保留 Session Entry 历史。 */
abstract class HarnessStoreDeletionContract {

  private HarnessStore store;

  abstract HarnessStore createStore();

  @BeforeEach
  void setUpStore() {
    store = createStore();
  }

  @Test
  void deleteThreadRemovesCommandsInvocationsAndWorkButKeepsSessionHistory() {
    // 同一聚合同时具备 Command、Model、Tool 与三类 Work，确保原子原语不依赖调用方拼接删除顺序。
    TurnBaseline baseline = seedTurnBaseline(store);
    var request = succeededRequest();
    var response = assistantResponse("call-1");
    UUID userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    UUID assistantEntryId =
        insertChildEntry(
            store, baseline.sessionId(), userEntryId, mappedAssistant(request, response));
    UUID modelId = id(100L);
    UUID toolId = id(101L);
    WorkTarget threadWork = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelWork = new WorkTarget(WorkTargetType.MODEL, modelId);
    WorkTarget toolWork = new WorkTarget(WorkTargetType.TOOL, toolId);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.insertCommands(List.of(command(baseline.threadId(), 1L, id(90L))));
          tx.insertModelInvocation(
              new ModelInvocation(
                  modelId,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  request,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  T1,
                  T1));
          ModelInvocation model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.beginDispatch(T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.markRunning(T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.succeed(response, T1));
          model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(model.attachResultEntry(assistantEntryId, T1));
          ToolInvocation tool =
              toolInvocation(
                  toolId, modelId, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, T1);
          tx.insertToolInvocations(List.of(tool));
          tx.requestWork(threadWork, T1);
          tx.requestWork(modelWork, T1);
          tx.requestWork(toolWork, T1);
        });

    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(baseline.threadId());
              tx.lockThread(baseline.threadId()).orElseThrow();
              return tx.deleteThreads(List.of(baseline.threadId()));
            });
    assertEquals(1, deleted);

    assertTrue(store.transaction(tx -> tx.findThread(baseline.threadId())).isEmpty());
    assertTrue(store.transaction(tx -> tx.loadCommandsByThread(baseline.threadId())).isEmpty());
    assertTrue(store.transaction(tx -> tx.findModelInvocation(modelId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findToolInvocation(toolId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findWork(threadWork)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findWork(modelWork)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findWork(toolWork)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findSession(baseline.sessionId())).isPresent());
    assertEquals(
        4, store.transaction(tx -> tx.loadEntriesBySessionId(baseline.sessionId())).size());
  }

  @Test
  void deleteJoinsForThreadsDeletesPendingJoinWhenBothEndsAreDeleted() {
    // 测试意图：验证批量 join 删除原语在父子两端都位于删除集合内时，可以删除尚未交付（pending delivery）的 join；
    // 同一 join 在只有 child 一侧被删除时仍被拒绝，保证任何存活 Thread 都不会留下悬挂的 join 引用。
    Baseline parentBaseline = seedThreadBaseline(store);
    UUID parentId = parentBaseline.threadId();
    // 子 Thread 位于另一个 Session（执行树 child session），但共享同一执行树根。
    Baseline childBaseline = seedThreadBaseline(store);
    UUID childId =
        createChildThread(
            parentId,
            childBaseline.sessionId(),
            childBaseline.rootEntryId(),
            ThreadExecutionControl.RUNNABLE);
    seedCommand(childId, 1L, id(1L));
    UUID joinId = id(700L);
    seedMatchedUndeliveredJoin(joinId, parentId, childId);

    // 单边删除（parent 存活）必须被拒绝：执行树根是 parent，锁的仍是同一棵树
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(parentId);
                  tx.lockThread(childId);
                  tx.deleteJoinsForThreads(List.of(childId));
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(parentId);
                  tx.lockThread(childId);
                  tx.deleteJoinsByChild(childId);
                }));
    assertTrue(store.transaction(tx -> tx.findJoin(joinId)).isPresent());

    // 父子两端同批删除时 pending join 可一并删除
    List<UUID> ordered = List.of(parentId, childId).stream().sorted(UuidOrder.COMPARATOR).toList();
    int deletedJoins =
        store.transaction(
            tx -> {
              tx.lockTree(parentId);
              for (UUID threadId : ordered) {
                tx.lockThread(threadId);
              }
              return tx.deleteJoinsForThreads(List.of(parentId, childId));
            });
    assertEquals(1, deletedJoins);
    assertTrue(store.transaction(tx -> tx.findJoin(joinId)).isEmpty());
  }

  @Test
  void deleteThreadsRemovesParentAndChildOfDifferentSessionsInSingleBatch() {
    // 测试意图：验证 deleteThreads 可以在单批次内删除跨 Session 的父子 Thread（child session），
    // 并对两端都已删除的 pending join 使用批量 join 删除原语完成一致性清理。
    Baseline parentBaseline = seedThreadBaseline(store);
    UUID parentId = parentBaseline.threadId();
    Baseline childBaseline = seedThreadBaseline(store);
    UUID childId =
        createChildThread(
            parentId,
            childBaseline.sessionId(),
            childBaseline.rootEntryId(),
            ThreadExecutionControl.RUNNABLE);
    seedCommand(parentId, 1L, id(2L));
    seedCommand(childId, 1L, id(3L));
    UUID joinId = id(701L);
    seedMatchedUndeliveredJoin(joinId, parentId, childId);

    List<UUID> ordered = List.of(parentId, childId).stream().sorted(UuidOrder.COMPARATOR).toList();
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(parentId);
              for (UUID threadId : ordered) {
                tx.lockThread(threadId);
              }
              assertThrows(IllegalArgumentException.class, () -> tx.deleteThreads(ordered));
              assertEquals(1, tx.deleteJoinsForThreads(ordered));
              return tx.deleteThreads(ordered);
            });
    assertEquals(2, deleted);

    store.transaction(
        tx -> {
          assertTrue(tx.findThread(parentId).isEmpty());
          assertTrue(tx.findThread(childId).isEmpty());
          assertTrue(tx.findJoin(joinId).isEmpty());
          assertTrue(tx.loadCommandsByThread(parentId).isEmpty());
          assertTrue(tx.loadCommandsByThread(childId).isEmpty());
          // 两个 Session 的 Entry 历史都不受影响（Session 级删除另行处理）
          assertTrue(tx.findSession(parentBaseline.sessionId()).isPresent());
          assertTrue(tx.findSession(childBaseline.sessionId()).isPresent());
          return null;
        });
  }

  /** 在已有 Session 中创建一个永久子 Thread（父 Thread 可以位于另一个 Session）。 */
  private UUID createChildThread(
      UUID parentThreadId, UUID sessionId, UUID headEntryId, ThreadExecutionControl executionControl) {
    return store.transaction(
        tx -> {
          UUID childId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  childId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "child-branch",
                  false,
                  executionControl,
                  0L,
                  1L,
                  0L,
                  T0,
                  T0));
          return childId;
        });
  }

  private void seedCommand(UUID threadId, long sequence, UUID idempotencyKey) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(threadId);
          tx.insertCommands(List.of(command(threadId, sequence, idempotencyKey)));
        });
  }

  /** 写入一条已经冻结结果、但尚未交付给父 Thread 的 join（父 Thread 处于 STOPPED 暂停交付）。 */
  private void seedMatchedUndeliveredJoin(UUID joinId, UUID parentThreadId, UUID childThreadId) {
    UUID resultHeadEntryId =
        store.transaction(tx -> tx.findThread(childThreadId).orElseThrow()).headEntryId();
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childThreadId);
          ThreadJoin join =
              new ThreadJoin(
                  joinId,
                  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  parentThreadId,
                  childThreadId,
                  1L,
                  0L,
                  "test-agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T1,
                  T1);
          tx.insertJoin(join);
          tx.updateJoin(join.match(1L, resultHeadEntryId, T2));
        });
  }
}
