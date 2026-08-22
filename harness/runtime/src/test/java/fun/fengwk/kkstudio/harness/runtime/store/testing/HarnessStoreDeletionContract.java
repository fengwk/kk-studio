package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
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
}
