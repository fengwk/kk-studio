package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread 深删的 Driver 生命周期：私有行在 Work 与 ToolInvocation 之后、Thread 之前删除。 */
class PostgresqlThreadDeletionLifecycleTest {

  @Test
  void driverRowsAreDeletedAfterWorkAndToolsButBeforeThread() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    BuiltinStore builtin = (BuiltinStore) store;
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
    WorkTarget modelWork = new WorkTarget(BuiltinWorkType.MODEL, modelId);
    WorkTarget toolWork = new WorkTarget(WorkTargetType.TOOL, toolId);

    store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
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
          return null;
        });

    DriverRegistry registry =
        new DriverRegistry(
            List.of(
                new BuiltinAgentDriver(
                    builtin, (threadId, rootSessionId, path, preparation) -> null)));
    AtomicBoolean driverDeleteCalled = new AtomicBoolean();
    DriverThreadDeletion deletion =
        new DriverThreadDeletion() {
          @Override
          public List<WorkTarget> lockForDeletion(
              HarnessStore.Transaction tx, List<UUID> threadIds) {
            return registry.lockForDeletion(tx, threadIds);
          }

          @Override
          public void deleteLocked(
              HarnessStore.Transaction tx,
              List<UUID> threadIds,
              List<WorkTarget> lockedWorkTargets) {
            // 第二阶段必须已经删除全部 Work 与 ToolInvocation，但 Model 行仍在。
            assertTrue(tx.work().find(threadWork).isEmpty());
            assertTrue(tx.work().find(modelWork).isEmpty());
            assertTrue(tx.work().find(toolWork).isEmpty());
            assertTrue(tx.tools().find(toolId).isEmpty());
            assertTrue(builtin(store, tx).findModelInvocation(modelId).isPresent());
            registry.deleteLocked(tx, threadIds, lockedWorkTargets);
            driverDeleteCalled.set(true);
          }
        };

    int deleted =
        store.transaction(
            tx -> {
              tx.threads().lockTree(baseline.threadId());
              tx.threads().lock(baseline.threadId()).orElseThrow();
              return tx.threads().delete(List.of(baseline.threadId()), deletion);
            });

    assertEquals(1, deleted);
    assertTrue(driverDeleteCalled.get());
    assertFalse(store.transaction(tx -> tx.threads().find(baseline.threadId())).isPresent());
    assertFalse(
        store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId)).isPresent());
    assertFalse(store.transaction(tx -> tx.tools().find(toolId)).isPresent());
    assertFalse(store.transaction(tx -> tx.work().find(threadWork)).isPresent());
    assertFalse(store.transaction(tx -> tx.work().find(modelWork)).isPresent());
    assertFalse(store.transaction(tx -> tx.work().find(toolWork)).isPresent());
  }
}
