package fun.fengwk.kkstudio.harness.builtin.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/** Thread 深删除必须在所有 Store 实现中原子移除 owned mutable facts，并保留 Session Entry 历史。 */
public abstract class HarnessStoreDeletionContract {

  private HarnessStore store;

  protected abstract HarnessStore createStore();

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
            store, baseline.sessionId(), userEntryId, mappedAssistant(request, response, T1));
    UUID modelId = id(100L);
    UUID toolId = id(101L);
    WorkTarget threadWork = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    WorkTarget modelWork = new WorkTarget(WorkTargetType.MODEL, modelId);
    WorkTarget toolWork = new WorkTarget(WorkTargetType.TOOL, toolId);

    inTransaction(
        store,
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.commands().insert(List.of(command(baseline.threadId(), 1L, id(90L))));
          builtin(store, tx)
              .insertModelInvocation(
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
          ModelInvocation model = builtin(store, tx).lockModelInvocation(modelId).orElseThrow();
          builtin(store, tx).updateModelInvocation(model.markRunning(T1));
          model = builtin(store, tx).lockModelInvocation(modelId).orElseThrow();
          builtin(store, tx).updateModelInvocation(model.succeed(response, T1));
          model = builtin(store, tx).lockModelInvocation(modelId).orElseThrow();
          builtin(store, tx).updateModelInvocation(model.attachResultEntry(assistantEntryId, T1));
          ToolInvocation tool =
              toolInvocation(
                  toolId,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  assistantEntryId,
                  0,
                  "call-1",
                  ToolInvocationStatus.READY,
                  T1);
          tx.tools().insert(List.of(tool));
          tx.work().request(threadWork);
          tx.work().request(modelWork);
          tx.work().request(toolWork);
        });

    int deleted =
        store.transaction(
            tx -> {
              tx.threads().lockTree(baseline.threadId());
              tx.threads().lock(baseline.threadId()).orElseThrow();
              return tx.threads().delete(List.of(baseline.threadId()));
            });
    assertEquals(1, deleted);

    assertTrue(store.transaction(tx -> tx.threads().find(baseline.threadId())).isEmpty());
    assertTrue(store.transaction(tx -> loadCommandsByThread(tx, baseline.threadId())).isEmpty());
    assertTrue(store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.tools().find(toolId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.work().find(threadWork)).isEmpty());
    assertTrue(store.transaction(tx -> tx.work().find(modelWork)).isEmpty());
    assertTrue(store.transaction(tx -> tx.work().find(toolWork)).isEmpty());
    assertTrue(store.transaction(tx -> tx.sessions().find(baseline.sessionId())).isPresent());
    assertEquals(
        4, store.transaction(tx -> tx.entries().loadBySession(baseline.sessionId())).size());
  }

  @Test
  void deleteJoinsForThreadsDeletesPendingJoinWhenBothEndsAreDeleted() {
    // 测试意图：验证批量 join 删除原语在父子两端都位于删除集合内时，可以删除尚未匹配的 join；
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
    seedUnmatchedJoin(joinId, parentId, childId);

    // 单边删除（parent 存活）必须被拒绝：执行树根是 parent，锁的仍是同一棵树
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.threads().lockTree(parentId);
                  tx.threads().lock(childId);
                  tx.joins().deleteForThreads(List.of(childId));
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.threads().lockTree(parentId);
                  tx.threads().lock(childId);
                  tx.joins().deleteByChild(childId);
                }));
    assertTrue(store.transaction(tx -> tx.joins().find(joinId)).isPresent());

    // 父子两端同批删除时 pending join 可一并删除
    List<UUID> ordered = List.of(parentId, childId).stream().sorted(UuidOrder.COMPARATOR).toList();
    int deletedJoins =
        store.transaction(
            tx -> {
              tx.threads().lockTree(parentId);
              for (UUID threadId : ordered) {
                tx.threads().lock(threadId);
              }
              return tx.joins().deleteForThreads(List.of(parentId, childId));
            });
    assertEquals(1, deletedJoins);
    assertTrue(store.transaction(tx -> tx.joins().find(joinId)).isEmpty());
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
    seedCommand(childId, 1L, id(3L));
    UUID joinId = id(701L);
    seedUnmatchedJoin(joinId, parentId, childId);

    List<UUID> ordered = List.of(parentId, childId).stream().sorted(UuidOrder.COMPARATOR).toList();
    int deleted =
        store.transaction(
            tx -> {
              tx.threads().lockTree(parentId);
              for (UUID threadId : ordered) {
                tx.threads().lock(threadId);
              }
              assertThrows(IllegalArgumentException.class, () -> tx.threads().delete(ordered));
              assertEquals(1, tx.joins().deleteForThreads(ordered));
              return tx.threads().delete(ordered);
            });
    assertEquals(2, deleted);

    store.transaction(
        tx -> {
          assertTrue(tx.threads().find(parentId).isEmpty());
          assertTrue(tx.threads().find(childId).isEmpty());
          assertTrue(tx.joins().find(joinId).isEmpty());
          assertTrue(loadCommandsByThread(tx, parentId).isEmpty());
          assertTrue(loadCommandsByThread(tx, childId).isEmpty());
          // 两个 Session 的 Entry 历史都不受影响（Session 级删除另行处理）
          assertTrue(tx.sessions().find(parentBaseline.sessionId()).isPresent());
          assertTrue(tx.sessions().find(childBaseline.sessionId()).isPresent());
          return null;
        });
  }

  /** 在已有 Session 中创建一个永久子 Thread（父 Thread 可以位于另一个 Session）。 */
  private UUID createChildThread(
      UUID parentThreadId,
      UUID sessionId,
      UUID headEntryId,
      ThreadExecutionControl executionControl) {
    return store.transaction(
        tx -> {
          UUID childId = tx.nextId();
          tx.threads()
              .insert(
                  new ThreadState(
                      childId,
                      sessionId,
                      parentThreadId,
                      headEntryId,
                      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "child-branch",
                      parentThreadId,
                      false,
                      SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
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
          tx.threads().lock(threadId);
          tx.commands().insert(List.of(command(threadId, sequence, idempotencyKey)));
        });
  }

  /** 写入尚未冻结结果的 NOTIFY_PARENT join，用于验证存活端拒绝删除、两端同删允许删除。 */
  private void seedUnmatchedJoin(UUID joinId, UUID parentThreadId, UUID childThreadId) {
    inTransaction(
        store,
        tx -> {
          tx.threads().lock(childThreadId);
          tx.joins()
              .insert(
                  new ThreadJoin(
                      joinId,
                      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      parentThreadId,
                      childThreadId,
                      1L,
                      "test-agent",
                      10,
                      0L,
                      null,
                      null,
                      null,
                      T1,
                      T1,
                      JoinDelivery.NOTIFY_PARENT,
                      null));
        });
  }
}
